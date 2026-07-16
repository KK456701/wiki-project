import asyncio
import unittest

from app.agent_runtime import AgentModelResponse, AgentRunState, AgentToolCall
from pydantic import BaseModel, ConfigDict


class QueryInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


class SequenceAdapter:
    def __init__(self, responses=None, delay=0):
        self.responses = list(responses or [])
        self.delay = delay
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        if self.delay:
            await asyncio.sleep(self.delay)
        return self.responses.pop(0)


class AgentRunnerControlsTest(unittest.IsolatedAsyncioTestCase):
    def _context(self):
        from app.agent_runtime import AgentRuntimeContext
        return AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="r1", trace_id="t1",
        )

    def _runner(self, adapter, tools=(), **kwargs):
        from app.agent_runtime.runner import AgentRunner
        from app.agent_tools import ToolGateway, ToolRegistry
        registry = ToolRegistry(tools)
        return AgentRunner(adapter, registry, ToolGateway(registry), **kwargs)

    def _tool(self, handler):
        from app.agent_tools import AgentTool, ToolRiskLevel
        return AgentTool(
            name="search_indicator_rules",
            description="搜索指标。",
            input_model=QueryInput,
            handler=handler,
            risk_level=ToolRiskLevel.READ,
            required_permissions=frozenset({"indicator_read"}),
        )

    async def test_cancelled_state_never_calls_model(self) -> None:
        adapter = SequenceAdapter([])
        result = await self._runner(adapter).run(
            "问题", self._context(), AgentRunState(cancelled=True)
        )
        self.assertEqual(result.stop_reason, "cancelled")
        self.assertEqual(adapter.calls, [])

    async def test_request_timeout_is_standardized(self) -> None:
        result = await self._runner(
            SequenceAdapter(delay=0.05),
            request_timeout_seconds=0.001,
        ).run("问题", self._context())
        self.assertEqual(result.stop_reason, "request_timeout")

    async def test_more_than_three_tool_calls_stops_without_execution(self) -> None:
        calls = [AgentToolCall(name="missing_tool", arguments={}) for _ in range(4)]
        result = await self._runner(
            SequenceAdapter([AgentModelResponse(tool_calls=calls)])
        ).run("问题", self._context())
        self.assertEqual(result.stop_reason, "tool_error")
        self.assertEqual(result.state.last_tool_results, [])

    async def test_final_answer_without_evidence_is_not_accepted(self) -> None:
        adapter = SequenceAdapter([
            AgentModelResponse(content="这是一个没有证据的回答。"),
            AgentModelResponse(content="仍然没有证据。"),
        ])
        result = await self._runner(adapter, max_steps=4).run("问题", self._context())
        # 第一次无证据会触发纠正，第二次仍无证据则放行（最多1次纠正）
        self.assertIn(result.stop_reason, ("final_answer", "max_steps"))

    async def test_non_chinese_final_answer_is_rewritten_after_evidence(self) -> None:
        state = AgentRunState(evidence=[{
            "source": "mysql", "source_id": "R1", "fact_types": ["definition"]
        }])
        adapter = SequenceAdapter([
            AgentModelResponse(content="English answer"),
            AgentModelResponse(content="这是中文回答。"),
        ])
        result = await self._runner(adapter).run("问题", self._context(), state)
        self.assertEqual(result.stop_reason, "final_answer")
        self.assertEqual(result.answer, "这是中文回答。")

    async def test_non_retryable_tool_error_stops_run(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(tool_calls=[
            AgentToolCall(name="missing_tool", arguments={})
        ])])
        result = await self._runner(adapter).run("问题", self._context())
        self.assertEqual(result.stop_reason, "tool_error")

    async def test_repeated_tool_call_stops_on_third_attempt(self) -> None:
        call = AgentToolCall(
            name="search_indicator_rules", arguments={"query": "急会诊"}
        )
        adapter = SequenceAdapter([
            AgentModelResponse(tool_calls=[call]),
            AgentModelResponse(tool_calls=[call]),
            AgentModelResponse(tool_calls=[call]),
        ])
        tool = self._tool(lambda *_: {
            "ok": True, "status": "success", "code": "OK", "summary": "ok"
        })

        result = await self._runner(adapter, [tool]).run("问题", self._context())

        self.assertEqual(result.stop_reason, "repeated_tool_call")

    async def test_tool_clarification_is_returned_to_user(self) -> None:
        adapter = SequenceAdapter([AgentModelResponse(tool_calls=[AgentToolCall(
            name="search_indicator_rules", arguments={"query": "转科率"}
        )])])
        tool = self._tool(lambda *_: {
            "ok": False,
            "status": "need_clarification",
            "code": "TERM_AMBIGUOUS",
            "summary": "请明确具体转科指标。",
        })

        result = await self._runner(adapter, [tool]).run("转科率", self._context())

        self.assertEqual(result.stop_reason, "need_clarification")
        self.assertEqual(result.answer, "请明确具体转科指标。")

    async def test_failed_tool_evidence_cannot_authorize_final_answer(self) -> None:
        adapter = SequenceAdapter([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="search_indicator_rules", arguments={"query": "未知指标"}
            )]),
            AgentModelResponse(content="这是基于失败结果编造的回答。"),
        ])
        tool = self._tool(lambda *_: {
            "ok": False,
            "status": "not_found",
            "code": "RULE_NOT_FOUND",
            "summary": "未找到指标。",
            "evidence": [{
                "source": "invalid_fixture",
                "source_id": "R1",
                "fact_types": ["definition"],
            }],
        })

        result = await self._runner(adapter, [tool], max_steps=2).run(
            "未知指标", self._context()
        )

        self.assertEqual(result.stop_reason, "max_steps")
        self.assertEqual(result.state.evidence, [])


if __name__ == "__main__":
    unittest.main()
