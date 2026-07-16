from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext

from .contracts import CompiledPlan
from .time_resolver import ResolvedTimeRange


class EvidenceEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    evidence_id: str
    evidence_type: str
    source: str
    source_id: str | None = None
    hospital_id: str
    db_source_id: str | None = None
    rule_id: str | None = None
    rule_version: str | None = None
    field_mapping_version: str | None = None
    sql_id: str | None = None
    result_id: str | None = None
    start_time: datetime | None = None
    end_time: datetime | None = None
    trace_id: str
    tool_run_id: str | None = None
    context_digest: str | None = None
    created_at: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )


class VerificationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ok: bool
    code: str
    message: str = ""
    missing_facts: set[str] = Field(default_factory=set)


def _facts(state: AgentRunState) -> set[str]:
    result: set[str] = set()
    for item in state.evidence:
        if isinstance(item, dict):
            for value in item.get("fact_types") or []:
                fact = str(value)
                if fact == "rule_identity" and not item.get("source_id"):
                    continue
                result.add(fact)
    for item in state.last_tool_results:
        if not isinstance(item, dict) or item.get("ok") is not True:
            continue
        code = str(item.get("code") or "")
        data = item.get("data") or {}
        for evidence in item.get("evidence") or []:
            if isinstance(evidence, dict):
                result.update(
                    str(value) for value in evidence.get("fact_types") or []
                )
        if code == "EFFECTIVE_RULE_FOUND":
            result.add("rule_identity")
        elif code in {"SQL_OBJECT_PREPARED", "TRIAL_RUN_COMPLETED"}:
            result.add("stat_period")
        if isinstance(data, dict) and data.get("rule_id"):
            result.add("rule_identity")
    if "rule_identity" in result and ({"definition", "formula"} & result):
        result.add("effective_rule")
    return result


def _latest_result(state: AgentRunState, code: str) -> dict | None:
    for item in reversed(state.last_tool_results):
        if (
            isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == code
            and isinstance(item.get("data"), dict)
        ):
            return item["data"]
    return None


class PlanVerifier:
    def __init__(self, rate_tolerance: Decimal = Decimal("0.01")) -> None:
        self.rate_tolerance = rate_tolerance

    def verify(
        self,
        plan: CompiledPlan,
        state: AgentRunState,
        context: AgentRuntimeContext,
        *,
        expected_time: ResolvedTimeRange | None = None,
    ) -> VerificationResult:
        missing = set(plan.required_facts) - _facts(state)
        if missing:
            return VerificationResult(
                ok=False,
                code="REQUIRED_FACTS_MISSING",
                message="执行计划仍缺少必要事实。",
                missing_facts=missing,
            )
        prepared = _latest_result(state, "SQL_OBJECT_PREPARED")
        trial = _latest_result(state, "TRIAL_RUN_COMPLETED")
        if trial is not None:
            if prepared is None or not self._same_sql_chain(prepared, trial, context):
                return VerificationResult(
                    ok=False,
                    code="SQL_CHAIN_INCONSISTENT",
                    message="试运行结果与当前已校验 SQL 上下文不一致。",
                )
            if expected_time is not None and not self._same_period(
                prepared, expected_time
            ):
                return VerificationResult(
                    ok=False,
                    code="SQL_PERIOD_INCONSISTENT",
                    message="已校验 SQL 的统计周期与当前请求不一致。",
                )
            if not self._numeric_consistent(trial):
                return VerificationResult(
                    ok=False,
                    code="NUMERIC_RESULT_INCONSISTENT",
                    message="试运行的分子、分母与指标值不一致。",
                )
        return VerificationResult(ok=True, code="PLAN_VERIFIED")

    @staticmethod
    def _same_period(prepared: dict, expected: ResolvedTimeRange) -> bool:
        return (
            str(prepared.get("stat_start") or "")
            == expected.start_time.strftime("%Y-%m-%d %H:%M:%S")
            and str(prepared.get("stat_end") or "")
            == expected.end_time.strftime("%Y-%m-%d %H:%M:%S")
        )

    @staticmethod
    def _same_sql_chain(
        prepared: dict,
        trial: dict,
        context: AgentRuntimeContext,
    ) -> bool:
        keys = (
            "sql_id",
            "rule_id",
            "hospital_id",
            "db_source_id",
            "stat_start",
            "stat_end",
            "context_digest",
        )
        for key in keys:
            left = prepared.get(key)
            right = trial.get(key)
            if left is None or right is None or str(left) != str(right):
                return False
        return (
            str(prepared.get("hospital_id")) == context.hospital_id
            and str(prepared.get("db_source_id") or "")
            == str(context.db_source_id or "")
        )

    def _numeric_consistent(self, trial: dict) -> bool:
        numerator = trial.get("numerator_count")
        denominator = trial.get("denominator_count")
        reported = trial.get("result_value")
        try:
            num = Decimal(str(numerator))
            den = Decimal(str(denominator))
            value = Decimal(str(reported))
        except (InvalidOperation, TypeError, ValueError):
            return False
        if den <= 0:
            return False
        expected = num / den * Decimal("100")
        return abs(expected - value) <= self.rate_tolerance
