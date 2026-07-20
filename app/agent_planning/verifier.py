from __future__ import annotations

from decimal import Decimal, InvalidOperation

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_evidence import EvidenceAccessError, EvidenceEnvelope, EvidenceLedger

from .contracts import CompiledPlan
from .capability_registry import CapabilitySpecRegistry, get_capability_registry
from .facts import canonical_fact_type
from .time_resolver import ResolvedTimeRange


class VerificationResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ok: bool
    code: str
    message: str = ""
    missing_facts: set[str] = Field(default_factory=set)
    verified_evidence_ids: list[str] = Field(default_factory=list)


def _facts(state: AgentRunState) -> set[str]:
    result: set[str] = set()
    for item in state.evidence:
        if isinstance(item, dict):
            for value in item.get("fact_types") or []:
                fact = canonical_fact_type(value)
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
                    canonical_fact_type(value)
                    for value in evidence.get("fact_types") or []
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
    version = "plan-verifier-v1"

    def __init__(
        self,
        rate_tolerance: Decimal = Decimal("0.01"),
        registry: CapabilitySpecRegistry | None = None,
        evidence_ledger: EvidenceLedger | None = None,
    ) -> None:
        self.rate_tolerance = rate_tolerance
        self.registry = registry or get_capability_registry()
        self.evidence_ledger = evidence_ledger

    def verify(
        self,
        plan: CompiledPlan,
        state: AgentRunState,
        context: AgentRuntimeContext,
        *,
        expected_time: ResolvedTimeRange | None = None,
    ) -> VerificationResult:
        facts = _facts(state)
        missing = set(plan.required_facts) - facts
        for node in plan.nodes:
            spec = self.registry.get(node.capability)
            if node.capability.value != "compose_answer" and not spec.verifier(facts, spec):
                missing.update(spec.produces - facts)
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
        verified_ids: list[str] = []
        if self.evidence_ledger is not None and state.evidence_ids:
            try:
                verified_ids = self.evidence_ledger.verify_many(
                    state.evidence_ids,
                    context=context,
                    subtask_id=state.subtask_id or context.request_id,
                    verifier_version=self.version,
                    expected_rule_id=state.current_rule_id,
                    expected_stat_start=(
                        expected_time.start_time.strftime("%Y-%m-%d %H:%M:%S")
                        if expected_time is not None
                        else None
                    ),
                    expected_stat_end=(
                        expected_time.end_time.strftime("%Y-%m-%d %H:%M:%S")
                        if expected_time is not None
                        else None
                    ),
                    expected_sql_id=(
                        str(prepared.get("sql_id") or "")
                        if prepared is not None
                        else None
                    ),
                    legacy_tool_results=state.last_tool_results,
                )
            except EvidenceAccessError as exc:
                return VerificationResult(
                    ok=False,
                    code=exc.code,
                    message=str(exc),
                )
            state.verified_evidence_ids = list(dict.fromkeys([
                *state.verified_evidence_ids,
                *verified_ids,
            ]))
        return VerificationResult(
            ok=True,
            code="PLAN_VERIFIED",
            verified_evidence_ids=verified_ids,
        )

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
