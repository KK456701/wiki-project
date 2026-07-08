import unittest

from app.workflows.manifest import annotate_trace_node, get_workflow_node, load_workflow_manifest


class WorkflowManifestTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
