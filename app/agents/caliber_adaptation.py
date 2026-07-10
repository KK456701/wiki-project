"""Rule search, effective-caliber composition, and feedback operations."""

from __future__ import annotations

from typing import Any

from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult


class CaliberAdaptationAgent:
    agent_id = "caliber_adaptation"

    def __init__(self, rule_repository: Any):
        self.rule_repository = rule_repository

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        return self.rule_repository.search(query, limit=limit)

    def search_contract(self, query: str, limit: int = 5) -> RuleSearchResult:
        payload = dict(self.rule_repository.search(query, limit=limit))
        payload.setdefault("query", query)
        return RuleSearchResult.model_validate(payload)

    def resolve(self, rule_id: str, hospital_id: str | None) -> dict[str, Any]:
        return self.rule_repository.get_effective_rule(rule_id, hospital_id)

    def resolve_contract(
        self, rule_id: str, hospital_id: str | None
    ) -> EffectiveRule:
        return EffectiveRule.model_validate(
            self.rule_repository.get_effective_rule(rule_id, hospital_id)
        )

    def field_mapping(self, rule_id: str, hospital_id: str) -> dict[str, Any]:
        try:
            return self.rule_repository.get_field_mapping(rule_id, hospital_id)
        except TypeError:
            return self.rule_repository.get_field_mapping(rule_id)

    def field_mapping_contract(
        self, rule_id: str, hospital_id: str
    ) -> FieldMapping:
        payload = self.field_mapping(rule_id, hospital_id)
        normalized = dict(payload)
        normalized.setdefault("rule_id", rule_id)
        normalized.setdefault("hospital_id", hospital_id)
        return FieldMapping.model_validate(normalized)

    def preview_feedback(
        self, rule_id: str, hospital_id: str | None, query: str
    ) -> dict[str, Any]:
        return self.rule_repository.build_feedback_preview(rule_id, hospital_id, query)

    def submit_change(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.rule_repository.submit_change_request(payload)
