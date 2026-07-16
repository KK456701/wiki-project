import unittest

from app.agent_runtime import AgentModelResponse, AgentToolCall
from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult


class FakeModelAdapter:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeCaliber:
    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        return RuleSearchResult(
            query=query,
            resolved_rule_id="MQSI2025_005",
            matches=[{"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"}],
            rule_source="mysql",
        )

    def resolve_contract(self, rule_id, hospital_id):
        return EffectiveRule.model_validate({
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "definition": "急会诊在规定时间内到位的比例。",
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "sql_status": "available",
            "national_version": "2025",
            "rule_source": "mysql",
        })

    def field_mapping_contract(self, rule_id, hospital_id):
        return FieldMapping(rule_id=rule_id, hospital_id=hospital_id)


class AgentRunnerTest(unittest.IsolatedAsyncioTestCase):
    def _context(self):
        from app.agent_runtime import AgentRuntimeContext
        return AgentRuntimeContext(
            user_id="u1", hospital_id="h1", session_id="s1",
            user_role="implementer", permissions=frozenset({"indicator_read"}),
            request_id="r1", trace_id="t1",
        )

    async def test_search_rule_answer_closed_loop(self) -> None:
        from app.agent_runtime.runner import AgentRunner
        from app.agent_tools import ToolGateway
        from app.agent_tools.read_tools import ReadToolServices, build_read_tool_registry

        adapter = FakeModelAdapter([
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="search_indicator_rules", arguments={"query": "急会诊及时到位率"}
            )]),
            AgentModelResponse(tool_calls=[AgentToolCall(
                name="get_effective_rule", arguments={"rule_id": "MQSI2025_005"}
            )]),
            AgentModelResponse(content="急会诊及时到位率是规定时间内到位例数占急会诊总例数的比例。"),
        ])
        registry = build_read_tool_registry(ReadToolServices(caliber=FakeCaliber()))
        runner = AgentRunner(adapter, registry, ToolGateway(registry))

        result = await runner.run("急会诊及时到位率怎么算？", self._context())

        self.assertEqual(result.stop_reason, "final_answer")
        self.assertIn("急会诊", result.answer)
        self.assertEqual(result.state.step_count, 3)
        self.assertEqual(
            [[schema["function"]["name"] for schema in call["tools"]] for call in adapter.calls],
            [
                ["search_indicator_rules"],
                ["search_indicator_rules", "get_effective_rule", "inspect_indicator_implementation"],
                ["search_indicator_rules", "get_effective_rule", "inspect_indicator_implementation"],
            ],
        )
        self.assertEqual(len(result.state.last_tool_results), 2)
        self.assertTrue(result.state.evidence)
        self.assertTrue(any(message["role"] == "tool" for message in result.state.messages))


if __name__ == "__main__":
    unittest.main()
