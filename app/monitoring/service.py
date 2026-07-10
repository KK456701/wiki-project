from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine

from app.db.repositories import create_recovery_task, fail_recovery_task
from app.monitoring.periods import comparison_period, resolve_run_period
from app.monitoring.repository import MonitoringRepository
from app.monitoring.wave import detect_wave


class MonitoringRunError(RuntimeError):
    pass


class IndicatorRunService:
    def __init__(
        self,
        *,
        runtime_engine: Engine,
        repository: MonitoringRepository,
        orchestrator: Any,
        worker_id: str | None = None,
        lease_seconds: int = 600,
    ) -> None:
        self.runtime_engine = runtime_engine
        self.repository = repository
        self.orchestrator = orchestrator
        self.worker_id = worker_id or f"worker-{uuid.uuid4().hex[:8]}"
        self.lease_seconds = lease_seconds

    @staticmethod
    def _model_dump(value: Any) -> dict[str, Any]:
        if hasattr(value, "model_dump"):
            return dict(value.model_dump(exclude_none=True))
        return dict(value or {})

    @staticmethod
    def _run_key(
        plan_id: str,
        trigger_type: str,
        period_label: str,
        request_id: str | None,
    ) -> str:
        if trigger_type == "scheduled":
            return f"{plan_id}:{period_label}"
        return f"{trigger_type}:{request_id or uuid.uuid4().hex}"

    def run_plan(
        self,
        plan_id: str,
        stat_period: str | None = None,
        trigger_type: str = "scheduled",
        request_id: str | None = None,
        retry_of_result_id: int | None = None,
    ) -> dict[str, Any]:
        plan = self.repository.get_plan(plan_id)
        if plan is None:
            raise MonitoringRunError(f"运行计划不存在: {plan_id}")
        now = datetime.now().replace(microsecond=0)
        leased = False
        if trigger_type == "scheduled":
            leased = self.repository.try_acquire_lease(
                plan_id, self.worker_id, now, self.lease_seconds
            )
            if not leased:
                return {
                    "status": "skipped",
                    "reason": "lease_not_acquired",
                    "plan_id": plan_id,
                }

        try:
            period = resolve_run_period(
                str(plan["frequency"]),
                stat_period=stat_period,
                timezone_name=str(plan.get("timezone") or "Asia/Shanghai"),
            )
            run_key = self._run_key(
                plan_id, trigger_type, period.label, request_id
            )
            existing = self.repository.get_result_by_run_key(run_key)
            if existing is not None:
                return existing

            prepared = self.orchestrator.prepare_rule_request(
                query=f"monitor:{plan['rule_id']}",
                hospital_id=str(plan["hospital_id"]),
                intent="trial_run",
                rule_id=str(plan["rule_id"]),
            )
            effective = self._model_dump(prepared.effective_rule)
            mapping = self._model_dump(prepared.field_mapping)
            generation = self.orchestrator.generate_indicator(
                prepared,
                stat_start_time=period.start_text,
                stat_end_time=period.end_text,
                trial_run=True,
                generated_by="monitoring_scheduler",
                persist_run_result=False,
            )
            trial = dict(generation.get("trial_run") or {})
            generation_ok = str(generation.get("status") or "success") == "success"
            trial_ok = str(trial.get("status") or "") == "success"
            if not generation_ok or not trial_ok:
                return self._save_failure(
                    plan,
                    period,
                    run_key,
                    trigger_type,
                    retry_of_result_id,
                    str(
                        generation.get("message")
                        or trial.get("error_message")
                        or "指标运算失败"
                    ),
                    str(generation.get("status") or trial.get("status") or "failed"),
                )

            no_sample = bool(trial.get("no_sample", False))
            result = self.repository.create_run_result(
                {
                    "run_key": run_key,
                    "plan_id": plan_id,
                    "retry_of_result_id": retry_of_result_id,
                    "hospital_id": plan["hospital_id"],
                    "rule_id": plan["rule_id"],
                    "trigger_type": trigger_type,
                    "stat_start_time": period.start,
                    "stat_end_time": period.end,
                    "stat_period": period.label,
                    "run_status": "no_sample" if no_sample else "success",
                    "result_value": trial.get("result_value"),
                    "no_sample": no_sample,
                    "effective_level": str(effective.get("effective_level") or ""),
                    "national_version": effective.get("national_version"),
                    "hospital_version": effective.get("hospital_version"),
                    "data_source": str(mapping.get("db_name") or "hospital_demo_data"),
                    "duration_ms": int(trial.get("duration_ms") or 0),
                    "run_id": trial.get("run_id"),
                }
            )
            result = self._apply_wave(plan, period, result, no_sample)
            if result.get("is_abnormal"):
                result["alert"] = self._create_wave_alert(
                    plan, prepared, generation, result
                )
            else:
                result["alert"] = None
            return result
        except Exception as exc:
            if isinstance(exc, MonitoringRunError):
                raise
            if "period" in locals() and "run_key" in locals():
                return self._save_failure(
                    plan,
                    period,
                    run_key,
                    trigger_type,
                    retry_of_result_id,
                    str(exc),
                    type(exc).__name__,
                )
            raise
        finally:
            if leased:
                self.repository.release_lease(
                    plan_id,
                    self.worker_id,
                    datetime.now().replace(microsecond=0),
                    plan.get("next_run_at"),
                )

    def _apply_wave(
        self,
        plan: dict[str, Any],
        period: Any,
        result: dict[str, Any],
        no_sample: bool,
    ) -> dict[str, Any]:
        mom_period = comparison_period(period, "mom")
        yoy_period = comparison_period(period, "yoy")
        mom = self.repository.find_success_result(
            str(plan["hospital_id"]),
            str(plan["rule_id"]),
            mom_period.start,
            mom_period.end,
        )
        yoy = (
            self.repository.find_success_result(
                str(plan["hospital_id"]),
                str(plan["rule_id"]),
                yoy_period.start,
                yoy_period.end,
            )
            if plan.get("yoy_enabled")
            else None
        )
        wave = detect_wave(
            result.get("result_value"),
            mom.get("result_value") if mom else None,
            yoy.get("result_value") if yoy else None,
            bool(plan.get("mom_enabled")),
            float(plan.get("mom_threshold_pct") or 20),
            bool(plan.get("yoy_enabled")),
            float(plan.get("yoy_threshold_pct") or 30),
            no_sample=no_sample,
        )
        return self.repository.update_wave_result(
            int(result["id"]),
            {
                "previous_value": mom.get("result_value") if mom else None,
                "change_rate": wave.get("mom_change_rate"),
                "mom_baseline_result_id": mom.get("id") if mom else None,
                "mom_change_rate": wave.get("mom_change_rate"),
                "yoy_baseline_result_id": yoy.get("id") if yoy else None,
                "yoy_change_rate": wave.get("yoy_change_rate"),
                "wave_status": wave["conclusion_code"],
                "is_abnormal": wave["is_abnormal"],
            },
        )

    def _create_wave_alert(
        self,
        plan: dict[str, Any],
        prepared: Any,
        generation: dict[str, Any],
        result: dict[str, Any],
    ) -> dict[str, Any]:
        mom = (
            self.repository.get_result_for_retry(int(result["mom_baseline_result_id"]))
            if result.get("mom_baseline_result_id")
            else None
        )
        yoy = (
            self.repository.get_result_for_retry(int(result["yoy_baseline_result_id"]))
            if result.get("yoy_baseline_result_id")
            else None
        )
        alert = self.repository.create_alert(
            {
                "hospital_id": plan["hospital_id"],
                "rule_id": plan["rule_id"],
                "plan_id": plan["plan_id"],
                "result_id": result["id"],
                "alert_type": "wave",
                "alert_level": "warning",
                "conclusion_code": result["wave_status"],
                "current_value": result.get("result_value"),
                "mom_value": mom.get("result_value") if mom else None,
                "mom_change_rate": result.get("mom_change_rate"),
                "yoy_value": yoy.get("result_value") if yoy else None,
                "yoy_change_rate": result.get("yoy_change_rate"),
                "diagnose_status": "running",
            }
        )
        try:
            diagnosis = self.orchestrator.diagnose(
                prepared,
                trigger="abnormal_result",
                related_sql_id=generation.get("sql_id"),
                stat_period=result["stat_period"],
            )
            return self.repository.update_alert(
                alert["alert_id"],
                str(plan["hospital_id"]),
                {
                    "diagnose_status": "completed",
                    "diagnose_report_id": diagnosis.get("report_id"),
                },
            )
        except Exception:
            return self.repository.update_alert(
                alert["alert_id"],
                str(plan["hospital_id"]),
                {"diagnose_status": "failed"},
            )

    def _save_failure(
        self,
        plan: dict[str, Any],
        period: Any,
        run_key: str,
        trigger_type: str,
        retry_of_result_id: int | None,
        error_message: str,
        error_code: str,
    ) -> dict[str, Any]:
        result = self.repository.create_run_result(
            {
                "run_key": run_key,
                "plan_id": plan["plan_id"],
                "retry_of_result_id": retry_of_result_id,
                "hospital_id": plan["hospital_id"],
                "rule_id": plan["rule_id"],
                "trigger_type": trigger_type,
                "stat_start_time": period.start,
                "stat_end_time": period.end,
                "stat_period": period.label,
                "run_status": "failed",
                "result_value": None,
                "error_code": error_code,
                "error_message": error_message,
            }
        )
        alert = self.repository.create_alert(
            {
                "hospital_id": plan["hospital_id"],
                "rule_id": plan["rule_id"],
                "plan_id": plan["plan_id"],
                "result_id": result["id"],
                "alert_type": "execution_failed",
                "alert_level": "error",
                "conclusion_code": "indicator_execution_failed",
                "diagnose_status": "not_applicable",
            }
        )
        task_id = create_recovery_task(
            self.runtime_engine,
            task_type="indicator_recompute",
            task_name="指标重新运算",
            current_step="monitor_indicator_execute_mcp",
            payload={
                "plan_id": plan["plan_id"],
                "stat_period": period.label,
                "failed_result_id": result["id"],
                "hospital_id": plan["hospital_id"],
                "rule_id": plan["rule_id"],
            },
            hospital_id=str(plan["hospital_id"]),
            rule_id=str(plan["rule_id"]),
            recoverable_action="retry",
        )
        fail_recovery_task(self.runtime_engine, task_id, error_message)
        return {**result, "alert": alert, "recovery_task_id": task_id}

    def retry_result(self, result_id: int, request_id: str) -> dict[str, Any]:
        failed = self.repository.get_result_for_retry(result_id)
        if failed is None or failed.get("run_status") != "failed":
            raise MonitoringRunError(f"不可重试的运行结果: {result_id}")
        if not failed.get("plan_id"):
            raise MonitoringRunError("失败结果未关联运行计划")
        return self.run_plan(
            str(failed["plan_id"]),
            stat_period=str(failed["stat_period"]),
            trigger_type="retry",
            request_id=request_id,
            retry_of_result_id=result_id,
        )
