import unittest


class AgentToolPolicyTest(unittest.TestCase):
    def test_fingerprint_is_stable_across_argument_key_order(self) -> None:
        from app.agent_tools.policy import tool_call_fingerprint

        left = tool_call_fingerprint("search_indicator_rules", {"query": "急会诊", "limit": 5})
        right = tool_call_fingerprint("search_indicator_rules", {"limit": 5, "query": "急会诊"})

        self.assertEqual(left, right)

    def test_policy_allows_first_warns_second_and_stops_third_call(self) -> None:
        from app.agent_runtime.contracts import AgentRunState
        from app.agent_tools.policy import RepeatDecision, ToolExecutionPolicy

        state = AgentRunState()
        policy = ToolExecutionPolicy()
        arguments = {"query": "急会诊"}

        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.ALLOW)
        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.DUPLICATE)
        self.assertEqual(policy.note_call(state, "search_indicator_rules", arguments), RepeatDecision.STOP)
        self.assertEqual(state.stop_reason, "repeated_tool_call")

    def test_redaction_masks_nested_sensitive_values_without_changing_safe_fields(self) -> None:
        from app.agent_tools.policy import redact_payload

        payload = {
            "query": "急会诊",
            "authorization": "Bearer secret",
            "nested": {
                "database_password": "123456",
                "sql_text": "SELECT * FROM patient",
                "safe_count": 3,
            },
        }

        redacted = redact_payload(payload)

        self.assertEqual(redacted["query"], "急会诊")
        self.assertEqual(redacted["authorization"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["database_password"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["sql_text"], "[REDACTED]")
        self.assertEqual(redacted["nested"]["safe_count"], 3)


if __name__ == "__main__":
    unittest.main()
