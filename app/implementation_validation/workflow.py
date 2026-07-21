from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
import time
from typing import Any
import uuid

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.sql_tools import (
    PrepareIndicatorSqlInput,
    SqlToolServices,
    TrialRunIndicatorSqlInput,
    prepare_indicator_sql,
    trial_run_indicator_sql,
)
from app.agent_tools.upload_tools import (
    AnalyzeUploadedIndicatorsInput,
    UploadToolServices,
    analyze_uploaded_indicators,
)

from .contracts import (
    ImplementationValidationReport,
    ValidationStageResult,
    ValidationStageStatus,
)


TraceCallback = Callable[[dict[str, Any]], None]


@dataclass(frozen=True, slots=True)
class ImplementationValidationServices:
    sql_services: SqlToolServices
    upload_services: UploadToolServices | None = None
    trace_callback: TraceCallback | None = None


def _latest_result(state: AgentRunState, code: str) -> dict[str, Any] | None:
    for item in reversed(state.last_tool_results):
        if (
            isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == code
            and isinstance(item.get("data"), dict)
        ):
            return item
    return None


def _overall_status(stages: list[ValidationStageResult]) -> ValidationStageStatus:
    if any(stage.status is ValidationStageStatus.FAILED for stage in stages):
        return ValidationStageStatus.FAILED
    if any(stage.status is ValidationStageStatus.WARNING for stage in stages):
        return ValidationStageStatus.WARNING
    return ValidationStageStatus.PASSED


class ImplementationValidationWorkflow:
    """L1/L4/L5/可选 L6 的固定顺序验收；模型不参与阶段选择。"""

    version = "implementation-validation-mvp-v1"

    def __init__(self, services: ImplementationValidationServices) -> None:
        self.services = services

    def _trace(
        self,
        stage: ValidationStageResult,
        *,
        input_data: dict[str, Any],
        config_data: dict[str, Any],
    ) -> None:
        callback = self.services.trace_callback
        if callback is None:
            return
        try:
            callback({
                "event": "trace_node",
                "node_name": f"implementation_validation_{stage.stage_id.lower()}",
                "node_type": "code",
                "status": (
                    "success"
                    if stage.status in {
                        ValidationStageStatus.PASSED,
                        ValidationStageStatus.SKIPPED,
                    }
                    else "warning"
                    if stage.status is ValidationStageStatus.WARNING
                    else "failed"
                ),
                "duration_ms": max(1, stage.duration_ms),
                "input_data": input_data,
                "output_data": stage.model_dump(mode="json"),
                "processing_data": {
                    "description": stage.summary,
                    "workflow_version": self.version,
                },
                "config_data": config_data,
                "error_code": (
                    stage.finding_codes[0]
                    if stage.status is ValidationStageStatus.FAILED
                    and stage.finding_codes
                    else None
                ),
            })
        except Exception:
            return

    def _stage_l1(self, rule_id: str, state: AgentRunState) -> ValidationStageResult:
        started = time.perf_counter()
        inspected = _latest_result(state, "IMPLEMENTATION_INSPECTED")
        if inspected is None:
            stage = ValidationStageResult(
                stage_id="L1",
                stage_name="字段映射与来源检查",
                status=ValidationStageStatus.FAILED,
                summary="未取得当前指标的实施映射证据。",
                finding_codes=["IMPLEMENTATION_EVIDENCE_MISSING"],
            )
        else:
            data = inspected["data"]
            missing = list(data.get("missing_mappings") or [])
            unconfirmed = list(data.get("unconfirmed_mappings") or [])
            confirmed = (
                str(data.get("rule_id") or "") == rule_id
                and str(data.get("status") or "") == "confirmed"
                and not missing
                and not unconfirmed
                and bool(data.get("main_table"))
            )
            findings = []
            if missing:
                findings.append("FIELD_MAPPING_MISSING")
            if unconfirmed:
                findings.append("FIELD_MAPPING_UNCONFIRMED")
            if not data.get("main_table"):
                findings.append("MAIN_TABLE_MISSING")
            if str(data.get("rule_id") or "") != rule_id:
                findings.append("RULE_ID_MISMATCH")
            stage = ValidationStageResult(
                stage_id="L1",
                stage_name="字段映射与来源检查",
                status=(
                    ValidationStageStatus.PASSED
                    if confirmed
                    else ValidationStageStatus.FAILED
                ),
                summary=(
                    "必需字段、主表和本院字段映射均已确认。"
                    if confirmed
                    else "字段来源或本院映射未满足实施验收要求。"
                ),
                finding_codes=(
                    []
                    if confirmed
                    else findings or ["FIELD_MAPPING_NOT_CONFIRMED"]
                ),
                safe_details={
                    "mapping_status": data.get("status"),
                    "main_table": data.get("main_table"),
                    "dialect": data.get("dialect"),
                    "required_field_count": len(data.get("required_business_fields") or []),
                    "mapped_field_count": len(data.get("mapped_fields") or []),
                    "missing_mappings": missing,
                    "unconfirmed_mappings": unconfirmed,
                },
            )
        stage.duration_ms = max(1, int((time.perf_counter() - started) * 1000))
        self._trace(
            stage,
            input_data={"rule_id": rule_id},
            config_data={"stage": "L1", "source": "inspect_indicator_implementation"},
        )
        return stage

    def _stage_l4(self, rule_id: str, state: AgentRunState) -> tuple[ValidationStageResult, str]:
        started = time.perf_counter()
        effective = _latest_result(state, "EFFECTIVE_RULE_FOUND")
        rule_name = ""
        if effective is None:
            stage = ValidationStageResult(
                stage_id="L4",
                stage_name="规则口径对齐",
                status=ValidationStageStatus.FAILED,
                summary="未取得当前医院的生效规则证据。",
                finding_codes=["EFFECTIVE_RULE_EVIDENCE_MISSING"],
            )
        else:
            data = effective["data"]
            rule_name = str(data.get("rule_name") or "")
            findings = []
            if str(data.get("rule_id") or "") != rule_id:
                findings.append("RULE_ID_MISMATCH")
            if not data.get("definition"):
                findings.append("RULE_DEFINITION_MISSING")
            if not data.get("formula"):
                findings.append("RULE_FORMULA_MISSING")
            if data.get("hospital_version") is None and not data.get("national_version"):
                findings.append("RULE_VERSION_MISSING")
            stage = ValidationStageResult(
                stage_id="L4",
                stage_name="规则口径对齐",
                status=(
                    ValidationStageStatus.PASSED
                    if not findings
                    else ValidationStageStatus.FAILED
                ),
                summary=(
                    "指标身份、定义、公式和生效版本均已确认。"
                    if not findings
                    else "生效规则证据不完整或与当前指标不一致。"
                ),
                finding_codes=findings,
                safe_details={
                    "rule_name": rule_name,
                    "effective_level": data.get("effective_level"),
                    "national_version": data.get("national_version"),
                    "hospital_version": data.get("hospital_version"),
                    "overridden_field_count": len(data.get("overridden_fields") or []),
                },
            )
        stage.duration_ms = max(1, int((time.perf_counter() - started) * 1000))
        self._trace(
            stage,
            input_data={"rule_id": rule_id},
            config_data={"stage": "L4", "source": "get_effective_rule"},
        )
        return stage, rule_name

    def _stage_l5(
        self,
        *,
        rule_id: str,
        stat_start_time: datetime,
        stat_end_time: datetime,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> tuple[ValidationStageResult, dict[str, Any]]:
        started = time.perf_counter()
        prepared = prepare_indicator_sql(
            PrepareIndicatorSqlInput(
                rule_id=rule_id,
                stat_start_time=stat_start_time,
                stat_end_time=stat_end_time,
            ),
            context,
            state,
            self.services.sql_services,
        )
        if not prepared.ok:
            stage = ValidationStageResult(
                stage_id="L5",
                stage_name="受控 SQL 与试运行",
                status=ValidationStageStatus.FAILED,
                summary=prepared.summary,
                finding_codes=[prepared.code],
                safe_details={
                    "sql_prepare_status": prepared.status,
                    "sql_prepare_code": prepared.code,
                },
            )
            result_data: dict[str, Any] = {}
        else:
            state.last_tool_results.append(prepared.model_dump(mode="json"))
            sql_id = str(prepared.data.get("sql_id") or "")
            trial = trial_run_indicator_sql(
                TrialRunIndicatorSqlInput(sql_id=sql_id),
                context,
                state,
                self.services.sql_services,
            )
            if not trial.ok:
                stage = ValidationStageResult(
                    stage_id="L5",
                    stage_name="受控 SQL 与试运行",
                    status=ValidationStageStatus.FAILED,
                    summary=trial.summary,
                    finding_codes=[trial.code],
                    safe_details={
                        "sql_id": sql_id,
                        "sql_validation_status": prepared.data.get("validation_status"),
                        "trial_status": trial.status,
                        "trial_code": trial.code,
                    },
                )
                result_data = {"sql_id": sql_id}
            else:
                state.last_tool_results.append(trial.model_dump(mode="json"))
                result_data = dict(trial.data)
                stage = ValidationStageResult(
                    stage_id="L5",
                    stage_name="受控 SQL 与试运行",
                    status=ValidationStageStatus.PASSED,
                    summary="SQL 已通过安全校验并完成医院业务库只读试运行。",
                    safe_details={
                        "sql_id": sql_id,
                        "run_id": trial.data.get("run_id"),
                        "sql_validation_status": prepared.data.get("validation_status"),
                        "trial_status": trial.data.get("status"),
                        "numerator_count": trial.data.get("numerator_count"),
                        "denominator_count": trial.data.get("denominator_count"),
                        "result_value": trial.data.get("result_value"),
                    },
                )
        stage.duration_ms = max(1, int((time.perf_counter() - started) * 1000))
        self._trace(
            stage,
            input_data={
                "rule_id": rule_id,
                "stat_start": stat_start_time.isoformat(),
                "stat_end": stat_end_time.isoformat(),
            },
            config_data={
                "stage": "L5",
                "tools": ["prepare_indicator_sql", "trial_run_indicator_sql"],
                "readonly": True,
            },
        )
        return stage, result_data

    def _stage_l6(
        self,
        *,
        file_key: str | None,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> ValidationStageResult:
        started = time.perf_counter()
        if not file_key:
            stage = ValidationStageResult(
                stage_id="L6",
                stage_name="报表数据核对",
                status=ValidationStageStatus.SKIPPED,
                summary="本轮未指定上传文件，已跳过报表数据核对。",
            )
        elif self.services.upload_services is None:
            stage = ValidationStageResult(
                stage_id="L6",
                stage_name="报表数据核对",
                status=ValidationStageStatus.FAILED,
                summary="上传文件分析服务当前不可用。",
                finding_codes=["UPLOAD_ANALYSIS_UNAVAILABLE"],
            )
        else:
            analyzed = analyze_uploaded_indicators(
                AnalyzeUploadedIndicatorsInput(file_key=file_key),
                context,
                state,
                self.services.upload_services,
            )
            if not analyzed.ok:
                stage = ValidationStageResult(
                    stage_id="L6",
                    stage_name="报表数据核对",
                    status=ValidationStageStatus.FAILED,
                    summary=analyzed.summary,
                    finding_codes=[analyzed.code],
                )
            else:
                state.last_tool_results.append(analyzed.model_dump(mode="json"))
                row = analyzed.data.get("row_comparison") or {}
                aggregate = analyzed.data.get("aggregate_comparison") or {}
                findings: list[str] = []
                details: dict[str, Any] = {
                    "file_name": analyzed.data.get("file_name"),
                    "row_count": analyzed.data.get("total_rows"),
                }
                status = ValidationStageStatus.WARNING
                summary = "文件已解析，但没有足够证据完成系统结果核对。"
                if row:
                    comparison_status = str(row.get("comparison_status") or "")
                    details.update({
                        "comparison_status": comparison_status,
                        "both_count": row.get("both_count"),
                        "system_only_count": row.get("system_only_count"),
                        "uploaded_only_count": row.get("uploaded_only_count"),
                        "field_difference_count": row.get("field_difference_count"),
                    })
                    if comparison_status == "indicator_mismatch":
                        status = ValidationStageStatus.FAILED
                        findings = ["UPLOADED_INDICATOR_MISMATCH"]
                        summary = str(row.get("message") or "上传文件指标与当前指标不一致。")
                    elif comparison_status == "row_level_compared":
                        different = sum(int(row.get(key) or 0) for key in (
                            "system_only_count",
                            "uploaded_only_count",
                            "field_difference_count",
                        ))
                        status = (
                            ValidationStageStatus.PASSED
                            if different == 0
                            else ValidationStageStatus.WARNING
                        )
                        findings = [] if different == 0 else ["ROW_COMPARISON_DIFFERENCES"]
                        summary = (
                            "上传明细与系统明细逐条一致。"
                            if different == 0
                            else "上传明细与系统明细存在逐条差异。"
                        )
                elif aggregate:
                    metrics = list(aggregate.get("metrics") or [])
                    mismatch_count = sum(1 for item in metrics if not item.get("match"))
                    details.update({
                        "comparison_status": "aggregate_compared",
                        "metric_count": len(metrics),
                        "mismatch_count": mismatch_count,
                    })
                    status = (
                        ValidationStageStatus.PASSED
                        if metrics and mismatch_count == 0
                        else ValidationStageStatus.WARNING
                    )
                    findings = [] if status is ValidationStageStatus.PASSED else ["AGGREGATE_COMPARISON_DIFFERENCES"]
                    summary = (
                        "上传汇总值与系统聚合结果一致。"
                        if status is ValidationStageStatus.PASSED
                        else "上传汇总值与系统聚合结果存在差异或证据不足。"
                    )
                else:
                    findings = ["REPORT_COMPARISON_EVIDENCE_INSUFFICIENT"]
                stage = ValidationStageResult(
                    stage_id="L6",
                    stage_name="报表数据核对",
                    status=status,
                    summary=summary,
                    finding_codes=findings,
                    safe_details=details,
                )
        stage.duration_ms = max(1, int((time.perf_counter() - started) * 1000))
        self._trace(
            stage,
            input_data={"file_key": file_key, "hospital_id": context.hospital_id},
            config_data={"stage": "L6", "tool": "analyze_uploaded_indicators"},
        )
        return stage

    def run(
        self,
        *,
        rule_id: str,
        stat_start_time: datetime,
        stat_end_time: datetime,
        file_key: str | None,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> ImplementationValidationReport:
        l1 = self._stage_l1(rule_id, state)
        l4, rule_name = self._stage_l4(rule_id, state)
        l5, result_data = self._stage_l5(
            rule_id=rule_id,
            stat_start_time=stat_start_time,
            stat_end_time=stat_end_time,
            context=context,
            state=state,
        )
        l6 = self._stage_l6(
            file_key=file_key,
            context=context,
            state=state,
        )
        stages = [l1, l4, l5, l6]
        return ImplementationValidationReport(
            report_id="IVR_" + uuid.uuid4().hex[:16],
            hospital_id=context.hospital_id,
            rule_id=rule_id,
            rule_name=rule_name,
            stat_start=stat_start_time.strftime("%Y-%m-%d %H:%M:%S"),
            stat_end=stat_end_time.strftime("%Y-%m-%d %H:%M:%S"),
            overall_status=_overall_status(stages),
            stages=stages,
            sql_id=str(result_data.get("sql_id") or "") or None,
            run_id=str(result_data.get("run_id") or "") or None,
            result_value=result_data.get("result_value"),
            numerator_count=result_data.get("numerator_count"),
            denominator_count=result_data.get("denominator_count"),
            file_key=file_key,
            created_at=datetime.now(timezone.utc),
        )
