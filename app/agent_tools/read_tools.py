"""核心制度指标的模型可见只读工具。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import ToolEvidence, ToolResult


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
