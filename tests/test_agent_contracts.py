import unittest

from pydantic import ValidationError


class AgentContractTest(unittest.TestCase):
    def test_rule_contracts_declare_calculation_and_mapping_details(self) -> None:
        from app.agents.contracts import EffectiveRule, FieldMapping

        self.assertIn("calculation_definition", EffectiveRule.model_fields)
        self.assertIn(
            "national_calculation_definition", EffectiveRule.model_fields
        )
        self.assertIn("mapping_items", FieldMapping.model_fields)
        self.assertIn("status", FieldMapping.model_fields)
        mapping = FieldMapping.model_validate(
            {
                "rule_id": "MQSI2025_005",
                "items": [{"business_field": "request_time"}],
            }
        )
        self.assertEqual(
            mapping.mapping_items[0]["business_field"], "request_time"
        )
        self.assertIn("items", mapping.model_dump(by_alias=True))

    def test_intent_contract_rejects_unknown_intent(self) -> None:
        from app.agents.contracts import IntentResult

        with self.assertRaises(ValidationError):
            IntentResult(intent="unknown", retrieval_query="急会诊及时到位率")

    def test_rule_search_contract_normalizes_mysql_and_wiki_shapes(self) -> None:
        from app.agents.contracts import RuleSearchResult

        mysql = RuleSearchResult.model_validate({
            "query": "急会诊及时到位率",
            "resolved_rule_id": "MQSI2025_005",
            "matches": [{"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"}],
            "rule_source": "mysql",
        })
        wiki = RuleSearchResult.model_validate({
            "query": "急会诊及时到位率",
            "resolved_rule_id": "MQSI2025_005",
            "results": [{"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"}],
            "rule_source": "wiki_fallback",
        })

        self.assertEqual(mysql.match_count, 1)
        self.assertEqual(wiki.match_count, 1)
        self.assertEqual(wiki.matches[0].rule_id, "MQSI2025_005")
        self.assertEqual(mysql["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(mysql.get("rule_source"), "mysql")

    def test_prepared_request_parses_nested_agent_contracts(self) -> None:
        from app.agents.contracts import PreparedRequest

        prepared = PreparedRequest(
            query="生成 SQL",
            hospital_id="hospital_001",
            intent="generate_sql",
            retrieval_query="急会诊及时到位率",
            rule_id="MQSI2025_005",
            search={
                "query": "急会诊及时到位率",
                "resolved_rule_id": "MQSI2025_005",
                "matches": [{"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"}],
            },
            effective_rule={
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
                "effective_level": "hospital",
            },
            field_mapping={
                "rule_id": "MQSI2025_005",
                "hospital_id": "hospital_001",
                "fields": {"request_time": "consult_record.request_time"},
            },
        )

        self.assertEqual(prepared.search.match_count, 1)
        self.assertEqual(prepared.effective_rule.rule_name, "急会诊及时到位率")
        self.assertEqual(
            prepared.field_mapping.fields["request_time"],
            "consult_record.request_time",
        )

    def test_sql_and_diagnosis_contracts_validate_nested_results(self) -> None:
        from app.agents.contracts import DiagnosisResult, SQLGenerationResult

        sql = SQLGenerationResult.model_validate({
            "status": "success",
            "sql_id": "SQL_001",
            "sql_status": "validated",
            "validation": {"ok": True, "message": "安全校验通过"},
            "precheck": {"ok": True, "missing_mappings": [], "missing_columns": []},
        })
        diagnosis = DiagnosisResult.model_validate({
            "ok": True,
            "diagnose_status": "warning",
            "layers": [{"layer": 1, "layer_name": "结构适配校验", "ok": True}],
        })

        self.assertTrue(sql.validation.ok)
        self.assertTrue(sql.precheck.ok)
        self.assertEqual(diagnosis.layers[0].layer_name, "结构适配校验")


if __name__ == "__main__":
    unittest.main()
