import unittest

from pydantic import BaseModel, ConfigDict


class SearchInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


def _handler(arguments, context, state):
    return {"ok": True, "status": "success", "code": "OK", "summary": arguments.query}


class AgentToolRegistryTest(unittest.TestCase):
    def _tool(self, **overrides):
        from app.agent_tools.contracts import AgentTool, ToolRiskLevel

        payload = {
            "name": "search_indicator_rules",
            "description": "根据指标名称或同义词搜索核心制度指标。",
            "input_model": SearchInput,
            "handler": _handler,
            "risk_level": ToolRiskLevel.READ,
            "required_permissions": frozenset({"indicator_read"}),
        }
        payload.update(overrides)
        return AgentTool(**payload)

    def test_registry_rejects_duplicate_names(self) -> None:
        from app.agent_tools.registry import ToolRegistry, ToolRegistryError

        registry = ToolRegistry()
        registry.register(self._tool())
        with self.assertRaises(ToolRegistryError):
            registry.register(self._tool())

    def test_registry_emits_ollama_function_schema_without_runtime_context(self) -> None:
        from app.agent_tools.registry import ToolRegistry

        registry = ToolRegistry([self._tool()])
        schema = registry.to_ollama_schema(registry.all())

        self.assertEqual(schema[0]["type"], "function")
        self.assertEqual(schema[0]["function"]["name"], "search_indicator_rules")
        properties = schema[0]["function"]["parameters"]["properties"]
        self.assertEqual(set(properties), {"query"})
        self.assertNotIn("hospital_id", properties)
        self.assertNotIn("user_id", properties)

    def test_registry_filters_missing_permissions(self) -> None:
        from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
        from app.agent_tools.registry import ToolRegistry

        registry = ToolRegistry([self._tool()])
        context = AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="doctor", permissions=frozenset(),
            request_id="r1", trace_id="t1",
        )

        self.assertEqual(registry.list_for_context(context, AgentRunState()), [])

    def test_registry_applies_state_availability(self) -> None:
        from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
        from app.agent_tools.registry import ToolRegistry

        tool = self._tool(
            availability=lambda _context, state: state.step_count > 0,
        )
        registry = ToolRegistry([tool])
        context = AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="r1", trace_id="t1",
        )

        self.assertEqual(registry.list_for_context(context, AgentRunState()), [])
        self.assertEqual(
            [item.name for item in registry.list_for_context(context, AgentRunState(step_count=1))],
            ["search_indicator_rules"],
        )

    def test_tool_definition_rejects_invalid_name_and_timeout(self) -> None:
        with self.assertRaises(ValueError):
            self._tool(name="Invalid Tool")
        with self.assertRaises(ValueError):
            self._tool(timeout_seconds=0)


if __name__ == "__main__":
    unittest.main()
