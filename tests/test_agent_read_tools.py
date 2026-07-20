import unittest

from app.agents.contracts import EffectiveRule, FieldMapping, RuleSearchResult
from app.terminology.contracts import TermMatch, TermNormalizationResult


class FakeTerminology:
    def __init__(self, result: TermNormalizationResult) -> None:
        self.result = result
        self.calls = []

    def normalize(self, text, hospital_id):
        self.calls.append((text, hospital_id))
        return self.result


class FakeCaliber:
    def __init__(self, search_result=None, rule=None, mapping=None) -> None:
        self.search_result = search_result or RuleSearchResult(query="", matches=[])
        self.rule = rule
        self.mapping = mapping
        self.search_calls = []
        self.resolve_calls = []
        self.mapping_calls = []

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        self.search_calls.append((query, hospital_id, limit))
        return self.search_result.model_copy(update={"query": query})

    def resolve_contract(self, rule_id, hospital_id):
        self.resolve_calls.append((rule_id, hospital_id))
        if self.rule is None:
            raise LookupError(rule_id)
        return self.rule

    def field_mapping_contract(self, rule_id, hospital_id):
        self.mapping_calls.append((rule_id, hospital_id))
        return self.mapping or FieldMapping(rule_id=rule_id, hospital_id=hospital_id)


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

    def _effective_rule(self):
        return EffectiveRule.model_validate({
            "rule_id": "MQSI2025_005",
            "rule_name": "急会诊及时到位率",
            "effective_level": "hospital",
            "definition": "急会诊在规定时间内到位的比例。",
            "formula": "及时到位例数 / 急会诊总例数 × 100%",
            "standard_sql": "SELECT patient_name FROM patient",
            "sql_status": "available",
            "field_status": "configured",
            "field_contract": {
                "business_fields": {
                    "consult_apply_time": {},
                    "consult_arrive_time": {},
                }
            },
            "national_version": "2025",
            "hospital_version": 2,
            "overridden_fields": ["denominator_rule"],
            "rule_source": "mysql",
        })

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

    def test_search_prefers_unique_production_candidate_over_test_indicator(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        caliber = FakeCaliber(RuleSearchResult(
            query="急会诊",
            resolved_rule_id="HOSP_TEST",
            matches=[
                {
                    "rule_id": "HOSP_TEST",
                    "rule_name": "急会诊10分钟到位率测试",
                    "type": "mysql_hospital_defined",
                },
                {
                    "rule_id": "MQSI2025_005",
                    "rule_name": "急会诊及时到位率",
                    "type": "mysql_standard",
                },
            ],
        ))

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="急会诊"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertTrue(result.ok)
        self.assertEqual(result.data["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(
            result.data["selection_reason"],
            "unique_production_candidate",
        )

    def test_search_keeps_multiple_production_candidates_unresolved(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            SearchIndicatorRulesInput,
            search_indicator_rules,
        )

        caliber = FakeCaliber(RuleSearchResult(
            query="急会诊",
            resolved_rule_id="MQSI2025_005",
            matches=[
                {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
                {"rule_id": "MQSI2025_006", "rule_name": "急会诊有效率"},
            ],
        ))

        result = search_indicator_rules(
            SearchIndicatorRulesInput(query="急会诊"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertTrue(result.ok)
        self.assertIsNone(result.data["resolved_rule_id"])
        self.assertEqual(result.data["selection_reason"], "ambiguous_candidates")

    def test_get_effective_rule_returns_safe_projection(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            RuleReferenceInput,
            get_effective_rule,
        )

        caliber = FakeCaliber(rule=self._effective_rule())
        result = get_effective_rule(
            RuleReferenceInput(rule_id="MQSI2025_005"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertTrue(result.ok)
        self.assertEqual(caliber.resolve_calls, [("MQSI2025_005", "hospital_001")])
        self.assertEqual(result.data["sql_status"], "available")
        self.assertNotIn("standard_sql", result.data)
        self.assertNotIn("SELECT", str(result.data))
        self.assertEqual(result.evidence[0].version, "2")

    def test_get_effective_rule_standardizes_not_found(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            RuleReferenceInput,
            get_effective_rule,
        )

        result = get_effective_rule(
            RuleReferenceInput(rule_id="missing"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=FakeCaliber()),
        )

        self.assertEqual(result.status, "not_found")
        self.assertEqual(result.code, "RULE_NOT_FOUND")

    def test_inspect_implementation_derives_gaps_without_database_name(self) -> None:
        from app.agent_runtime import AgentRunState
        from app.agent_tools.read_tools import (
            ReadToolServices,
            RuleReferenceInput,
            inspect_indicator_implementation,
        )

        mapping = FieldMapping.model_validate({
            "rule_id": "MQSI2025_005",
            "hospital_id": "hospital_001",
            "db_name": "patient_prod",
            "dialect": "sqlserver",
            "main_table": "consultation",
            "fields": {"consult_apply_time": "consultation.apply_time"},
            "status": "pending",
            "items": [{
                "business_field": "consult_apply_time",
                "db_name": "patient_prod",
                "table_name": "consultation",
                "column_name": "apply_time",
                "data_type": "datetime",
                "status": "pending",
            }],
            "relations": [{
                "left_table": "consultation",
                "left_column": "encounter_id",
                "right_table": "encounter",
                "right_column": "id",
                "join_type": "inner",
                "relation_source": "confirmed_mapping",
                "status": "confirmed",
                "db_name": "patient_prod",
            }],
        })
        caliber = FakeCaliber(rule=self._effective_rule(), mapping=mapping)

        result = inspect_indicator_implementation(
            RuleReferenceInput(rule_id="MQSI2025_005"),
            self._context(),
            AgentRunState(),
            ReadToolServices(caliber=caliber),
        )

        self.assertTrue(result.ok)
        self.assertEqual(result.data["missing_mappings"], ["consult_arrive_time"])
        self.assertEqual(result.data["unconfirmed_mappings"], ["consult_apply_time"])
        self.assertNotIn("db_name", str(result.data))
        self.assertEqual(result.evidence[0].version, "2")


if __name__ == "__main__":
    unittest.main()
