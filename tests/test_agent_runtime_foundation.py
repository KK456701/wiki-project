import unittest

from pydantic import BaseModel, ConfigDict


class SearchInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str


class AgentRuntimeFoundationTest(unittest.IsolatedAsyncioTestCase):
    async def test_context_registry_gateway_and_result_form_closed_loop(self) -> None:
        from app.agent_runtime import AgentRunState, AgentRuntimeContext
        from app.agent_tools import (
            AgentTool,
            ToolGateway,
            ToolRegistry,
            ToolResult,
            ToolRiskLevel,
        )

        def search(arguments, context, state):
            return ToolResult(
                ok=True,
                status="success",
                code="RULE_FOUND",
                summary="已找到急会诊及时到位率",
                data={"rule_id": "MQSI2025_005", "hospital_id": context.hospital_id},
            )

        registry = ToolRegistry([
            AgentTool(
                name="search_indicator_rules",
                description="根据用户问法搜索核心制度指标。",
                input_model=SearchInput,
                handler=search,
                risk_level=ToolRiskLevel.READ,
                required_permissions=frozenset({"indicator_read"}),
            )
        ])
        context = AgentRuntimeContext(
            user_id="user_001", hospital_id="hospital_001", session_id="session_001",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="REQ_001", trace_id="TRACE_001",
        )
        state = AgentRunState()

        schemas = registry.to_ollama_schema(registry.list_for_context(context, state))
        result = await ToolGateway(registry).execute(
            "search_indicator_rules",
            {"query": "急会诊及时到位率怎么算"},
            context,
            state,
        )

        self.assertEqual(schemas[0]["function"]["name"], "search_indicator_rules")
        self.assertTrue(result.ok)
        self.assertEqual(result.data["rule_id"], "MQSI2025_005")
        self.assertEqual(result.data["hospital_id"], "hospital_001")


if __name__ == "__main__":
    unittest.main()
