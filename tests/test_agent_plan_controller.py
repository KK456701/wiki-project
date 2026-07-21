from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from app.agent_planning import PlanCompiler, RequestPlan
from app.agent_planning.controller import (
    AgentStateController,
    ControllerAction,
)
from app.agent_planning.validator import PlanValidator
from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools.contracts import AgentTool, ToolResult, ToolRiskLevel
from app.agent_tools.registry import ToolRegistry
from pydantic import BaseModel


class EmptyInput(BaseModel):
    pass


def _tool(name: str) -> AgentTool:
    return AgentTool(
        name=name,
        description=name,
        input_model=EmptyInput,
        handler=lambda *_: ToolResult(ok=True, status="success", code="OK"),
        risk_level=ToolRiskLevel.READ,
    )


def _registry() -> ToolRegistry:
    return ToolRegistry([
        _tool("search_indicator_rules"),
        _tool("get_effective_rule"),
        _tool("inspect_indicator_implementation"),
        _tool("prepare_indicator_sql"),
        _tool("trial_run_indicator_sql"),
        _tool("diagnose_indicator_issue"),
        _tool("preview_rule_change"),
        _tool("analyze_uploaded_indicators"),
        _tool("validate_indicator_implementation"),
    ])


def _context() -> AgentRuntimeContext:
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset(),
        request_id="req1",
        trace_id="trace1",
    )


def _trial_runtime():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "查询本月结果",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {"raw_text": "本月"},
        "requested_outputs": ["trial_result"],
    })
    now = datetime(2026, 7, 16, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai"))
    return (
        PlanCompiler().compile(plan),
        PlanValidator().validate(plan, now=now),
    )


def _evidence(*fact_types: str, source_id: str = "RULE_1") -> dict:
    return {
        "source": "test",
        "source_id": source_id,
        "fact_types": list(fact_types),
    }


def test_trial_plan_exposes_one_next_tool_per_state():
    compiled, validation = _trial_runtime()
    controller = AgentStateController()

    first = controller.next_decision(compiled, validation, AgentRunState())
    assert first.action is ControllerAction.EXECUTE_TOOL
    assert first.tool_names == ["search_indicator_rules"]

    rule_state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[_evidence("rule_identity")],
    )
    second = controller.next_decision(compiled, validation, rule_state)
    assert second.tool_names == ["get_effective_rule"]

    effective_state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[
            _evidence("rule_identity"),
            _evidence("definition", "formula", "effective_level"),
        ],
    )
    third = controller.next_decision(compiled, validation, effective_state)
    assert third.tool_names == ["prepare_indicator_sql"]

    sql_state = effective_state.model_copy(deep=True)
    sql_state.validated_sql_ids = ["SQL_1"]
    sql_state.evidence.append(_evidence("sql_validation", source_id="SQL_1"))
    fourth = controller.next_decision(compiled, validation, sql_state)
    assert fourth.tool_names == ["trial_run_indicator_sql"]

    sql_state.evidence.append(_evidence("trial_run", source_id="RUN_1"))
    final = controller.next_decision(compiled, validation, sql_state)
    assert final.action is ControllerAction.COMPOSE_ANSWER
    assert final.tool_names == []


def test_validation_plan_exposes_specialized_tool_after_prerequisites():
    plan = RequestPlan.model_validate({
        "intent": "implementation_validation",
        "goal": "全面实施验收",
        "target_indicator": {"raw_name": "目标指标"},
        "time_expression": {"raw_text": "2026年1月到3月"},
        "requested_outputs": ["implementation_validation_report"],
    })
    now = datetime(2026, 7, 16, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai"))
    compiled = PlanCompiler().compile(plan)
    validation = PlanValidator().validate(plan, now=now)
    state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[
            _evidence("rule_identity"),
            _evidence("definition", "formula", "effective_level"),
            _evidence("implementation_status", "field_mapping"),
        ],
    )

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.action is ControllerAction.EXECUTE_TOOL
    assert decision.tool_names == ["validate_indicator_implementation"]


def test_invalid_plan_validation_returns_fallback_without_tools():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "不查数据库但返回结果",
        "time_expression": {"raw_text": "本月"},
        "requested_outputs": ["trial_result"],
        "constraints": ["no_database_access"],
    })
    now = datetime(2026, 7, 16, tzinfo=ZoneInfo("Asia/Shanghai"))
    validation = PlanValidator().validate(plan, now=now)

    decision = AgentStateController().next_decision(
        PlanCompiler().compile(plan), validation, AgentRunState()
    )

    assert decision.action is ControllerAction.FALLBACK
    assert decision.tool_names == []
    assert decision.code == "DATABASE_ACCESS_CONFLICT"


def test_registry_lists_only_requested_available_tools():
    tools = _registry().list_for_names(
        ["prepare_indicator_sql", "trial_run_indicator_sql"],
        _context(),
        AgentRunState(),
    )

    assert [tool.name for tool in tools] == [
        "prepare_indicator_sql",
        "trial_run_indicator_sql",
    ]


def test_controller_never_exposes_more_than_two_tools():
    compiled, validation = _trial_runtime()
    decision = AgentStateController().next_decision(
        compiled, validation, AgentRunState()
    )

    assert len(decision.tool_names) <= 2


def test_unresolved_search_evidence_does_not_confirm_indicator():
    compiled, validation = _trial_runtime()
    state = AgentRunState(evidence=[{
        "source": "rules",
        "source_id": None,
        "fact_types": ["rule_identity"],
    }])

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.tool_names == ["search_indicator_rules"]


def test_multiple_unresolved_indicator_candidates_require_clarification():
    compiled, validation = _trial_runtime()
    state = AgentRunState(
        evidence=[{
            "source": "rules",
            "source_id": None,
            "fact_types": ["rule_identity"],
        }],
        last_tool_results=[{
            "ok": True,
            "status": "success",
            "code": "RULE_SEARCHED",
            "data": {
                "resolved_rule_id": "",
                "matches": [
                    {"rule_id": "RULE_1", "rule_name": "指标一"},
                    {"rule_id": "RULE_2", "rule_name": "指标二"},
                ],
            },
        }],
    )

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.action is ControllerAction.FALLBACK
    assert decision.code == "INDICATOR_AMBIGUOUS"
    assert "指标一" in decision.message


def test_trial_run_infrastructure_failure_routes_to_system_operator():
    compiled, validation = _trial_runtime()
    state = AgentRunState(last_tool_results=[{
        "ok": False,
        "status": "error",
        "code": "TRIAL_RUN_FAILED",
        "summary": "只读试运行失败，未获得可用聚合结果。",
        "retryable": True,
    }])

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.action is ControllerAction.FALLBACK
    assert decision.fallback_category.value == "SYSTEM_OPERATOR"
    assert decision.code == "TRIAL_RUN_FAILED"


def test_diagnosis_failure_stops_instead_of_repeating_tool_call():
    compiled, validation = _trial_runtime()
    state = AgentRunState(last_tool_results=[{
        "ok": False,
        "status": "error",
        "code": "DIAGNOSIS_FAILED",
        "summary": "诊断执行失败，未获得可用结论。",
        "retryable": False,
    }])

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.action is ControllerAction.FALLBACK
    assert decision.fallback_category.value == "SYSTEM_OPERATOR"
    assert decision.code == "DIAGNOSIS_FAILED"


def test_upload_analysis_legacy_evidence_completes_without_repeated_tool_call():
    plan = RequestPlan.model_validate({
        "intent": "upload_analysis",
        "goal": "分析刚上传的指标文件",
        "requested_outputs": ["file_analysis"],
    })
    compiled = PlanCompiler().compile(plan)
    validation = PlanValidator().validate(
        plan,
        now=datetime(2026, 7, 17, tzinfo=ZoneInfo("Asia/Shanghai")),
    )
    state = AgentRunState(
        evidence=[_evidence("upload_analysis", source_id="hospital_001_report.xlsx")],
    )

    decision = AgentStateController().next_decision(compiled, validation, state)

    assert decision.action is ControllerAction.COMPOSE_ANSWER
    assert decision.tool_names == []


def test_upload_comparison_analyzes_file_after_trial_run_then_composes():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "对比上传文件与本院指标结果",
        "target_indicator": {"raw_name": "患者入院 48 小时内转科的比例"},
        "time_expression": {"raw_text": "从1月到现在"},
        "requested_outputs": ["file_analysis", "trial_result"],
    })
    now = datetime(2026, 7, 17, tzinfo=ZoneInfo("Asia/Shanghai"))
    compiled = PlanCompiler().compile(plan)
    validation = PlanValidator().validate(plan, now=now)
    state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[
            _evidence("rule_identity"),
            _evidence("definition", "formula", "effective_level"),
            _evidence("sql_validation", source_id="SQL_1"),
            _evidence("trial_run", source_id="RUN_1"),
        ],
    )

    analyze = AgentStateController().next_decision(compiled, validation, state)
    assert analyze.action is ControllerAction.EXECUTE_TOOL
    assert analyze.tool_names == ["analyze_uploaded_indicators"]

    state.evidence.append(_evidence("file_analysis", source_id="h1_report.xlsx"))
    complete = AgentStateController().next_decision(compiled, validation, state)
    assert complete.action is ControllerAction.COMPOSE_ANSWER
    assert complete.tool_names == []
