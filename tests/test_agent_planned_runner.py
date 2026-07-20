from __future__ import annotations

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from pydantic import BaseModel

from app.agent_planning import RequestPlan
from app.agent_planning.planner import AgentPlanningError, ModelRequestPlanner
from app.agent_planning.runtime import AgentPlanningRuntime
from app.agent_runtime.contracts import (
    AgentModelResponse,
    AgentRunResult,
    AgentRunState,
    AgentRuntimeContext,
)
from app.agent_runtime.runner import (
    AgentRunner,
    _compound_indicator_target,
    _request_kind_from_plan,
    _split_compound_indicator_query,
)
from app.agent_tools.contracts import AgentTool, ToolEvidence, ToolResult, ToolRiskLevel
from app.agent_tools.gateway import ToolGateway
from app.agent_tools.registry import ToolRegistry


NOW = datetime(2026, 7, 16, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai"))


class SequenceAdapter:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


class StaticPlanner:
    def __init__(self, plan: RequestPlan):
        self._plan = plan

    async def plan(self, **kwargs):
        return self._plan


class ReplanningStaticPlanner(StaticPlanner):
    def __init__(self, plan: RequestPlan, replanned: RequestPlan):
        super().__init__(plan)
        self.replanned = replanned
        self.replan_calls = []

    async def replan(self, **kwargs):
        self.replan_calls.append(kwargs)
        return self.replanned


class FailingPlanner:
    async def plan(self, **kwargs):
        raise AgentPlanningError("2 validation errors with internal schema details")


class CompoundProbeRunner(AgentRunner):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.subtasks = []

    async def _run(
        self,
        user_message,
        context,
        run_state,
        *,
        allow_compound=True,
        forced_time_range=None,
        request_plan_override=None,
    ):
        if allow_compound:
            return await super()._run(
                user_message,
                context,
                run_state,
                allow_compound=True,
                forced_time_range=forced_time_range,
                request_plan_override=request_plan_override,
            )
        index = len(self.subtasks) + 1
        rule_name = (
            "患者入院 48 小时内转科的比例"
            if index == 1
            else "急会诊及时到位率"
        )
        run_id = f"RUN_{index}"
        self.subtasks.append((user_message, forced_time_range, request_plan_override))
        run_state.step_count = 1
        run_state.current_rule_id = f"RULE_{index}"
        run_state.last_run_id = run_id
        if forced_time_range:
            run_state.current_stat_start = forced_time_range[0]
            run_state.current_stat_end = forced_time_range[1]
        run_state.last_tool_results = [{
            "ok": True,
            "data": {"rule_name": rule_name},
        }]
        return AgentRunResult(
            answer=f"{rule_name}结果。\n\n{{{{detail_export:{run_id}}}}}",
            stop_reason="final_answer",
            state=run_state,
        )


class SearchInput(BaseModel):
    query: str


class RuleInput(BaseModel):
    rule_id: str


def _context():
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset({"indicator_read"}),
        request_id="r1",
        trace_id="t1",
    )


def _registry():
    def search(arguments, context, state):
        del arguments, context
        return ToolResult(
            ok=True,
            status="success",
            code="RULE_SEARCHED",
            summary="found",
            data={"resolved_rule_id": "RULE_1"},
            evidence=[ToolEvidence(
                source="rules",
                source_id="RULE_1",
                fact_types=["rule_identity"],
            )],
        )

    def get_rule(arguments, context, state):
        del context
        state.current_rule_id = arguments.rule_id
        return ToolResult(
            ok=True,
            status="success",
            code="EFFECTIVE_RULE_FOUND",
            summary="loaded",
            data={"rule_id": arguments.rule_id, "formula": "分子 ÷ 分母 × 100%"},
            evidence=[ToolEvidence(
                source="rules",
                source_id=arguments.rule_id,
                fact_types=["rule_identity", "definition", "formula"],
            )],
        )

    permission = frozenset({"indicator_read"})
    return ToolRegistry([
        AgentTool(
            name="search_indicator_rules",
            description="search",
            input_model=SearchInput,
            handler=search,
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
        ),
        AgentTool(
            name="get_effective_rule",
            description="rule",
            input_model=RuleInput,
            handler=get_rule,
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
        ),
        AgentTool(
            name="diagnose_indicator_issue",
            description="diagnose",
            input_model=RuleInput,
            handler=get_rule,
            risk_level=ToolRiskLevel.READ,
            required_permissions=permission,
        ),
    ])


def _rule_plan():
    return RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "解释指标公式",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "requested_outputs": ["formula", "explanation"],
    })


def test_model_planner_repairs_invalid_steps_and_returns_strict_plan():
    adapter = SequenceAdapter([
        AgentModelResponse(content='{"intent":"rule_explanation","goal":"解释公式","steps":["search_indicator_rules"]}'),
        AgentModelResponse(content='{"intent":"rule_explanation","goal":"解释公式","target_indicator":{"raw_name":"急会诊及时到位率"},"requested_outputs":["formula"]}'),
    ])

    plan = asyncio.run(ModelRequestPlanner(adapter).plan(
        query="怎么算",
        context=_context(),
        state=None,
        now=NOW,
    ))

    assert plan.intent.value == "rule_explanation"
    assert not hasattr(plan, "steps")
    assert len(adapter.calls) == 2
    assert all(call["tools"] == [] for call in adapter.calls)


def test_compound_indicator_request_is_split_and_uses_one_common_period():
    query = "患者入院 48 小时内转科的比例从26年1月到现在的结果怎么算，还有急会诊的结果"
    assert _split_compound_indicator_query(query) == [
        "患者入院 48 小时内转科的比例从26年1月到现在的结果怎么算",
        "急会诊的结果",
    ]
    assert _compound_indicator_target(
        "患者入院 48 小时内转科的比例从26年1月到现在的结果怎么算"
    ) == "患者入院 48 小时内转科的比例"
    assert _compound_indicator_target("急会诊的结果") == "急会诊"
    assert _split_compound_indicator_query("解释指标定义以及计算公式") == []

    registry = _registry()
    runner = CompoundProbeRunner(
        SequenceAdapter([]),
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run(query, _context()))

    assert result.stop_reason == "final_answer"
    assert len(runner.subtasks) == 2
    assert runner.subtasks[0][1] == runner.subtasks[1][1] == (
        "2026-01-01T00:00:00+08:00",
        "2026-07-16T12:00:00+08:00",
    )
    assert "## 患者入院 48 小时内转科的比例" in result.answer
    assert "## 急会诊及时到位率" in result.answer
    assert "{{detail_export:RUN_1}}" in result.answer
    assert "{{detail_export:RUN_2}}" in result.answer
    assert result.state.current_rule_ids == ["RULE_1", "RULE_2"]


def test_compound_sql_followup_reuses_all_rule_ids_and_common_period():
    registry = _registry()
    runner = CompoundProbeRunner(
        SequenceAdapter([]),
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
    )
    state = AgentRunState(
        current_rule_id="MQSI2025_005",
        current_rule_ids=["MQSI2025_001", "MQSI2025_005"],
        current_stat_start="2026-01-01T00:00:00+08:00",
        current_stat_end="2026-07-16T12:00:00+08:00",
    )

    result = asyncio.run(runner.run("这两个的 SQL 怎么写？", _context(), state))

    assert result.stop_reason == "final_answer"
    assert len(runner.subtasks) == 2
    plans = [item[2] for item in runner.subtasks]
    assert [plan.target_indicator.rule_id for plan in plans] == [
        "MQSI2025_001",
        "MQSI2025_005",
    ]
    assert all(plan.intent.value == "indicator_sql_prepare" for plan in plans)
    assert runner.subtasks[0][1] == runner.subtasks[1][1] == (
        "2026-01-01T00:00:00+08:00",
        "2026-07-16T12:00:00+08:00",
    )


def test_compound_rule_components_followup_reuses_all_rule_ids():
    registry = _registry()
    runner = CompoundProbeRunner(
        SequenceAdapter([]),
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
    )
    state = AgentRunState(
        current_rule_id="MQSI2025_005",
        current_rule_ids=["MQSI2025_001", "MQSI2025_005"],
    )

    result = asyncio.run(
        runner.run("这两个的分子分母是什么意思？", _context(), state)
    )

    assert result.stop_reason == "final_answer"
    assert len(runner.subtasks) == 2
    assert all("分子分母含义" in item[0] for item in runner.subtasks)
    assert all(
        item[2].intent.value == "rule_explanation"
        for item in runner.subtasks
    )


def test_model_planner_normalizes_safe_scalar_container_shapes_for_4b():
    adapter = SequenceAdapter([AgentModelResponse(content='''{
      "intent":"rule_explanation",
      "goal":"解释急会诊及时到位率的计算方法",
      "target_indicator":{"raw_name":"急会诊及时到位率","rule_id":null},
      "time_expression":"raw_text",
      "requested_outputs":"definition",
      "constraints":[],
      "semantic_ambiguities":[]
    }''')])

    plan = asyncio.run(ModelRequestPlanner(adapter).plan(
        query="急会诊及时到位率怎么算",
        context=_context(),
        state=None,
        now=NOW,
    ))

    assert plan.time_expression.raw_text == ""
    assert [item.value for item in plan.requested_outputs] == ["definition"]
    assert len(adapter.calls) == 1


def test_model_planner_normalizes_null_optional_containers_for_general_chat():
    adapter = SequenceAdapter([AgentModelResponse(content='''{
      "intent":"general_chat",
      "goal":"",
      "target_indicator":null,
      "time_expression":null,
      "requested_outputs":["explanation"],
      "constraints":[],
      "semantic_ambiguities":[]
    }''')])

    plan = asyncio.run(ModelRequestPlanner(adapter).plan(
        query="你好",
        context=_context(),
        state=AgentRunState(),
        now=NOW,
    ))

    assert plan.intent.value == "general_chat"
    assert plan.goal == "回应普通问候或帮助请求"
    assert plan.target_indicator.raw_name == ""
    assert plan.time_expression.raw_text == ""
    assert len(adapter.calls) == 1


def test_model_planner_normalizes_string_semantic_ambiguities_for_4b():
    adapter = SequenceAdapter([AgentModelResponse(content='''{
      "intent":"indicator_trial_run",
      "goal":"查询指标实际结果",
      "target_indicator":{},
      "time_expression":{"raw_text":"从6月1日至今"},
      "requested_outputs":["trial_result"],
      "constraints":[],
      "semantic_ambiguities":["需要确认统计时间"]
    }''')])

    plan = asyncio.run(ModelRequestPlanner(adapter).plan(
        query="从6月1日至今",
        context=_context(),
        state=AgentRunState(current_rule_id="RULE_1"),
        now=NOW,
    ))

    assert plan.semantic_ambiguities[0].field == "unspecified"
    assert plan.semantic_ambiguities[0].description == "需要确认统计时间"


def test_model_planner_uses_recent_history_and_last_selected_time_option():
    adapter = SequenceAdapter([AgentModelResponse(content=(
        '{"intent":"indicator_trial_run","goal":"查询指标实际结果",'
        '"target_indicator":{},'
        '"time_expression":{"raw_text":"从6月1日至今"},'
        '"requested_outputs":["trial_result"]}'
    ))])
    state = AgentRunState(
        current_rule_id="RULE_1",
        recent_history=(
            "用户：患者入院 48 小时内转科的比例怎么算\n"
            "助手：可查询‘2026年1月到3月’或‘从6月1日至今’。"
        ),
    )

    asyncio.run(ModelRequestPlanner(adapter).plan(
        query='2026年1月到3月”或“从6月1日至今”这个',
        context=_context(),
        state=state,
        now=NOW,
    ))

    assert "最近对话" in adapter.calls[0]["messages"][0]["content"]
    assert "患者入院 48 小时内转科" in adapter.calls[0]["messages"][0]["content"]
    assert adapter.calls[0]["messages"][1]["content"] == "从6月1日至今的结果"


def test_model_planner_rejects_repeated_invalid_plan():
    invalid = AgentModelResponse(content='{"intent":"rule_explanation","goal":"解释公式","proposed_steps":[]}')
    adapter = SequenceAdapter([invalid, invalid])

    with pytest.raises(AgentPlanningError):
        asyncio.run(ModelRequestPlanner(adapter).plan(
            query="怎么算",
            context=_context(),
            state=None,
            now=NOW,
        ))


def test_runner_hides_internal_planner_validation_details():
    adapter = SequenceAdapter([])
    registry = _registry()
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=FailingPlanner(),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("查询指标", _context()))

    assert result.stop_reason == "tool_error"
    assert result.answer == "无法生成有效业务计划，请重新描述目标。"
    assert "validation" not in result.answer


def test_model_planner_receives_safe_structured_followup_context():
    adapter = SequenceAdapter([AgentModelResponse(content=(
        '{"intent":"indicator_trial_run","goal":"直接给出结果",'
        '"target_indicator":{},"time_expression":{},'
        '"requested_outputs":["trial_result"]}'
    ))])
    state = AgentRunState(
        current_rule_id="RULE_1",
        current_stat_start="2026-06-01T00:00:00+08:00",
        current_stat_end="2026-07-01T00:00:00+08:00",
        current_upload_file_key="h1_report.xlsx",
    )

    asyncio.run(ModelRequestPlanner(adapter).plan(
        query="直接给我结果",
        context=_context(),
        state=state,
        now=NOW,
    ))

    planner_context = adapter.calls[0]["messages"][0]["content"]
    assert "RULE_1" in planner_context
    assert "2026-06-01" in planner_context
    assert "h1_report.xlsx" in planner_context
    assert "file_analysis 和 trial_result" in planner_context


def test_planning_runtime_reuses_structured_rule_and_period_context():
    plan = RequestPlan.model_validate({
        "intent": "indicator_trial_run",
        "goal": "直接给出结果",
        "requested_outputs": ["trial_result"],
    })
    state = AgentRunState(
        current_rule_id="RULE_1",
        current_stat_start="2026-06-01T00:00:00+08:00",
        current_stat_end="2026-07-01T00:00:00+08:00",
    )
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )

    execution = asyncio.run(runtime.prepare("直接给我结果", _context(), state))

    assert execution.request_plan.target_indicator.rule_id == "RULE_1"
    assert execution.validation.ok is True
    assert execution.validation.resolved_time.start_time.isoformat() == "2026-06-01T00:00:00+08:00"


def test_upload_difference_followup_overrides_misclassified_diagnosis_plan():
    plan = RequestPlan.model_validate({
        "intent": "indicator_diagnosis",
        "goal": "分析指标计算结果差异的原因",
        "target_indicator": {
            "raw_name": "患者入院48小时内转科的比例",
            "rule_id": "RULE_1",
        },
        "time_expression": {"raw_text": "从26年1月到现在"},
        "requested_outputs": ["diagnosis"],
    })
    state = AgentRunState(
        current_rule_id="RULE_1",
        current_stat_start="2026-01-01T00:00:00+08:00",
        current_stat_end="2026-07-17T12:29:58+08:00",
        current_upload_file_key="h1_report.xlsx",
    )
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )

    execution = asyncio.run(runtime.prepare(
        "怎么我们的结果不一样，分析一下原因",
        _context(),
        state,
    ))

    assert execution.request_plan.intent.value == "indicator_trial_run"
    assert {item.value for item in execution.request_plan.requested_outputs} == {
        "file_analysis",
        "trial_result",
    }
    assert [node.capability.value for node in execution.compiled_plan.nodes] == [
        "resolve_indicator",
        "resolve_effective_rule",
        "resolve_time_range",
        "prepare_verified_sql",
        "execute_trial_run",
        "analyze_uploaded_file",
        "compose_answer",
    ]
    assert execution.validation.resolved_time.start_time.isoformat() == (
        "2026-01-01T00:00:00+08:00"
    )
    assert _request_kind_from_plan("怎么我们的结果不一样", execution) == "trial_run"


def test_diagnosis_request_kind_follows_validated_plan_instead_of_result_keyword():
    plan = RequestPlan.model_validate({
        "intent": "indicator_diagnosis",
        "goal": "诊断两个系统结果不一样的原因",
        "target_indicator": {"rule_id": "RULE_1"},
        "requested_outputs": ["diagnosis"],
    })
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )
    execution = asyncio.run(runtime.prepare(
        "两个系统结果不一样，排查原因",
        _context(),
        AgentRunState(current_rule_id="RULE_1"),
    ))

    assert _request_kind_from_plan("两个系统结果不一样，排查原因", execution) == (
        "diagnosis"
    )


def test_rule_caliber_difference_is_not_hijacked_by_existing_upload():
    plan = RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "解释国标与本院口径差异",
        "target_indicator": {"rule_id": "RULE_1"},
        "requested_outputs": ["explanation"],
    })
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )
    execution = asyncio.run(runtime.prepare(
        "国标和本院口径有什么差异",
        _context(),
        AgentRunState(
            current_rule_id="RULE_1",
            current_upload_file_key="h1_report.xlsx",
        ),
    ))

    assert execution.request_plan.intent.value == "rule_explanation"
    assert {item.value for item in execution.request_plan.requested_outputs} == {
        "explanation"
    }


def test_runtime_reuses_confirmed_period_when_sql_followup_has_no_time_text():
    plan = RequestPlan.model_validate({
        "intent": "indicator_sql_prepare",
        "goal": "生成当前指标 SQL",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {
            "raw_text": "2026-01-01 00:00:00 至 2026-03-31 00:00:00",
        },
        "requested_outputs": ["prepared_sql_handle"],
    })
    state = AgentRunState(
        current_rule_id="RULE_1",
        current_stat_start="2026-01-01T00:00:00+08:00",
        current_stat_end="2026-04-01T00:00:00+08:00",
    )
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )

    execution = asyncio.run(runtime.prepare(
        "把这个指标的 SQL 脚本生成出来",
        _context(),
        state,
    ))

    assert execution.validation.ok is True
    assert execution.validation.resolved_time.start_time.isoformat() == (
        "2026-01-01T00:00:00+08:00"
    )
    assert execution.validation.resolved_time.end_time.isoformat() == (
        "2026-04-01T00:00:00+08:00"
    )


def test_runtime_overrides_model_dates_with_user_month_range():
    plan = RequestPlan.model_validate({
        "intent": "indicator_sql_prepare",
        "goal": "生成当前指标 SQL",
        "target_indicator": {"raw_name": "急会诊及时到位率"},
        "time_expression": {
            "raw_text": "从一月份到三月份的",
            "start_time": "2026-01-01 00:00:00",
            "end_time": "2026-03-31 00:00:00",
        },
        "requested_outputs": ["prepared_sql_handle"],
    })
    runtime = AgentPlanningRuntime(
        planner=StaticPlanner(plan),
        now_provider=lambda: NOW,
    )

    execution = asyncio.run(runtime.prepare(
        "从一月份到三月份的",
        _context(),
        AgentRunState(current_rule_id="RULE_1"),
    ))

    assert execution.validation.ok is True
    assert execution.validation.resolved_time.start_time.isoformat() == (
        "2026-01-01T00:00:00+08:00"
    )
    assert execution.validation.resolved_time.end_time.isoformat() == (
        "2026-04-01T00:00:00+08:00"
    )


def test_planned_runner_dispatches_tools_without_model_routing():
    adapter = SequenceAdapter([
        AgentModelResponse(content="指标率 = 分子 ÷ 分母 × 100%"),
    ])
    trace_events = []
    registry = _registry()
    planning = AgentPlanningRuntime(
        planner=StaticPlanner(_rule_plan()),
        now_provider=lambda: NOW,
    )
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        trace_callback=trace_events.append,
        planning_runtime=planning,
    )

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "final_answer", result.answer
    assert len(adapter.calls) == 1
    assert adapter.calls[0]["tools"] == []
    dispatches = [
        event for event in trace_events
        if event.get("node_name") == "deterministic_tool_dispatch"
    ]
    assert [event["output_data"]["tool_call"]["name"] for event in dispatches] == [
        "search_indicator_rules",
        "get_effective_rule",
    ]


def test_general_chat_plan_calls_no_tools():
    plan = RequestPlan.model_validate({
        "intent": "general_chat",
        "goal": "回应问候",
        "requested_outputs": ["explanation"],
    })
    adapter = SequenceAdapter([AgentModelResponse(content="你好，请问需要查询哪个指标？")])
    registry = _registry()
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(plan),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("你好", _context()))

    assert result.stop_reason == "final_answer"
    assert adapter.calls[0]["tools"] == []


def test_empty_final_model_action_is_warning_and_retried_once():
    adapter = SequenceAdapter([
        AgentModelResponse(content="", tool_calls=[], model="qwen3:8b"),
        AgentModelResponse(
            content="指标率 = 分子 ÷ 分母 × 100%",
            model="qwen3:8b",
        ),
    ])
    trace_events = []
    registry = _registry()
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        trace_callback=trace_events.append,
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "final_answer"
    assert len(adapter.calls) == 2
    executor_nodes = [
        event for event in trace_events
        if event.get("node_name") == "final_answer_llm"
    ]
    assert executor_nodes[0]["status"] == "warning"
    assert executor_nodes[0]["error_code"] == "MODEL_EMPTY_ACTION"
    assert executor_nodes[1]["status"] == "success"


def test_final_dsml_tool_markup_is_never_returned_to_user():
    adapter = SequenceAdapter([
        AgentModelResponse(content=(
            '<｜｜DSML｜｜tool_calls>\n'
            '<｜｜DSML｜｜invoke name="get_indicator_result">\n'
            '</｜｜DSML｜｜invoke>\n'
            '</｜｜DSML｜｜tool_calls>'
        )),
        AgentModelResponse(content="请明确需要对比的统计时间范围。"),
    ])
    registry = _registry()
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("这个指标", _context()))

    assert result.stop_reason == "final_answer"
    assert result.answer == "请明确需要对比的统计时间范围。"
    assert "DSML" not in result.answer
    assert "get_indicator_result" not in result.answer
    assert len(adapter.calls) == 2
    assert any(
        "工具协议" in message.get("content", "")
        for message in adapter.calls[1]["messages"]
        if message.get("role") == "system"
    )


def test_time_clarification_trace_is_warning_not_failure():
    plan = RequestPlan.model_validate({
        "intent": "rule_explanation",
        "goal": "只生成 SQL",
        "target_indicator": {"raw_name": "急会诊及时到位率", "rule_id": "RULE_1"},
        "requested_outputs": ["prepared_sql_handle"],
    })
    state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[{
            "source": "rules",
            "source_id": "RULE_1",
            "fact_types": ["rule_identity", "definition", "formula"],
        }],
    )
    trace_events = []
    registry = _registry()
    runner = AgentRunner(
        SequenceAdapter([]),
        registry,
        ToolGateway(registry),
        trace_callback=trace_events.append,
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(plan),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("SQL 怎么写", _context(), state))

    assert result.stop_reason == "need_clarification"
    controller_node = next(
        event for event in trace_events
        if event.get("node_name") == "state_controller"
    )
    assert controller_node["status"] == "warning"


def test_prepared_sql_plan_composes_validated_sql_without_another_model_call():
    plan = RequestPlan.model_validate({
        "intent": "indicator_sql_prepare",
        "goal": "只生成患者入院 48 小时内转科比例的 SQL，不执行数据库",
        "target_indicator": {
            "raw_name": "患者入院 48 小时内转科的比例",
            "rule_id": "RULE_1",
        },
        "time_expression": {
            "raw_text": "从1月到现在",
            "start_time": "2026-01-01 00:00:00",
            "end_time": "2026-07-17 00:00:00",
        },
        "requested_outputs": ["prepared_sql_handle"],
        "constraints": ["仅输出 SQL，不执行数据库"],
    })
    state = AgentRunState(
        current_rule_id="RULE_1",
        evidence=[{
            "source": "rules",
            "source_id": "RULE_1",
            "fact_types": [
                "rule_identity",
                "definition",
                "formula",
                "effective_rule",
                "implementation_status",
                "sql_validation",
            ],
        }],
        last_tool_results=[{
            "ok": True,
            "status": "success",
            "code": "SQL_OBJECT_PREPARED",
            "summary": "SQL 已完成确定性生成和只读安全校验。",
            "data": {
                "sql_id": "SQL_1",
                "sql_preview": "SELECT COUNT(*) AS denominator FROM admissions WHERE admit_time >= :stat_start AND admit_time < :stat_end",
                "parameters": {
                    "stat_start": "2026-01-01 00:00:00",
                    "stat_end": "2026-07-17 00:00:00",
                },
            },
            "evidence": [],
            "retryable": False,
        }],
    )
    adapter = SequenceAdapter([])
    runner = AgentRunner(
        adapter,
        _registry(),
        ToolGateway(_registry()),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(plan),
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("从1月到现在", _context(), state))

    assert result.stop_reason == "final_answer"
    assert "```sql" in result.answer
    assert "SELECT COUNT(*)" in result.answer
    assert "2026-01-01 00:00:00" in result.answer
    assert "不会执行数据库" in result.answer
    assert adapter.calls == []


def test_plan_direction_failure_replans_once_with_failure_context():
    initial = _rule_plan()
    replanned = RequestPlan.model_validate({
        "intent": "general_chat",
        "goal": "说明当前无法匹配该业务目标",
        "requested_outputs": ["explanation"],
    })
    planner = ReplanningStaticPlanner(initial, replanned)

    def mismatch(arguments, context, state):
        del arguments, context, state
        return ToolResult(
            ok=False,
            status="validation_failed",
            code="PLAN_INTENT_MISMATCH",
            summary="当前问题不是指标规则查询。",
        )

    registry = _registry()
    registry._tools["search_indicator_rules"] = AgentTool(
        name="search_indicator_rules",
        description="search",
        input_model=SearchInput,
        handler=mismatch,
        risk_level=ToolRiskLevel.READ,
        required_permissions=frozenset({"indicator_read"}),
    )
    adapter = SequenceAdapter([
        AgentModelResponse(content="当前问题无法按指标规则查询，请补充具体指标。"),
    ])
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=planner,
            now_provider=lambda: NOW,
        ),
    )

    result = asyncio.run(runner.run("帮我查这个", _context()))

    assert result.stop_reason == "final_answer"
    assert result.state.replan_count == 1
    assert len(adapter.calls) == 1
    assert len(planner.replan_calls) == 1
    assert planner.replan_calls[0]["failure_code"] == "PLAN_INTENT_MISMATCH"
