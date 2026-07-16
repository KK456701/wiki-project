"""指标工作草稿和本院口径变更预览工具。"""

from __future__ import annotations

from dataclasses import dataclass
from functools import partial
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import AgentTool, ToolEvidence, ToolResult, ToolRiskLevel
from app.agent_tools.state_facts import has_verified_rule


_IMPLEMENTATION_ROLES = {"implementer", "admin", "developer"}


class CreateIndicatorDraftInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    description: str = Field(min_length=10, max_length=5000)


@dataclass(frozen=True, slots=True)
class PreviewToolServices:
    orchestrator: Any


def _missing_information(result: dict[str, Any]) -> list[str]:
    return [
        label
        for field, label in (
            ("numerator_rule", "分子规则"),
            ("denominator_rule", "分母规则"),
            ("stat_cycle", "统计周期"),
            ("metadata_requirements", "字段需求"),
        )
        if not result.get(field)
    ]


def create_indicator_draft(
    arguments: CreateIndicatorDraftInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: PreviewToolServices,
) -> ToolResult:
    try:
        result = services.orchestrator.create_indicator_draft(
            arguments.description,
            context.hospital_id,
            context.user_id,
        )
    except Exception:
        return ToolResult(
            ok=False,
            status="error",
            code="DRAFT_CREATION_FAILED",
            summary="指标草稿创建失败，内部错误已记录。",
            retryable=True,
        )
    draft_id = str(result.get("draft_id") or "")
    if not draft_id:
        return ToolResult(
            ok=False,
            status="error",
            code="DRAFT_CREATION_FAILED",
            summary="指标草稿创建失败，未获得草稿标识。",
        )
    safe_data = {
        key: result.get(key)
        for key in (
            "draft_id",
            "status",
            "current_version",
            "index_name",
            "index_desc",
            "stat_cycle",
            "numerator_rule",
            "denominator_rule",
            "filter_rule",
            "exclude_rule",
            "metric_type",
            "metadata_requirements",
        )
    }
    safe_data["missing_information"] = _missing_information(result)
    state.last_draft_id = draft_id
    return ToolResult(
        ok=True,
        status="preview_ready",
        code="INDICATOR_DRAFT_CREATED",
        summary="指标工作草稿已创建，尚未提交审批或发布。",
        data=safe_data,
        evidence=[ToolEvidence(
            source="indicator_draft",
            source_id=draft_id,
            version=str(result.get("current_version") or "") or None,
            fact_types=["indicator_draft"],
        )],
    )


def _can_create_draft(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    return (
        context.user_role in _IMPLEMENTATION_ROLES
        and not has_verified_rule(state)
    )


def build_preview_tools(services: PreviewToolServices) -> list[AgentTool]:
    return [AgentTool(
        name="create_indicator_draft",
        description=(
            "根据医院指标业务描述创建不参与正式查询的工作草稿，返回分子、分母、"
            "统计周期和字段需求；不会提交审批、发布规则或执行 SQL。"
        ),
        input_model=CreateIndicatorDraftInput,
        handler=partial(create_indicator_draft, services=services),
        risk_level=ToolRiskLevel.PREVIEW_ONLY,
        timeout_seconds=30.0,
        required_permissions=frozenset({"indicator_read"}),
        availability=_can_create_draft,
    )]
