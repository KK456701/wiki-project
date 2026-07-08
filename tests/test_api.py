import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

import app.api.main as api_main
from app.api.main import app
from tests.test_kb_tools import make_minimal_kb, temp_kb_dir


class ApiTest(unittest.TestCase):
    def test_health_reports_workflow_engine(self) -> None:
        client = TestClient(app)

        response = client.get("/api/health")

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "ok")
        self.assertIn(data["workflow_engine"], {"langgraph", "deterministic_fallback"})
        self.assertIn("langgraph_installed", data)

    def test_change_request_default_change_type_is_readable(self) -> None:
        request = api_main.ChangeRequestCreate(
            rule_id="R001",
            hospital_id="hospital_001",
            requested_formula="formula",
        )

        self.assertEqual(request.change_type, "本院口径反馈")

    def test_chat_endpoint_returns_answer(self) -> None:
        client = TestClient(app)

        response = client.post(
            "/api/chat",
            json={"query": "急会诊及时到位率怎么算？", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["rule_id"], "MQSI2025_005")
        self.assertIn("急会诊及时到位率", data["answer"])
        self.assertIn("SQL 状态：可用", data["answer"])
        self.assertIn("生成 SQL", data["answer"])

    def test_chat_endpoint_uses_session_memory_for_follow_up_feedback(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root):
                first = client.post(
                    "/api/chat",
                    json={
                        "query": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387\u600e\u4e48\u7b97\uff1f",
                        "hospital_id": "hospital_001",
                        "use_llm": False,
                    },
                ).json()
                second = client.post(
                    "/api/chat",
                    json={
                        "query": "\u6211\u4eec\u533b\u9662\u662f\u6309\u716730\u5206\u949f\u6765\u7684",
                        "hospital_id": "hospital_001",
                        "use_llm": False,
                        "session_id": first["session_id"],
                    },
                ).json()

            self.assertEqual(second["session_id"], first["session_id"])
            self.assertEqual(second["intent"], "feedback")
            self.assertEqual(second["rule_id"], "R001")
            self.assertEqual(second["feedback_preview"]["target_level"], "hospital")
            self.assertNotIn("change_request", second)

    def test_metadata_sync_dbhub_uses_mcp_client(self) -> None:
        class FakeDBHubClient:
            def __init__(self, *args, **kwargs):
                self.calls = []

            def execute_sql(self, sql):
                self.calls.append(sql)
                if "INFORMATION_SCHEMA.TABLES" in sql:
                    return [{"TABLE_NAME": "consult_record", "TABLE_COMMENT": "", "TABLE_TYPE": "BASE TABLE"}]
                if "INFORMATION_SCHEMA.COLUMNS" in sql:
                    return [
                        {"TABLE_NAME": "consult_record", "COLUMN_NAME": "id", "DATA_TYPE": "bigint", "COLUMN_TYPE": "bigint", "IS_NULLABLE": "NO", "COLUMN_KEY": "PRI", "COLUMN_DEFAULT": None, "COLUMN_COMMENT": ""}
                    ]
                return []

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            runtime_engine = _metadata_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine), \
                 patch.object(api_main, "DBHubMCPClient", FakeDBHubClient):
                response = client.post("/api/metadata/sync", json={"hospital_id": "hospital_001", "db_name": "hospital_demo_data", "source": "dbhub"})

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertEqual(data["metadata_source"], "dbhub")
            self.assertEqual(data["table_count"], 1)
            self.assertEqual(data["column_count"], 1)

    def test_metadata_sync_records_trace_node(self) -> None:
        from app.observability.trace import TraceRecorder

        class FakeDBHubClient:
            def __init__(self, *args, **kwargs):
                pass

            def execute_sql(self, sql):
                if "INFORMATION_SCHEMA.TABLES" in sql:
                    return [{"TABLE_NAME": "consult_record", "TABLE_COMMENT": "", "TABLE_TYPE": "BASE TABLE"}]
                if "INFORMATION_SCHEMA.COLUMNS" in sql:
                    return [
                        {"TABLE_NAME": "consult_record", "COLUMN_NAME": "id", "DATA_TYPE": "bigint", "COLUMN_TYPE": "bigint", "IS_NULLABLE": "NO", "COLUMN_KEY": "PRI", "COLUMN_DEFAULT": None, "COLUMN_COMMENT": ""}
                    ]
                return []

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            runtime_engine = _metadata_trace_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine), \
                 patch.object(api_main, "DBHubMCPClient", FakeDBHubClient):
                response = client.post("/api/metadata/sync", json={"hospital_id": "hospital_001", "db_name": "hospital_demo_data", "source": "dbhub"})

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertTrue(data["trace_id"].startswith("TRACE_"))
            trace = TraceRecorder(runtime_engine).get_trace(data["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertEqual(by_name["metadata_sync_mcp"]["output_data"]["batch_id"], data["batch_id"])
            self.assertEqual(by_name["metadata_sync_mcp"]["output_data"]["table_count"], 1)

    def test_diagnose_api_records_three_layer_trace_nodes(self) -> None:
        from app.observability.trace import TraceRecorder

        class FakeDiagnoseAgent:
            def __init__(self, *args, **kwargs):
                pass

            def run(self, **kwargs):
                return {
                    "ok": True,
                    "diagnose_status": "warning",
                    "report_id": "DR_API_TRACE",
                    "layers": [
                        {"layer": 1, "ok": True, "diagnose_type": "结构适配正常", "metadata_source": "dbhub", "checks": []},
                        {"layer": 2, "ok": True, "diagnose_type": "口径规则正常", "checks": []},
                        {"layer": 3, "ok": True, "diagnose_type": "数据质量风险", "checks": [{"status": "warn", "message": "样本量偏低"}]},
                    ],
                }

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            runtime_engine = _trace_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine), \
                 patch("app.diagnose.agent.DiagnoseAgent", FakeDiagnoseAgent), \
                 patch.object(api_main, "create_business_db_client", return_value=object()), \
                 patch.object(api_main, "create_dbhub_metadata_provider", return_value=object()):
                response = client.post("/api/diagnose/run", json={"hospital_id": "hospital_001", "rule_id": "R001"})

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertTrue(data["trace_id"].startswith("TRACE_"))
            trace = TraceRecorder(runtime_engine).get_trace(data["trace_id"])
            by_name = {node["node_name"]: node for node in trace["nodes"]}
            self.assertEqual(by_name["diagnose_structure_mcp"]["output_data"]["metadata_source"], "dbhub")
            self.assertEqual(by_name["diagnose_rule_check"]["status"], "success")
            self.assertEqual(by_name["diagnose_data_check_mcp"]["status"], "warning")

    def test_metadata_sync_dbhub_selects_tool_by_database(self) -> None:
        captured = []

        class FakeDBHubClient:
            def __init__(self, *args, **kwargs):
                captured.append(kwargs)

            def execute_sql(self, sql):
                if "INFORMATION_SCHEMA.TABLES" in sql:
                    return [{"TABLE_NAME": "med_metadata_column", "TABLE_COMMENT": "", "TABLE_TYPE": "BASE TABLE"}]
                if "INFORMATION_SCHEMA.COLUMNS" in sql:
                    return [
                        {"TABLE_NAME": "med_metadata_column", "COLUMN_NAME": "column_name", "DATA_TYPE": "varchar", "COLUMN_TYPE": "varchar(128)", "IS_NULLABLE": "NO", "COLUMN_KEY": "", "COLUMN_DEFAULT": None, "COLUMN_COMMENT": ""}
                    ]
                return []

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            runtime_engine = _metadata_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine), \
                 patch.object(api_main, "DBHubMCPClient", FakeDBHubClient):
                response = client.post("/api/metadata/sync", json={"hospital_id": "system", "db_name": "wiki_agent_runtime", "source": "dbhub"})

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["metadata_source"], "dbhub")
            self.assertEqual(captured[0]["execute_tool"], "execute_sql_wiki_agent_runtime")
            self.assertEqual(captured[0]["source_id"], "wiki_agent_runtime")

    def test_metadata_sync_request_accepts_source_default(self) -> None:
        request = api_main.MetadataSyncRequest(hospital_id="hospital_001", db_name="hospital_demo_data")

        self.assertEqual(request.source, "dbhub")

    def test_kb_export_and_merge_upload_workflow(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=True)
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root):
                exported = client.get("/api/kb/export", params={"hospital_id": "hospital_001"})
                login = client.post("/api/admin/login", json={"password": "admin123"})
                headers = {"Authorization": f"Bearer {login.json()['token']}", "Content-Type": "application/zip"}
                uploaded = client.post("/api/kb/merge/upload", content=exported.content, headers=headers)
                report_id = uploaded.json()["report_id"]
                listed = client.get("/api/kb/merge/reports", headers={"Authorization": headers["Authorization"]})
                detail = client.get(f"/api/kb/merge/report/{report_id}", headers={"Authorization": headers["Authorization"]})
                item_id = detail.json()["items"][0]["item_id"]
                approved = client.post(
                    f"/api/kb/merge/report/{report_id}/items/{item_id}/approve",
                    headers={"Authorization": headers["Authorization"], "Content-Type": "application/json"},
                    json={"decision": "adopt_as_company_candidate", "approver_id": "admin"},
                )

            self.assertEqual(exported.status_code, 200)
            self.assertEqual(exported.headers["content-type"], "application/zip")
            self.assertEqual(uploaded.status_code, 200)
            self.assertEqual(uploaded.json()["status"], "pending_review")
            self.assertGreaterEqual(uploaded.json()["summary"]["total_items"], 1)
            self.assertEqual(listed.status_code, 200)
            self.assertTrue(any(item["report_id"] == report_id for item in listed.json()["items"]))
            self.assertEqual(detail.status_code, 200)
            self.assertEqual(approved.status_code, 200)
            self.assertEqual(approved.json()["status"], "approved_candidate")

    def test_review_api_creates_and_approves_hospital_change_request(self) -> None:
        from app.observability.trace import TraceRecorder

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            runtime_engine = _trace_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine):
                login = client.post("/api/admin/login", json={"password": "admin123"})
                token = login.json()["token"]
                headers = {"Authorization": f"Bearer {token}"}
                created = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "indicator_name": "????????",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u7533\u8bf7\u53d1\u51fa\u5230\u533b\u751f\u7b7e\u5230\u4e0d\u8d85\u8fc7\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                        "hospital_feedback": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                        "original_user_message": "\u6211\u4eec\u533b\u9662\u6309\u0032\u0030\u5206\u949f\u8ba1\u7b97\u3002",
                    },
                )
                pending = client.get("/api/review/pending", headers=headers)
                approved = client.post(
                    f"/api/review/change-requests/{created.json()['change_id']}/approve",
                    headers=headers,
                )
                effective = client.get("/api/kb/rules/R001/effective", params={"hospital_id": "hospital_001"})

            self.assertEqual(login.status_code, 200)
            self.assertEqual(created.status_code, 200)
            self.assertEqual(pending.status_code, 200)
            self.assertEqual(len(pending.json()["items"]), 1)
            self.assertEqual(approved.json()["status"], "approved")
            self.assertEqual(effective.json()["effective_level"], "hospital")
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective.json()["formula"])
            self.assertTrue(created.json()["trace_id"].startswith("TRACE_"))
            self.assertTrue(approved.json()["trace_id"].startswith("TRACE_"))
            created_trace = TraceRecorder(runtime_engine).get_trace(created.json()["trace_id"])
            approved_trace = TraceRecorder(runtime_engine).get_trace(approved.json()["trace_id"])
            self.assertEqual(created_trace["nodes"][0]["node_name"], "change_request_submit")
            approved_nodes = {node["node_name"]: node for node in approved_trace["nodes"]}
            self.assertIn("approval_apply_override", approved_nodes)
            self.assertIn("index_rebuild", approved_nodes)


    def test_review_api_lists_and_restores_hospital_override_versions(self) -> None:
        from app.observability.trace import TraceRecorder

        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root, with_hospital=False)
            runtime_engine = _trace_runtime_engine()
            client = TestClient(app)

            with patch.object(api_main, "DEFAULT_KB_ROOT", root), \
                 patch("app.db.engine.create_runtime_engine", return_value=runtime_engine):
                login = client.post("/api/admin/login", json={"password": "admin123"})
                headers = {"Authorization": f"Bearer {login.json()['token']}"}
                first = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u0032\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0032\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                    },
                )
                approved_first = client.post(f"/api/review/change-requests/{first.json()['change_id']}/approve", headers=headers).json()
                second = client.post(
                    "/api/review/change-requests",
                    json={
                        "rule_id": "R001",
                        "hospital_id": "hospital_001",
                        "target_level": "hospital",
                        "requested_definition": "\u672c\u9662\u6025\u4f1a\u8bca\u6309\u0033\u0030\u5206\u949f\u7edf\u8ba1\u3002",
                        "requested_formula": "\u6025\u4f1a\u8bca\u53ca\u65f6\u5230\u4f4d\u7387 = \u0033\u0030\u5206\u949f\u5185\u7b7e\u5230\u6025\u4f1a\u8bca\u6b21\u6570 / \u540c\u671f\u6025\u4f1a\u8bca\u603b\u6b21\u6570 \u00d7 \u0031\u0030\u0030%",
                    },
                )
                approved_second = client.post(f"/api/review/change-requests/{second.json()['change_id']}/approve", headers=headers).json()
                versions = client.get("/api/review/hospital-overrides/hospital_001/R001/versions", headers=headers)
                restored = client.post(
                    f"/api/review/hospital-overrides/hospital_001/R001/versions/{approved_first['active_version_id']}/restore",
                    headers=headers,
                    json={"approver_id": "admin_restore"},
                )
                effective = client.get("/api/kb/rules/R001/effective", params={"hospital_id": "hospital_001"})

            self.assertEqual(versions.status_code, 200)
            self.assertEqual(versions.json()["active_version_id"], approved_second["active_version_id"])
            self.assertEqual(len(versions.json()["versions"]), 2)
            self.assertEqual(restored.status_code, 200)
            self.assertEqual(restored.json()["active_version_id"], approved_first["active_version_id"])
            self.assertIn("\u0032\u0030\u5206\u949f\u5185\u7b7e\u5230", effective.json()["formula"])
            self.assertTrue(restored.json()["trace_id"].startswith("TRACE_"))
            restored_trace = TraceRecorder(runtime_engine).get_trace(restored.json()["trace_id"])
            restored_nodes = {node["node_name"]: node for node in restored_trace["nodes"]}
            self.assertIn("approval_apply_override", restored_nodes)
            self.assertIn("index_rebuild", restored_nodes)

    def test_review_pending_without_admin_token_returns_401(self) -> None:
        client = TestClient(app)

        response = client.get("/api/review/pending")

        self.assertEqual(response.status_code, 401)

    def test_chat_stream_greeting_returns_chat_intent(self) -> None:
        client = TestClient(app)
        response = client.post(
            "/api/chat/stream",
            json={"query": "\u4f60\u597d", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn('"intent": "chat"', body)
        self.assertIn('"rule_id": null', body)
        self.assertIn("\u6838\u5fc3\u5236\u5ea6\u6307\u6807 Agent", body)

    def test_chat_stream_returns_sse_events(self) -> None:
        client = TestClient(app)
        response = client.post(
            "/api/chat/stream",
            json={"query": "????????????", "hospital_id": "hospital_001", "use_llm": False},
        )

        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn("event: token", body)
        self.assertIn("event: done", body)

    def test_health_dependencies_reports_dbhub_and_runtime(self) -> None:
        class FakeBusinessDB:
            def check_available(self):
                return {"ok": True, "source": "hospital_demo_data", "tool_name": "execute_sql_hospital_demo_data"}

        client = TestClient(app)
        engine = _trace_runtime_engine()
        with patch("app.db.engine.create_runtime_engine", return_value=engine), \
             patch.object(api_main, "create_business_db_client", return_value=FakeBusinessDB()), \
             patch.object(api_main, "dbhub_sources", return_value={"sources": [{"name": "hospital_demo_data"}]}):
            response = client.get("/api/health/dependencies")

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["fastapi"]["ok"])
        self.assertTrue(data["runtime_db"]["ok"])
        self.assertTrue(data["business_db_mcp"]["ok"])
        self.assertTrue(data["dbhub_http"]["ok"])

    def test_trace_api_returns_trace_nodes(self) -> None:
        from app.observability.trace import TraceRecorder

        client = TestClient(app)
        engine = _trace_runtime_engine()
        recorder = TraceRecorder(engine)
        recorder.start_trace("TRACE_API_TEST", "session_1", "hospital_001", "测试")
        recorder.record_node("TRACE_API_TEST", "intent_detect", "llm", "success")
        recorder.finish_trace("TRACE_API_TEST", "success", "完成")

        with patch("app.db.engine.create_runtime_engine", return_value=engine):
            response = client.get("/api/traces/TRACE_API_TEST")

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["trace_id"], "TRACE_API_TEST")
        self.assertEqual(data["nodes"][0]["node_name"], "intent_detect")


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


def _metadata_runtime_engine():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE med_metadata_table (
              hospital_id TEXT, db_name TEXT, table_name TEXT, table_comment TEXT,
              table_type TEXT, sync_batch_id TEXT, sync_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_metadata_column (
              hospital_id TEXT, db_name TEXT, table_name TEXT, column_name TEXT,
              data_type TEXT, column_type TEXT, is_nullable TEXT, column_key TEXT,
              column_default TEXT, column_comment TEXT, sync_batch_id TEXT, sync_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_metadata_sync_log (
              hospital_id TEXT, db_name TEXT, table_name TEXT, field_name TEXT,
              change_type TEXT, change_desc TEXT, sync_batch_id TEXT, sync_time TEXT
            )
        """))
        conn.execute(text("""
            CREATE TABLE med_metadata_snapshot (
              hospital_id TEXT, db_name TEXT, metadata_source TEXT, sync_batch_id TEXT,
              snapshot_json TEXT, created_at TEXT
            )
        """))
    return engine


def _metadata_trace_runtime_engine():
    engine = _metadata_runtime_engine()
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
