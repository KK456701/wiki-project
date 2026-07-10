from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text

from app.observability.trace import TraceRecorder


class TraceRecorderTest(unittest.TestCase):
    def _engine(self):
        engine = create_engine("sqlite:///:memory:")
        with engine.begin() as conn:
            conn.execute(
                text(
                    """
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
                      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """
                )
            )
            conn.execute(
                text(
                    """
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
                      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """
                )
            )
        return engine

    def test_trace_records_nodes_and_jsonl(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = Path(tmp) / "trace_events.jsonl"
            recorder = TraceRecorder(self._engine(), jsonl)
            recorder.start_trace("TRACE_1", "session_1", "hospital_001", "急会诊怎么处理")
            recorder.record_node(
                "TRACE_1",
                node_name="intent_detect",
                node_type="llm",
                status="success",
                input_summary="急会诊怎么处理",
                output_summary="query",
                duration_ms=12,
            )
            time.sleep(0.02)
            recorder.finish_trace("TRACE_1", "success", "已回答", intent="query")

            trace = recorder.get_trace("TRACE_1")
            self.assertEqual(trace["trace_id"], "TRACE_1")
            self.assertEqual(trace["final_status"], "success")
            self.assertEqual(trace["nodes"][0]["node_name"], "intent_detect")
            self.assertEqual(trace["nodes"][0]["status"], "success")
            self.assertGreater(trace["duration_ms"], 0)

            events = [json.loads(line) for line in jsonl.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(events[0]["event"], "trace_started")
            self.assertEqual(events[1]["event"], "trace_node")
            self.assertEqual(events[2]["event"], "trace_finished")
            self.assertGreater(events[2]["duration_ms"], 0)

    def test_jsonl_fallback_keeps_trace_readable_when_runtime_db_write_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = Path(tmp) / "trace_events.jsonl"
            recorder = TraceRecorder(create_engine("sqlite:///:memory:"), jsonl)

            recorder.start_trace("TRACE_FAIL", None, None, "fallback test")
            recorder.record_node("TRACE_FAIL", "intent_detect", "llm", "success")
            recorder.finish_trace("TRACE_FAIL", "success", "done", intent="query")

            events = [json.loads(line) for line in jsonl.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(events[0]["event"], "trace_started")
            self.assertEqual(events[0]["trace_id"], "TRACE_FAIL")
            self.assertEqual(events[1]["event"], "trace_node")
            self.assertEqual(events[2]["event"], "trace_finished")

            trace = recorder.get_trace("TRACE_FAIL")
            self.assertEqual(trace["trace_id"], "TRACE_FAIL")
            self.assertEqual(trace["trace_storage"], "jsonl")
            self.assertEqual(trace["final_status"], "success")
            self.assertEqual(trace["nodes"][0]["node_name"], "intent_detect")

    def test_trace_nodes_include_structured_data_and_manifest_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = Path(tmp) / "trace_events.jsonl"
            recorder = TraceRecorder(self._engine(), jsonl)
            recorder.start_trace("TRACE_STRUCT", "session_1", "hospital_001", "急会诊怎么算")
            recorder.record_node(
                "TRACE_STRUCT",
                "intent_detect",
                "llm_or_rule",
                "success",
                input_summary="急会诊怎么算",
                output_summary="query",
                input_data={"query": "急会诊怎么算", "session_memory": {}},
                output_data={"intent": "query", "retrieval_query": "急会诊怎么算"},
                config_data={"strategy": "规则兜底 + LLM"},
            )
            recorder.finish_trace("TRACE_STRUCT", "success", "已回答", intent="query")

            trace = recorder.get_trace("TRACE_STRUCT")
            node = trace["nodes"][0]
            self.assertEqual(node["node_title"], "识别用户意图")
            self.assertEqual(node["input_data"]["query"], "急会诊怎么算")
            self.assertEqual(node["output_data"]["intent"], "query")
            self.assertEqual(node["config_data"]["strategy"], "规则兜底 + LLM")

    def test_failed_node_gets_default_failure_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = Path(tmp) / "trace_events.jsonl"
            recorder = TraceRecorder(self._engine(), jsonl)
            recorder.start_trace("TRACE_FAILED_NODE", None, None, "生成 SQL")
            recorder.record_node(
                "TRACE_FAILED_NODE",
                "sql_validate",
                "sql_validator",
                "failed",
                input_data={"sql_text": "delete from x", "rule_id": "R001"},
                output_data={"validation_status": "failed"},
            )
            recorder.finish_trace("TRACE_FAILED_NODE", "failed", "SQL 校验失败", intent="generate_sql", error_count=1)

            trace = recorder.get_trace("TRACE_FAILED_NODE")
            node = trace["nodes"][0]
            self.assertEqual(node["error_code"], "SQL_VALIDATE_FAILED")
            self.assertEqual(node["failure_code"], "SQL_VALIDATE_FAILED")
            self.assertEqual(node["contract_status"], "ok")


if __name__ == "__main__":
    unittest.main()
