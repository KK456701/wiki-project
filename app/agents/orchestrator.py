"""Unified routing across the five specialized indicator agents."""

from __future__ import annotations

import time
from typing import Any

from app.agents.contracts import (
    DiagnosisResult,
    EffectiveRule,
    FieldMapping,
    IntentResult,
    MetadataPrecheckResult,
    MetadataSyncResult,
    PreparedRequest,
    RuleSearchResult,
    SQLGenerationResult,
)

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
        understood = self.understand_request(query, memory_context, errors)
        prepared = self.create_request(query, hospital_id, understood, errors)
        self.search_request(prepared, memory_context)
        self.resolve_request(prepared)
        return prepared

    def understand_request(
        self,
        query: str,
        memory_context: dict[str, Any] | None = None,
        errors: list[str] | None = None,
    ) -> IntentResult:
        error_list = errors if errors is not None else []
        if hasattr(self.interaction, "understand_contract"):
            return self.interaction.understand_contract(
                query, memory_context=memory_context, errors=error_list
            )
        return IntentResult.model_validate(
            self.interaction.understand(
                query, memory_context=memory_context, errors=error_list
            )
        )

    @staticmethod
    def create_request(
        query: str,
        hospital_id: str | None,
        understood: IntentResult,
        errors: list[str] | None = None,
    ) -> PreparedRequest:
        return PreparedRequest(
            query=query,
            hospital_id=hospital_id,
            intent=understood.intent,
            retrieval_query=str(understood.get("retrieval_query") or query),
            custom_filters=list(understood.get("custom_filters") or []),
            errors=errors or [],
        )

    def search_request(
        self,
        prepared: PreparedRequest,
        memory_context: dict[str, Any] | None = None,
    ) -> PreparedRequest:
        if prepared.intent not in RULE_INTENTS:
            return prepared

        if hasattr(self.caliber, "search_contract"):
            search_result = self.caliber.search_contract(
                prepared.retrieval_query, limit=5
            )
        else:
            search_result = self.caliber.search(prepared.retrieval_query, limit=5)
        if isinstance(search_result, RuleSearchResult):
            prepared.search = search_result
        else:
            search_payload = dict(search_result)
            search_payload.setdefault("query", prepared.retrieval_query)
            prepared.search = RuleSearchResult.model_validate(search_payload)
        prepared.rule_id = prepared.search.get("resolved_rule_id")
        if (
            not prepared.rule_id
            and memory_context
            and memory_context.get("rule_id")
            and self.interaction.can_reuse_memory(prepared.query, prepared.intent)
        ):
            prepared.rule_id = str(memory_context["rule_id"])
            prepared.search["resolved_rule_id"] = prepared.rule_id
            prepared.search["context_source"] = "memory_last_rule"
        return prepared

    def resolve_request(self, prepared: PreparedRequest) -> PreparedRequest:
        if not prepared.rule_id:
            return prepared
        if hasattr(self.caliber, "resolve_contract"):
            prepared.effective_rule = self.caliber.resolve_contract(
                prepared.rule_id, prepared.hospital_id
            )
        else:
            prepared.effective_rule = EffectiveRule.model_validate(
                self.caliber.resolve(prepared.rule_id, prepared.hospital_id)
            )
        if hasattr(self.caliber, "field_mapping_contract"):
            prepared.field_mapping = self.caliber.field_mapping_contract(
                prepared.rule_id, prepared.hospital_id or ""
            )
        else:
            prepared.field_mapping = FieldMapping.model_validate(
                self.caliber.field_mapping(
                    prepared.rule_id, prepared.hospital_id or ""
                )
            )
        return prepared

    def prepare_rule_request(
        self,
        *,
        query: str,
        hospital_id: str | None,
        intent: str,
        rule_id: str,
        custom_filters: list[dict[str, Any]] | None = None,
    ) -> PreparedRequest:
        prepared = PreparedRequest(
            query=query,
            hospital_id=hospital_id,
            intent=intent,
            retrieval_query=rule_id,
            rule_id=rule_id,
            custom_filters=custom_filters or [],
        )
        return self.resolve_request(prepared)

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

    def chat_answer(self) -> str:
        return self.interaction.chat_answer()

    def answer_from_rule(self, effective_rule: Any) -> str:
        return self.interaction.answer_from_rule(effective_rule)

    def build_answer_prompt(self, query: str, effective_rule: Any) -> str:
        return self.interaction.build_answer_prompt(query, effective_rule)

    def answer_passes_guard(self, answer: str, effective_rule: Any) -> bool:
        return self.interaction.answer_passes_guard(answer, effective_rule)

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
        precheck_started = time.perf_counter()
        precheck = getattr(
            self.metadata, "precheck_contract", self.metadata.precheck
        )(
            str(prepared.hospital_id or ""),
            str(prepared.rule_id),
        )
        precheck_contract = (
            precheck
            if isinstance(precheck, MetadataPrecheckResult)
            else MetadataPrecheckResult.model_validate(precheck)
        )
        precheck_duration_ms = max(
            1, int((time.perf_counter() - precheck_started) * 1000)
        )
        if not precheck_contract.ok:
            missing_mappings = precheck_contract.missing_mappings
            missing_columns = precheck_contract.missing_columns
            return SQLGenerationResult(
                status="field_precheck_failed",
                precheck=precheck_contract,
                message=(
                    f"字段预校验未通过。缺失映射: {missing_mappings}。"
                    f"缺失字段: {missing_columns}"
                ),
                node_timings={"field_mapping_precheck": precheck_duration_ms},
            ).model_dump(by_alias=True, exclude_none=True)
        generate = getattr(
            self.indicator_generation,
            "generate_contract",
            self.indicator_generation.generate,
        )
        result = generate(
            query=prepared.query,
            hospital_id=str(prepared.hospital_id or ""),
            rule_id=str(prepared.rule_id),
            effective_rule=prepared.effective_rule.model_dump(),
            stat_start_time=stat_start_time,
            stat_end_time=stat_end_time,
            precheck=precheck_contract.model_dump(exclude_none=True),
            trial_run=trial_run,
            generated_by=generated_by,
            custom_filters=[item.model_dump() for item in prepared.custom_filters],
        )
        contract = (
            result
            if isinstance(result, SQLGenerationResult)
            else SQLGenerationResult.model_validate(result)
        )
        contract.node_timings["field_mapping_precheck"] = precheck_duration_ms
        return contract.model_dump(by_alias=True, exclude_none=True)

    def diagnose(
        self,
        prepared: PreparedRequest,
        *,
        trigger: str = "manual",
        related_sql_id: str | None = None,
        stat_period: str | None = None,
    ) -> dict[str, Any]:
        self._require_rule(prepared)
        diagnose = getattr(
            self.diagnosis_agent,
            "run_contract",
            self.diagnosis_agent.run,
        )
        result = diagnose(
            hospital_id=str(prepared.hospital_id or ""),
            rule_id=str(prepared.rule_id),
            effective_rule=prepared.effective_rule.model_dump(),
            trigger=trigger,
            related_sql_id=related_sql_id,
            stat_period=stat_period,
        )
        contract = (
            result
            if isinstance(result, DiagnosisResult)
            else DiagnosisResult.model_validate(result)
        )
        return contract.model_dump(exclude_none=True)

    def sync_metadata(
        self, provider: Any, hospital_id: str, db_name: str
    ) -> dict[str, Any]:
        sync = getattr(self.metadata, "sync_contract", self.metadata.sync)
        result = sync(provider, hospital_id, db_name)
        contract = (
            result
            if isinstance(result, MetadataSyncResult)
            else MetadataSyncResult.model_validate(result)
        )
        return contract.model_dump(exclude_none=True)

    @staticmethod
    def _require_rule(prepared: PreparedRequest) -> None:
        if not prepared.rule_id or not prepared.effective_rule:
            raise ValueError("当前请求未解析出可执行的指标口径")
