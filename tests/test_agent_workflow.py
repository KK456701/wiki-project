import unittest
from pathlib import Path

from app.agent.graph import run_chat
from app.memory.store import ConversationMemory
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


class FakeLLM:
    def __init__(self, intent: str = "query") -> None:
        self.intent = intent
        self.prompts: list[str] = []

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        if "医疗质量指标知识库的意图识别器" in prompt:
            return '{"intent":"' + self.intent + '","question_type":"指标公式查询","indicator_name":"急会诊及时到位率","retrieval_query":"急会诊及时到位率"}'
        return "LLM整理回答：急会诊及时到位率 = 10分钟内到位急会诊次数 / 同期急会诊总次数 × 100%。SQL 当前不可用。"

    @property
    def prompt(self) -> str:
        return "\n\n".join(self.prompts)


class BadFormulaLLM:
    def generate(self, prompt: str) -> str:
        if "医疗质量指标知识库的意图识别器" in prompt:
            return '{"intent":"query","indicator_name":"急会诊及时到位率","retrieval_query":"急会诊及时到位率"}'
        return "计算公式：急会诊及时到位率 = 10分钟内到位急会诊次数 / 同期急会诊总次数 × 10、00%"


class FailingLLM:
    def generate(self, prompt: str) -> str:
        raise RuntimeError("ollama unavailable")


class AgentWorkflowTest(unittest.TestCase):
    def test_query_uses_effective_rule_and_reports_missing_sql(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat("急会诊及时到位率怎么算？", hospital_id="hospital_001", kb_root=root)

            self.assertEqual(result["intent"], "query")
            self.assertEqual(result["rule_id"], "R001")
            self.assertIn("10分钟内到位", result["answer"])
            self.assertIn("当前不能生成可执行 SQL", result["answer"])

    def test_query_can_use_llm_answer_generation(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            llm = FakeLLM(intent="query")

            result = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                kb_root=root,
                use_llm=True,
                llm_client=llm,
            )

            self.assertEqual(result["generation_method"], "llm")
            self.assertIn("LLM整理回答", result["answer"])
            self.assertIn("医疗质量指标知识库的意图识别器", llm.prompts[0])
            self.assertIn("急会诊及时到位率", llm.prompt)
            self.assertIn("待医院字段映射确认", llm.prompt)

    def test_llm_intent_can_route_feedback_preview(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            llm = FakeLLM(intent="feedback")

            result = run_chat(
                "请帮我登记急会诊及时到位率的新口径",
                hospital_id="hospital_001",
                kb_root=root,
                use_llm=True,
                llm_client=llm,
            )

            self.assertEqual(result["intent"], "feedback")
            self.assertNotIn("change_request", result)
            self.assertEqual(result["feedback_preview"]["target_level"], "hospital")
            self.assertEqual(result["feedback_preview"]["rule_id"], "R001")
            self.assertFalse((root / "review" / "pending").exists())

    def test_query_falls_back_when_llm_fails(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                kb_root=root,
                use_llm=True,
                llm_client=FailingLLM(),
            )

            self.assertEqual(result["generation_method"], "tool_fallback")
            self.assertIn("10分钟内到位", result["answer"])
            self.assertIn("ollama unavailable", result["errors"][0])

    def test_llm_answer_with_wrong_formula_falls_back_to_tool_answer(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                kb_root=root,
                use_llm=True,
                llm_client=BadFormulaLLM(),
            )

            self.assertEqual(result["generation_method"], "llm_guarded_fallback")
            self.assertIn("× 100%", result["answer"])
            self.assertNotIn("10、00%", result["answer"])

    def test_feedback_returns_preview_without_creating_pending_request(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat(
                "我们医院急会诊及时到位率应该按15分钟内签到计算",
                hospital_id="hospital_001",
                kb_root=root,
            )

            self.assertEqual(result["intent"], "feedback")
            self.assertNotIn("change_request", result)
            preview = result["feedback_preview"]
            self.assertEqual(preview["target_level"], "hospital")
            self.assertEqual(preview["current_hospital"]["status"], "not_configured")
            self.assertIn("\u0031\u0035\u5206\u949f", preview["requested"]["formula"])
            self.assertFalse((root / "review" / "pending").exists())

    def test_feedback_without_indicator_uses_previous_session_rule_context(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")

            first = run_chat(
                "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                hospital_id="hospital_001",
                kb_root=root,
                session_id="session-feedback-context",
                memory=memory,
            )
            second = run_chat(
                "\u6211\u4eec\u533b\u9662\u662f\u6309\u716730\u5206\u949f\u6765\u7684",
                hospital_id="hospital_001",
                kb_root=root,
                session_id=first["session_id"],
                memory=memory,
            )

            self.assertEqual(second["intent"], "feedback")
            self.assertEqual(second["rule_id"], "R001")
            self.assertEqual(second["memory_context"]["rule_id"], "R001")
            self.assertNotIn("change_request", second)
            self.assertEqual(second["feedback_preview"]["target_level"], "hospital")
            self.assertIn("\u0033\u0030\u5206\u949f", second["feedback_preview"]["requested"]["formula"])

    def test_conversation_memory_persists_sqlite_and_jsonl(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory_root = root / "runtime" / "conversations"
            memory = ConversationMemory(memory_root)

            result = run_chat(
                "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                hospital_id="hospital_001",
                kb_root=root,
                session_id="session-persisted",
                memory=memory,
            )

            self.assertEqual(result["session_id"], "session-persisted")
            self.assertTrue((memory_root / "conversations.sqlite3").exists())
            events = (memory_root / "events.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertGreaterEqual(len(events), 2)
            self.assertIn("\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f", events[0])


if __name__ == "__main__":
    unittest.main()