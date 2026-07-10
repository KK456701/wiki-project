"""Unified routing across the five specialized indicator agents."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


INTENT_OWNERS = {
    "chat": "human_interaction",
    "query": "human_interaction",
    "feedback": "caliber_adaptation",
    "generate_sql": "indicator_generation",
    "trial_run": "indicator_generation",
    "diagnose": "root_cause_diagnosis",
    "metadata_sync": "metadata_parsing",
}
RULE_INTENTS = {"query", "feedback", "generate_sql", "trial_run", "diagnose"}


@dataclass
class PreparedRequest:
    query: str
    hospital_id: str | None
    intent: str
    retrieval_query: str = ""
    rule_id: str | None = None
    search: dict[str, Any] = field(default_factory=dict)
    effective_rule: dict[str, Any] = field(default_factory=dict)
    field_mapping: dict[str, Any] = field(default_factory=dict)
    custom_filters: list[dict[str, Any]] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


class CoreIndicatorOrchestrator:
    orchestrator_id = "core_indicator_orchestrator"

    def __init__(
        self,
        *,
        interaction: Any,
        caliber: Any,
        indicator_generation: Any,
        diagnosis: Any,
        metadata: Any,
    ):
        self.interaction = interaction
        self.caliber = caliber
        self.indicator_generation = indicator_generation
        self.diagnosis_agent = diagnosis
        self.metadata = metadata

    @staticmethod
    def owner_for_intent(intent: str) -> str:
        return INTENT_OWNERS.get(intent, "human_interaction")

    def prepare(
        self,
        query: str,
        hospital_id: str | None,
        memory_context: dict[str, Any] | None = None,
    ) -> PreparedRequest:
        errors: list[str] = []
        understood = self.interaction.understand(
            query,
            memory_context=memory_context,
            errors=errors,
        )
        intent = str(understood.get("intent") or "query")
        prepared = PreparedRequest(
            query=query,
            hospital_id=hospital_id,
            intent=intent,
            retrieval_query=str(understood.get("retrieval_query") or query),
            custom_filters=list(understood.get("custom_filters") or []),
            errors=errors,
        )
        if intent not in RULE_INTENTS:
            return prepared

        prepared.search = self.caliber.search(prepared.retrieval_query, limit=5)
        prepared.rule_id = prepared.search.get("resolved_rule_id")
        if (
            not prepared.rule_id
            and memory_context
            and memory_context.get("rule_id")
            and self.interaction.can_reuse_memory(query, intent)
        ):
            prepared.rule_id = str(memory_context["rule_id"])
            prepared.search["resolved_rule_id"] = prepared.rule_id
            prepared.search["context_source"] = "memory_last_rule"

        if not prepared.rule_id:
            return prepared
        prepared.effective_rule = self.caliber.resolve(
            prepared.rule_id, hospital_id
        )
        prepared.field_mapping = self.caliber.field_mapping(
            prepared.rule_id, hospital_id or ""
        )
        return prepared

    def answer(self, prepared: PreparedRequest) -> tuple[str, str]:
        if prepared.intent == "chat":
            return self.interaction.chat_answer(), "chat"
        if not prepared.rule_id:
            return "未命中规则。请提供更明确的指标名称或 rule_id。", "tool"
        return self.interaction.answer(
            prepared.query,
            prepared.effective_rule,
            errors=prepared.errors,
        )

    def preview_feedback(self, prepared: PreparedRequest) -> dict[str, Any]:
        self._require_rule(prepared)
        return self.caliber.preview_feedback(
            str(prepared.rule_id), prepared.hospital_id, prepared.query
        )

    def generate_indicator(
        self,
        prepared: PreparedRequest,
        *,
        stat_start_time: str,
        stat_end_time: str,
        trial_run: bool = False,
        generated_by: str = "agent",
    ) -> dict[str, Any]:
        self._require_rule(prepared)
        return self.indicator_generation.generate(
            query=prepared.query,
            hospital_id=str(prepared.hospital_id or ""),
            rule_id=str(prepared.rule_id),
            effective_rule=prepared.effective_rule,
            stat_start_time=stat_start_time,
            stat_end_time=stat_end_time,
            trial_run=trial_run,
            generated_by=generated_by,
            custom_filters=prepared.custom_filters,
        )

    def diagnose(
        self,
        prepared: PreparedRequest,
        *,
        trigger: str = "manual",
        related_sql_id: str | None = None,
        stat_period: str | None = None,
    ) -> dict[str, Any]:
        self._require_rule(prepared)
        return self.diagnosis_agent.run(
            hospital_id=str(prepared.hospital_id or ""),
            rule_id=str(prepared.rule_id),
            effective_rule=prepared.effective_rule,
            trigger=trigger,
            related_sql_id=related_sql_id,
            stat_period=stat_period,
        )

    def sync_metadata(
        self, provider: Any, hospital_id: str, db_name: str
    ) -> dict[str, Any]:
        return self.metadata.sync(provider, hospital_id, db_name)

    @staticmethod
    def _require_rule(prepared: PreparedRequest) -> None:
        if not prepared.rule_id or not prepared.effective_rule:
            raise ValueError("当前请求未解析出可执行的指标口径")
