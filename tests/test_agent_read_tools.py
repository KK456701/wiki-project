import unittest

from app.agents.contracts import RuleSearchResult
from app.terminology.contracts import TermMatch, TermNormalizationResult


class FakeTerminology:
    def __init__(self, result: TermNormalizationResult) -> None:
        self.result = result
        self.calls = []

    def normalize(self, text, hospital_id):
        self.calls.append((text, hospital_id))
        return self.result


class FakeCaliber:
    def __init__(self, search_result=None) -> None:
        self.search_result = search_result or RuleSearchResult(query="", matches=[])
        self.search_calls = []

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        self.search_calls.append((query, hospital_id, limit))
        return self.search_result.model_copy(update={"query": query})


class AgentReadToolsTest(unittest.TestCase):
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

    def test_search_uses_linked_rule_and_server_hospital_context(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        terminology = FakeTerminology(TermNormalizationResult(
            original_text="急会诊怎么算",
            normalized_text="急会诊及时到位率怎么算",
            release_version="TERM_2026_07",
            matches=[TermMatch(
                matched_text="急会诊",
                concept_code="IND_MQSI2025_005",
                canonical_name="急会诊及时到位率",
                relation_type="colloquial",
                retrieval_enabled=True,
                sql_safe=True,
                linked_rule_ids=["MQSI2025_005"],
            )],
        ))
        caliber = FakeCaliber(RuleSearchResult(
            query="MQSI2025_005",
            resolved_rule_id="MQSI2025_005",
            matches=[{
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
                "type": "mysql_standard",
            }],
            rule_source="mysql",
        ))

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="急会诊怎么算"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber, terminology=terminology),
        )

        self.assertTrue(result.ok)
        self.assertEqual(caliber.search_calls, [("MQSI2025_005", "hospital_001", 5)])
        self.assertEqual(result.data["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(result.evidence[0].source_id, "MQSI2025_005")

    def test_search_returns_clarification_without_repository_call_for_ambiguity(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        terminology = FakeTerminology(TermNormalizationResult(
            original_text="转科率",
            normalized_text="转科率",
            ambiguities=[{"text": "转科率", "concept_codes": ["A", "B"]}],
        ))
        caliber = FakeCaliber()

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="转科率"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber, terminology=terminology),
        )

        self.assertEqual(result.status, "need_clarification")
        self.assertEqual(caliber.search_calls, [])

    def test_search_returns_not_found_as_standard_result(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        caliber = FakeCaliber(RuleSearchResult(query="未知指标", matches=[]))
        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="未知指标"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertEqual(result.status, "not_found")
        self.assertEqual(result.code, "RULE_NOT_FOUND")


if __name__ == "__main__":
    unittest.main()
