import asyncio
import unittest

from pydantic import BaseModel, ConfigDict


class QueryInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


class AgentToolGatewayTest(unittest.IsolatedAsyncioTestCase):
    def _context(self, permissions=frozenset({"indicator_read"})):
        from app.agent_runtime.contracts import AgentRuntimeContext

        return AgentRuntimeContext(
            user_id="user_001", hospital_id="hospital_001", session_id="session_001",
            user_role="implementer", permissions=permissions,
            request_id="REQ_001", trace_id="TRACE_001",
        )

    def _gateway(
        self,
        handler,
        *,
        timeout=1.0,
        permissions=frozenset({"indicator_read"}),
        trace_events=None,
        availability=None,
    ):
        from app.agent_tools.contracts import AgentTool, ToolRiskLevel
        from app.agent_tools.gateway import ToolGateway
        from app.agent_tools.registry import ToolRegistry

        tool = AgentTool(
            name="search_indicator_rules",
            description="搜索核心制度指标。",
            input_model=QueryInput,
            handler=handler,
            risk_level=ToolRiskLevel.READ,
            timeout_seconds=timeout,
            required_permissions=permissions,
            availability=availability,
        )
        return ToolGateway(
            ToolRegistry([tool]),
            trace_callback=(trace_events.append if trace_events is not None else None),
        )

    async def test_unknown_tool_returns_standard_result(self) -> None:
        from app.agent_runtime.contracts import AgentRunState
        from app.agent_tools.gateway import ToolGateway
        from app.agent_tools.registry import ToolRegistry

        result = await ToolGateway(ToolRegistry()).execute(
            "missing_tool", {}, self._context(), AgentRunState()
        )

        self.assertFalse(result.ok)
        self.assertEqual(result.code, "TOOL_NOT_FOUND")

    async def test_extra_hospital_id_is_rejected_before_handler(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        called = False

        def handler(arguments, context, state):
            nonlocal called
            called = True
            return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

        result = await self._gateway(handler).execute(
            "search_indicator_rules",
            {"query": "急会诊", "hospital_id": "hospital_002"},
            self._context(),
            AgentRunState(),
        )

        self.assertFalse(called)
        self.assertEqual(result.code, "INVALID_TOOL_ARGUMENTS")

    async def test_permission_is_checked_again_at_execution(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        result = await self._gateway(lambda *_: None).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(frozenset()), AgentRunState(),
        )

        self.assertEqual(result.code, "PERMISSION_DENIED")

    async def test_unavailable_tool_is_rejected_before_handler(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        called = False

        def handler(arguments, context, state):
            nonlocal called
            called = True
            return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

        result = await self._gateway(
            handler,
            availability=lambda _context, _state: False,
        ).execute(
            "search_indicator_rules",
            {"query": "急会诊"},
            self._context(),
            AgentRunState(),
        )

        self.assertFalse(called)
        self.assertEqual(result.status, "unavailable")
        self.assertEqual(result.code, "TOOL_UNAVAILABLE")

    async def test_availability_exception_fails_closed(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        def unavailable(_context, _state):
            raise RuntimeError("internal state error")

        result = await self._gateway(
            lambda *_: None,
            availability=unavailable,
        ).execute(
            "search_indicator_rules",
            {"query": "急会诊"},
            self._context(),
            AgentRunState(),
        )

        self.assertEqual(result.code, "TOOL_UNAVAILABLE")
        self.assertNotIn("internal state error", result.summary)

    async def test_sync_handler_runs_and_receives_server_context(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        def handler(arguments, context, state):
            return {
                "ok": True,
                "status": "success",
                "code": "RULE_FOUND",
                "summary": f"{context.hospital_id}:{arguments.query}",
            }

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertTrue(result.ok)
        self.assertEqual(result.summary, "hospital_001:急会诊")

    async def test_async_handler_runs(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        async def handler(arguments, context, state):
            return {"ok": True, "status": "success", "code": "OK", "summary": arguments.query}

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertTrue(result.ok)

    async def test_timeout_is_standardized(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        async def handler(arguments, context, state):
            await asyncio.sleep(0.05)
            return {"ok": True, "status": "success", "code": "OK", "summary": "late"}

        result = await self._gateway(handler, timeout=0.001).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(result.status, "timeout")
        self.assertEqual(result.code, "TOOL_TIMEOUT")
        self.assertTrue(result.retryable)

    async def test_handler_exception_does_not_expose_internal_message(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        def handler(arguments, context, state):
            raise RuntimeError("database_password=secret")

        result = await self._gateway(handler).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(result.code, "TOOL_EXECUTION_FAILED")
        self.assertNotIn("secret", result.summary)
        self.assertNotIn("secret", str(result.data))

    async def test_second_duplicate_is_not_executed_and_third_stops_run(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        calls = 0
        events = []

        def handler(arguments, context, state):
            nonlocal calls
            calls += 1
            return {"ok": True, "status": "success", "code": "OK", "summary": "ok"}

        gateway = self._gateway(handler, trace_events=events)
        state = AgentRunState()
        arguments = {"query": "急会诊"}

        first = await gateway.execute("search_indicator_rules", arguments, self._context(), state)
        second = await gateway.execute("search_indicator_rules", arguments, self._context(), state)
        third = await gateway.execute("search_indicator_rules", arguments, self._context(), state)

        self.assertTrue(first.ok)
        self.assertEqual(second.model_dump(), first.model_dump())
        self.assertFalse(third.retryable)
        self.assertEqual(calls, 1)
        self.assertEqual(state.stop_reason, "repeated_tool_call")
        result_events = [event for event in events if event["event"] == "tool_result"]
        self.assertEqual(len(result_events), 3)
        self.assertFalse(result_events[0].get("reused", False))
        self.assertTrue(result_events[1]["reused"])
        self.assertFalse(result_events[2]["reused"])
        self.assertEqual(result_events[2]["result"]["code"], "AGENT_REPEATED_TOOL_CALL")

    async def test_trace_callback_receives_redacted_arguments_and_results(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        events = []

        def handler(arguments, context, state):
            return {
                "ok": True, "status": "success", "code": "OK", "summary": "ok",
                "data": {"sql_text": "SELECT patient_name FROM patient"},
            }

        await self._gateway(handler, trace_events=events).execute(
            "search_indicator_rules", {"query": "急会诊"},
            self._context(), AgentRunState(),
        )

        self.assertEqual(events[0]["event"], "tool_call")
        self.assertEqual(events[-1]["event"], "tool_result")
        self.assertEqual(events[-1]["result"]["data"]["sql_text"], "[REDACTED]")


if __name__ == "__main__":
    unittest.main()
