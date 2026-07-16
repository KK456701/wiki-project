"""核心制度指标的模型可见只读工具。"""

from __future__ import annotations

from dataclasses import dataclass
from functools import partial
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import (
    AgentTool,
    ToolEvidence,
    ToolResult,
    ToolRiskLevel,
)
from app.agent_tools.registry import ToolRegistry
from app.agent_tools.state_facts import has_verified_rule


class ReadToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SearchIndicatorRulesInput(ReadToolInput):
    query: str = Field(min_length=1, max_length=200)
    limit: int = Field(default=5, ge=1, le=10)


class RuleReferenceInput(ReadToolInput):
    rule_id: str = Field(min_length=1, max_length=128)


@dataclass(frozen=True, slots=True)
class ReadToolServices:
    caliber: Any
    terminology: Any | None = None


def _normalization_payload(result: Any) -> dict[str, Any]:
    if result is None:
        return {}
    return {
        "normalized_text": str(result.normalized_text),
        "release_version": str(result.release_version),
        "matches": [
            {
                "matched_text": item.matched_text,
                "concept_code": item.concept_code,
                "canonical_name": item.canonical_name,
                "linked_rule_ids": list(item.linked_rule_ids),
            }
            for item in result.matches
            if item.retrieval_enabled
        ],
    }


def _retrieval_query(query: str, normalization: Any | None) -> str:
    if normalization is None:
        return query
    linked_rule_ids = sorted({
        rule_id
        for item in normalization.matches
        if item.retrieval_enabled
        for rule_id in item.linked_rule_ids
        if rule_id
    })
    if len(linked_rule_ids) == 1:
        return linked_rule_ids[0]
    return str(normalization.normalized_text or query)


def search_indicator_rules(
    arguments: SearchIndicatorRulesInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    del state
    normalization = (
        services.terminology.normalize(arguments.query, context.hospital_id)
        if services.terminology is not None
        else None
    )
    if normalization is not None and normalization.ambiguities:
        return ToolResult(
            ok=False,
            status="need_clarification",
            code="TERM_AMBIGUOUS",
            summary="问题中的术语存在多个可能含义，请明确具体指标。",
            data={
                "ambiguities": list(normalization.ambiguities),
                "terminology": _normalization_payload(normalization),
            },
        )

    retrieval_query = _retrieval_query(arguments.query, normalization)
    search = services.caliber.search_for_hospital_contract(
        retrieval_query,
        context.hospital_id,
        limit=arguments.limit,
    )
    payload = search.model_dump(mode="json")
    payload["retrieval_query"] = retrieval_query
    if normalization is not None:
        payload["terminology"] = _normalization_payload(normalization)
    matches = list(payload.get("matches") or [])
    resolved_rule_id = str(payload.get("resolved_rule_id") or "")
    if not matches and not resolved_rule_id:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="未找到匹配的核心制度指标。",
            data=payload,
            warnings=list(payload.get("warnings") or []),
        )

    evidence = [ToolEvidence(
        source=str(payload.get("rule_source") or "rule_repository"),
        source_id=resolved_rule_id or None,
        fact_types=["rule_identity"],
    )]
    if normalization is not None and normalization.release_version:
        evidence.append(ToolEvidence(
            source="terminology",
            version=str(normalization.release_version),
            fact_types=["term_normalization"],
        ))
    return ToolResult(
        ok=True,
        status="success",
        code="RULE_SEARCHED",
        summary=f"找到 {len(matches)} 个匹配指标。",
        data=payload,
        evidence=evidence,
        warnings=list(payload.get("warnings") or []),
    )


_RULE_RESULT_FIELDS = (
    "rule_id",
    "rule_name",
    "category",
    "effective_level",
    "definition",
    "formula",
    "numerator_rule",
    "denominator_rule",
    "filter_rule",
    "exclude_rule",
    "calculation_definition",
    "field_contract",
    "field_status",
    "sql_status",
    "national_version",
    "hospital_version",
    "overridden_fields",
    "fallback_chain",
    "rule_source",
    "warnings",
)


def _safe_rule_payload(rule: Any) -> dict[str, Any]:
    raw = rule.model_dump(mode="json")
    return {key: raw[key] for key in _RULE_RESULT_FIELDS if key in raw}


def _rule_evidence(
    payload: dict[str, Any],
    fact_types: list[str] | None = None,
) -> list[ToolEvidence]:
    version = payload.get("hospital_version")
    if version is None:
        version = payload.get("national_version")
    return [ToolEvidence(
        source=str(payload.get("rule_source") or "rule_repository"),
        source_id=str(payload.get("rule_id") or "") or None,
        version=str(version) if version is not None and str(version) else None,
        fact_types=fact_types or [
            "definition",
            "formula",
            "effective_level",
            "implementation_status",
        ],
    )]


def get_effective_rule(
    arguments: RuleReferenceInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    try:
        rule = services.caliber.resolve_contract(arguments.rule_id, context.hospital_id)
    except LookupError:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="当前医院未找到该指标的生效规则。",
            data={"rule_id": arguments.rule_id},
        )
    payload = _safe_rule_payload(rule)
    state.current_rule_id = arguments.rule_id
    return ToolResult(
        ok=True,
        status="success",
        code="EFFECTIVE_RULE_FOUND",
        summary=f"已读取 {payload.get('rule_name') or arguments.rule_id} 的生效规则。",
        data=payload,
        evidence=_rule_evidence(payload),
        warnings=list(payload.get("warnings") or []),
    )


def _required_business_fields(payload: dict[str, Any]) -> list[str]:
    contract = payload.get("field_contract") or {}
    if not isinstance(contract, dict):
        return []
    fields = contract.get("business_fields") or {}
    if isinstance(fields, dict):
        return sorted(str(key) for key in fields)
    return []


def _safe_mapping_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    allowed = (
        "business_field",
        "table_name",
        "column_name",
        "data_type",
        "status",
    )
    return [
        {key: item[key] for key in allowed if key in item}
        for item in items
    ]


def _safe_relations(relations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    allowed = (
        "left_table",
        "left_column",
        "right_table",
        "right_column",
        "join_type",
        "relation_source",
        "status",
    )
    return [
        {key: item[key] for key in allowed if key in item}
        for item in relations
    ]


def inspect_indicator_implementation(
    arguments: RuleReferenceInput,
    context: AgentRuntimeContext,
    state: AgentRunState,
    services: ReadToolServices,
) -> ToolResult:
    del state
    try:
        rule = services.caliber.resolve_contract(arguments.rule_id, context.hospital_id)
    except LookupError:
        return ToolResult(
            ok=False,
            status="not_found",
            code="RULE_NOT_FOUND",
            summary="当前医院未找到该指标，无法检查实施状态。",
            data={"rule_id": arguments.rule_id},
        )
    mapping = services.caliber.field_mapping_contract(
        arguments.rule_id,
        context.hospital_id,
    )
    rule_payload = _safe_rule_payload(rule)
    required = _required_business_fields(rule_payload)
    mapped = sorted(str(key) for key in mapping.fields)
    missing = sorted(set(required) - set(mapped))
    raw_items = [dict(item) for item in mapping.mapping_items]
    unconfirmed = sorted({
        str(item.get("business_field") or "")
        for item in raw_items
        if str(item.get("status") or "") != "confirmed"
        and str(item.get("business_field") or "")
    })
    payload = {
        "rule_id": arguments.rule_id,
        "hospital_id": context.hospital_id,
        "status": mapping.status,
        "dialect": mapping.dialect,
        "main_table": mapping.main_table,
        "mapped_fields": mapped,
        "required_business_fields": required,
        "missing_mappings": missing,
        "unconfirmed_mappings": unconfirmed,
        "mapping_items": _safe_mapping_items(raw_items),
        "relations": _safe_relations([dict(item) for item in mapping.relations]),
        "query_profile": mapping.query_profile,
        "sql_status": rule_payload.get("sql_status", "unavailable"),
    }
    return ToolResult(
        ok=True,
        status="success",
        code="IMPLEMENTATION_INSPECTED",
        summary=(
            "指标实施映射已确认。"
            if not missing and not unconfirmed and mapping.status == "confirmed"
            else "指标实施仍有缺失或未确认映射。"
        ),
        data=payload,
        evidence=_rule_evidence(
            rule_payload,
            ["field_mapping", "implementation_status"],
        ),
        warnings=[
            message
            for message, present in (
                ("存在缺失字段映射。", bool(missing)),
                ("存在未确认字段映射。", bool(unconfirmed)),
            )
            if present
        ],
    )


def _state_has_verified_rule(
    context: AgentRuntimeContext,
    state: AgentRunState,
) -> bool:
    del context
    return has_verified_rule(state)


def build_read_tools(services: ReadToolServices) -> list[AgentTool]:
    permission = frozenset({"indicator_read"})
    return [
        AgentTool(
            name="search_indicator_rules",
            description="根据指标名称、简称、错别字、医学同义词或主题搜索当前医院可用的核心制度指标。",
            input_model=SearchIndicatorRulesInput,
            handler=partial(search_indicator_rules, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
        ),
        AgentTool(
            name="get_effective_rule",
            description="读取当前医院指定指标的定义、公式、生效层级、版本和 SQL 可用状态，不返回 SQL 文本。",
            input_model=RuleReferenceInput,
            handler=partial(get_effective_rule, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
            availability=_state_has_verified_rule,
        ),
        AgentTool(
            name="inspect_indicator_implementation",
            description="检查当前医院指定指标的字段映射、缺失项、未确认项、关联关系和实施状态，不读取患者数据。",
            input_model=RuleReferenceInput,
            handler=partial(inspect_indicator_implementation, services=services),
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
            availability=_state_has_verified_rule,
        ),
    ]


def build_read_tool_registry(services: ReadToolServices) -> ToolRegistry:
    return ToolRegistry(build_read_tools(services))
