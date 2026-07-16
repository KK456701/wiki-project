from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState

from .contracts import CompiledPlan, PlanCapability
from .validator import FallbackCategory, PlanValidation


class ControllerAction(str, Enum):
    EXECUTE_TOOL = "execute_tool"
    COMPOSE_ANSWER = "compose_answer"
    FALLBACK = "fallback"


class ControllerDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: ControllerAction
    capability: PlanCapability | None = None
    tool_names: list[str] = Field(default_factory=list, max_length=2)
    code: str = ""
    message: str = ""
    fallback_category: FallbackCategory | None = None


_CAPABILITY_TOOLS: dict[PlanCapability, tuple[str, ...]] = {
    PlanCapability.RESOLVE_INDICATOR: ("search_indicator_rules",),
    PlanCapability.RESOLVE_EFFECTIVE_RULE: ("get_effective_rule",),
    PlanCapability.INSPECT_IMPLEMENTATION: ("inspect_indicator_implementation",),
    PlanCapability.PREPARE_VERIFIED_SQL: ("prepare_indicator_sql",),
    PlanCapability.EXECUTE_TRIAL_RUN: ("trial_run_indicator_sql",),
    PlanCapability.DIAGNOSE_INDICATOR: ("diagnose_indicator_issue",),
    PlanCapability.PREVIEW_RULE_CHANGE: ("preview_rule_change",),
    PlanCapability.ANALYZE_UPLOADED_FILE: ("analyze_uploaded_indicators",),
}


def _state_facts(state: AgentRunState, validation: PlanValidation) -> set[str]:
    facts: set[str] = set()
    for evidence in state.evidence:
        if not isinstance(evidence, dict):
            continue
        for item in evidence.get("fact_types") or []:
            fact = str(item)
            if fact == "rule_identity" and not evidence.get("source_id"):
                continue
            facts.add(fact)
    for result in state.last_tool_results:
        if not isinstance(result, dict) or result.get("ok") is not True:
            continue
        code = str(result.get("code") or "")
        if code == "EFFECTIVE_RULE_FOUND":
            facts.add("effective_rule")
        if code == "SQL_OBJECT_PREPARED":
            facts.add("sql_validation")
        if code == "TRIAL_RUN_COMPLETED":
            facts.add("trial_run")
    if "rule_identity" in facts and ({"definition", "formula"} & facts):
        facts.add("effective_rule")
    if validation.resolved_time is not None:
        facts.add("stat_period")
    if state.current_rule_id:
        facts.add("rule_identity")
    return facts


class AgentStateController:
    def next_decision(
        self,
        plan: CompiledPlan,
        validation: PlanValidation,
        state: AgentRunState,
    ) -> ControllerDecision:
        if not validation.ok:
            return ControllerDecision(
                action=ControllerAction.FALLBACK,
                code=validation.code,
                message=validation.message,
                fallback_category=validation.fallback_category,
            )
        blocking = self._blocking_failure(state)
        if blocking is not None:
            return blocking
        ambiguity = self._indicator_ambiguity(state)
        if ambiguity:
            return ControllerDecision(
                action=ControllerAction.FALLBACK,
                capability=PlanCapability.RESOLVE_INDICATOR,
                code="INDICATOR_AMBIGUOUS",
                message=ambiguity,
                fallback_category=FallbackCategory.USER_CLARIFICATION,
            )
        facts = _state_facts(state, validation)
        for node in plan.nodes:
            capability = node.capability
            if capability is PlanCapability.COMPOSE_ANSWER:
                continue
            if self._is_complete(capability, facts):
                continue
            if capability is PlanCapability.RESOLVE_TIME_RANGE:
                return ControllerDecision(
                    action=ControllerAction.FALLBACK,
                    capability=capability,
                    code="TIME_RANGE_AMBIGUOUS",
                    message="请明确需要统计的开始时间和结束时间。",
                    fallback_category=FallbackCategory.USER_CLARIFICATION,
                )
            return ControllerDecision(
                action=ControllerAction.EXECUTE_TOOL,
                capability=capability,
                tool_names=list(_CAPABILITY_TOOLS.get(capability, ()))[:2],
                code="NEXT_CAPABILITY",
            )
        return ControllerDecision(
            action=ControllerAction.COMPOSE_ANSWER,
            capability=PlanCapability.COMPOSE_ANSWER,
            code="PLAN_FACTS_READY",
        )

    @staticmethod
    def _indicator_ambiguity(state: AgentRunState) -> str:
        for result in reversed(state.last_tool_results):
            if not isinstance(result, dict) or result.get("code") != "RULE_SEARCHED":
                continue
            data = result.get("data") or {}
            if not isinstance(data, dict) or data.get("resolved_rule_id"):
                return ""
            matches = data.get("matches") or []
            if len(matches) <= 1:
                return ""
            names = [
                str(item.get("rule_name") or item.get("rule_id") or "")
                for item in matches[:3]
                if isinstance(item, dict)
            ]
            return "找到多个可能的指标，请明确选择：" + "、".join(
                name for name in names if name
            )
        return ""

    @staticmethod
    def _blocking_failure(state: AgentRunState) -> ControllerDecision | None:
        categories = {
            "TRIAL_RUN_FAILED": FallbackCategory.SYSTEM_OPERATOR,
            "TOOL_TIMEOUT": FallbackCategory.SYSTEM_OPERATOR,
            "TOOL_EXECUTION_FAILED": FallbackCategory.SYSTEM_OPERATOR,
            "FIELD_PRECHECK_FAILED": FallbackCategory.IMPLEMENTATION_SUPPORT,
            "PERMISSION_DENIED": FallbackCategory.SECURITY_DENIAL,
            "PATIENT_DETAIL_FORBIDDEN": FallbackCategory.SECURITY_DENIAL,
        }
        for result in reversed(state.last_tool_results):
            if not isinstance(result, dict) or result.get("ok") is True:
                continue
            code = str(result.get("code") or "")
            category = categories.get(code)
            if category is None:
                return None
            return ControllerDecision(
                action=ControllerAction.FALLBACK,
                code=code,
                message=str(result.get("summary") or "当前执行环境无法继续处理。"),
                fallback_category=category,
            )
        return None

    @staticmethod
    def _is_complete(capability: PlanCapability, facts: set[str]) -> bool:
        completion_fact = {
            PlanCapability.RESOLVE_INDICATOR: "rule_identity",
            PlanCapability.RESOLVE_EFFECTIVE_RULE: "effective_rule",
            PlanCapability.RESOLVE_TIME_RANGE: "stat_period",
            PlanCapability.INSPECT_IMPLEMENTATION: "implementation_status",
            PlanCapability.PREPARE_VERIFIED_SQL: "sql_validation",
            PlanCapability.EXECUTE_TRIAL_RUN: "trial_run",
            PlanCapability.DIAGNOSE_INDICATOR: "diagnosis",
            PlanCapability.PREVIEW_RULE_CHANGE: "rule_change_preview",
            PlanCapability.ANALYZE_UPLOADED_FILE: "file_analysis",
        }.get(capability)
        return completion_fact is None or completion_fact in facts
