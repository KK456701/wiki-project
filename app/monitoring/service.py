from __future__ import annotations

import time
import uuid
from datetime import datetime
from typing import Any

from app.business_source import current_business_source

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
        trace_recorder: Any | None = None,
    ) -> None:
        self.runtime_engine = runtime_engine
        self.repository = repository
        self.orchestrator = orchestrator
        self.worker_id = worker_id or f"worker-{uuid.uuid4().hex[:8]}"
        self.lease_seconds = lease_seconds
        self.trace_recorder = trace_recorder

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

    @staticmethod
    def _elapsed_ms(started: float) -> int:
        return max(1, int((time.perf_counter() - started) * 1000))

    def run_plan(
        self,
        plan_id: str,
        stat_period: str | None = None,
        trigger_type: str = "scheduled",
        request_id: str | None = None,
        retry_of_result_id: int | None = None,
    ) -> dict[str, Any]:
        timings: dict[str, int] = {}
        stage_started = time.perf_counter()
        plan = self.repository.get_plan(plan_id)
        if plan is None:
            raise MonitoringRunError(f"运行计划不存在: {plan_id}")
        timings["plan_load"] = self._elapsed_ms(stage_started)
        trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
        if self.trace_recorder is not None:
            try:
                self.trace_recorder.start_trace(
                    trace_id,
                    None,
                    str(plan["hospital_id"]),
                    f"monitor:{plan['rule_id']}",
                    workflow_id="indicator_monitoring",
                )
            except Exception:
                pass
        now = datetime.now().replace(microsecond=0)
        leased = False
        if trigger_type == "scheduled":
            stage_started = time.perf_counter()
            leased = self.repository.try_acquire_lease(
                plan_id, self.worker_id, now, self.lease_seconds
            )
            timings["lease"] = self._elapsed_ms(stage_started)
            if not leased:
                skipped = {
                    "status": "skipped",
                    "reason": "lease_not_acquired",
                    "plan_id": plan_id,
                }
                return self._complete_trace(
                    plan,
                    skipped,
                    trace_id,
                    trigger_type,
                    lease_status="contended",
                    timings=timings,
                )
        else:
            timings["lease"] = 1

        try:
            stage_started = time.perf_counter()
            period = resolve_run_period(
                str(plan["frequency"]),
                stat_period=stat_period,
                timezone_name=str(plan.get("timezone") or "Asia/Shanghai"),
            )
            timings["period"] = self._elapsed_ms(stage_started)
            run_key = self._run_key(
                plan_id, trigger_type, period.label, request_id
            )
            existing = self.repository.get_result_by_run_key(run_key)
            if existing is not None:
                return self._complete_trace(
                    plan,
                    existing,
                    trace_id,
                    trigger_type,
                    lease_status="acquired",
                    timings=timings,
                )

            prepared = self.orchestrator.prepare_rule_request(
                query=f"monitor:{plan['rule_id']}",
                hospital_id=str(plan["hospital_id"]),
                intent="trial_run",
                rule_id=str(plan["rule_id"]),
            )
            effective = self._model_dump(prepared.effective_rule)
            mapping = self._model_dump(prepared.field_mapping)
            stage_started = time.perf_counter()
            generation = self.orchestrator.generate_indicator(
                prepared,
                stat_start_time=period.start_text,
                stat_end_time=period.end_text,
                trial_run=True,
                generated_by="monitoring_scheduler",
                persist_run_result=False,
            )
            trial = dict(generation.get("trial_run") or {})
            timings["execute"] = max(
                1,
                int(trial.get("duration_ms") or 0),
                self._elapsed_ms(stage_started),
            )
            generation_ok = str(generation.get("status") or "success") == "success"
            trial_ok = str(trial.get("status") or "") == "success"
            if not generation_ok or not trial_ok:
                failed = self._save_failure(
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
                return self._complete_trace(
                    plan,
                    failed,
                    trace_id,
                    trigger_type,
                    lease_status="acquired",
                    timings=timings,
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
                    "data_source": str(
                        mapping.get("db_name")
                        or current_business_source().source_id
                    ),
                    "duration_ms": int(trial.get("duration_ms") or 0),
                    "run_id": trial.get("run_id"),
                }
            )
            stage_started = time.perf_counter()
            result = self._apply_wave(plan, period, result, no_sample)
            timings["wave"] = self._elapsed_ms(stage_started)
            if result.get("is_abnormal"):
                result["alert"], alert_timings = self._create_wave_alert(
                    plan, prepared, generation, result
                )
                timings.update(alert_timings)
            else:
                result["alert"] = None
            return self._complete_trace(
                plan,
                result,
                trace_id,
                trigger_type,
                lease_status="acquired",
                timings=timings,
            )
        except Exception as exc:
            if isinstance(exc, MonitoringRunError):
                raise
            if "period" in locals() and "run_key" in locals():
                failed = self._save_failure(
                    plan,
                    period,
                    run_key,
                    trigger_type,
                    retry_of_result_id,
                    str(exc),
                    type(exc).__name__,
                )
                return self._complete_trace(
                    plan,
                    failed,
                    trace_id,
                    trigger_type,
                    lease_status="acquired",
                    timings=timings,
                )
            if self.trace_recorder is not None:
                try:
                    self.trace_recorder.finish_trace(
                        trace_id, "failed", str(exc), intent="indicator_monitoring",
                        error_count=1,
                    )
                except Exception:
                    pass
            raise
        finally:
            if leased:
                self.repository.release_lease(
                    plan_id,
                    self.worker_id,
                    datetime.now().replace(microsecond=0),
                    plan.get("next_run_at"),
                )

    def _complete_trace(
        self,
        plan: dict[str, Any],
        result: dict[str, Any],
        trace_id: str,
        trigger_type: str,
        *,
        lease_status: str,
        timings: dict[str, int],
    ) -> dict[str, Any]:
        if self.trace_recorder is None:
            return result
        from app.observability.workflow_nodes import record_monitoring_trace_nodes

        payload = {**result, "trace_id": trace_id}
        run_status = str(result.get("run_status") or result.get("status") or "success")
        failed = run_status == "failed"
        period_output = {
            "stat_start_time": result.get("stat_start_time"),
            "stat_end_time": result.get("stat_end_time"),
            "stat_period": result.get("stat_period"),
        }
        events = [
            {
                "node_name": "monitor_plan_load",
                "status": "success",
                "duration_ms": timings.get("plan_load", 1),
                "input_data": {"plan_id": plan["plan_id"]},
                "output_data": {
                    "hospital_id": plan["hospital_id"],
                    "rule_id": plan["rule_id"],
                    "frequency": plan["frequency"],
                    "thresholds": {
                        "mom": plan.get("mom_threshold_pct"),
                        "yoy": plan.get("yoy_threshold_pct"),
                    },
                },
            },
            {
                "node_name": "monitor_lease_acquire",
                "status": "failed" if lease_status == "contended" else "success",
                "duration_ms": timings.get("lease", 1),
                "input_data": {
                    "plan_id": plan["plan_id"],
                    "worker_id": self.worker_id,
                },
                "output_data": {
                    "lease_status": (
                        "not_required" if trigger_type != "scheduled" else lease_status
                    )
                },
            },
        ]
        if result.get("stat_period"):
            events.extend(
                [
                    {
                        "node_name": "monitor_period_resolve",
                        "status": "success",
                        "duration_ms": timings.get("period", 1),
                        "input_data": {
                            "frequency": plan["frequency"],
                            "timezone": plan.get("timezone"),
                            "stat_period": result.get("stat_period"),
                        },
                        "output_data": period_output,
                    },
                    {
                        "node_name": "monitor_indicator_execute_mcp",
                        "status": "failed" if failed else "success",
                        "duration_ms": timings.get(
                            "execute", max(1, int(result.get("duration_ms") or 0))
                        ),
                        "error_code": str(result.get("error_code") or ""),
                        "error_message": str(result.get("error_message") or ""),
                        "input_data": {
                            "hospital_id": plan["hospital_id"],
                            "rule_id": plan["rule_id"],
                            **period_output,
                        },
                        "output_data": {
                            "result_value": result.get("result_value"),
                            "no_sample": result.get("no_sample"),
                            "effective_level": result.get("effective_level"),
                            "national_version": result.get("national_version"),
                            "hospital_version": result.get("hospital_version"),
                            "data_source": result.get("data_source"),
                            "duration_ms": result.get("duration_ms"),
                            "run_id": result.get("run_id"),
                        },
                    },
                ]
            )
        if result.get("wave_status") and not failed:
            events.append(
                {
                    "node_name": "monitor_wave_detect",
                    "status": "success",
                    "duration_ms": timings.get("wave", 1),
                    "input_data": {
                        "result_value": result.get("result_value"),
                        "thresholds": {
                            "mom": plan.get("mom_threshold_pct"),
                            "yoy": plan.get("yoy_threshold_pct"),
                        },
                    },
                    "output_data": {
                        "mom_change_rate": result.get("mom_change_rate"),
                        "yoy_change_rate": result.get("yoy_change_rate"),
                        "conclusion_code": result.get("wave_status"),
                        "is_abnormal": result.get("is_abnormal"),
                    },
                }
            )
        alert = result.get("alert") or {}
        if alert:
            events.append(
                {
                    "node_name": "monitor_alert_create",
                    "status": "success",
                    "duration_ms": timings.get("alert", 1),
                    "input_data": {
                        "result_id": result.get("id"),
                        "conclusion_code": alert.get("conclusion_code"),
                    },
                    "output_data": {
                        "alert_id": alert.get("alert_id"),
                        "alert_status": alert.get("status"),
                    },
                }
            )
            if alert.get("alert_type") == "wave":
                events.append(
                    {
                        "node_name": "monitor_auto_diagnose",
                        "status": (
                            "success"
                            if alert.get("diagnose_status") == "completed"
                            else "failed"
                        ),
                        "duration_ms": timings.get("diagnose", 1),
                        "input_data": {
                            "alert_id": alert.get("alert_id"),
                            "hospital_id": plan["hospital_id"],
                            "rule_id": plan["rule_id"],
                            "stat_period": result.get("stat_period"),
                        },
                        "output_data": {
                            "diagnose_status": alert.get("diagnose_status"),
                            "diagnose_report_id": alert.get("diagnose_report_id"),
                        },
                    }
                )
        try:
            record_monitoring_trace_nodes(self.trace_recorder, trace_id, events)
            self.trace_recorder.finish_trace(
                trace_id,
                "failed" if failed else "success",
                str(result.get("wave_status") or result.get("reason") or run_status),
                intent="indicator_monitoring",
                error_count=1 if failed else 0,
            )
        except Exception:
            pass
        return payload

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
    ) -> tuple[dict[str, Any], dict[str, int]]:
        timings: dict[str, int] = {}
        stage_started = time.perf_counter()
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
        timings["alert"] = self._elapsed_ms(stage_started)
        stage_started = time.perf_counter()
        try:
            diagnosis = self.orchestrator.diagnose(
                prepared,
                trigger="abnormal_result",
                related_sql_id=generation.get("sql_id"),
                stat_period=result["stat_period"],
            )
            updated = self.repository.update_alert(
                alert["alert_id"],
                str(plan["hospital_id"]),
                {
                    "diagnose_status": "completed",
                    "diagnose_report_id": diagnosis.get("report_id"),
                },
            )
            timings["diagnose"] = self._elapsed_ms(stage_started)
            return updated, timings
        except Exception:
            updated = self.repository.update_alert(
                alert["alert_id"],
                str(plan["hospital_id"]),
                {"diagnose_status": "failed"},
            )
            timings["diagnose"] = self._elapsed_ms(stage_started)
            return updated, timings

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
        existing = self.repository.get_result_by_run_key(run_key)
        failure_payload = {
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
        result = (
            self.repository.mark_run_result_failed(
                int(existing["id"]), error_code, error_message
            )
            if existing is not None
            else self.repository.create_run_result(failure_payload)
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

    def diagnose_alert(
        self, alert_id: str, hospital_id: str
    ) -> dict[str, Any]:
        alert = self.repository.get_alert(alert_id, hospital_id)
        if alert is None:
            raise MonitoringRunError(f"指标预警不存在: {alert_id}")
        result = self.repository.get_result(
            int(alert["result_id"]), hospital_id
        )
        if result is None:
            raise MonitoringRunError(f"预警运行结果不存在: {alert['result_id']}")
        prepared = self.orchestrator.prepare_rule_request(
            query=f"diagnose:{alert['rule_id']}",
            hospital_id=hospital_id,
            intent="diagnose",
            rule_id=str(alert["rule_id"]),
        )
        self.repository.update_alert(
            alert_id, hospital_id, {"diagnose_status": "running"}
        )
        try:
            diagnosis = self.orchestrator.diagnose(
                prepared,
                trigger="manual_alert",
                related_sql_id=None,
                stat_period=str(result.get("stat_period") or ""),
            )
            return self.repository.update_alert(
                alert_id,
                hospital_id,
                {
                    "diagnose_status": "completed",
                    "diagnose_report_id": diagnosis.get("report_id"),
                },
            )
        except Exception:
            self.repository.update_alert(
                alert_id, hospital_id, {"diagnose_status": "failed"}
            )
            raise

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
