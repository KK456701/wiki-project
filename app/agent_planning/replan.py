from __future__ import annotations

from app.agent_runtime.contracts import AgentRunState

from .validator import FallbackCategory
from .failures import FailureClass, classify_failure

_REPLANNABLE_FAILURE_CLASSES = {
    FailureClass.SEMANTIC_PLAN_ERROR,
    FailureClass.TASK_TYPE_ERROR,
    FailureClass.USER_GOAL_CHANGED,
    FailureClass.ALTERNATIVE_DIRECTION_AVAILABLE,
}

_FALLBACKS = {
    "INDICATOR_AMBIGUOUS": FallbackCategory.USER_CLARIFICATION,
    "TIME_RANGE_AMBIGUOUS": FallbackCategory.USER_CLARIFICATION,
    "DATABASE_ACCESS_CONFLICT": FallbackCategory.BUSINESS_CONFIRMATION,
    "FIELD_MAPPING_MISSING": FallbackCategory.IMPLEMENTATION_SUPPORT,
    "FORMAL_WRITE_REQUIRED": FallbackCategory.ADMIN_APPROVAL,
    "DATABASE_UNAVAILABLE": FallbackCategory.SYSTEM_OPERATOR,
    "PATIENT_DETAIL_FORBIDDEN": FallbackCategory.SECURITY_DENIAL,
    "PERMISSION_DENIED": FallbackCategory.SECURITY_DENIAL,
}


class ReplanPolicy:
    def __init__(self, max_replan_count: int = 1) -> None:
        self.max_replan_count = max(0, max_replan_count)

    def can_replan(self, state: AgentRunState, failure_code: str) -> bool:
        return (
            self.classify(failure_code) in _REPLANNABLE_FAILURE_CLASSES
            and state.replan_count < self.max_replan_count
        )

    @staticmethod
    def classify(failure_code: str) -> FailureClass:
        return classify_failure(failure_code)

    @staticmethod
    def record_failure(state: AgentRunState, plan_fingerprint: str) -> None:
        state.replan_count += 1
        if plan_fingerprint not in state.failed_plan_fingerprints:
            state.failed_plan_fingerprints.append(plan_fingerprint)

    @staticmethod
    def accept_plan(state: AgentRunState, plan_fingerprint: str) -> bool:
        return plan_fingerprint not in state.failed_plan_fingerprints

    @staticmethod
    def fallback_for(failure_code: str) -> FallbackCategory:
        return _FALLBACKS.get(
            failure_code,
            FallbackCategory.SYSTEM_OPERATOR,
        )
