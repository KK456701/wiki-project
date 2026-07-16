"""从经过校验的工具结果中提取 Agent 运行事实。"""

from __future__ import annotations

from typing import Any

from app.agent_runtime.contracts import AgentRunState


def _rule_evidence_id(value: Any) -> str:
    if not isinstance(value, dict):
        return ""
    if "rule_identity" not in (value.get("fact_types") or []):
        return ""
    return str(value.get("source_id") or "")


def verified_rule_ids(state: AgentRunState) -> set[str]:
    result: set[str] = set()
    for item in [*state.last_tool_results, *state.evidence]:
        if not isinstance(item, dict):
            continue
        if "ok" in item and item.get("ok") is not True:
            continue
        data = item.get("data") if isinstance(item.get("data"), dict) else item
        rule_id = str(data.get("resolved_rule_id") or data.get("rule_id") or "")
        if rule_id:
            result.add(rule_id)
        evidence_items = item.get("evidence") or []
        if "fact_types" in item:
            evidence_items = [item, *evidence_items]
        for evidence in evidence_items:
            evidence_id = _rule_evidence_id(evidence)
            if evidence_id:
                result.add(evidence_id)
    return result


def has_verified_rule(
    state: AgentRunState,
    rule_id: str | None = None,
) -> bool:
    rule_ids = verified_rule_ids(state)
    return bool(rule_ids) if rule_id is None else rule_id in rule_ids


def has_active_sql(
    state: AgentRunState,
    sql_id: str | None = None,
) -> bool:
    return (
        bool(state.validated_sql_ids)
        if sql_id is None
        else sql_id in state.validated_sql_ids
    )
