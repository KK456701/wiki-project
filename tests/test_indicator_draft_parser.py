import json
import unittest

from app.agents.indicator_generation import IndicatorGenerationAgent
from app.agents.orchestrator import CoreIndicatorOrchestrator
from app.agents.human_interaction import detect_intent_by_rule
from app.indicators.parser import DraftParseError, IndicatorDraftParser


class _FakeLLM:
    def __init__(self, response: str):
        self.response = response
        self.prompts: list[str] = []

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        return self.response


class IndicatorDraftParserTest(unittest.TestCase):
    def test_parses_ratio_plan_and_generates_hospital_code(self) -> None:
        llm = _FakeLLM(json.dumps(_ratio_payload(), ensure_ascii=False))

        result = IndicatorDraftParser(llm).parse(
            "创建夜间急会诊15分钟到位率", "hospital_001"
        )

        self.assertEqual(result.metric_type, "ratio")
        self.assertEqual(result.hospital_id, "hospital_001")
        self.assertTrue(result.proposed_index_code.startswith("HOSP001_"))
        self.assertEqual(result.sql_plan.main_table, "consult_record")
        self.assertIn("request_time", result.metadata_requirements)
        self.assertIn("只输出 JSON", llm.prompts[0])

    def test_parses_count_plan(self) -> None:
        payload = _ratio_payload()
        payload.update(
            {
                "index_name": "夜间急会诊次数",
                "metric_type": "count",
                "numerator_rule": "夜间急会诊次数",
                "denominator_rule": "不适用",
            }
        )
        payload["sql_plan"]["metric_type"] = "count"

        result = IndicatorDraftParser(
            _FakeLLM(json.dumps(payload, ensure_ascii=False))
        ).parse("创建夜间急会诊次数", "hospital_001")

        self.assertEqual(result.metric_type, "count")

    def test_rejects_malformed_model_output(self) -> None:
        with self.assertRaisesRegex(DraftParseError, "无法解析"):
            IndicatorDraftParser(_FakeLLM("不是JSON")).parse(
                "创建指标", "hospital_001"
            )

    def test_rejects_model_supplied_sql(self) -> None:
        payload = _ratio_payload()
        payload["sql_text"] = "SELECT * FROM patient"

        with self.assertRaisesRegex(DraftParseError, "不能直接提供SQL"):
            IndicatorDraftParser(
                _FakeLLM(json.dumps(payload, ensure_ascii=False))
            ).parse("创建指标", "hospital_001")

    def test_rejects_multi_table_plan(self) -> None:
        payload = _ratio_payload()
        payload["required_tables"] = ["consult_record", "department"]
        payload["requires_join"] = True

        with self.assertRaisesRegex(DraftParseError, "暂不支持多表"):
            IndicatorDraftParser(
                _FakeLLM(json.dumps(payload, ensure_ascii=False))
            ).parse("创建指标", "hospital_001")

    def test_existing_rule_reference_is_preserved(self) -> None:
        payload = _ratio_payload()
        payload["base_index_code"] = "MQSI2025_005"

        result = IndicatorDraftParser(
            _FakeLLM(json.dumps(payload, ensure_ascii=False))
        ).parse("基于急会诊及时到位率创建本院版本", "hospital_001")

        self.assertEqual(result.base_index_code, "MQSI2025_005")


class IndicatorDraftAgentBoundaryTest(unittest.TestCase):
    def test_create_indicator_intent_belongs_to_generation_agent(self) -> None:
        self.assertEqual(detect_intent_by_rule("创建一个夜间急会诊指标"), "create_indicator")
        self.assertEqual(
            CoreIndicatorOrchestrator.owner_for_intent("create_indicator"),
            "indicator_generation",
        )

    def test_generation_agent_parses_and_persists_draft(self) -> None:
        calls = []

        class Parser:
            def parse(self, query, hospital_id):
                calls.append(("parse", query, hospital_id))
                from app.indicators.contracts import IndicatorDraftSpec

                return IndicatorDraftSpec.model_validate(
                    {
                        **_ratio_payload(),
                        "hospital_id": hospital_id,
                        "proposed_index_code": "HOSP001_TEST",
                    }
                )

        class Repository:
            def create(self, spec, actor_id):
                calls.append(("create", spec.index_name, actor_id))
                return {"draft_id": "DRAFT_001", "status": "metadata_pending"}

        agent = IndicatorGenerationAgent(
            object(), draft_parser=Parser(), draft_repository=Repository()
        )

        result = agent.create_draft("创建指标", "hospital_001", "user_001")

        self.assertEqual(result["draft_id"], "DRAFT_001")
        self.assertEqual([call[0] for call in calls], ["parse", "create"])


def _ratio_payload() -> dict:
    return {
        "index_name": "夜间急会诊15分钟到位率",
        "index_type": "会诊制度",
        "index_desc": "统计夜间急会诊15分钟内到位情况。",
        "stat_cycle": "month",
        "numerator_rule": "15分钟内到位的夜间急会诊次数",
        "denominator_rule": "夜间急会诊总次数",
        "filter_rule": "18点至次日8点",
        "exclude_rule": "",
        "metric_type": "ratio",
        "metadata_requirements": [
            "hospital_id",
            "consult_id",
            "request_time",
            "arrive_time",
        ],
        "required_tables": ["consult_record"],
        "requires_join": False,
        "sql_plan": {
            "main_table": "consult_record",
            "metric_type": "ratio",
            "subject_field": "consult_id",
            "time_field": "request_time",
            "hospital_field": "hospital_id",
            "numerator_conditions": [
                {"field": "consult_type", "operator": "eq", "value": "急会诊"}
            ],
            "denominator_conditions": [
                {"field": "consult_type", "operator": "eq", "value": "急会诊"}
            ],
        },
    }


if __name__ == "__main__":
    unittest.main()
