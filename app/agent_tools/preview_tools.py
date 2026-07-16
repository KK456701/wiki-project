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


class PreviewRuleChangeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rule_id: str = Field(min_length=1, max_length=128)
    change_description: str = Field(min_length=2, max_length=5000)


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


def _safe_change_version(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {
        key: value[key]
        for key in (
            "level",
            "status",
            "definition",
            "formula",
            "implementation_status",
        )
        if key in value
    }


def _safe_field_changes(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [
        {
            key: item[key]
            for key in ("field", "requested", "current", "changed")
            if key in item
        }
        for item in value
        if isinstance(item, dict)
    ]


def preview_rule_change(
    arguments: PreviewRuleChangeInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: PreviewToolServices,
) -> ToolResult:
    if not has_verified_rule(state, arguments.rule_id):
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="RULE_NOT_VERIFIED",
            summary="该指标尚未经过规则搜索或读取，不能预览口径变更。",
        )

    try:
        prepared = services.orchestrator.prepare_rule_request(
            query=arguments.change_description,
            hospital_id=context.hospital_id,
            intent="feedback",
            rule_id=arguments.rule_id,
        )
        preview = services.orchestrator.preview_feedback(prepared)
    except Exception:
        return ToolResult(
            ok=False,
            status="error",
            code="RULE_CHANGE_PREVIEW_FAILED",
            summary="本院口径变更预览失败，内部错误已记录。",
            retryable=True,
        )

    field_changes = _safe_field_changes(preview.get("field_changes"))
    changed_fields = [
        str(item.get("field") or "")
        for item in field_changes
        if item.get("changed") is True and item.get("field")
    ]
    affects_definition = "指标定义" in changed_fields
    affects_formula = "计算公式" in changed_fields
    requires_field_review = "实现状态" in changed_fields
    impact = {
        "changed_fields": changed_fields,
        "affects_definition": affects_definition,
        "affects_formula": affects_formula,
        "requires_field_review": requires_field_review,
        "requires_sql_regeneration": affects_formula or requires_field_review,
        "requires_version_increment": bool(changed_fields),
    }
    safe_data = {
        "rule_id": str(preview.get("rule_id") or arguments.rule_id),
        "rule_name": preview.get("rule_name"),
        "target_level": preview.get("target_level"),
        "current_effective_level": preview.get("current_effective_level"),
        "requested": _safe_change_version(preview.get("requested")),
        "current_effective": _safe_change_version(
            preview.get("current_effective")
        ),
        "field_changes": field_changes,
        "impact": impact,
        "message": preview.get("message"),
    }
    return ToolResult(
        ok=True,
        status="preview_ready",
        code="RULE_CHANGE_PREVIEWED",
        summary="本院口径变更预览已生成，尚未提交审批或发布。",
        data=safe_data,
        evidence=[ToolEvidence(
            source="rule_change_preview",
            source_id=safe_data["rule_id"],
            version=(
                str(safe_data["current_effective_level"])
                if safe_data["current_effective_level"]
                else None
            ),
            fact_types=["rule_change_preview"],
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


def _can_preview_rule_change(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    return (
        context.user_role in _IMPLEMENTATION_ROLES
        and has_verified_rule(state)
    )


def build_preview_tools(services: PreviewToolServices) -> list[AgentTool]:
    permission = frozenset({"indicator_read"})
    return [
        AgentTool(
            name="create_indicator_draft",
            description=(
                "根据医院指标业务描述创建不参与正式查询的工作草稿，返回分子、分母、"
                "统计周期和字段需求；不会提交审批、发布规则或执行 SQL。"
            ),
            input_model=CreateIndicatorDraftInput,
            handler=partial(create_indicator_draft, services=services),
            risk_level=ToolRiskLevel.PREVIEW_ONLY,
            timeout_seconds=30.0,
            required_permissions=permission,
            availability=_can_create_draft,
        ),
        AgentTool(
            name="preview_rule_change",
            description=(
                "根据本院口径调整描述预览与当前生效规则的字段差异及实施影响；"
                "不会提交、审批、发布或回退任何变更。"
            ),
            input_model=PreviewRuleChangeInput,
            handler=partial(preview_rule_change, services=services),
            risk_level=ToolRiskLevel.PREVIEW_ONLY,
            timeout_seconds=30.0,
            required_permissions=permission,
            availability=_can_preview_rule_change,
        ),
    ]
