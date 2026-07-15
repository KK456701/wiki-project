import unittest
import inspect
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.agent.graph import (
    _format_diagnose_answer,
    _search_match_count,
    run_chat,
    run_chat_stream,
)
from app.observability.trace import TraceRecorder
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


class ChatIntentLLM:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def generate(self, prompt: str) -> str:
        self.prompts.append(prompt)
        if "???????????????" in prompt:
            return '{"intent":"chat","question_type":"????","indicator_name":"","retrieval_query":""}'
        return "SHOULD_NOT_CALL_ANSWER_PROMPT"


class BadFormulaLLM:
    def generate(self, prompt: str) -> str:
        if "医疗质量指标知识库的意图识别器" in prompt:
            return '{"intent":"query","indicator_name":"急会诊及时到位率","retrieval_query":"急会诊及时到位率"}'
        return "计算公式：急会诊及时到位率 = 10分钟内到位急会诊次数 / 同期急会诊总次数 × 10、00%"


class FailingLLM:
    def generate(self, prompt: str) -> str:
        raise RuntimeError("ollama unavailable")


class ContextOnlySQLCommandLLM:
    def generate(self, prompt: str) -> str:
        return (
            '{"intent":"generate_sql","indicator_name":"急会诊及时到位率",'
            '"retrieval_query":"生成 SQL","custom_filters":[]}'
        )


class FakeSQLGenerationAgent:
    calls: list[dict[str, object]] = []

    def __init__(self, *args, **kwargs) -> None:
        pass

    def generate(self, **kwargs):
        self.__class__.calls.append(kwargs)
        result = {
            "status": "success",
            "precheck": {
                "ok": True,
                "main_table": "consult_record",
                "field_mapping": {"request_time": "consult_record.request_time"},
                "missing_mappings": [],
                "missing_columns": [],
            },
            "sql_id": "SQL_TRACE_TEST",
            "sql_text": "SELECT 50.0 AS index_value FROM consult_record",
            "sql_status": "validated",
            "validation": {"ok": True, "message": "SQL 安全校验通过"},
            "dialect": "mysql",
            "params": {"arrive_minutes_threshold": 20},
            "lineage": {
                "denominator_name": "同期急会诊总次数",
                "numerator_name": "及时到位急会诊次数",
                "db_name": "hospital_demo_data",
                "main_table": "consult_record",
                "denominator_rows": [
                    {
                        "label": "分母筛选条件",
                        "business_fields": ["consult_type"],
                        "physical_fields": ["consult_record.consult_type"],
                        "condition_text": "会诊类型等于急会诊",
                        "source": "标准口径",
                        "effect": "满足时进入分母",
                    }
                ],
                "numerator_rows": [
                    {
                        "label": "继承分母",
                        "business_fields": [],
                        "physical_fields": [],
                        "condition_text": "先满足全部分母条件",
                        "source": "指标定义",
                        "effect": "分子一定是分母的子集",
                    },
                    {
                        "label": "判断申请至到位耗时",
                        "business_fields": ["request_time", "arrive_time"],
                        "physical_fields": [
                            "consult_record.request_time",
                            "consult_record.arrive_time",
                        ],
                        "condition_text": "申请至到位耗时为0至20分钟",
                        "derivation_text": "急会诊到位时间减急会诊申请时间，换算为分钟",
                        "source": "本院版本 v1",
                        "effect": "在分母基础上满足时进入分子",
                    },
                ],
                "caliber_rows": [
                    {
                        "parameter": "arrive_minutes_threshold",
                        "current_value": "20分钟",
                        "standard_value": "10分钟",
                        "condition_name": "判断申请至到位耗时",
                        "physical_fields": [
                            "consult_record.request_time",
                            "consult_record.arrive_time",
                        ],
                        "effect_scope": "只改变分子，不改变分母",
                        "version": "本院版本 v1",
                    }
                ],
            },
        }
        if kwargs.get("trial_run"):
            result["trial_run"] = {
                "run_id": "RUN_TRACE_TEST",
                "status": "success",
                "result_value": 80.0,
                "numerator_count": 8,
                "denominator_count": 10,
                "source": "hospital_demo_data",
                "stat_start": "2026-07-01 00:00:00",
                "stat_end": "2026-08-01 00:00:00",
                "duration_ms": 7,
                "error_message": None,
            }
        return result


PRECHECK_OK = {
    "ok": True,
    "main_table": "consult_record",
    "field_mapping": {"request_time": "consult_record.request_time"},
    "missing_mappings": [],
    "missing_columns": [],
}


class FakeDiagnoseAgent:
    def __init__(self, *args, **kwargs) -> None:
        pass

    def run(self, **kwargs):
        return {
            "ok": True,
            "diagnose_status": "warning",
            "report_id": "DR_TRACE_TEST",
            "layers": [
                {
                    "layer": 1,
                    "layer_name": "结构适配校验",
                    "ok": True,
                    "diagnose_type": "结构适配正常",
                    "checks": [{"status": "pass", "message": "字段存在"}],
                    "metadata_source": "dbhub",
                },
                {
                    "layer": 2,
                    "layer_name": "口径规则校验",
                    "ok": True,
                    "diagnose_type": "口径规则正常",
                    "checks": [{"status": "pass", "message": "规则完整"}],
                },
                {
                    "layer": 3,
                    "layer_name": "数据质量校验",
                    "ok": True,
                    "diagnose_type": "数据质量风险",
                    "checks": [{"status": "warn", "message": "样本量偏低"}],
                },
            ],
        }


class DiagnoseFormattingTest(unittest.TestCase):
    def test_pasted_diagnosis_uses_doctor_readable_summary(self) -> None:
        answer = _format_diagnose_answer({
            "user_summary": "## 结论\n两段 SQL 的结果不同，主要是口径差异。",
            "layers": [],
        })

        self.assertEqual(
            answer,
            "## 结论\n两段 SQL 的结果不同，主要是口径差异。",
        )

    def test_warning_layers_show_warning_details_instead_of_normal(self) -> None:
        answer = _format_diagnose_answer({
            "layers": [
                {
                    "ok": True,
                    "layer": 1,
                    "layer_name": "\u7cfb\u7edf\u7ed3\u6784\u6821\u9a8c",
                    "checks": [
                        {"status": "pass", "message": "metadata ok"},
                        {"status": "warn", "message": "Required business field arrive_time is nullable in metadata.", "repair_suggest": "Focus on null rate in data quality checks."},
                    ],
                }
            ],
            "summary": "\u5b58\u5728\u98ce\u9669: \u7cfb\u7edf\u7ed3\u6784\u6821\u9a8c",
        })

        self.assertIn("\u901a\u8fc7\u4f46\u6709\u98ce\u9669", answer)
        self.assertIn("\u5fc5\u586b\u4e1a\u52a1\u5b57\u6bb5 arrive_time \u5728\u5143\u6570\u636e\u4e2d\u5141\u8bb8\u4e3a\u7a7a", answer)
        self.assertIn("\u8bf7\u5728\u6570\u636e\u8d28\u91cf\u6821\u9a8c\u4e2d\u91cd\u70b9\u5173\u6ce8\u7a7a\u503c\u7387", answer)
        self.assertNotIn("Required business field", answer)
        self.assertNotIn("\n   \u6b63\u5e38\n", answer)


class AgentWorkflowTest(unittest.TestCase):
    def test_graph_does_not_keep_legacy_agent_business_duplicates(self) -> None:
        import app.agent.graph as graph_module

        source = inspect.getsource(graph_module)
        for legacy_name in [
            "_detect_intent",
            "_apply_memory_context_if_needed",
            "_generate_answer",
            "_preview_feedback",
            "_get_field_mapping",
        ]:
            with self.subTest(legacy_name=legacy_name):
                self.assertNotIn(f"def {legacy_name}(", source)

    def test_run_chat_reports_workflow_engine(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat("hello", hospital_id="hospital_001", kb_root=root)

            self.assertIn(result["workflow_engine"], {"langgraph", "deterministic_fallback"})
            self.assertEqual(result["orchestrator"], "core_indicator_orchestrator")
            self.assertEqual(result["agent_owner"], "human_interaction")
            if result["workflow_engine"] == "deterministic_fallback":
                self.assertTrue(any("LangGraph" in err for err in result.get("errors", [])))

    def test_stream_done_reports_orchestrator_and_owner(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            events = list(
                run_chat_stream(
                    "急会诊及时到位率怎么算？",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                )
            )

        done = next(data for event, data in reversed(events) if event == "done")
        self.assertEqual(done["orchestrator"], "core_indicator_orchestrator")
        self.assertEqual(done["agent_owner"], "human_interaction")

    def test_metadata_sync_stream_routes_without_indicator_search(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            events = list(
                run_chat_stream(
                    "同步元数据",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                )
            )

        done = next(data for event, data in reversed(events) if event == "done")
        self.assertEqual(done["intent"], "metadata_sync")
        self.assertEqual(done["agent_owner"], "metadata_parsing")
        self.assertIn("/api/metadata/sync", done["answer"])
        self.assertIsNone(done["rule_id"])

    def test_greeting_is_chat_and_does_not_reuse_previous_rule_context(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            first = run_chat(
                "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                hospital_id="hospital_001",
                kb_root=root,
                session_id="session-chat-intent",
                memory=memory,
            )

            second = run_chat(
                "\u4f60\u597d",
                hospital_id="hospital_001",
                kb_root=root,
                session_id=first["session_id"],
                memory=memory,
            )

            self.assertEqual(second["intent"], "chat")
            self.assertIsNone(second.get("rule_id"))
            self.assertEqual(second["generation_method"], "chat")
            self.assertIn("\u6838\u5fc3\u5236\u5ea6\u6307\u6807", second["answer"])
            self.assertNotIn("\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387", second["answer"])

    def test_llm_chat_intent_skips_knowledge_base_answer_prompt(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            llm = ChatIntentLLM()

            result = run_chat(
                "\u4f60\u597d",
                hospital_id="hospital_001",
                kb_root=root,
                use_llm=True,
                llm_client=llm,
            )

            self.assertEqual(result["intent"], "chat")
            self.assertIsNone(result.get("rule_id"))
            self.assertEqual(result["generation_method"], "chat")
            self.assertEqual(len(llm.prompts), 1)

    def test_query_uses_effective_rule_and_reports_missing_sql(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)

            result = run_chat("急会诊及时到位率怎么算？", hospital_id="hospital_001", kb_root=root)

            self.assertEqual(result["intent"], "query")
            self.assertEqual(result["rule_id"], "R001")
            self.assertIn("10分钟内到位", result["answer"])
            self.assertIn("SQL 状态：不可用", result["answer"])
            self.assertIn("禁止生成可执行 SQL", result["answer"])

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

    def test_run_chat_returns_trace_id_and_records_nodes(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine):
                result = run_chat(
                    "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                    hospital_id="hospital_001",
                    kb_root=root,
                )

            self.assertTrue(result["trace_id"].startswith("TRACE_"))
            trace = TraceRecorder(engine).get_trace(result["trace_id"])
            self.assertEqual(trace["trace_id"], result["trace_id"])
            node_names = [node["node_name"] for node in trace["nodes"]]
            self.assertIn("intent_detect", node_names)
            self.assertIn("final_response", node_names)

    def test_run_chat_trace_nodes_have_manifest_metadata_and_structured_data(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine):
                result = run_chat(
                    "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                    hospital_id="hospital_001",
                    kb_root=root,
                )

            trace = TraceRecorder(engine).get_trace(result["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            traced_memory = by_name["memory_load"]["output_data"]["memory_context"]
            self.assertEqual(by_name["intent_detect"]["node_title"], "识别用户意图")
            self.assertEqual(by_name["rule_search"]["node_title"], "检索指标规则")
            self.assertEqual(by_name["final_response"]["node_title"], "生成最终回答")
            self.assertIn("query", by_name["intent_detect"]["input_data"])
            self.assertEqual(by_name["rule_search"]["output_data"]["rule_id"], "R001")
            self.assertIn("answer_preview", by_name["final_response"]["output_data"])
            self.assertNotIn("recent_history", traced_memory)
            self.assertNotIn("structured_summary", traced_memory)
            self.assertIn("prompt_context_stats", traced_memory)

    def test_query_trace_records_memory_and_effective_rule_nodes(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=True)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine):
                result = run_chat(
                    "急会诊及时到位率怎么算？",
                    hospital_id="hospital_001",
                    kb_root=root,
                    session_id="trace-node-session",
                )

            trace = TraceRecorder(engine).get_trace(result["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertEqual(by_name["memory_load"]["node_title"], "读取会话记忆")
            self.assertEqual(by_name["memory_load"]["output_data"]["active_session_id"], "trace-node-session")
            self.assertEqual(by_name["effective_rule_resolve"]["node_title"], "合成本院生效口径")
            self.assertEqual(by_name["effective_rule_resolve"]["output_data"]["effective_level"], "hospital")
            self.assertEqual(by_name["term_normalize"]["node_title"], "标准化医学术语")
            self.assertIn("normalized_query", by_name["term_normalize"]["output_data"])

            for node_name in ["memory_load", "intent_detect", "term_normalize", "rule_search", "effective_rule_resolve", "final_response"]:
                self.assertGreater(by_name[node_name]["duration_ms"], 0, node_name)

    def test_query_accepts_rule_repository_and_traces_mysql_versions(self) -> None:
        class FakeRuleRepository:
            def search(self, query, limit=5):
                return {
                    "query": query,
                    "resolved_rule_id": "MQSI2025_005",
                    "matches": [{"rule_id": "MQSI2025_005"}],
                }

            def get_effective_rule(self, rule_id, hospital_id):
                return {
                    "rule_id": rule_id,
                    "rule_name": "急会诊及时到位率",
                    "effective_level": "hospital",
                    "definition": "急会诊请求发出后及时到位的比例。",
                    "formula": "急会诊及时到位率 = 20分钟内到位次数 / 急会诊总次数 × 100%",
                    "implementation_status": "SELECT 1",
                    "field_status": "configured",
                    "sql_status": "available",
                    "warnings": [],
                    "rule_source": "mysql",
                    "national_version": "2025",
                    "hospital_version": 1,
                    "overridden_fields": ["arrive_minutes_threshold"],
                }

            def get_field_mapping(self, rule_id, hospital_id=None):
                return {"rule_id": rule_id, "status": "confirmed", "items": []}

        engine = _trace_runtime_engine()
        with patch("app.agent.graph.create_runtime_engine", return_value=engine):
            result = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                session_id="mysql-rule-session",
                rule_repository=FakeRuleRepository(),
            )

        trace = TraceRecorder(engine).get_trace(result["trace_id"])
        node = {item["node_name"]: item for item in trace["nodes"]}[
            "effective_rule_resolve"
        ]
        self.assertEqual(result["effective_rule"]["rule_source"], "mysql")
        self.assertEqual(node["output_data"]["rule_source"], "mysql")
        self.assertEqual(node["output_data"]["national_version"], "2025")
        self.assertEqual(node["output_data"]["hospital_version"], 1)
        self.assertEqual(
            node["output_data"]["overridden_fields"],
            ["arrive_minutes_threshold"],
        )

    def test_sql_stream_trace_records_generation_nodes(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                events = list(run_chat_stream(
                    "生成 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=FakeLLM(intent="generate_sql"),
                ))

            trace_id = next(data["trace_id"] for event, data in events if event == "meta")
            trace = TraceRecorder(engine).get_trace(trace_id)
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertIn("field_mapping_precheck", by_name)
            self.assertIn("sql_generate", by_name)
            self.assertIn("sql_validate", by_name)
            self.assertEqual(by_name["field_mapping_precheck"]["output_data"]["ok"], True)
            self.assertEqual(by_name["sql_generate"]["output_data"]["sql_id"], "SQL_TRACE_TEST")
            self.assertEqual(by_name["sql_validate"]["output_data"]["ok"], True)
            done = next(data for event, data in reversed(events) if event == "done")
            answer = done["answer"]
            self.assertIn("当前采用口径", answer)
            self.assertIn("## 分母如何取数", answer)
            self.assertIn("## 分子如何从分母中筛选", answer)
            self.assertIn("## 本院口径作用在哪里", answer)
            self.assertLess(answer.index("## 分母如何取数"), answer.index("```sql"))

    def test_sql_command_reuses_previous_session_rule_context(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                first = run_chat(
                    "急会诊及时到位率怎么算？",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                    session_id="sql-memory-session",
                    memory=memory,
                )
                events = list(run_chat_stream(
                    "生成 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=ContextOnlySQLCommandLLM(),
                    session_id=first["session_id"],
                    memory=memory,
                ))

            done = next(data for event, data in reversed(events) if event == "done")
            trace = TraceRecorder(engine).get_trace(done["trace_id"])
            search_node = next(
                node for node in trace["nodes"] if node["node_name"] == "rule_search"
            )
            intent_node = next(
                node for node in trace["nodes"] if node["node_name"] == "intent_detect"
            )

            self.assertEqual(done["intent"], "generate_sql")
            self.assertEqual(done["rule_id"], "R001")
            self.assertEqual(len(FakeSQLGenerationAgent.calls), 1)
            self.assertEqual(
                intent_node["output_data"]["rewritten_query"],
                "生成急会诊及时到位率 SQL",
            )
            self.assertEqual(
                intent_node["output_data"]["retrieval_query"],
                "急会诊及时到位率",
            )
            self.assertEqual(
                intent_node["output_data"]["context_source"],
                "memory_last_rule",
            )
            self.assertEqual(
                search_node["input_data"]["retrieval_query"],
                "急会诊及时到位率",
            )

    def test_sql_command_reuses_previous_session_stat_period(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                first = run_chat(
                    "急会诊及时到位率统计时间从2026-06-01到2026-08-01怎么算？",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                    session_id="sql-period-memory-session",
                    memory=memory,
                )
                list(run_chat_stream(
                    "生成 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=ContextOnlySQLCommandLLM(),
                    session_id=first["session_id"],
                    memory=memory,
                ))

        self.assertEqual(len(FakeSQLGenerationAgent.calls), 1)
        call = FakeSQLGenerationAgent.calls[0]
        self.assertEqual(call["stat_start_time"], "2026-06-01 00:00:00")
        self.assertEqual(call["stat_end_time"], "2026-08-01 00:00:00")

    def test_sql_command_recovers_stat_period_from_legacy_user_message(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            session_id = memory.ensure_session(
                "legacy-period-memory-session", "hospital_001"
            )
            memory.append_message(
                session_id,
                "user",
                "如果统计时间改成从2026-06-01到2026-08-01怎么算？",
            )
            memory.append_message(
                session_id,
                "assistant",
                "已按新的统计时间说明。",
                {"rule_id": "R001", "rule_name": "急会诊及时到位率"},
            )
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                list(run_chat_stream(
                    "生成 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=ContextOnlySQLCommandLLM(),
                    session_id=session_id,
                    memory=memory,
                ))

        self.assertEqual(len(FakeSQLGenerationAgent.calls), 1)
        call = FakeSQLGenerationAgent.calls[0]
        self.assertEqual(call["stat_start_time"], "2026-06-01 00:00:00")
        self.assertEqual(call["stat_end_time"], "2026-08-01 00:00:00")

    def test_partial_start_date_change_keeps_previous_end_date(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            session_id = memory.ensure_session(
                "partial-period-memory-session", "hospital_001"
            )
            memory.append_message(
                session_id,
                "assistant",
                "已生成本期指标 SQL。",
                {
                    "rule_id": "R001",
                    "rule_name": "急会诊及时到位率",
                    "stat_start_time": "2026-07-01 00:00:00",
                    "stat_end_time": "2026-08-01 00:00:00",
                },
            )
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                changed = run_chat(
                    "如果把统计时间起始时间改成2026-05-01怎么算？",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                    session_id=session_id,
                    memory=memory,
                )
                list(run_chat_stream(
                    "生成 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=ContextOnlySQLCommandLLM(),
                    session_id=changed["session_id"],
                    memory=memory,
                ))

        self.assertEqual(changed["intent"], "query")
        self.assertEqual(changed["rule_id"], "R001")
        self.assertEqual(len(FakeSQLGenerationAgent.calls), 1)
        call = FakeSQLGenerationAgent.calls[0]
        self.assertEqual(call["stat_start_time"], "2026-05-01 00:00:00")
        self.assertEqual(call["stat_end_time"], "2026-08-01 00:00:00")

    def test_search_match_count_accepts_mysql_repository_shape(self) -> None:
        self.assertEqual(_search_match_count({"matches": [{}, {}]}), 2)
        self.assertEqual(_search_match_count({"results": [{}]}), 1)

    def test_trial_run_trace_records_mcp_execution_node(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                events = list(run_chat_stream(
                    "试运行 SQL",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=True,
                    llm_client=FakeLLM(intent="trial_run"),
                ))

            trace_id = next(data["trace_id"] for event, data in events if event == "meta")
            trace = TraceRecorder(engine).get_trace(trace_id)
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertIn("sql_trial_mcp", by_name)
            self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["run_id"], "RUN_TRACE_TEST")
            self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["status"], "success")
            self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["numerator_count"], 8)
            self.assertEqual(by_name["sql_trial_mcp"]["output_data"]["denominator_count"], 10)
            self.assertNotIn("rows", by_name["sql_trial_mcp"]["output_data"])
            self.assertNotIn("bound_sql", by_name["sql_trial_mcp"]["output_data"])
            done = next(data for event, data in reversed(events) if event == "done")
            self.assertIn("8 / 10 x 100% = 80%", done["answer"])
            self.assertNotIn("patient_id", done["answer"])

    def test_feedback_stream_trace_records_preview_and_final_nodes(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine):
                events = list(run_chat_stream(
                    "\u6211\u4eec\u533b\u9662\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u5e94\u8be5\u6309\u0031\u0035\u5206\u949f\u5185\u7b7e\u5230\u8ba1\u7b97",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                ))

            done = next(data for event, data in reversed(events) if event == "done")
            trace = TraceRecorder(engine).get_trace(done["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertIn("feedback_preview", by_name)
            self.assertEqual(by_name["feedback_preview"]["output_data"]["target_level"], "hospital")
            self.assertIn("final_response", by_name)

    def test_diagnose_stream_trace_records_three_layer_nodes(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.diagnose.agent.DiagnoseAgent", FakeDiagnoseAgent):
                events = list(run_chat_stream(
                    "\u8bca\u65ad\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387",
                    hospital_id="hospital_001",
                    kb_root=root,
                    use_llm=False,
                ))

            done = next(data for event, data in reversed(events) if event == "done")
            trace = TraceRecorder(engine).get_trace(done["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertEqual(by_name["diagnose_structure_mcp"]["output_data"]["metadata_source"], "dbhub")
            self.assertEqual(by_name["diagnose_rule_check"]["status"], "success")
            self.assertEqual(by_name["diagnose_data_check_mcp"]["status"], "warning")

    def test_run_chat_stream_meta_contains_trace_id(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            engine = _trace_runtime_engine()

            with patch("app.agent.graph.create_runtime_engine", return_value=engine):
                events = list(run_chat_stream(
                    "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                    hospital_id="hospital_001",
                    kb_root=root,
                ))

            meta = next(data for event, data in events if event == "meta")
            done = next(data for event, data in reversed(events) if event == "done")
            self.assertTrue(meta["trace_id"].startswith("TRACE_"))
            self.assertEqual(done["trace_id"], meta["trace_id"])

    def test_ambiguous_ward_entry_request_is_persisted_as_clarification(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            first = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                kb_root=root,
                session_id="ward-entry-clarification",
                memory=memory,
            )

            result = run_chat(
                "按入区时间算",
                hospital_id="hospital_001",
                kb_root=root,
                session_id=first["session_id"],
                memory=memory,
            )

        context = memory.load_context(first["session_id"])
        self.assertEqual(result["status"], "context_clarification_required")
        self.assertIn("统计范围", result["answer"])
        self.assertEqual(
            context.pending_clarifications[0].code,
            "WARD_ENTRY_SCOPE_REQUIRED",
        )

    def test_generate_sql_uses_session_caliber_and_blocks_unmapped_field(self) -> None:
        FakeSQLGenerationAgent.calls = []
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            memory = ConversationMemory(root / "runtime" / "conversations")
            engine = _trace_runtime_engine()
            first = run_chat(
                "急会诊及时到位率怎么算？",
                hospital_id="hospital_001",
                kb_root=root,
                session_id="ward-entry-generation",
                memory=memory,
            )
            run_chat(
                "48小时从入区时间开始算",
                hospital_id="hospital_001",
                kb_root=root,
                session_id=first["session_id"],
                memory=memory,
            )

            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.sqlgen.agent.SQLGenerationAgent", FakeSQLGenerationAgent), \
                 patch("app.agents.metadata_parsing.MetadataParsingAgent.precheck", return_value=PRECHECK_OK):
                events = list(
                    run_chat_stream(
                        "生成 SQL",
                        hospital_id="hospital_001",
                        kb_root=root,
                        use_llm=True,
                        llm_client=ContextOnlySQLCommandLLM(),
                        session_id=first["session_id"],
                        memory=memory,
                    )
                )

        done = next(data for event, data in reversed(events) if event == "done")
        context = memory.load_context(first["session_id"])
        override = context.working_caliber.get("elapsed_time_start")
        self.assertIsNotNone(override)
        self.assertEqual(override.business_value, "ward_entry_time")
        self.assertEqual(done["status"], "context_blocked")
        self.assertFalse(done["execution_context"]["executable"])
        self.assertIn("入区时间对应的医院字段", done["answer"])
        self.assertEqual(FakeSQLGenerationAgent.calls, [])
        trace = TraceRecorder(engine).get_trace(done["trace_id"])
        by_name = {node["node_name"]: node for node in trace["nodes"]}
        self.assertEqual(
            by_name["context_resolve"]["output_data"]["resolution_status"],
            "pending_mapping",
        )
        apply_node = by_name["working_caliber_apply"]
        self.assertEqual(apply_node["status"], "warning")
        self.assertFalse(apply_node["output_data"]["executable"])
        self.assertEqual(
            apply_node["output_data"]["blockers"][0]["code"],
            "CONTEXT_FIELD_MAPPING_REQUIRED",
        )


def _trace_runtime_engine():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE med_agent_trace (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              trace_id TEXT NOT NULL UNIQUE,
              session_id TEXT,
              hospital_id TEXT,
              user_id TEXT,
              user_query TEXT,
              intent TEXT,
              final_status TEXT,
              final_answer_summary TEXT,
              error_count INTEGER DEFAULT 0,
              fallback_count INTEGER DEFAULT 0,
              started_at TEXT NOT NULL,
              ended_at TEXT,
              duration_ms INTEGER,
              created_at TEXT NOT NULL
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_agent_trace_node (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              trace_id TEXT NOT NULL,
              node_id TEXT NOT NULL,
              node_name TEXT NOT NULL,
              node_type TEXT NOT NULL,
              status TEXT NOT NULL,
              input_summary TEXT,
              output_summary TEXT,
              error_code TEXT,
              error_message TEXT,
              tool_name TEXT,
              db_source TEXT,
              sql_id TEXT,
              run_id TEXT,
              rule_id TEXT,
              llm_model TEXT,
              started_at TEXT NOT NULL,
              ended_at TEXT,
              duration_ms INTEGER,
              created_at TEXT NOT NULL
            )
        """))
    return engine


if __name__ == "__main__":
    unittest.main()
