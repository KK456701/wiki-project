import unittest
from pathlib import Path


class _FakeRuleRepository:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    def search(self, query, limit=5):
        self.calls.append(("search", query, limit))
        return {"resolved_rule_id": "MQSI2025_005"}

    def get_effective_rule(self, rule_id, hospital_id):
        self.calls.append(("resolve", rule_id, hospital_id))
        return {"rule_id": rule_id, "effective_level": "hospital"}

    def get_field_mapping(self, rule_id, hospital_id):
        self.calls.append(("mapping", rule_id, hospital_id))
        return {"rule_id": rule_id, "hospital_id": hospital_id}

    def build_feedback_preview(self, rule_id, hospital_id, query):
        self.calls.append(("preview", rule_id, hospital_id, query))
        return {"rule_id": rule_id, "status": "preview"}

    def submit_change_request(self, payload):
        self.calls.append(("submit", payload))
        return {"change_id": "CR_001", "status": "pending"}


class _FakeExecutor:
    def __init__(self, result):
        self.result = result
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return self.result

    def run(self, **kwargs):
        self.calls.append(kwargs)
        return self.result


class SpecializedAgentTest(unittest.TestCase):
    def test_caliber_agent_owns_rule_repository_operations(self) -> None:
        from app.agents.caliber_adaptation import CaliberAdaptationAgent

        repository = _FakeRuleRepository()
        agent = CaliberAdaptationAgent(repository)

        self.assertEqual(agent.agent_id, "caliber_adaptation")
        self.assertEqual(agent.search("急会诊", 3)["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(agent.resolve("MQSI2025_005", "hospital_001")["effective_level"], "hospital")
        self.assertEqual(agent.field_mapping("MQSI2025_005", "hospital_001")["hospital_id"], "hospital_001")
        self.assertEqual(agent.preview_feedback("MQSI2025_005", "hospital_001", "按20分钟")["status"], "preview")
        self.assertEqual(agent.submit_change({"rule_id": "MQSI2025_005"})["status"], "pending")
        self.assertEqual([call[0] for call in repository.calls], ["search", "resolve", "mapping", "preview", "submit"])

    def test_indicator_generation_agent_delegates_structured_generation(self) -> None:
        from app.agents.indicator_generation import IndicatorGenerationAgent

        executor = _FakeExecutor({"sql_id": "SQL_001", "sql_status": "validated"})
        agent = IndicatorGenerationAgent(executor)
        result = agent.generate(query="生成SQL", hospital_id="hospital_001")

        self.assertEqual(agent.agent_id, "indicator_generation")
        self.assertEqual(result["sql_id"], "SQL_001")
        self.assertEqual(executor.calls[0]["hospital_id"], "hospital_001")

    def test_root_cause_agent_delegates_three_layer_diagnosis(self) -> None:
        from app.agents.root_cause_diagnosis import RootCauseDiagnosisAgent

        executor = _FakeExecutor({"diagnose_status": "success", "layers": [1, 2, 3]})
        agent = RootCauseDiagnosisAgent(executor)
        result = agent.run(hospital_id="hospital_001", rule_id="MQSI2025_005")

        self.assertEqual(agent.agent_id, "root_cause_diagnosis")
        self.assertEqual(result["layers"], [1, 2, 3])
        self.assertEqual(executor.calls[0]["rule_id"], "MQSI2025_005")

    def test_metadata_agent_owns_sync_and_precheck(self) -> None:
        from app.agents.metadata_parsing import MetadataParsingAgent

        calls = []

        def sync_fn(**kwargs):
            calls.append(("sync", kwargs))
            return {"batch_id": "B001"}

        def precheck_fn(kb_root, runtime_engine, hospital_id, rule_id):
            calls.append(("precheck", {"kb_root": kb_root, "hospital_id": hospital_id, "rule_id": rule_id}))
            return {"ok": True}

        agent = MetadataParsingAgent(
            runtime_engine=object(),
            kb_root=Path("core-rules-wiki"),
            sync_fn=sync_fn,
            precheck_fn=precheck_fn,
        )

        self.assertEqual(agent.agent_id, "metadata_parsing")
        self.assertEqual(agent.sync(object(), "hospital_001", "hospital_demo_data")["batch_id"], "B001")
        self.assertTrue(agent.precheck("hospital_001", "MQSI2025_005")["ok"])
        self.assertEqual([call[0] for call in calls], ["sync", "precheck"])

    def test_human_interaction_agent_understands_and_answers(self) -> None:
        from app.agents.human_interaction import HumanInteractionAgent

        agent = HumanInteractionAgent()
        understood = agent.understand("急会诊及时到位率怎么算？")
        answer, method = agent.answer(
            "急会诊及时到位率怎么算？",
            {
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
                "effective_level": "hospital",
                "definition": "20分钟内到位的比例。",
                "formula": "20分钟内到位次数 / 总次数 × 100%",
                "implementation_status": "已配置",
                "sql_status": "available",
                "warnings": [],
            },
        )

        self.assertEqual(agent.agent_id, "human_interaction")
        self.assertEqual(understood["intent"], "query")
        self.assertEqual(method, "tool")
        self.assertIn("20分钟", answer)


if __name__ == "__main__":
    unittest.main()
