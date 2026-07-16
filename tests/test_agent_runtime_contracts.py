import unittest

from pydantic import ValidationError


class AgentRuntimeContractsTest(unittest.TestCase):
    def test_runtime_context_rejects_model_supplied_extra_fields_and_is_frozen(self) -> None:
        from app.agent_runtime.contracts import AgentRuntimeContext

        context = AgentRuntimeContext(
            user_id="user_001",
            hospital_id="hospital_001",
            session_id="session_001",
            user_role="implementer",
            permissions=frozenset({"indicator_read"}),
            request_id="REQ_001",
            trace_id="TRACE_001",
        )

        self.assertEqual(context.hospital_id, "hospital_001")
        with self.assertRaises(ValidationError):
            AgentRuntimeContext(
                user_id="user_001",
                hospital_id="hospital_001",
                session_id="session_001",
                user_role="implementer",
                request_id="REQ_001",
                trace_id="TRACE_001",
                database_password="secret",
            )
        with self.assertRaises(ValidationError):
            context.hospital_id = "hospital_002"

    def test_run_state_uses_isolated_mutable_defaults(self) -> None:
        from app.agent_runtime.contracts import AgentRunState

        first = AgentRunState()
        second = AgentRunState()
        first.messages.append({"role": "user", "content": "问题"})
        first.tool_call_counts["fingerprint"] = 1

        self.assertEqual(second.messages, [])
        self.assertEqual(second.tool_call_counts, {})

    def test_model_response_parses_nested_tool_calls(self) -> None:
        from app.agent_runtime.contracts import AgentModelResponse

        response = AgentModelResponse.model_validate(
            {
                "content": "",
                "tool_calls": [
                    {
                        "id": "call_001",
                        "name": "search_indicator_rules",
                        "arguments": {"query": "急会诊及时到位率"},
                    }
                ],
                "model": "qwen3:4b-instruct",
            }
        )

        self.assertEqual(response.tool_calls[0].name, "search_indicator_rules")
        self.assertEqual(response.tool_calls[0].arguments["query"], "急会诊及时到位率")


if __name__ == "__main__":
    unittest.main()
