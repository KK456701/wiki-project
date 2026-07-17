from __future__ import annotations

import re

from app.agent_runtime.contracts import AgentRunState, AgentToolCall
from app.diagnose.evidence import extract_pasted_evidence

from .contracts import PlanCapability
from .controller import CAPABILITY_TOOLS, ControllerAction, ControllerDecision
from .runtime import PlanningExecution


class DeterministicDispatchError(RuntimeError):
    def __init__(self, code: str, message: str, *, needs_clarification: bool = False):
        super().__init__(message)
        self.code = code
        self.needs_clarification = needs_clarification


_FILE_KEY_PATTERN = re.compile(
    r"(?:文件编号|file_key)\s*[:：=]\s*([A-Za-z0-9_.-]{1,128})",
    re.IGNORECASE,
)


def _rule_id(execution: PlanningExecution, state: AgentRunState) -> str:
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
        raise DeterministicDispatchError(
            "RULE_ID_MISSING",
            "当前尚未确认唯一指标，请先明确指标名称。",
            needs_clarification=True,
        )
    return value


def _file_key(user_message: str, state: AgentRunState) -> str:
    structured_file_key = str(state.current_upload_file_key or "").strip()
    if structured_file_key:
        return structured_file_key
    matches = _FILE_KEY_PATTERN.findall(
        "\n".join((state.recent_history, user_message))
    )
    if not matches:
        raise DeterministicDispatchError(
            "UPLOAD_FILE_KEY_MISSING",
            "请先上传需要分析的 Excel 文件。",
            needs_clarification=True,
        )
    return matches[-1]


def _diagnosis_arguments(
    user_message: str,
    rule_id: str,
) -> dict[str, object]:
    evidence = extract_pasted_evidence(user_message, rule_id=rule_id)
    issue_description = str(evidence.question or "").strip()
    if not issue_description:
        issue_description = "请排查当前指标异常。"
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


def build_deterministic_tool_call(
    execution: PlanningExecution,
    decision: ControllerDecision,
    state: AgentRunState,
    *,
    user_message: str,
) -> AgentToolCall:
    capability = decision.capability
    if decision.action is not ControllerAction.EXECUTE_TOOL or capability is None:
        raise DeterministicDispatchError(
            "DISPATCH_ACTION_INVALID",
            "当前控制器决策不是工具执行步骤。",
        )
    expected_tools = CAPABILITY_TOOLS.get(capability, ())
    if len(decision.tool_names) != 1 or decision.tool_names[0] not in expected_tools:
        raise DeterministicDispatchError(
            "DISPATCH_TOOL_INVALID",
            "当前业务能力没有唯一、受控的执行工具。",
        )

    tool_name = decision.tool_names[0]
    if capability is PlanCapability.RESOLVE_INDICATOR:
        query = str(execution.request_plan.target_indicator.raw_name or "").strip()
        if not query:
            query = str(user_message or "").strip()
        if not query:
            raise DeterministicDispatchError(
                "INDICATOR_QUERY_MISSING",
                "请提供需要查询的指标名称。",
                needs_clarification=True,
            )
        arguments: dict[str, object] = {"query": query, "limit": 5}
    elif capability in {
        PlanCapability.RESOLVE_EFFECTIVE_RULE,
        PlanCapability.INSPECT_IMPLEMENTATION,
    }:
        arguments = {"rule_id": _rule_id(execution, state)}
    elif capability is PlanCapability.PREPARE_VERIFIED_SQL:
        period = execution.validation.resolved_time
        if period is None:
            raise DeterministicDispatchError(
                "STAT_PERIOD_MISSING",
                "请明确需要统计的开始时间和结束时间。",
                needs_clarification=True,
            )
        arguments = {
            "rule_id": _rule_id(execution, state),
            "stat_start_time": period.start_time.isoformat(),
            "stat_end_time": period.end_time.isoformat(),
        }
    elif capability is PlanCapability.EXECUTE_TRIAL_RUN:
        if not state.validated_sql_ids:
            raise DeterministicDispatchError(
                "VALIDATED_SQL_ID_MISSING",
                "当前没有可试运行的已校验 SQL，请重新准备 SQL。",
            )
        arguments = {"sql_id": state.validated_sql_ids[-1]}
    elif capability is PlanCapability.DIAGNOSE_INDICATOR:
        arguments = _diagnosis_arguments(
            str(user_message or ""),
            _rule_id(execution, state),
        )
    elif capability is PlanCapability.PREVIEW_RULE_CHANGE:
        description = str(user_message or "").strip()
        if not description:
            raise DeterministicDispatchError(
                "CHANGE_DESCRIPTION_MISSING",
                "请说明希望调整的本院指标口径。",
                needs_clarification=True,
            )
        arguments = {
            "rule_id": _rule_id(execution, state),
            "change_description": description,
        }
    elif capability is PlanCapability.ANALYZE_UPLOADED_FILE:
        arguments = {"file_key": _file_key(user_message, state)}
    else:
        raise DeterministicDispatchError(
            "DISPATCH_CAPABILITY_UNSUPPORTED",
            "当前业务能力不支持确定性工具执行。",
        )

    return AgentToolCall(
        id=f"server_{state.step_count}_{tool_name}",
        name=tool_name,
        arguments=arguments,
    )
