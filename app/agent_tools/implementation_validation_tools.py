"""面向全面实施验收的单一顶层 Agent 工具。"""

from __future__ import annotations

from functools import partial

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.implementation_validation import (
    ImplementationValidationServices,
    ImplementationValidationWorkflow,
    ValidationStageStatus,
)

from .contracts import AgentTool, ToolEvidence, ToolResult, ToolRiskLevel
from .state_facts import has_verified_rule


class ValidateIndicatorImplementationInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rule_id: str = Field(min_length=1, max_length=128)
    stat_start_time: str = Field(min_length=1, max_length=64)
    stat_end_time: str = Field(min_length=1, max_length=64)
    file_key: str | None = Field(default=None, max_length=128)

    @model_validator(mode="after")
    def validate_period(self):
        from datetime import datetime

        start = datetime.fromisoformat(self.stat_start_time)
        end = datetime.fromisoformat(self.stat_end_time)
        if start >= end:
            raise ValueError("统计开始时间必须早于结束时间")
        return self


def validate_indicator_implementation(
    arguments: ValidateIndicatorImplementationInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ImplementationValidationServices,
) -> ToolResult:
    from datetime import datetime

    report = ImplementationValidationWorkflow(services).run(
        rule_id=arguments.rule_id,
        stat_start_time=datetime.fromisoformat(arguments.stat_start_time),
        stat_end_time=datetime.fromisoformat(arguments.stat_end_time),
        file_key=arguments.file_key,
        context=context,
        state=state,
    )
    stages = [stage.model_dump(mode="json") for stage in report.stages]
    status_groups = {
        status.value: [
            stage.stage_id
            for stage in report.stages
            if stage.status is status
        ]
        for status in ValidationStageStatus
    }
    warnings = [
        f"{stage.stage_id} {stage.stage_name}：{stage.summary}"
        for stage in report.stages
        if stage.status in {
            ValidationStageStatus.WARNING,
            ValidationStageStatus.FAILED,
        }
    ]
    fact_types = [
        "source_schema_validation",
        "rule_alignment_validation",
        "derived_sql_validation",
        "implementation_validation_report",
    ]
    if arguments.file_key:
        fact_types.append("report_data_validation")
    return ToolResult(
        ok=True,
        status="success",
        code="IMPLEMENTATION_VALIDATION_COMPLETED",
        summary=(
            "指标全面实施验收已完成，结论为通过。"
            if report.overall_status is ValidationStageStatus.PASSED
            else "指标全面实施验收已完成，存在警告。"
            if report.overall_status is ValidationStageStatus.WARNING
            else "指标全面实施验收已完成，存在未通过项。"
        ),
        data={
            "report_id": report.report_id,
            "report_schema_version": report.schema_version,
            "overall_status": report.overall_status.value,
            "rule_id": report.rule_id,
            "rule_name": report.rule_name,
            "hospital_id": report.hospital_id,
            "stat_start": report.stat_start,
            "stat_end": report.stat_end,
            "stages": stages,
            "passed_stages": status_groups[ValidationStageStatus.PASSED.value],
            "warning_stages": status_groups[ValidationStageStatus.WARNING.value],
            "failed_stages": status_groups[ValidationStageStatus.FAILED.value],
            "skipped_stages": status_groups[ValidationStageStatus.SKIPPED.value],
            "sql_id": report.sql_id,
            "run_id": report.run_id,
            "result_value": report.result_value,
            "numerator_count": report.numerator_count,
            "denominator_count": report.denominator_count,
            "file_key": report.file_key,
            "created_at": report.created_at.isoformat(),
        },
        evidence=[ToolEvidence(
            source="implementation_validation_workflow",
            source_id=report.report_id,
            version=ImplementationValidationWorkflow.version,
            fact_types=fact_types,
        )],
        warnings=warnings,
    )


def _state_has_verified_rule(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context
    return has_verified_rule(state)


def build_implementation_validation_tools(
    services: ImplementationValidationServices,
) -> list[AgentTool]:
    return [AgentTool(
        name="validate_indicator_implementation",
        description=(
            "仅当用户明确要求全面实施验收、上线验收、迁移核对或全链路实施验证时调用。"
            "内部固定执行字段映射与来源检查、规则口径对齐、受控 SQL 安全校验与只读试运行，"
            "若指定上传文件则继续执行报表数据核对；不要用于普通公式解释、查结果、生成 SQL 或异常诊断。"
        ),
        input_model=ValidateIndicatorImplementationInput,
        handler=partial(validate_indicator_implementation, services=services),
        risk_level=ToolRiskLevel.CONTROLLED_EXECUTION,
        timeout_seconds=150.0,
        required_permissions=frozenset({"indicator_read"}),
        availability=_state_has_verified_rule,
    )]
