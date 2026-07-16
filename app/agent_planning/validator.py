from __future__ import annotations

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict

from .contracts import PlanIntent, RequestPlan, RequestedOutput
from .time_resolver import ResolvedTimeRange, TimeRangeResolver


class FallbackCategory(str, Enum):
    USER_CLARIFICATION = "USER_CLARIFICATION"
    BUSINESS_CONFIRMATION = "BUSINESS_CONFIRMATION"
    ADMIN_APPROVAL = "ADMIN_APPROVAL"
    IMPLEMENTATION_SUPPORT = "IMPLEMENTATION_SUPPORT"
    SYSTEM_OPERATOR = "SYSTEM_OPERATOR"
    SECURITY_DENIAL = "SECURITY_DENIAL"


class PlanValidation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ok: bool
    code: str
    message: str = ""
    resolved_time: ResolvedTimeRange | None = None
    fallback_category: FallbackCategory | None = None


class PlanValidator:
    def __init__(self, resolver: TimeRangeResolver | None = None) -> None:
        self.resolver = resolver or TimeRangeResolver()

    def validate(self, plan: RequestPlan, *, now: datetime) -> PlanValidation:
        constraints = {item.strip().lower() for item in plan.constraints}
        if "patient_level_detail" in constraints:
            return PlanValidation(
                ok=False,
                code="PATIENT_DETAIL_FORBIDDEN",
                message="当前 Agent 不允许访问或返回患者明细。",
                fallback_category=FallbackCategory.SECURITY_DENIAL,
            )
        outputs = set(plan.requested_outputs)
        needs_database = RequestedOutput.TRIAL_RESULT in outputs
        needs_time_range = bool(outputs & {
            RequestedOutput.PREPARED_SQL_HANDLE,
            RequestedOutput.TRIAL_RESULT,
        }) or plan.intent in {
            PlanIntent.INDICATOR_SQL_PREPARE,
            PlanIntent.INDICATOR_TRIAL_RUN,
        }
        if needs_database and "no_database_access" in constraints:
            return PlanValidation(
                ok=False,
                code="DATABASE_ACCESS_CONFLICT",
                message="实际指标结果需要执行医院业务库只读聚合查询。",
                fallback_category=FallbackCategory.BUSINESS_CONFIRMATION,
            )
        resolved_time = None
        if needs_time_range:
            resolved_time = self.resolver.resolve(plan.time_expression, now=now)
            if resolved_time is None:
                return PlanValidation(
                    ok=False,
                    code="TIME_RANGE_AMBIGUOUS",
                    message="请明确需要统计的开始时间和结束时间。",
                    fallback_category=FallbackCategory.USER_CLARIFICATION,
                )
        return PlanValidation(
            ok=True,
            code="PLAN_VALID",
            resolved_time=resolved_time,
        )
