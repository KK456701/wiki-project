from __future__ import annotations

import hashlib
import json

from .contracts import (
    CompiledPlan,
    PlanCapability,
    PlanIntent,
    PlanNode,
    RequestPlan,
    RequestedOutput,
)


_NODE_FACTS: dict[PlanCapability, tuple[set[str], set[str]]] = {
    PlanCapability.RESOLVE_INDICATOR: (set(), {"rule_identity"}),
    PlanCapability.RESOLVE_EFFECTIVE_RULE: (
        {"rule_identity"},
        {"effective_rule", "definition", "formula"},
    ),
    PlanCapability.RESOLVE_TIME_RANGE: (set(), {"stat_period"}),
    PlanCapability.INSPECT_IMPLEMENTATION: (
        {"effective_rule"},
        {"implementation_status", "field_mapping"},
    ),
    PlanCapability.PREPARE_VERIFIED_SQL: (
        {"effective_rule", "stat_period"},
        {"sql_validation"},
    ),
    PlanCapability.EXECUTE_TRIAL_RUN: (
        {"sql_validation"},
        {"trial_run"},
    ),
    PlanCapability.DIAGNOSE_INDICATOR: (
        {"effective_rule"},
        {"diagnosis"},
    ),
    PlanCapability.PREVIEW_RULE_CHANGE: (
        {"effective_rule"},
        {"rule_change_preview"},
    ),
    PlanCapability.ANALYZE_UPLOADED_FILE: (set(), {"file_analysis"}),
    PlanCapability.COMPOSE_ANSWER: (set(), set()),
}

_CAPABILITY_REQUIRED_FACT: dict[PlanCapability, str] = {
    PlanCapability.RESOLVE_INDICATOR: "rule_identity",
    PlanCapability.RESOLVE_EFFECTIVE_RULE: "effective_rule",
    PlanCapability.RESOLVE_TIME_RANGE: "stat_period",
    PlanCapability.INSPECT_IMPLEMENTATION: "implementation_status",
    PlanCapability.PREPARE_VERIFIED_SQL: "sql_validation",
    PlanCapability.EXECUTE_TRIAL_RUN: "trial_run",
    PlanCapability.DIAGNOSE_INDICATOR: "diagnosis",
    PlanCapability.PREVIEW_RULE_CHANGE: "rule_change_preview",
    PlanCapability.ANALYZE_UPLOADED_FILE: "file_analysis",
}


class PlanCompiler:
    def compile(self, plan: RequestPlan) -> CompiledPlan:
        capabilities = self._capabilities(plan)
        nodes = [
            PlanNode(
                capability=capability,
                requires=_NODE_FACTS[capability][0],
                produces=_NODE_FACTS[capability][1],
            )
            for capability in capabilities
        ]
        required_facts = self._required_facts(plan, nodes)
        canonical = json.dumps(
            plan.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        plan_id = "PLAN_" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
        return CompiledPlan(
            plan_id=plan_id,
            intent=plan.intent,
            goal=plan.goal,
            nodes=nodes,
            required_facts=required_facts,
            requested_outputs=set(plan.requested_outputs),
        )

    @staticmethod
    def _capabilities(plan: RequestPlan) -> list[PlanCapability]:
        outputs = set(plan.requested_outputs)
        if RequestedOutput.FILE_ANALYSIS in outputs:
            return [
                PlanCapability.ANALYZE_UPLOADED_FILE,
                PlanCapability.COMPOSE_ANSWER,
            ]
        operational_outputs = {
            RequestedOutput.PREPARED_SQL_HANDLE,
            RequestedOutput.TRIAL_RESULT,
            RequestedOutput.DIAGNOSIS,
            RequestedOutput.CHANGE_PREVIEW,
        }
        if (
            plan.intent in {PlanIntent.GENERAL_CHAT, PlanIntent.UNKNOWN}
            and not (outputs & operational_outputs)
        ):
            return [PlanCapability.COMPOSE_ANSWER]
        if plan.intent is PlanIntent.UPLOAD_ANALYSIS:
            return [
                PlanCapability.ANALYZE_UPLOADED_FILE,
                PlanCapability.COMPOSE_ANSWER,
            ]
        base = [
            PlanCapability.RESOLVE_INDICATOR,
            PlanCapability.RESOLVE_EFFECTIVE_RULE,
        ]
        if RequestedOutput.TRIAL_RESULT in outputs:
            return [
                *base,
                PlanCapability.RESOLVE_TIME_RANGE,
                PlanCapability.PREPARE_VERIFIED_SQL,
                PlanCapability.EXECUTE_TRIAL_RUN,
                PlanCapability.COMPOSE_ANSWER,
            ]
        if (
            plan.intent is PlanIntent.INDICATOR_SQL_PREPARE
            or RequestedOutput.PREPARED_SQL_HANDLE in outputs
        ):
            return [
                *base,
                PlanCapability.RESOLVE_TIME_RANGE,
                PlanCapability.PREPARE_VERIFIED_SQL,
                PlanCapability.COMPOSE_ANSWER,
            ]
        if (
            plan.intent is PlanIntent.INDICATOR_DIAGNOSIS
            or RequestedOutput.DIAGNOSIS in outputs
        ):
            return [
                *base,
                PlanCapability.INSPECT_IMPLEMENTATION,
                PlanCapability.DIAGNOSE_INDICATOR,
                PlanCapability.COMPOSE_ANSWER,
            ]
        if (
            plan.intent is PlanIntent.RULE_CHANGE_PREVIEW
            or RequestedOutput.CHANGE_PREVIEW in outputs
        ):
            return [
                *base,
                PlanCapability.PREVIEW_RULE_CHANGE,
                PlanCapability.COMPOSE_ANSWER,
            ]
        return [*base, PlanCapability.COMPOSE_ANSWER]

    @staticmethod
    def _required_facts(plan: RequestPlan, nodes: list[PlanNode]) -> set[str]:
        required: set[str] = set()
        for node in nodes:
            fact = _CAPABILITY_REQUIRED_FACT.get(node.capability)
            if fact:
                required.add(fact)
        output_facts = {
            RequestedOutput.DEFINITION: "definition",
            RequestedOutput.FORMULA: "formula",
            RequestedOutput.IMPLEMENTATION_STATUS: "implementation_status",
            RequestedOutput.PREPARED_SQL_HANDLE: "sql_validation",
            RequestedOutput.TRIAL_RESULT: "trial_run",
            RequestedOutput.DIAGNOSIS: "diagnosis",
            RequestedOutput.CHANGE_PREVIEW: "rule_change_preview",
            RequestedOutput.FILE_ANALYSIS: "file_analysis",
        }
        required.update(
            output_facts[output]
            for output in plan.requested_outputs
            if output in output_facts
        )
        return required
