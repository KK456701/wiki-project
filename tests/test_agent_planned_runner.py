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
    AgentRunState,
    AgentRuntimeContext,
    AgentToolCall,
)
from app.agent_runtime.runner import AgentRunner
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


def test_planned_runner_exposes_only_next_capability_tool():
    adapter = SequenceAdapter([
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="search_indicator_rules",
            arguments={"query": "急会诊及时到位率"},
        )]),
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="get_effective_rule",
            arguments={"rule_id": "RULE_1"},
        )]),
        AgentModelResponse(content="指标率 = 分子 ÷ 分母 × 100%"),
    ])
    registry = _registry()
    planning = AgentPlanningRuntime(
        planner=StaticPlanner(_rule_plan()),
        now_provider=lambda: NOW,
    )
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=planning,
    )

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "final_answer", result.answer
    assert [
        [item["function"]["name"] for item in call["tools"]]
        for call in adapter.calls
    ] == [
        ["search_indicator_rules"],
        ["get_effective_rule"],
        [],
    ]


def test_planned_runner_rejects_tool_outside_current_capability():
    adapter = SequenceAdapter([AgentModelResponse(tool_calls=[AgentToolCall(
        name="diagnose_indicator_issue",
        arguments={"rule_id": "RULE_1"},
    )])])
    registry = _registry()
    runner = AgentRunner(
        adapter,
        registry,
        ToolGateway(registry),
        planning_runtime=AgentPlanningRuntime(
            planner=StaticPlanner(_rule_plan()),
            now_provider=lambda: NOW,
        ),
        max_steps=1,
    )

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "tool_error"
    assert "当前计划" in result.answer


def test_planned_runner_reflects_once_after_wrong_tool_choice():
    adapter = SequenceAdapter([
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="diagnose_indicator_issue",
            arguments={"rule_id": "RULE_1"},
        )]),
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="search_indicator_rules",
            arguments={"query": "急会诊及时到位率"},
        )]),
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="get_effective_rule",
            arguments={"rule_id": "RULE_1"},
        )]),
        AgentModelResponse(content="指标率 = 分子 ÷ 分母 × 100%"),
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

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "final_answer"
    assert any(
        "未允许" in message["content"]
        for message in result.state.messages
        if message["role"] == "system"
    )


def test_planned_runner_reflects_once_after_premature_answer():
    adapter = SequenceAdapter([
        AgentModelResponse(content="指标率 = 分子 ÷ 分母 × 100%"),
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="search_indicator_rules",
            arguments={"query": "急会诊及时到位率"},
        )]),
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="get_effective_rule",
            arguments={"rule_id": "RULE_1"},
        )]),
        AgentModelResponse(content="指标率 = 分子 ÷ 分母 × 100%"),
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

    result = asyncio.run(runner.run("急会诊及时到位率怎么算", _context()))

    assert result.stop_reason == "final_answer"


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
        AgentModelResponse(tool_calls=[AgentToolCall(
            name="search_indicator_rules",
            arguments={"query": "问题"},
        )]),
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
    assert len(planner.replan_calls) == 1
    assert planner.replan_calls[0]["failure_code"] == "PLAN_INTENT_MISMATCH"
