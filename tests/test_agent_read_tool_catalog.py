import unittest

from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult


class CatalogCaliber:
    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        return RuleSearchResult(
            query=query,
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            }],
            rule_source="mysql",
        )

    def resolve_contract(self, rule_id, hospital_id):
        return EffectiveRule.model_validate({
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "sql_status": "available",
            "rule_source": "mysql",
        })

    def field_mapping_contract(self, rule_id, hospital_id):
        return FieldMapping(
            rule_id=rule_id,
            hospital_id=hospital_id,
            status="confirmed",
        )


class AgentReadToolCatalogTest(unittest.IsolatedAsyncioTestCase):
    def _context(self):
        from app.agent_runtime import AgentRuntimeContext

        return AgentRuntimeContext(
            user_id="user_001",
            hospital_id="hospital_001",
            session_id="session_001",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="REQ_001",
            trace_id="TRACE_001",
        )

    async def test_catalog_exposes_search_then_rule_tools_after_verified_result(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools import ToolGateway
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        context = self._context()
        state = AgentRunState()

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(context, state)],
            ["search_indicator_rules"],
        )
        search_result = await ToolGateway(registry).execute(
            "search_indicator_rules",
            {"query": "急会诊"},
            context,
            state,
        )
        state.last_tool_results.append(search_result.model_dump(mode="json"))

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(context, state)],
            [
                "search_indicator_rules",
                "get_effective_rule",
                "inspect_indicator_implementation",
            ],
        )
        result = await ToolGateway(registry).execute(
            "get_effective_rule",
            {"rule_id": "MQSI2025_005"},
            context,
            state,
        )
        self.assertTrue(result.ok)
        self.assertEqual(result.code, "EFFECTIVE_RULE_FOUND")

    async def test_hidden_rule_tool_is_also_unavailable_at_gateway(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools import ToolGateway
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        result = await ToolGateway(registry).execute(
            "get_effective_rule",
            {"rule_id": "MQSI2025_005"},
            self._context(),
            AgentRunState(),
        )

        self.assertEqual(result.code, "TOOL_UNAVAILABLE")

    def test_verified_rule_evidence_exposes_follow_up_tools(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        state = AgentRunState(evidence=[{
            "source": "mysql",
            "source_id": "MQSI2025_005",
            "fact_types": ["rule_identity"],
        }])

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(self._context(), state)],
            [
                "search_indicator_rules",
                "get_effective_rule",
                "inspect_indicator_implementation",
            ],
        )

    def test_failed_result_does_not_expose_follow_up_tools(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        state = AgentRunState(last_tool_results=[{
            "ok": False,
            "status": "not_found",
            "data": {"rule_id": "MQSI2025_005"},
        }])

        self.assertEqual(
            [tool.name for tool in registry.list_for_context(self._context(), state)],
            ["search_indicator_rules"],
        )

    def test_catalog_schemas_never_expose_runtime_context(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        registry = build_read_tool_registry(ReadToolServices(caliber=CatalogCaliber()))
        state = AgentRunState(last_tool_results=[{
            "ok": True,
            "data": {"resolved_rule_id": "MQSI2025_005"},
        }])
        schemas = registry.to_ollama_schema(
            registry.list_for_context(self._context(), state)
        )

        serialized = str(schemas)
        self.assertNotIn("hospital_id", serialized)
        self.assertNotIn("user_id", serialized)
        self.assertNotIn("db_name", serialized)


if __name__ == "__main__":
    unittest.main()
