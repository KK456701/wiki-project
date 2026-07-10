import unittest


class _FakeInteraction:
    agent_id = "human_interaction"

    def __init__(self, intent="query"):
        self.intent = intent
        self.calls = []

    def understand(self, query, memory_context=None, errors=None):
        self.calls.append(("understand", query, memory_context))
        return {
            "intent": self.intent,
            "retrieval_query": "急会诊及时到位率",
            "indicator_name": "急会诊及时到位率",
            "custom_filters": [{"field": "dept_id", "operator": "!=", "value": "ICU"}],
        }

    def can_reuse_memory(self, query, intent):
        return True

    def answer(self, query, effective_rule, errors=None):
        self.calls.append(("answer", query, effective_rule))
        return "回答", "tool"

    def chat_answer(self):
        return "你好"


class _FakeCaliber:
    agent_id = "caliber_adaptation"

    def __init__(self, resolve_rule=True):
        self.resolve_rule = resolve_rule
        self.calls = []

    def search(self, query, limit=5):
        self.calls.append(("search", query, limit))
        return {"resolved_rule_id": "MQSI2025_005" if self.resolve_rule else None, "results": []}

    def search_for_hospital_contract(self, query, hospital_id, limit=5):
        self.calls.append(("search_for_hospital", query, hospital_id, limit))
        return {
            "query": query,
            "resolved_rule_id": "MQSI2025_005" if self.resolve_rule else None,
            "matches": [],
        }

    def resolve(self, rule_id, hospital_id):
        self.calls.append(("resolve", rule_id, hospital_id))
        return {"rule_id": rule_id, "rule_name": "急会诊及时到位率", "effective_level": "hospital"}

    def comparison_context_contract(self, rule_id, hospital_id):
        from app.agents.contracts import CaliberComparisonContext

        self.calls.append(("comparison", rule_id, hospital_id))
        return CaliberComparisonContext(
            rule_id=rule_id,
            hospital_id=hospital_id,
            applicable=True,
            national_sql_template="SELECT 1",
            effective_sql_template="SELECT 2",
        )

    def field_mapping(self, rule_id, hospital_id):
        self.calls.append(("mapping", rule_id, hospital_id))
        return {
            "rule_id": rule_id,
            "hospital_id": hospital_id,
            "db_name": "hospital_demo_data",
            "main_table": "consult_record",
            "fields": {"request_time": "consult_record.request_time"},
        }

    def preview_feedback(self, rule_id, hospital_id, query):
        self.calls.append(("preview", rule_id, hospital_id, query))
        return {"status": "preview"}


class _FakeDomainAgent:
    def __init__(self, agent_id):
        self.agent_id = agent_id
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return {"sql_id": "SQL_001"}

    def run(self, **kwargs):
        self.calls.append(kwargs)
        return {"diagnose_status": "success"}

    def sync(self, provider, hospital_id, db_name):
        self.calls.append({"provider": provider, "hospital_id": hospital_id, "db_name": db_name})
        return {"batch_id": "B001"}

    def precheck(self, hospital_id, rule_id):
        self.calls.append({"operation": "precheck", "hospital_id": hospital_id, "rule_id": rule_id})
        return {
            "ok": True,
            "main_table": "consult_record",
            "field_mapping": {"request_time": "consult_record.request_time"},
            "missing_mappings": [],
            "missing_columns": [],
        }


def _orchestrator(intent="query", resolve_rule=True):
    from app.agents.orchestrator import CoreIndicatorOrchestrator

    interaction = _FakeInteraction(intent)
    caliber = _FakeCaliber(resolve_rule)
    indicator = _FakeDomainAgent("indicator_generation")
    diagnosis = _FakeDomainAgent("root_cause_diagnosis")
    metadata = _FakeDomainAgent("metadata_parsing")
    orchestrator = CoreIndicatorOrchestrator(
        interaction=interaction,
        caliber=caliber,
        indicator_generation=indicator,
        diagnosis=diagnosis,
        metadata=metadata,
    )
    return orchestrator, interaction, caliber, indicator, diagnosis, metadata


class AgentOrchestratorTest(unittest.TestCase):
    def test_routes_each_intent_to_one_specialized_agent(self) -> None:
        orchestrator, *_ = _orchestrator()
        expected = {
            "chat": "human_interaction",
            "query": "human_interaction",
            "feedback": "caliber_adaptation",
            "generate_sql": "indicator_generation",
            "trial_run": "indicator_generation",
            "diagnose": "root_cause_diagnosis",
            "metadata_sync": "metadata_parsing",
            "create_indicator": "indicator_generation",
        }

        self.assertEqual(
            {intent: orchestrator.owner_for_intent(intent) for intent in expected},
            expected,
        )

    def test_prepare_understands_then_resolves_rule_once(self) -> None:
        orchestrator, interaction, caliber, *_ = _orchestrator()

        prepared = orchestrator.prepare(
            "这个指标怎么算？",
            "hospital_001",
            {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
        )

        self.assertEqual(prepared.intent, "query")
        self.assertEqual(prepared.rule_id, "MQSI2025_005")
        self.assertEqual(prepared.effective_rule["effective_level"], "hospital")
        self.assertEqual(prepared.field_mapping["hospital_id"], "hospital_001")
        self.assertEqual(prepared.custom_filters[0]["field"], "dept_id")
        self.assertEqual(interaction.calls[0][0], "understand")
        self.assertEqual(
            caliber.calls[0],
            ("search_for_hospital", prepared.retrieval_query, "hospital_001", 5),
        )
        self.assertEqual(
            [call[0] for call in caliber.calls],
            ["search_for_hospital", "resolve", "mapping"],
        )

    def test_staged_preparation_matches_one_shot_prepare(self) -> None:
        orchestrator, _, caliber, *_ = _orchestrator()
        errors = []

        understood = orchestrator.understand_request(
            "这个指标怎么算？",
            {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
            errors,
        )
        prepared = orchestrator.create_request(
            "这个指标怎么算？", "hospital_001", understood, errors
        )
        orchestrator.search_request(
            prepared,
            {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
        )
        orchestrator.resolve_request(prepared)

        self.assertEqual(prepared.rule_id, "MQSI2025_005")
        self.assertEqual(prepared.effective_rule.effective_level, "hospital")
        self.assertEqual(
            [call[0] for call in caliber.calls],
            ["search_for_hospital", "resolve", "mapping"],
        )

    def test_prepare_rule_request_resolves_without_search(self) -> None:
        orchestrator, _, caliber, *_ = _orchestrator()

        prepared = orchestrator.prepare_rule_request(
            query="diagnose:MQSI2025_005",
            hospital_id="hospital_001",
            intent="diagnose",
            rule_id="MQSI2025_005",
        )

        self.assertEqual(prepared.rule_id, "MQSI2025_005")
        self.assertEqual(prepared.effective_rule.rule_name, "急会诊及时到位率")
        self.assertEqual([call[0] for call in caliber.calls], ["resolve", "mapping"])

    def test_prepare_chat_skips_rule_repository(self) -> None:
        orchestrator, _, caliber, *_ = _orchestrator(intent="chat")

        prepared = orchestrator.prepare("你好", "hospital_001")

        self.assertEqual(prepared.intent, "chat")
        self.assertIsNone(prepared.rule_id)
        self.assertEqual(caliber.calls, [])

    def test_prepare_uses_memory_rule_when_search_misses(self) -> None:
        orchestrator, _, caliber, *_ = _orchestrator(resolve_rule=False)

        prepared = orchestrator.prepare(
            "这个指标怎么算？",
            "hospital_001",
            {"rule_id": "MQSI2025_005", "rule_name": "急会诊及时到位率"},
        )

        self.assertEqual(prepared.rule_id, "MQSI2025_005")
        self.assertEqual(prepared.search["context_source"], "memory_last_rule")
        self.assertEqual(
            [call[0] for call in caliber.calls],
            ["search_for_hospital", "resolve", "mapping"],
        )

    def test_dispatch_methods_delegate_prepared_context(self) -> None:
        orchestrator, _, _, indicator, diagnosis, metadata = _orchestrator()
        prepared = orchestrator.prepare("生成SQL", "hospital_001")

        sql = orchestrator.generate_indicator(
            prepared,
            stat_start_time="2026-07-01 00:00:00",
            stat_end_time="2026-08-01 00:00:00",
            trial_run=True,
        )
        diagnosed = orchestrator.diagnose(prepared, trigger="manual")
        synced = orchestrator.sync_metadata(object(), "hospital_001", "hospital_demo_data")

        self.assertEqual(sql["sql_id"], "SQL_001")
        self.assertTrue(indicator.calls[0]["precheck"]["ok"])
        self.assertEqual(indicator.calls[0]["rule_id"], "MQSI2025_005")
        self.assertTrue(indicator.calls[0]["trial_run"])
        self.assertEqual(diagnosed["diagnose_status"], "success")
        self.assertEqual(diagnosis.calls[0]["effective_rule"]["effective_level"], "hospital")
        self.assertTrue(diagnosis.calls[0]["caliber_context"]["applicable"])
        self.assertEqual(
            diagnosis.calls[0]["field_mapping"]["main_table"], "consult_record"
        )
        self.assertEqual(synced["batch_id"], "B001")
        self.assertEqual(metadata.calls[0]["operation"], "precheck")
        self.assertEqual(metadata.calls[1]["db_name"], "hospital_demo_data")

    def test_precheck_failure_stops_before_indicator_generation(self) -> None:
        orchestrator, _, _, indicator, _, metadata = _orchestrator()
        prepared = orchestrator.prepare("生成SQL", "hospital_001")

        def failed_precheck(hospital_id, rule_id):
            return {
                "ok": False,
                "missing_mappings": ["request_time"],
                "missing_columns": [],
            }

        metadata.precheck = failed_precheck
        result = orchestrator.generate_indicator(
            prepared,
            stat_start_time="2026-07-01 00:00:00",
            stat_end_time="2026-08-01 00:00:00",
        )

        self.assertEqual(result["status"], "field_precheck_failed")
        self.assertEqual(result["precheck"]["missing_mappings"], ["request_time"])
        self.assertEqual(indicator.calls, [])


if __name__ == "__main__":
    unittest.main()
