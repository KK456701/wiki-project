"""现有三层诊断链路的模型安全工具适配。"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import partial
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import (
    AgentTool,
    ToolEvidence,
    ToolResult,
    ToolRiskLevel,
)
from app.agent_tools.state_facts import has_verified_rule


_SENSITIVE_PARAMETER_PARTS = (
    "password",
    "secret",
    "token",
    "authorization",
    "connection",
    "db_url",
)


def _contains_sensitive_key(value: Any) -> bool:
    if isinstance(value, dict):
        for name, item in value.items():
            normalized = str(name).lower()
            if any(part in normalized for part in _SENSITIVE_PARAMETER_PARTS):
                return True
            if _contains_sensitive_key(item):
                return True
    if isinstance(value, (list, tuple)):
        return any(_contains_sensitive_key(item) for item in value)
    return False


class DiagnoseIndicatorIssueInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rule_id: str = Field(min_length=1, max_length=128)
    issue_description: str = Field(min_length=1, max_length=1000)
    pasted_sql: str | None = Field(default=None, max_length=20_000)
    declared_params: dict[str, Any] = Field(default_factory=dict)
    stat_period: str | None = Field(default=None, max_length=64)

    @field_validator("declared_params")
    @classmethod
    def reject_sensitive_parameter_names(cls, value: dict[str, Any]):
        if _contains_sensitive_key(value):
            raise ValueError("诊断参数不能包含凭据或连接信息")
        return value


@dataclass(frozen=True, slots=True)
class DiagnosisToolServices:
    orchestrator: Any


def _query_text(arguments: DiagnoseIndicatorIssueInput) -> str:
    parts = [arguments.issue_description.strip()]
    if arguments.declared_params:
        parts.append(
            "用户声明参数："
            + json.dumps(
                arguments.declared_params,
                ensure_ascii=False,
                sort_keys=True,
                default=str,
            )
        )
    if arguments.pasted_sql:
        parts.append(f"用户粘贴 SQL：\n```sql\n{arguments.pasted_sql}\n```")
    return "\n\n".join(parts)


def _mapping(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    return {}


def _safe_check(value: Any) -> dict[str, Any]:
    raw = _mapping(value)
    return {
        key: str(raw.get(key) or "")
        for key in ("status", "message", "repair_suggest")
        if raw.get(key) is not None
    }


def _safe_layer(value: Any) -> dict[str, Any]:
    raw = _mapping(value)
    return {
        "layer": int(raw.get("layer") or 0),
        "layer_name": str(raw.get("layer_name") or ""),
        "ok": bool(raw.get("ok")),
        "checks": [_safe_check(item) for item in (raw.get("checks") or [])],
    }


def diagnose_indicator_issue(
    arguments: DiagnoseIndicatorIssueInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: DiagnosisToolServices,
) -> ToolResult:
    if not has_verified_rule(state, arguments.rule_id):
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="RULE_NOT_VERIFIED",
            summary="该指标尚未经过规则搜索或读取，不能启动诊断。",
        )
    prepared = services.orchestrator.prepare_rule_request(
        query=_query_text(arguments),
        hospital_id=context.hospital_id,
        intent="diagnose",
        rule_id=arguments.rule_id,
    )
    result = services.orchestrator.diagnose(
        prepared,
        trigger="agent_tool",
        stat_period=arguments.stat_period,
    )
    if result.get("ok") is False:
        return ToolResult(
            ok=False,
            status="error",
            code="DIAGNOSIS_FAILED",
            summary="诊断执行失败，未获得可用结论。",
            retryable=False,
        )

    report_id = str(result.get("report_id") or "") or None
    if report_id:
        state.last_diagnosis_id = report_id
    diagnosis_status = str(result.get("diagnose_status") or "success")
    return ToolResult(
        ok=True,
        status="success",
        code="INDICATOR_DIAGNOSED",
        summary="指标诊断已完成。",
        data={
            "rule_id": arguments.rule_id,
            "diagnose_status": diagnosis_status,
            "report_id": report_id,
            "summary": str(result.get("summary") or "")[:2000],
            "user_summary": str(result.get("user_summary") or "")[:2000],
            "layers": [_safe_layer(item) for item in (result.get("layers") or [])],
        },
        evidence=[ToolEvidence(
            source="diagnosis_report",
            source_id=report_id,
            version=diagnosis_status,
            fact_types=["diagnosis"],
        )],
    )


def _state_has_verified_rule(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context
    return (
        has_verified_rule(state)
        and state.current_request_kind in {None, "diagnosis"}
    )


def build_diagnosis_tools(services: DiagnosisToolServices) -> list[AgentTool]:
    return [AgentTool(
        name="diagnose_indicator_issue",
        description=(
            "仅当用户明确要求排查异常、诊断原因、解释结果不一致或算不对时调用；"
            "不要用于普通公式解释、统计周期变更、结果试运行、SQL 生成或“从某日期开始怎么算”。"
            "对已确认指标执行结构、口径和数据质量诊断；可接收用户明确粘贴的只读 SQL，"
            "所有 SQL 仍经过现有安全诊断链，不返回患者明细。"
        ),
        input_model=DiagnoseIndicatorIssueInput,
        handler=partial(diagnose_indicator_issue, services=services),
        risk_level=ToolRiskLevel.CONTROLLED_EXECUTION,
        timeout_seconds=60.0,
        required_permissions=frozenset({"indicator_read"}),
        availability=_state_has_verified_rule,
    )]
