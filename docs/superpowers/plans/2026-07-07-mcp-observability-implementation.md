# 数据库 MCP 化与全链路可观测性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将业务库访问统一改为 DBHub MCP，并为聊天、SQL 生成、试运行、诊断、元数据同步补齐可查看的执行链路 Trace。

**Architecture:** 业务库只读访问走 `BusinessDBClient -> DBHubMCPClient -> execute_sql_hospital_demo_data`；运行库仍通过项目内部 Repository 写入审计、Trace、SQL 日志和诊断报告。每次请求生成 `trace_id`，关键节点写入运行库和 JSONL 兜底日志，前端可查看链路。

**Tech Stack:** FastAPI、SQLAlchemy、DBHub MCP、LangGraph、Ollama、原生 HTML/CSS/JS、Python unittest。

## Global Constraints

- 业务库 `hospital_demo_data` 全部通过 DBHub MCP 只读访问。
- 运行库 `wiki_agent_runtime` 保留项目内部 Repository 写入。
- 不暴露运行库通用可写 SQL MCP 工具。
- 不让 LLM 直接决定执行任意 SQL。
- 不在 MCP 失败时绕过 MCP 直连业务库。
- 不把 DBHub 的 `node_modules` 提交到仓库。
- 项目文档、注释和面向用户的文字优先使用中文。

---

## File Structure

- Create: `app/db_access/query_result.py`  
  定义业务库 SQL 查询的统一返回结构，隐藏 DBHub 原始响应差异。

- Create: `app/db_access/business_db.py`  
  封装业务库只读 SQL 执行，唯一依赖 `DBHubMCPClient.execute_sql()`。

- Create: `app/observability/trace.py`  
  提供 `TraceRecorder`、`TraceContext`、节点记录、JSONL 兜底和 Trace 查询。

- Modify: `scripts/init_runtime_db.sql`  
  新增 `med_agent_trace` 和 `med_agent_trace_node` 表。

- Modify: `app/db/repositories.py`  
  新增 Trace 写入和查询函数；保持原有运行库仓储写入方式。

- Modify: `app/sqlgen/runner.py`  
  SQL 试运行不再接收 `business_engine`，改为接收 `BusinessDBClient`。

- Modify: `app/sqlgen/agent.py`  
  构造函数接收 `business_db`，生成 SQL 后通过 MCP 试运行。

- Modify: `app/diagnose/data_check.py`  
  第三层数据质量检查改为 MCP 查询。

- Modify: `app/diagnose/agent.py`  
  构造函数接收 `business_db` 和可选 `metadata_provider`；第一层优先 DBHub 实时元数据。

- Modify: `app/api/main.py`  
  增加工厂方法、Trace API、依赖健康检查、业务库 MCP 注入。

- Modify: `app/agent/graph.py`  
  聊天流式和非流式链路写 Trace，并返回 `trace_id`。

- Modify: `web/index.html`  
  每条 Agent 回复显示“查看链路”，新增 Trace 弹窗。

- Test: `tests/test_business_db_mcp.py`  
  覆盖业务库 MCP 客户端。

- Test: `tests/test_observability_trace.py`  
  覆盖 Trace 写入、查询和 JSONL 兜底。

- Modify/Test: `tests/test_sqlgen.py`、`tests/test_diagnose_agent.py`、`tests/test_api.py`、`tests/test_agent_workflow.py`

---

### Task 1: Trace 存储与 JSONL 兜底

**Files:**
- Create: `app/observability/__init__.py`
- Create: `app/observability/trace.py`
- Modify: `app/db/repositories.py`
- Modify: `scripts/init_runtime_db.sql`
- Test: `tests/test_observability_trace.py`

**Interfaces:**
- Produces: `TraceRecorder(runtime_engine: Engine, jsonl_path: Path | None = None)`
- Produces: `TraceRecorder.start_trace(trace_id: str, session_id: str | None, hospital_id: str | None, user_query: str | None) -> None`
- Produces: `TraceRecorder.record_node(trace_id: str, node_name: str, node_type: str, status: str, input_summary: str = "", output_summary: str = "", error_code: str = "", error_message: str = "", tool_name: str = "", db_source: str = "", sql_id: str = "", run_id: str = "", rule_id: str = "", duration_ms: int = 0) -> None`
- Produces: `TraceRecorder.finish_trace(trace_id: str, final_status: str, final_answer_summary: str = "", intent: str = "", error_count: int = 0, fallback_count: int = 0) -> None`
- Produces: `TraceRecorder.get_trace(trace_id: str) -> dict[str, Any]`

- [ ] **Step 1: Write failing tests**

Add `tests/test_observability_trace.py`:

```python
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from sqlalchemy import create_engine, text

from app.observability.trace import TraceRecorder


class TraceRecorderTest(unittest.TestCase):
    def _engine(self):
        engine = create_engine("sqlite:///:memory:")
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
                  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """))
        return engine

    def test_trace_records_nodes_and_jsonl(self):
        with tempfile.TemporaryDirectory() as tmp:
            jsonl = Path(tmp) / "trace_events.jsonl"
            recorder = TraceRecorder(self._engine(), jsonl)
            recorder.start_trace("TRACE_1", "session_1", "hospital_001", "急会诊怎么算")
            recorder.record_node(
                "TRACE_1",
                node_name="intent_detect",
                node_type="llm",
                status="success",
                input_summary="急会诊怎么算",
                output_summary="query",
                duration_ms=12,
            )
            recorder.finish_trace("TRACE_1", "success", "已回答", intent="query")

            trace = recorder.get_trace("TRACE_1")
            self.assertEqual(trace["trace_id"], "TRACE_1")
            self.assertEqual(trace["final_status"], "success")
            self.assertEqual(trace["nodes"][0]["node_name"], "intent_detect")
            self.assertEqual(trace["nodes"][0]["status"], "success")

            events = [json.loads(line) for line in jsonl.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(events[0]["event"], "trace_started")
            self.assertEqual(events[1]["event"], "trace_node")
            self.assertEqual(events[2]["event"], "trace_finished")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
python -B -m unittest tests.test_observability_trace -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'app.observability'`.

- [ ] **Step 3: Add runtime schema**

Append to `scripts/init_runtime_db.sql`:

```sql
CREATE TABLE IF NOT EXISTS med_agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(128),
  hospital_id VARCHAR(64),
  user_id VARCHAR(128),
  user_query TEXT,
  intent VARCHAR(64),
  final_status VARCHAR(32),
  final_answer_summary TEXT,
  error_count INT DEFAULT 0,
  fallback_count INT DEFAULT 0,
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS med_agent_trace_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  node_name VARCHAR(128) NOT NULL,
  node_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_summary TEXT,
  output_summary TEXT,
  error_code VARCHAR(128),
  error_message TEXT,
  tool_name VARCHAR(128),
  db_source VARCHAR(128),
  sql_id VARCHAR(64),
  run_id VARCHAR(64),
  rule_id VARCHAR(64),
  llm_model VARCHAR(128),
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_trace_node_trace_id (trace_id),
  INDEX idx_trace_node_status (status),
  INDEX idx_trace_node_rule_id (rule_id)
);
```

- [ ] **Step 4: Implement TraceRecorder**

Create `app/observability/__init__.py`:

```python
"""可观测性模块。"""
```

Create `app/observability/trace.py` with these exact public methods:

```python
from __future__ import annotations

import json
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text


def _now() -> str:
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat(sep=" ", timespec="seconds")


class TraceRecorder:
    def __init__(self, runtime_engine: Engine, jsonl_path: Path | None = None):
        self.runtime_engine = runtime_engine
        self.jsonl_path = jsonl_path or Path("runtime") / "trace_events.jsonl"

    def _write_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

    def start_trace(self, trace_id: str, session_id: str | None, hospital_id: str | None, user_query: str | None) -> None:
        now = _now()
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text("""
                        INSERT INTO med_agent_trace
                          (trace_id, session_id, hospital_id, user_query, final_status, started_at, created_at)
                        VALUES (:tid, :sid, :hid, :q, 'running', :now, :now)
                    """),
                    {"tid": trace_id, "sid": session_id, "hid": hospital_id, "q": user_query or "", "now": now},
                )
        finally:
            self._write_jsonl({"event": "trace_started", "trace_id": trace_id, "session_id": session_id, "hospital_id": hospital_id, "user_query": user_query, "time": now})

    def record_node(
        self,
        trace_id: str,
        node_name: str,
        node_type: str,
        status: str,
        input_summary: str = "",
        output_summary: str = "",
        error_code: str = "",
        error_message: str = "",
        tool_name: str = "",
        db_source: str = "",
        sql_id: str = "",
        run_id: str = "",
        rule_id: str = "",
        duration_ms: int = 0,
    ) -> None:
        now = _now()
        node_id = f"NODE_{uuid.uuid4().hex[:12]}"
        payload = {
            "trace_id": trace_id, "node_id": node_id, "node_name": node_name, "node_type": node_type,
            "status": status, "input_summary": input_summary, "output_summary": output_summary,
            "error_code": error_code, "error_message": error_message, "tool_name": tool_name,
            "db_source": db_source, "sql_id": sql_id, "run_id": run_id, "rule_id": rule_id,
            "duration_ms": duration_ms, "now": now,
        }
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text("""
                        INSERT INTO med_agent_trace_node
                          (trace_id, node_id, node_name, node_type, status, input_summary, output_summary,
                           error_code, error_message, tool_name, db_source, sql_id, run_id, rule_id,
                           started_at, ended_at, duration_ms, created_at)
                        VALUES
                          (:trace_id, :node_id, :node_name, :node_type, :status, :input_summary, :output_summary,
                           :error_code, :error_message, :tool_name, :db_source, :sql_id, :run_id, :rule_id,
                           :now, :now, :duration_ms, :now)
                    """),
                    payload,
                )
        finally:
            event = dict(payload)
            event["event"] = "trace_node"
            event.pop("now", None)
            self._write_jsonl(event)

    def finish_trace(self, trace_id: str, final_status: str, final_answer_summary: str = "", intent: str = "", error_count: int = 0, fallback_count: int = 0) -> None:
        now = _now()
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text("""
                        UPDATE med_agent_trace
                        SET final_status=:status, final_answer_summary=:answer, intent=:intent,
                            error_count=:errors, fallback_count=:fallbacks, ended_at=:now,
                            duration_ms=0
                        WHERE trace_id=:tid
                    """),
                    {"tid": trace_id, "status": final_status, "answer": final_answer_summary[:2000], "intent": intent, "errors": error_count, "fallbacks": fallback_count, "now": now},
                )
        finally:
            self._write_jsonl({"event": "trace_finished", "trace_id": trace_id, "final_status": final_status, "intent": intent, "error_count": error_count, "fallback_count": fallback_count, "time": now})

    def get_trace(self, trace_id: str) -> dict[str, Any]:
        with self.runtime_engine.connect() as conn:
            trace = conn.execute(text("SELECT * FROM med_agent_trace WHERE trace_id=:tid"), {"tid": trace_id}).mappings().first()
            nodes = conn.execute(text("SELECT * FROM med_agent_trace_node WHERE trace_id=:tid ORDER BY id"), {"tid": trace_id}).mappings().all()
        if not trace:
            return {"trace_id": trace_id, "nodes": []}
        result = dict(trace)
        result["nodes"] = [dict(row) for row in nodes]
        return result
```

- [ ] **Step 5: Run focused test**

Run:

```powershell
python -B -m unittest tests.test_observability_trace -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/observability scripts/init_runtime_db.sql tests/test_observability_trace.py
git commit -m "feat: 增加 Agent 执行链路 Trace 存储"
```

---

### Task 2: 业务库 MCP 客户端

**Files:**
- Create: `app/db_access/query_result.py`
- Create: `app/db_access/business_db.py`
- Test: `tests/test_business_db_mcp.py`

**Interfaces:**
- Consumes: `DBHubMCPClient.execute_sql(sql: str) -> list[dict[str, Any]]`
- Produces: `QueryResult(rows: list[dict[str, Any]], row_count: int, source: str, tool_name: str, duration_ms: int)`
- Produces: `BusinessDBClient.execute_select(sql: str) -> QueryResult`
- Produces: `BusinessDBClient.check_available() -> dict[str, Any]`

- [ ] **Step 1: Write failing tests**

Create `tests/test_business_db_mcp.py`:

```python
from __future__ import annotations

import unittest

from app.db_access.business_db import BusinessDBClient


class FakeMCPClient:
    def __init__(self):
        self.sql = []

    def execute_sql(self, sql: str):
        self.sql.append(sql)
        return [{"TABLE_NAME": "consult_record"}]


class BusinessDBClientTest(unittest.TestCase):
    def test_execute_select_uses_mcp_client(self):
        fake = FakeMCPClient()
        client = BusinessDBClient(fake.execute_sql, source_id="hospital_demo_data", tool_name="execute_sql_hospital_demo_data")

        result = client.execute_select("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES")

        self.assertEqual(fake.sql, ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"])
        self.assertEqual(result.row_count, 1)
        self.assertEqual(result.rows[0]["TABLE_NAME"], "consult_record")
        self.assertEqual(result.source, "hospital_demo_data")
        self.assertEqual(result.tool_name, "execute_sql_hospital_demo_data")

    def test_rejects_non_select_sql_before_mcp(self):
        fake = FakeMCPClient()
        client = BusinessDBClient(fake.execute_sql, source_id="hospital_demo_data", tool_name="execute_sql_hospital_demo_data")

        with self.assertRaises(ValueError):
            client.execute_select("DELETE FROM consult_record")

        self.assertEqual(fake.sql, [])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test and verify failure**

```powershell
python -B -m unittest tests.test_business_db_mcp -v
```

Expected: FAIL with missing module or missing class.

- [ ] **Step 3: Implement query result**

Create `app/db_access/query_result.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class QueryResult:
    rows: list[dict[str, Any]]
    row_count: int
    source: str
    tool_name: str
    duration_ms: int
```

- [ ] **Step 4: Implement BusinessDBClient**

Create `app/db_access/business_db.py`:

```python
from __future__ import annotations

import re
import time
from typing import Any, Callable

from app.db_access.query_result import QueryResult


class BusinessDBClient:
    def __init__(self, execute_sql: Callable[[str], list[dict[str, Any]]], source_id: str, tool_name: str):
        self._execute_sql = execute_sql
        self.source_id = source_id
        self.tool_name = tool_name

    def _assert_select(self, sql: str) -> None:
        normalized = re.sub(r"\s+", " ", sql.strip()).lower()
        if not normalized.startswith("select"):
            raise ValueError("业务库 MCP 只允许执行 SELECT 查询")
        if ";" in normalized.rstrip(";"):
            raise ValueError("业务库 MCP 禁止多语句 SQL")
        forbidden = (" insert ", " update ", " delete ", " drop ", " alter ", " truncate ", " create ")
        padded = f" {normalized} "
        if any(keyword in padded for keyword in forbidden):
            raise ValueError("业务库 MCP 禁止写入或结构变更 SQL")

    def execute_select(self, sql: str) -> QueryResult:
        self._assert_select(sql)
        start = time.perf_counter()
        rows = self._execute_sql(sql)
        duration_ms = int((time.perf_counter() - start) * 1000)
        safe_rows = [dict(row) for row in rows]
        return QueryResult(
            rows=safe_rows,
            row_count=len(safe_rows),
            source=self.source_id,
            tool_name=self.tool_name,
            duration_ms=duration_ms,
        )

    def check_available(self) -> dict[str, Any]:
        result = self.execute_select("SELECT 1 AS ok")
        return {"ok": True, "source": self.source_id, "tool_name": self.tool_name, "row_count": result.row_count, "duration_ms": result.duration_ms}
```

- [ ] **Step 5: Run focused test**

```powershell
python -B -m unittest tests.test_business_db_mcp -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/db_access/query_result.py app/db_access/business_db.py tests/test_business_db_mcp.py
git commit -m "feat: 增加业务库 MCP 只读客户端"
```

---

### Task 3: SQL 试运行改为 MCP

**Files:**
- Modify: `app/sqlgen/runner.py`
- Modify: `app/sqlgen/agent.py`
- Modify: `tests/test_sqlgen.py`

**Interfaces:**
- Consumes: `BusinessDBClient.execute_select(sql: str) -> QueryResult`
- Changes: `run_sql_trial(runtime_engine: Engine, business_db: BusinessDBClient, sql_id: str, sql_text: str, hospital_id: str, rule_id: str, stat_start: str, stat_end: str, run_by: str = "agent") -> dict[str, Any]`
- Changes: `SQLGenerationAgent.__init__(self, kb_root, runtime_engine, business_db)`

- [ ] **Step 1: Add failing SQL runner test**

Add this method to class `SqlGenerationSafetyTest` in `tests/test_sqlgen.py`. Also add `from sqlalchemy import create_engine, text` at the top of the file.

```python
    def test_sql_trial_uses_business_db_mcp(self) -> None:
        class FakeBusinessDB:
            def __init__(self):
                self.sql = []

            def execute_select(self, sql: str):
                from app.db_access.query_result import QueryResult
                self.sql.append(sql)
                return QueryResult(
                    rows=[{"indicator_value": 50.0}],
                    row_count=1,
                    source="hospital_demo_data",
                    tool_name="execute_sql_hospital_demo_data",
                    duration_ms=7,
                )

        from app.sqlgen.runner import run_sql_trial

        runtime_engine = create_engine("sqlite:///:memory:")
        with runtime_engine.begin() as conn:
            conn.execute(text("""
                CREATE TABLE med_sql_run_log (
                  run_id TEXT,
                  sql_id TEXT,
                  hospital_id TEXT,
                  rule_id TEXT,
                  stat_start_time TEXT,
                  stat_end_time TEXT,
                  run_status TEXT,
                  result_value REAL,
                  error_message TEXT,
                  duration_ms INTEGER,
                  run_by TEXT,
                  run_time TEXT
                )
            """))
        fake = FakeBusinessDB()

        result = run_sql_trial(
            runtime_engine,
            fake,
            "SQL_1",
            "SELECT 50.0 AS indicator_value",
            "hospital_001",
            "MQSI2025_005",
            "2026-01-01",
            "2026-01-31",
        )

        self.assertEqual(result["status"], "success")
        self.assertEqual(result["result_value"], 50.0)
        self.assertEqual(fake.sql, ["SELECT 50.0 AS indicator_value"])
```

- [ ] **Step 2: Run test and verify failure**

```powershell
python -B -m unittest tests.test_sqlgen -v
```

Expected: FAIL because `run_sql_trial` still expects `business_engine`.

- [ ] **Step 3: Update runner implementation**

In `app/sqlgen/runner.py`, replace business engine execution block with:

```python
        result = business_db.execute_select(sql_text)
        first = result.rows[0] if result.rows else {}
        val = list(first.values())[0] if first else None
        result_value = float(val) if val is not None else None
        run_status = "success" if result_value is not None else "empty"
```

Keep `insert_sql_run_log(...)` unchanged, but set duration from MCP result when possible:

```python
    duration_ms = int((time.time() - start) * 1000)
```

The function signature must be:

```python
def run_sql_trial(
    runtime_engine: Engine,
    business_db: BusinessDBClient,
    sql_id: str,
    sql_text: str,
    hospital_id: str,
    rule_id: str,
    stat_start: str,
    stat_end: str,
    run_by: str = "agent",
) -> dict[str, Any]:
```

- [ ] **Step 4: Update SQLGenerationAgent**

In `app/sqlgen/agent.py`:

```python
from app.db_access.business_db import BusinessDBClient
```

Change constructor:

```python
def __init__(self, kb_root: str | Path, runtime_engine: Engine, business_db: BusinessDBClient):
    self.kb_root = Path(kb_root)
    self.runtime_engine = runtime_engine
    self.business_db = business_db
```

Change trial call:

```python
trial = run_sql_trial(
    self.runtime_engine,
    self.business_db,
    sql_id,
    sql_text,
    hospital_id,
    rule_id,
    stat_start_time,
    stat_end_time,
)
```

- [ ] **Step 5: Run focused tests**

```powershell
python -B -m unittest tests.test_sqlgen -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/sqlgen/runner.py app/sqlgen/agent.py tests/test_sqlgen.py
git commit -m "feat: SQL 试运行改用业务库 MCP"
```

---

### Task 4: 诊断 Agent 改为 MCP 查询业务库

**Files:**
- Modify: `app/diagnose/data_check.py`
- Modify: `app/diagnose/agent.py`
- Modify: `tests/test_diagnose_agent.py`

**Interfaces:**
- Consumes: `BusinessDBClient.execute_select(sql: str) -> QueryResult`
- Changes: `data_check(kb_root: Path, business_db: BusinessDBClient, hospital_id: str, rule_id: str) -> dict[str, Any]`
- Changes: `DiagnoseAgent.__init__(self, kb_root, runtime_engine, business_db, metadata_provider=None)`

- [ ] **Step 1: Add failing test for MCP data check**

Add this method to class `DiagnoseAgentProductionTest` in `tests/test_diagnose_agent.py`. It reuses the existing helpers `_make_diag_kb`, `_runtime_engine`, and `_effective_rule` already defined in that file.

```python
    def test_data_check_uses_business_db_mcp(self) -> None:
        class FakeBusinessDB:
            def __init__(self):
                self.sql = []

            def execute_select(self, sql):
                from app.db_access.query_result import QueryResult
                self.sql.append(sql)
                if "COUNT(*)" in sql.upper():
                    return QueryResult(rows=[{"cnt": 6}], row_count=1, source="hospital_demo_data", tool_name="execute_sql_hospital_demo_data", duration_ms=3)
                return QueryResult(rows=[{"null_count": 2}], row_count=1, source="hospital_demo_data", tool_name="execute_sql_hospital_demo_data", duration_ms=3)

        with temp_kb_dir() as root:
            root = Path(root)
            _make_diag_kb(root, include_arrive_metadata=True)
            fake = FakeBusinessDB()

            from app.diagnose.data_check import data_check
            result = data_check(root, fake, "hospital_001", "MQSI2025_005")

            self.assertTrue(fake.sql)
            self.assertIn(result["diagnose_status"], {"healthy", "warning"})
```

- [ ] **Step 2: Run test and verify failure**

```powershell
python -B -m unittest tests.test_diagnose_agent -v
```

Expected: FAIL because `data_check` expects `business_engine`.

- [ ] **Step 3: Update data_check**

In `app/diagnose/data_check.py`, remove direct `business_engine.connect()` usage. Replace both query sites with:

```python
result = business_db.execute_select(sql)
row = result.rows[0] if result.rows else {}
```

Keep existing SQL construction and warning logic intact. The function signature becomes:

```python
def data_check(kb_root: Path, business_db: BusinessDBClient, hospital_id: str, rule_id: str) -> dict[str, Any]:
```

For dialect, use mapping first, then default to MySQL:

```python
dialect = str(mapping.get("dialect") or "mysql")
```

- [ ] **Step 4: Update DiagnoseAgent**

In `app/diagnose/agent.py`, constructor becomes:

```python
def __init__(
    self,
    kb_root: str | Path,
    runtime_engine: Engine,
    business_db: BusinessDBClient,
    metadata_provider: MetadataProvider | None = None,
):
    self.kb_root = Path(kb_root)
    self.runtime_engine = runtime_engine
    self.business_db = business_db
    self.metadata_provider = metadata_provider
```

Data layer call becomes:

```python
r3 = data_check(self.kb_root, self.business_db, hospital_id, rule_id)
```

- [ ] **Step 5: Run focused tests**

```powershell
python -B -m unittest tests.test_diagnose_agent -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/diagnose/data_check.py app/diagnose/agent.py tests/test_diagnose_agent.py
git commit -m "feat: 诊断数据质量检查改用业务库 MCP"
```

---

### Task 5: FastAPI 注入 MCP、Trace API 和依赖健康检查

**Files:**
- Modify: `app/api/main.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Produces: `create_business_db_client(db_name: str = "hospital_demo_data") -> BusinessDBClient`
- Produces: `create_dbhub_metadata_provider(db_name: str = "hospital_demo_data") -> DBHubMetadataProvider`
- Produces: `GET /api/traces/{trace_id}`
- Produces: `GET /api/health/dependencies`
- Changes: `/api/metadata/sync` default source should be `dbhub`

- [ ] **Step 1: Add failing API tests**

Add to `tests/test_api.py`:

```python
def test_health_dependencies_reports_dbhub_and_runtime(self):
    response = client.get("/api/health/dependencies")
    self.assertEqual(response.status_code, 200)
    data = response.json()
    self.assertIn("fastapi", data)
    self.assertIn("dbhub_http", data)
    self.assertIn("runtime_db", data)


def test_trace_api_returns_trace_nodes(self):
    from app.db.engine import create_runtime_engine
    from app.observability.trace import TraceRecorder

    recorder = TraceRecorder(create_runtime_engine())
    recorder.start_trace("TRACE_API_TEST", "session_1", "hospital_001", "测试")
    recorder.record_node("TRACE_API_TEST", "intent_detect", "llm", "success")
    recorder.finish_trace("TRACE_API_TEST", "success", "完成")

    response = client.get("/api/traces/TRACE_API_TEST")
    self.assertEqual(response.status_code, 200)
    data = response.json()
    self.assertEqual(data["trace_id"], "TRACE_API_TEST")
    self.assertEqual(data["nodes"][0]["node_name"], "intent_detect")
```

Add both methods to class `ApiTest`. If `ApiTest` already patches `create_runtime_engine()` in `setUp`, reuse that existing patch. If it does not, create the two Trace tables in the same runtime database setup block used by the metadata sync API tests.

- [ ] **Step 2: Run tests and verify failure**

```powershell
python -B -m unittest tests.test_api -v
```

Expected: FAIL because endpoints do not exist.

- [ ] **Step 3: Add factory helpers**

In `app/api/main.py`, add:

```python
from app.db_access.business_db import BusinessDBClient
```

Add:

```python
def create_dbhub_client_for_db(db_name: str) -> DBHubMCPClient:
    return DBHubMCPClient(
        endpoint=get("dbhub_mcp_url", "http://127.0.0.1:8080/mcp"),
        execute_tool=_dbhub_execute_tool_for_db(db_name),
        timeout_seconds=int(get("dbhub_timeout_seconds", "10")),
        source_id=_dbhub_source_id_for_db(db_name),
    )


def create_business_db_client(db_name: str = "hospital_demo_data") -> BusinessDBClient:
    client = create_dbhub_client_for_db(db_name)
    return BusinessDBClient(
        client.execute_sql,
        source_id=_dbhub_source_id_for_db(db_name),
        tool_name=_dbhub_execute_tool_for_db(db_name),
    )


def create_dbhub_metadata_provider(db_name: str = "hospital_demo_data") -> DBHubMetadataProvider:
    client = create_dbhub_client_for_db(db_name)
    return DBHubMetadataProvider(client.execute_sql)
```

- [ ] **Step 4: Wire SQL and diagnose endpoints**

In `/api/sql/generate`, construct:

```python
agent = SQLGenerationAgent(
    kb_root=DEFAULT_KB_ROOT,
    runtime_engine=create_runtime_engine(),
    business_db=create_business_db_client("hospital_demo_data"),
)
```

In `/api/diagnose/run`, construct:

```python
agent = DiagnoseAgent(
    kb_root=DEFAULT_KB_ROOT,
    runtime_engine=create_runtime_engine(),
    business_db=create_business_db_client("hospital_demo_data"),
    metadata_provider=create_dbhub_metadata_provider("hospital_demo_data"),
)
```

- [ ] **Step 5: Add Trace APIs**

Add:

```python
@app.get("/api/traces/{trace_id}")
def get_trace(trace_id: str) -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    from app.observability.trace import TraceRecorder
    return TraceRecorder(create_runtime_engine()).get_trace(trace_id)
```

Add dependency health:

```python
@app.get("/api/health/dependencies")
def health_dependencies() -> dict[str, Any]:
    from app.db.engine import create_runtime_engine
    result: dict[str, Any] = {
        "fastapi": {"ok": True},
        "langgraph": {"ok": langgraph_installed(), "engine": workflow_engine_name()},
    }
    try:
        with create_runtime_engine().connect() as conn:
            conn.execute(text("SELECT 1"))
        result["runtime_db"] = {"ok": True}
    except Exception as exc:
        result["runtime_db"] = {"ok": False, "error": str(exc)}
    try:
        result["business_db_mcp"] = create_business_db_client("hospital_demo_data").check_available()
    except Exception as exc:
        result["business_db_mcp"] = {"ok": False, "error": str(exc)}
    try:
        sources = dbhub_sources()
        result["dbhub_http"] = {"ok": True, "source_count": len(sources.get("sources", []))}
    except Exception as exc:
        result["dbhub_http"] = {"ok": False, "error": str(exc)}
    return result
```

Also import `text`:

```python
from sqlalchemy import text
```

- [ ] **Step 6: Run focused tests**

```powershell
python -B -m unittest tests.test_api -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/api/main.py tests/test_api.py
git commit -m "feat: 接入业务库 MCP 工厂与 Trace API"
```

---

### Task 6: 聊天链路写 Trace 并返回 trace_id

**Files:**
- Modify: `app/agent/graph.py`
- Modify: `tests/test_agent_workflow.py`

**Interfaces:**
- Consumes: `TraceRecorder`
- Produces: `run_chat(...)` result includes `trace_id`
- Produces: `run_chat_stream(...)` meta event includes `trace_id`

- [ ] **Step 1: Add failing workflow test**

Add to `tests/test_agent_workflow.py`:

```python
def test_run_chat_returns_trace_id_and_records_nodes(self):
    result = run_chat("急会诊及时到位率怎么算？", hospital_id="hospital_001", use_llm=False)
    self.assertIn("trace_id", result)
    self.assertTrue(result["trace_id"].startswith("TRACE_"))
```

Add the test to the existing `AgentWorkflowTest` class and follow its current patching style for temporary knowledge base and memory roots. The assertion is only about the returned `trace_id`, so it should not introduce new fixture files.

- [ ] **Step 2: Run test and verify failure**

```powershell
python -B -m unittest tests.test_agent_workflow -v
```

Expected: FAIL because `trace_id` is missing.

- [ ] **Step 3: Add trace lifecycle to run_chat**

In `app/agent/graph.py`, import:

```python
import uuid
from app.db.engine import create_runtime_engine
from app.observability.trace import TraceRecorder
```

At the start of `run_chat` and `run_chat_stream`, create:

```python
trace_id = f"TRACE_{uuid.uuid4().hex[:12]}"
trace_recorder = TraceRecorder(create_runtime_engine())
trace_recorder.start_trace(trace_id, active_session_id, hospital_id, query)
```

At key branches, record minimal nodes:

```python
trace_recorder.record_node(trace_id, "intent_detect", "llm_or_rule", "success", input_summary=query, output_summary=state.get("intent", ""))
trace_recorder.record_node(trace_id, "rule_search", "kb_tool", "success", input_summary=search_query, output_summary=rule_id or "")
trace_recorder.record_node(trace_id, "final_response", "agent", "success", output_summary=answer[:500], rule_id=rule_id or "")
```

When exceptions are caught, record:

```python
trace_recorder.record_node(trace_id, "final_response", "agent", "fallback", error_code=type(exc).__name__, error_message=str(exc))
```

Always call:

```python
trace_recorder.finish_trace(trace_id, "success", answer[:500], intent=state.get("intent", ""), error_count=len(errors), fallback_count=sum(1 for e in errors if "fallback" in str(e).lower()))
```

Add `trace_id` to final dict and SSE meta:

```python
result["trace_id"] = trace_id
yield {"event": "meta", "data": {"session_id": active_session_id, "trace_id": trace_id}}
```

- [ ] **Step 4: Run focused workflow tests**

```powershell
python -B -m unittest tests.test_agent_workflow -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/agent/graph.py tests/test_agent_workflow.py
git commit -m "feat: 聊天链路记录 Trace"
```

---

### Task 7: 前端查看链路与最终验证

**Files:**
- Modify: `web/index.html`
- Test: manual browser verification

**Interfaces:**
- Consumes: `GET /api/traces/{trace_id}`
- Consumes: chat stream `meta.trace_id`
- Produces: Frontend trace modal

- [ ] **Step 1: Add front-end state**

In `web/index.html`, add:

```javascript
var latestTraceId = "";
```

When stream meta event arrives:

```javascript
if (parsed.event === "meta" && parsed.data.trace_id) {
  latestTraceId = parsed.data.trace_id;
  ass.traceId = latestTraceId;
}
```

- [ ] **Step 2: Add Trace modal HTML**

Add after MCP modal:

```html
<div id="traceModal" class="modal" hidden>
  <section class="dialog" role="dialog" aria-modal="true">
    <header>
      <div><h2>执行链路</h2><div id="traceMeta" style="color:#5b677a;font-size:14px;"></div></div>
      <button class="ghost" data-close="traceModal">关闭</button>
    </header>
    <div class="dialog-body">
      <div id="traceNodeList" class="review-list"></div>
    </div>
  </section>
</div>
```

- [ ] **Step 3: Add trace button on agent messages**

When final meta includes `trace_id`, append a button:

```javascript
function attachTraceButton(message, traceId) {
  if (!traceId || !message || message.traceButtonAttached) return;
  var btn = document.createElement("button");
  btn.className = "ghost";
  btn.type = "button";
  btn.textContent = "查看链路";
  btn.addEventListener("click", function() { showTrace(traceId); });
  message.body.appendChild(document.createElement("br"));
  message.body.appendChild(btn);
  message.traceButtonAttached = true;
}
```

Call it when stream finishes:

```javascript
attachTraceButton(ass, ass.traceId || latestTraceId);
```

- [ ] **Step 4: Add showTrace function**

```javascript
async function showTrace(traceId) {
  traceModal.hidden = false;
  traceMeta.textContent = traceId;
  traceNodeList.innerHTML = "正在加载链路...";
  try {
    var resp = await fetch("/api/traces/" + encodeURIComponent(traceId));
    var data = await resp.json();
    if (!resp.ok) throw new Error(data.detail || "链路加载失败");
    traceNodeList.innerHTML = "";
    (data.nodes || []).forEach(function(node) {
      var box = document.createElement("div");
      box.className = "review-item";
      box.innerHTML =
        "<strong>" + node.node_name + " · " + node.status + "</strong>" +
        "<div class='review-meta'>类型：" + (node.node_type || "") + " 耗时：" + (node.duration_ms || 0) + "ms</div>" +
        "<div>输入：" + (node.input_summary || "无") + "</div>" +
        "<div>输出：" + (node.output_summary || "无") + "</div>" +
        (node.error_message ? "<div style='color:#a64536;'>错误：" + node.error_message + "</div>" : "");
      traceNodeList.appendChild(box);
    });
    if (!(data.nodes || []).length) traceNodeList.textContent = "暂无节点记录。";
  } catch (e) {
    traceNodeList.textContent = "链路加载失败：" + e.message;
  }
}
```

- [ ] **Step 5: Manual browser verification**

Run services:

```powershell
cd F:\A-wiki-project
python -B -m uvicorn app.api.main:app --host 127.0.0.1 --port 8765
```

Ensure DBHub sidecar is running:

```powershell
cd F:\A-wiki-project\tools\dbhub
.\start-dbhub.ps1
```

In browser:

1. Open `http://127.0.0.1:8765`.
2. Login with default user.
3. Ask `急会诊及时到位率怎么算？`.
4. Confirm answer appears.
5. Click `查看链路`.
6. Confirm modal shows at least `intent_detect` and `final_response`.
7. Open MCP panel.
8. Confirm DBHub sources still show 2 sources.
9. Sync `hospital_demo_data`.
10. Confirm result shows 2 tables and 17 columns.

- [ ] **Step 6: Full test suite**

```powershell
python -B -m unittest discover -s tests -v
python -B -m py_compile app\api\main.py app\agent\graph.py app\sqlgen\agent.py app\sqlgen\runner.py app\diagnose\agent.py app\diagnose\data_check.py app\db_access\business_db.py app\observability\trace.py
```

Expected: all tests PASS and py_compile exits 0.

- [ ] **Step 7: Commit**

```powershell
git add web/index.html
git commit -m "feat: 前端展示 Agent 执行链路"
```

---

## Final Verification

After all tasks:

- [ ] Search for forbidden business DB direct usage:

```powershell
rg "business_engine\.connect|create_business_engine\(\)" app tests -n
```

Expected: no production business-query path uses `business_engine.connect`; remaining `create_business_engine()` usage is absent from SQL generation and diagnosis paths. If tests still use it to build fixtures, that is acceptable.

- [ ] Verify DBHub remains running:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/sources | ConvertTo-Json -Depth 4
```

Expected: 2 sources.

- [ ] Verify dependency health:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/api/health/dependencies | ConvertTo-Json -Depth 6
```

Expected: `fastapi.ok=true`, `runtime_db.ok=true`, `business_db_mcp.ok=true`.

- [ ] Verify metadata sync via page or API:

```powershell
$body = @{ hospital_id = "hospital_001"; db_name = "hospital_demo_data"; source = "dbhub" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/metadata/sync -Method Post -ContentType "application/json" -Body $body
```

Expected: `metadata_source=dbhub`, `table_count=2`, `column_count=17`.

- [ ] Verify trace lookup:

Use a real `trace_id` returned by chat, then run:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/api/traces/TRACE_xxxxxxxxxxxx | ConvertTo-Json -Depth 8
```

Expected: response contains `nodes`.

