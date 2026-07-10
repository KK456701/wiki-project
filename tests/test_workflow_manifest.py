import unittest

from app.workflows.manifest import (
    annotate_trace_node,
    default_failure_code_for_node,
    get_workflow_node,
    load_workflow_manifest,
    validate_workflow_manifest,
)


class WorkflowManifestTest(unittest.TestCase):
    def test_every_manifest_node_has_specialized_agent_owner(self) -> None:
        manifest = load_workflow_manifest("core_indicator_chat")
        allowed = {
            "metadata_parsing",
            "indicator_generation",
            "caliber_adaptation",
            "root_cause_diagnosis",
            "human_interaction",
        }

        self.assertTrue(manifest["nodes"])
        for node in manifest["nodes"]:
            with self.subTest(node=node["id"]):
                self.assertIn(node["agent_owner"], allowed)

    def test_load_core_indicator_chat_manifest(self) -> None:
        manifest = load_workflow_manifest("core_indicator_chat")

        self.assertEqual(manifest["workflow_id"], "core_indicator_chat")
        self.assertGreaterEqual(len(manifest["nodes"]), 3)
        self.assertIn("edges", manifest)

    def test_get_workflow_node_returns_chinese_metadata(self) -> None:
        node = get_workflow_node("core_indicator_chat", "intent_detect")

        self.assertEqual(node["title"], "识别用户意图")
        self.assertIn("query", node["inputs"])
        self.assertIn("intent", node["outputs"])
        self.assertIn("失败", node["failure_hint"])

    def test_effective_rule_node_uses_hospital_caliber_composition_wording(self) -> None:
        node = get_workflow_node("core_indicator_chat", "effective_rule_resolve")

        self.assertEqual(node["title"], "合成本院生效口径")
        self.assertIn("不修改国标", node["description"])

    def test_diagnose_rule_node_declares_dual_caliber_execution(self) -> None:
        node = get_workflow_node("core_indicator_chat", "diagnose_rule_check")

        self.assertIn("国标", node["description"])
        self.assertIn("本院生效口径", node["description"])
        self.assertIn("caliber_context", node["inputs"])
        self.assertIn("field_mapping", node["inputs"])
        self.assertIn("stat_period", node["inputs"])
        self.assertIn("caliber_comparison", node["outputs"])
        self.assertIn("conclusion_code", node["outputs"])
        self.assertEqual(
            node["config"]["tool"], "execute_sql_hospital_demo_data"
        )
        self.assertTrue(node["config"]["readonly"])

    def test_annotate_trace_node_keeps_runtime_fields(self) -> None:
        runtime = {
            "node_name": "rule_search",
            "node_type": "kb_tool",
            "status": "success",
            "input_summary": "急会诊及时到位率",
            "output_summary": "MQSI2025_005",
        }

        annotated = annotate_trace_node(runtime)

        self.assertEqual(annotated["node_name"], "rule_search")
        self.assertEqual(annotated["node_title"], "检索指标规则")
        self.assertEqual(annotated["status"], "success")
        self.assertIn("retrieval_query", annotated["expected_inputs"])
        self.assertIn("rule_id", annotated["expected_outputs"])
        self.assertEqual(annotated["agent_owner"], "caliber_adaptation")

    def test_manifest_exposes_operational_contract(self) -> None:
        memory_node = get_workflow_node("core_indicator_chat", "memory_load")
        sql_node = get_workflow_node("core_indicator_chat", "sql_validate")

        self.assertTrue(memory_node["required"])
        self.assertEqual(memory_node["on_failure"], "continue")
        self.assertEqual(memory_node["failure_code"], "MEMORY_LOAD_FAILED")
        self.assertIn("session_id", memory_node["required_inputs"])
        self.assertIn("memory_context", memory_node["required_outputs"])
        self.assertEqual(sql_node["on_failure"], "stop")
        self.assertEqual(default_failure_code_for_node("sql_validate"), "SQL_VALIDATE_FAILED")

    def test_manifest_validation_reports_unknown_edges(self) -> None:
        manifest = load_workflow_manifest("core_indicator_chat")
        manifest["edges"].append({"from": "missing_node", "to": "final_response"})

        result = validate_workflow_manifest(manifest)

        self.assertFalse(result["ok"])
        self.assertTrue(any("missing_node" in issue["message"] for issue in result["issues"]))

    def test_annotate_trace_node_reports_contract_gaps(self) -> None:
        runtime = {
            "node_name": "memory_load",
            "node_type": "memory",
            "status": "success",
            "input_data": {},
            "output_data": {},
        }

        annotated = annotate_trace_node(runtime)

        self.assertEqual(annotated["contract_status"], "warning")
        self.assertIn("session_id", annotated["missing_inputs"])
        self.assertIn("memory_context", annotated["missing_outputs"])

    def test_indicator_generation_closed_loop_has_complete_manifest(self) -> None:
        manifest = load_workflow_manifest("indicator_generation_closed_loop")
        validation = validate_workflow_manifest(manifest)

        self.assertTrue(validation["ok"])
        self.assertEqual(
            [node["id"] for node in manifest["nodes"]],
            [
                "draft_parse",
                "draft_save",
                "metadata_confirm",
                "draft_sql_generate",
                "draft_trial_run",
                "draft_submit",
                "draft_publish",
            ],
        )
        self.assertEqual(
            get_workflow_node(
                "indicator_generation_closed_loop", "draft_publish"
            )["agent_owner"],
            "caliber_adaptation",
        )


if __name__ == "__main__":
    unittest.main()
