from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass
import re
from typing import Any, Protocol

from app.agent_runtime.contracts import AgentRunState
from app.diagnose.evidence import extract_pasted_evidence

from .contracts import PlanCapability, PlanIntent, RequestPlan, RequestedOutput


CAPABILITY_REGISTRY_VERSION = "capability-registry-v1"
KNOWN_VERIFIERS = {"fact_present"}


class PlanningExecutionLike(Protocol):
    request_plan: RequestPlan
    validation: Any


class CapabilityDispatchError(RuntimeError):
    def __init__(self, code: str, message: str, *, needs_clarification: bool = False):
        super().__init__(message)
        self.code = code
        self.needs_clarification = needs_clarification


ArgumentCompiler = Callable[
    [PlanningExecutionLike, AgentRunState, str],
    dict[str, object],
]
FactVerifier = Callable[[set[str], "CapabilitySpec"], bool]


@dataclass(frozen=True, slots=True)
class CapabilitySpec:
    capability: PlanCapability
    version: str
    requires: frozenset[str]
    produces: frozenset[str]
    tool_name: str | None
    policy_action: str
    argument_compiler: ArgumentCompiler | None
    verifier_name: str
    verifier: FactVerifier
    retry_policy: str
    answer_mode: str
    completion_fact: str | None


def _fact_present(facts: set[str], spec: CapabilitySpec) -> bool:
    return spec.completion_fact is None or spec.completion_fact in facts


def _rule_id(execution: PlanningExecutionLike, state: AgentRunState) -> str:
    value = str(state.current_rule_id or "").strip()
    if not value:
        for result in reversed(state.last_tool_results):
            if not isinstance(result, dict) or result.get("ok") is not True:
                continue
            data = result.get("data") or {}
            if isinstance(data, dict):
                value = str(data.get("resolved_rule_id") or "").strip()
            if value:
                break
    if not value:
        for evidence in reversed(state.evidence):
            if not isinstance(evidence, dict):
                continue
            if "rule_identity" not in (evidence.get("fact_types") or []):
                continue
            value = str(evidence.get("source_id") or "").strip()
            if value:
                break
    if not value:
        value = str(execution.request_plan.target_indicator.rule_id or "").strip()
    if not value:
        raise CapabilityDispatchError(
            "RULE_ID_MISSING",
            "当前尚未确认唯一指标，请先明确指标名称。",
            needs_clarification=True,
        )
    return value


_FILE_KEY_PATTERN = re.compile(
    r"(?:文件编号|file_key)\s*[:：=]\s*([A-Za-z0-9_.-]{1,128})",
    re.IGNORECASE,
)


def _file_key(user_message: str, state: AgentRunState) -> str:
    structured_file_key = str(state.current_upload_file_key or "").strip()
    if structured_file_key:
        return structured_file_key
    matches = _FILE_KEY_PATTERN.findall("\n".join((state.recent_history, user_message)))
    if not matches:
        raise CapabilityDispatchError(
            "UPLOAD_FILE_KEY_MISSING",
            "请先上传需要分析的 Excel 文件。",
            needs_clarification=True,
        )
    return matches[-1]


def _compile_indicator_query(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    del state
    query = str(execution.request_plan.target_indicator.raw_name or "").strip()
    if not query:
        query = str(user_message or "").strip()
    if not query:
        raise CapabilityDispatchError(
            "INDICATOR_QUERY_MISSING",
            "请提供需要查询的指标名称。",
            needs_clarification=True,
        )
    return {"query": query, "limit": 5}


def _compile_rule_id(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    del user_message
    return {"rule_id": _rule_id(execution, state)}


def _compile_sql(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    del user_message
    period = execution.validation.resolved_time
    if period is None:
        raise CapabilityDispatchError(
            "STAT_PERIOD_MISSING",
            "请明确需要统计的开始时间和结束时间。",
            needs_clarification=True,
        )
    return {
        "rule_id": _rule_id(execution, state),
        "stat_start_time": period.start_time.isoformat(),
        "stat_end_time": period.end_time.isoformat(),
    }


def _compile_trial_run(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    del execution, user_message
    if not state.validated_sql_ids:
        raise CapabilityDispatchError(
            "VALIDATED_SQL_ID_MISSING",
            "当前没有可试运行的已校验 SQL，请重新准备 SQL。",
        )
    return {"sql_id": state.validated_sql_ids[-1]}


def _compile_diagnosis(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    rule_id = _rule_id(execution, state)
    evidence = extract_pasted_evidence(user_message, rule_id=rule_id)
    issue_description = str(evidence.question or "").strip() or "请排查当前指标异常。"
    arguments: dict[str, object] = {
        "rule_id": rule_id,
        "issue_description": issue_description[:1000],
    }
    if evidence.sql_text:
        arguments["pasted_sql"] = evidence.sql_text
    if evidence.declared_params:
        arguments["declared_params"] = dict(evidence.declared_params)
    period = evidence.stat_period
    if period.start or period.end:
        arguments["stat_period"] = (
            f"{period.start or '未明确'} 至 {period.end or '未明确'}"
        )[:64]
    return arguments


def _compile_rule_change(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    description = str(user_message or "").strip()
    if not description:
        raise CapabilityDispatchError(
            "CHANGE_DESCRIPTION_MISSING",
            "请说明希望调整的本院指标口径。",
            needs_clarification=True,
        )
    return {
        "rule_id": _rule_id(execution, state),
        "change_description": description,
    }


def _compile_upload(
    execution: PlanningExecutionLike,
    state: AgentRunState,
    user_message: str,
) -> dict[str, object]:
    del execution
    return {"file_key": _file_key(user_message, state)}


class CapabilitySpecRegistry:
    version = CAPABILITY_REGISTRY_VERSION

    def __init__(self, specs: Iterable[CapabilitySpec] | None = None) -> None:
        values = tuple(specs or _default_specs())
        self._specs = {spec.capability: spec for spec in values}
        if len(self._specs) != len(values):
            raise ValueError("duplicate capability ID")
        self.validate()

    def get(self, capability: PlanCapability) -> CapabilitySpec:
        try:
            return self._specs[capability]
        except KeyError as exc:
            raise KeyError(f"unknown capability: {capability.value}") from exc

    def producer_for(self, fact: str) -> CapabilitySpec | None:
        matches = [spec for spec in self._specs.values() if fact in spec.produces]
        if len(matches) > 1:
            raise ValueError(f"duplicate Fact Producer: {fact}")
        return matches[0] if matches else None

    def validate(self, known_tools: set[str] | None = None) -> None:
        fact_producers: dict[str, PlanCapability] = {}
        for spec in self._specs.values():
            if (
                spec.verifier_name not in KNOWN_VERIFIERS
                or not callable(spec.verifier)
            ):
                raise ValueError(f"unknown verifier: {spec.verifier_name}")
            if spec.tool_name and spec.argument_compiler is None:
                raise ValueError(f"missing argument compiler: {spec.capability.value}")
            if known_tools is not None and spec.tool_name not in {None, *known_tools}:
                raise ValueError(f"unknown tool: {spec.tool_name}")
            for fact in spec.produces:
                previous = fact_producers.setdefault(fact, spec.capability)
                if previous is not spec.capability:
                    raise ValueError(f"duplicate Fact Producer: {fact}")
        self._validate_cycles(fact_producers)

    def _validate_cycles(self, producers: dict[str, PlanCapability]) -> None:
        visiting: set[PlanCapability] = set()
        visited: set[PlanCapability] = set()

        def visit(capability: PlanCapability) -> None:
            if capability in visiting:
                raise ValueError(f"capability dependency cycle: {capability.value}")
            if capability in visited:
                return
            visiting.add(capability)
            for fact in self.get(capability).requires:
                producer = producers.get(fact)
                if producer is not None:
                    visit(producer)
            visiting.remove(capability)
            visited.add(capability)

        for capability in self._specs:
            visit(capability)

    def required_output_facts(self, plan: RequestPlan) -> set[str]:
        mapping = {
            RequestedOutput.DEFINITION: "definition",
            RequestedOutput.FORMULA: "formula",
            RequestedOutput.IMPLEMENTATION_STATUS: "implementation_status",
            RequestedOutput.PREPARED_SQL_HANDLE: "sql_validation",
            RequestedOutput.TRIAL_RESULT: "trial_run",
            RequestedOutput.DIAGNOSIS: "diagnosis",
            RequestedOutput.CHANGE_PREVIEW: "rule_change_preview",
            RequestedOutput.FILE_ANALYSIS: "file_analysis",
        }
        facts = {mapping[value] for value in plan.requested_outputs if value in mapping}
        if facts:
            return facts
        implied = {
            PlanIntent.RULE_EXPLANATION: {"effective_rule"},
            PlanIntent.INDICATOR_SQL_PREPARE: {"sql_validation"},
            PlanIntent.INDICATOR_TRIAL_RUN: {"trial_run"},
            PlanIntent.INDICATOR_DIAGNOSIS: {"diagnosis"},
            PlanIntent.RULE_CHANGE_PREVIEW: {"rule_change_preview"},
            PlanIntent.UPLOAD_ANALYSIS: {"file_analysis"},
        }
        return set(implied.get(plan.intent, set()))

    def compile_capabilities(self, target_facts: set[str]) -> list[PlanCapability]:
        ordered: list[PlanCapability] = []
        visited: set[PlanCapability] = set()

        def add_for_fact(fact: str) -> None:
            producer = self.producer_for(fact)
            if producer is None:
                return
            if producer.capability in visited:
                return
            for required in sorted(producer.requires):
                add_for_fact(required)
            visited.add(producer.capability)
            ordered.append(producer.capability)

        for fact in sorted(target_facts):
            add_for_fact(fact)
        phase_order = {
            capability: index
            for index, capability in enumerate((
                PlanCapability.RESOLVE_INDICATOR,
                PlanCapability.RESOLVE_EFFECTIVE_RULE,
                PlanCapability.RESOLVE_TIME_RANGE,
                PlanCapability.INSPECT_IMPLEMENTATION,
                PlanCapability.PREPARE_VERIFIED_SQL,
                PlanCapability.EXECUTE_TRIAL_RUN,
                PlanCapability.DIAGNOSE_INDICATOR,
                PlanCapability.PREVIEW_RULE_CHANGE,
                PlanCapability.ANALYZE_UPLOADED_FILE,
            ))
        }
        ordered.sort(key=lambda value: phase_order[value])
        ordered.append(PlanCapability.COMPOSE_ANSWER)
        return ordered


def _spec(
    capability: PlanCapability,
    *,
    requires: Iterable[str] = (),
    produces: Iterable[str] = (),
    tool_name: str | None = None,
    argument_compiler: ArgumentCompiler | None = None,
    completion_fact: str | None = None,
    policy_action: str = "agent.tool.execute",
    retry_policy: str = "none",
    answer_mode: str = "evidence_only",
) -> CapabilitySpec:
    return CapabilitySpec(
        capability=capability,
        version="1.0",
        requires=frozenset(requires),
        produces=frozenset(produces),
        tool_name=tool_name,
        policy_action=policy_action,
        argument_compiler=argument_compiler,
        verifier_name="fact_present",
        verifier=_fact_present,
        retry_policy=retry_policy,
        answer_mode=answer_mode,
        completion_fact=completion_fact,
    )


def _default_specs() -> tuple[CapabilitySpec, ...]:
    return (
        _spec(PlanCapability.RESOLVE_INDICATOR, produces={"rule_identity"}, tool_name="search_indicator_rules", argument_compiler=_compile_indicator_query, completion_fact="rule_identity"),
        _spec(PlanCapability.RESOLVE_EFFECTIVE_RULE, requires={"rule_identity"}, produces={"effective_rule", "definition", "formula"}, tool_name="get_effective_rule", argument_compiler=_compile_rule_id, completion_fact="effective_rule"),
        _spec(PlanCapability.RESOLVE_TIME_RANGE, produces={"stat_period"}, completion_fact="stat_period", policy_action="agent.time.resolve"),
        _spec(PlanCapability.INSPECT_IMPLEMENTATION, requires={"effective_rule"}, produces={"implementation_status", "field_mapping"}, tool_name="inspect_indicator_implementation", argument_compiler=_compile_rule_id, completion_fact="implementation_status"),
        _spec(PlanCapability.PREPARE_VERIFIED_SQL, requires={"effective_rule", "stat_period"}, produces={"sql_validation"}, tool_name="prepare_indicator_sql", argument_compiler=_compile_sql, completion_fact="sql_validation"),
        _spec(PlanCapability.EXECUTE_TRIAL_RUN, requires={"sql_validation"}, produces={"trial_run"}, tool_name="trial_run_indicator_sql", argument_compiler=_compile_trial_run, completion_fact="trial_run"),
        _spec(PlanCapability.DIAGNOSE_INDICATOR, requires={"effective_rule", "implementation_status"}, produces={"diagnosis"}, tool_name="diagnose_indicator_issue", argument_compiler=_compile_diagnosis, completion_fact="diagnosis"),
        _spec(PlanCapability.PREVIEW_RULE_CHANGE, requires={"effective_rule"}, produces={"rule_change_preview"}, tool_name="preview_rule_change", argument_compiler=_compile_rule_change, completion_fact="rule_change_preview"),
        _spec(PlanCapability.ANALYZE_UPLOADED_FILE, produces={"file_analysis"}, tool_name="analyze_uploaded_indicators", argument_compiler=_compile_upload, completion_fact="file_analysis"),
        _spec(PlanCapability.COMPOSE_ANSWER, policy_action="agent.answer.compose", answer_mode="verified_evidence_only"),
    )


_REGISTRY = CapabilitySpecRegistry()


def get_capability_registry() -> CapabilitySpecRegistry:
    return _REGISTRY
