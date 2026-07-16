import unittest

from app.agent_runtime import (
    AgentModelResponse,
    AgentRunState,
    AgentRuntimeContext,
)
from app.agent_runtime.events import AGENT_EVENT_NAMES, public_agent_event
from app.agent_runtime.runner import AgentRunner
from app.agent_tools import ToolGateway, ToolRegistry


class FinalAdapter:
    async def chat(self, **kwargs):
        del kwargs
        return AgentModelResponse(content="已根据现有规则完成说明。", model="fake")


def _context():
    return AgentRuntimeContext(
        user_id="u1",
        hospital_id="h1",
        session_id="s1",
        user_role="doctor",
        permissions=frozenset({"indicator_read"}),
        request_id="r1",
        trace_id="t1",
    )


class AgentRuntimeEventsTest(unittest.IsolatedAsyncioTestCase):
    async def test_runner_emits_business_events_without_messages_or_prompts(self) -> None:
        events = []
        registry = ToolRegistry()
        runner = AgentRunner(
            FinalAdapter(),
            registry,
            ToolGateway(registry),
            event_callback=events.append,
        )
        state = AgentRunState(evidence=[{
            "source_id": "R1",
            "fact_types": ["rule_identity"],
        }])

        result = await runner.run("这个指标是什么？", _context(), state)

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(
            [event["event"] for event in events],
            ["agent_start", "model_start", "assistant_message", "agent_done"],
        )
        self.assertEqual(events[1]["step"], 1)
        self.assertEqual(events[-1]["stop_reason"], "final_answer")
        self.assertTrue(all("messages" not in event for event in events))
        self.assertTrue(all("prompt" not in event for event in events))

    def test_event_protocol_contains_only_public_event_names(self) -> None:
        self.assertEqual(AGENT_EVENT_NAMES, frozenset({
            "agent_start",
            "model_start",
            "tool_call",
            "tool_result",
            "clarification_required",
            "assistant_message",
            "agent_done",
            "agent_error",
        }))

    def test_public_agent_error_keeps_sanitized_message(self) -> None:
        event = public_agent_event(
            {
                "event": "agent_error",
                "stop_reason": "tool_error",
                "answer": "模型服务暂时不可用。（HTTP 400: invalid tool_call_id）",
            },
            trace_id="TRACE_1",
        )

        self.assertEqual(
            event["message"],
            "模型服务暂时不可用。（HTTP 400: invalid tool_call_id）",
        )

    def test_public_terminal_event_keeps_fallback_classification(self) -> None:
        event = public_agent_event(
            {
                "event": "agent_done",
                "stop_reason": "need_clarification",
                "fallback_category": "USER_CLARIFICATION",
                "failure_code": "INDICATOR_AMBIGUOUS",
            },
            trace_id="TRACE_2",
        )

        self.assertEqual(event["fallback_category"], "USER_CLARIFICATION")
        self.assertEqual(event["failure_code"], "INDICATOR_AMBIGUOUS")
