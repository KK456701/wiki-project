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
        return {
            "rule_id": rule_id,
            "rule_name": "急会诊及时到位率",
            "effective_level": "hospital",
        }

    def get_caliber_comparison(self, rule_id, hospital_id):
        self.calls.append(("comparison", rule_id, hospital_id))
        return {
            "rule_id": rule_id,
            "hospital_id": hospital_id,
            "applicable": True,
            "national_sql_template": "SELECT 1",
            "effective_sql_template": "SELECT 2",
        }

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
        from app.agents.contracts import (
            CaliberComparisonContext,
            EffectiveRule,
            FieldMapping,
            RuleSearchResult,
        )

        repository = _FakeRuleRepository()
        agent = CaliberAdaptationAgent(repository)

        self.assertEqual(agent.agent_id, "caliber_adaptation")
        search = agent.search_contract("急会诊", 3)
        effective = agent.resolve_contract("MQSI2025_005", "hospital_001")
        comparison = agent.comparison_context_contract(
            "MQSI2025_005", "hospital_001"
        )
        mapping = agent.field_mapping_contract("MQSI2025_005", "hospital_001")

        self.assertIsInstance(search, RuleSearchResult)
        self.assertIsInstance(effective, EffectiveRule)
        self.assertIsInstance(comparison, CaliberComparisonContext)
        self.assertIsInstance(mapping, FieldMapping)
        self.assertEqual(search["resolved_rule_id"], "MQSI2025_005")
        self.assertEqual(effective["effective_level"], "hospital")
        self.assertEqual(mapping["hospital_id"], "hospital_001")
        self.assertEqual(agent.preview_feedback("MQSI2025_005", "hospital_001", "按20分钟")["status"], "preview")
        self.assertEqual(agent.submit_change({"rule_id": "MQSI2025_005"})["status"], "pending")
        self.assertEqual(
            [call[0] for call in repository.calls],
            ["search", "resolve", "comparison", "mapping", "preview", "submit"],
        )

    def test_indicator_generation_agent_delegates_structured_generation(self) -> None:
        from app.agents.contracts import SQLGenerationResult
        from app.agents.indicator_generation import IndicatorGenerationAgent

        executor = _FakeExecutor({"sql_id": "SQL_001", "sql_status": "validated"})
        agent = IndicatorGenerationAgent(executor)
        result = agent.generate_contract(query="生成SQL", hospital_id="hospital_001")

        self.assertEqual(agent.agent_id, "indicator_generation")
        self.assertIsInstance(result, SQLGenerationResult)
        self.assertEqual(result["sql_id"], "SQL_001")
        self.assertEqual(executor.calls[0]["hospital_id"], "hospital_001")

    def test_root_cause_agent_delegates_three_layer_diagnosis(self) -> None:
        from app.agents.contracts import DiagnosisResult
        from app.agents.root_cause_diagnosis import RootCauseDiagnosisAgent

        executor = _FakeExecutor({
            "ok": True,
            "diagnose_status": "success",
            "layers": [
                {"layer": 1, "layer_name": "结构适配校验", "ok": True},
                {"layer": 2, "layer_name": "口径规则校验", "ok": True},
                {"layer": 3, "layer_name": "数据质量校验", "ok": True},
            ],
        })
        agent = RootCauseDiagnosisAgent(executor)
        result = agent.run_contract(hospital_id="hospital_001", rule_id="MQSI2025_005")

        self.assertEqual(agent.agent_id, "root_cause_diagnosis")
        self.assertIsInstance(result, DiagnosisResult)
        self.assertEqual([layer.layer for layer in result.layers], [1, 2, 3])
        self.assertEqual(executor.calls[0]["rule_id"], "MQSI2025_005")

    def test_metadata_agent_owns_sync_and_precheck(self) -> None:
        from app.agents.contracts import MetadataPrecheckResult, MetadataSyncResult
        from app.agents.metadata_parsing import MetadataParsingAgent

        calls = []

        def sync_fn(**kwargs):
            calls.append(("sync", kwargs))
            return {"batch_id": "B001"}

        def precheck_fn(
            kb_root,
            runtime_engine,
            hospital_id,
            rule_id,
            **kwargs,
        ):
            calls.append(("precheck", {
                "kb_root": kb_root,
                "hospital_id": hospital_id,
                "rule_id": rule_id,
                **kwargs,
            }))
            return {"ok": True}

        agent = MetadataParsingAgent(
            runtime_engine=object(),
            kb_root=Path("core-rules-wiki"),
            sync_fn=sync_fn,
            precheck_fn=precheck_fn,
        )

        self.assertEqual(agent.agent_id, "metadata_parsing")
        sync_result = agent.sync_contract(object(), "hospital_001", "hospital_demo_data")
        precheck_result = agent.precheck_contract("hospital_001", "MQSI2025_005")
        self.assertIsInstance(sync_result, MetadataSyncResult)
        self.assertIsInstance(precheck_result, MetadataPrecheckResult)
        self.assertEqual(sync_result["batch_id"], "B001")
        self.assertTrue(precheck_result["ok"])
        self.assertEqual([call[0] for call in calls], ["sync", "precheck"])

    def test_metadata_agent_delegates_draft_mapping_workflow(self) -> None:
        from app.agents.metadata_parsing import MetadataParsingAgent

        class Resolver:
            def suggest(self, draft_id):
                return {"draft_id": draft_id, "suggestions": {}}

            def confirm(self, draft_id, expected_version, mappings, actor_id):
                return {
                    "draft_id": draft_id,
                    "current_version": expected_version + 1,
                    "status": "metadata_ready",
                }

        agent = MetadataParsingAgent(
            object(), ".", draft_metadata_resolver=Resolver()
        )

        suggested = agent.suggest_draft_fields("DRAFT_001")
        confirmed = agent.confirm_draft_fields(
            "DRAFT_001", 1, {}, "user_001"
        )

        self.assertEqual(suggested["draft_id"], "DRAFT_001")
        self.assertEqual(confirmed["status"], "metadata_ready")

    def test_indicator_generation_agent_renders_structured_draft_sql(self) -> None:
        from app.agents.indicator_generation import IndicatorGenerationAgent

        calls = []

        def renderer(plan, mappings):
            calls.append((plan, mappings))
            return {"sql_text": "SELECT 1", "params": {}}

        agent = IndicatorGenerationAgent(object(), draft_sql_renderer=renderer)

        result = agent.render_draft_sql({"metric_type": "count"}, {})

        self.assertEqual(result["sql_text"], "SELECT 1")
        self.assertEqual(len(calls), 1)

    def test_human_interaction_agent_understands_and_answers(self) -> None:
        from app.agents.contracts import IntentResult
        from app.agents.human_interaction import HumanInteractionAgent

        agent = HumanInteractionAgent()
        understood = agent.understand_contract("急会诊及时到位率怎么算？")
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
        self.assertIsInstance(understood, IntentResult)
        self.assertEqual(understood["intent"], "query")
        self.assertEqual(method, "tool")
        self.assertIn("20分钟", answer)

    def test_human_interaction_reuses_memory_for_contextual_actions(self) -> None:
        from app.agents.human_interaction import HumanInteractionAgent

        for query, intent in [
            ("生成 SQL", "generate_sql"),
            ("试运行", "trial_run"),
            ("诊断", "diagnose"),
            ("按20分钟计算", "feedback"),
        ]:
            with self.subTest(intent=intent):
                self.assertTrue(HumanInteractionAgent.can_reuse_memory(query, intent))

    def test_human_interaction_rewrites_context_only_sql_command(self) -> None:
        from app.agents.human_interaction import HumanInteractionAgent

        understood = HumanInteractionAgent().understand(
            "生成 SQL",
            {
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            },
        )

        self.assertEqual(understood["intent"], "generate_sql")
        self.assertEqual(understood["rewritten_query"], "生成急会诊及时到位率 SQL")
        self.assertEqual(understood["retrieval_query"], "急会诊及时到位率")

    def test_stat_period_change_is_query_not_caliber_feedback(self) -> None:
        from app.agents.human_interaction import (
            HumanInteractionAgent,
            detect_intent_by_rule,
        )

        class FeedbackLLM:
            def generate(self, prompt):
                return (
                    '{"intent":"feedback","indicator_name":"",'
                    '"retrieval_query":"修改统计时间"}'
                )

        query = "如果把统计时间起始时间改成2026-05-01怎么算？"
        intent = detect_intent_by_rule(query)
        understood = HumanInteractionAgent(FeedbackLLM()).understand(
            query,
            {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
        )

        self.assertEqual(intent, "query")
        self.assertEqual(understood["intent"], "query")
        self.assertTrue(HumanInteractionAgent.can_reuse_memory(query, "query"))


if __name__ == "__main__":
    unittest.main()
