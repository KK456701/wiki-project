# Workflow Manifest Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Dify-lite 工作流 manifest，并让执行链路展示节点职责、期望入参/出参、本次实际入参/出参和故障定位建议。

**Architecture:** Manifest 使用 YAML 保存设计时节点说明，`TraceRecorder` 继续负责运行时记录。Trace API 合并运行库节点、JSONL 结构化数据和 manifest 元数据后返回前端，前端按节点详情展示。

**Tech Stack:** Python、PyYAML、FastAPI、SQLAlchemy、原生 HTML/CSS/JavaScript、unittest。

## Global Constraints

- 默认中文文档、中文注释和中文用户可见文案。
- 不做拖拽编排器。
- 不让前端修改 manifest。
- 不把 manifest 作为真正执行引擎。
- 不强制迁移已有运行库表结构。
- 保持老 Trace 可读取。

---

## File Structure

- Create: `app/workflows/__init__.py`
  导出工作流 manifest 加载能力。

- Create: `app/workflows/manifest.py`
  读取 YAML manifest，按节点 ID 返回节点元数据。

- Create: `app/workflows/core_indicator_chat.yaml`
  描述核心制度指标问答工作流节点和边。

- Modify: `app/observability/trace.py`
  `record_node()` 增加结构化 `input_data`、`output_data`、`config_data`；`get_trace()` 合并 manifest 元数据和 JSONL 结构化数据。

- Modify: `app/agent/graph.py`
  聊天主链路写入更清楚的结构化节点入参/出参。

- Modify: `web/index.html`
  Trace 弹窗改为节点详情展示。

- Test: `tests/test_workflow_manifest.py`
  验证 manifest 加载和节点元数据。

- Modify: `tests/test_observability_trace.py`
  验证结构化入参/出参与 manifest 注解。

- Modify: `tests/test_agent_workflow.py`
  验证聊天 Trace 节点包含可读元数据。

---

### Task 1: 工作流 Manifest 加载器

**Files:**
- Create: `app/workflows/__init__.py`
- Create: `app/workflows/manifest.py`
- Create: `app/workflows/core_indicator_chat.yaml`
- Test: `tests/test_workflow_manifest.py`

**Interfaces:**
- Produces: `load_workflow_manifest(workflow_id: str = "core_indicator_chat") -> dict[str, Any]`
- Produces: `get_workflow_node(workflow_id: str, node_id: str) -> dict[str, Any]`
- Produces: `annotate_trace_node(node: dict[str, Any], workflow_id: str = "core_indicator_chat") -> dict[str, Any]`

- [ ] **Step 1: Write failing tests**

Create `tests/test_workflow_manifest.py`:

```python
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
```

- [ ] **Step 2: Run failing test**

Run:

```powershell
python -B -m unittest tests.test_workflow_manifest -v
```

Expected: FAIL because `app.workflows` does not exist.

- [ ] **Step 3: Implement manifest loader**

Create `app/workflows/manifest.py` with YAML loading, in-memory cache, unknown-node fallback, and `annotate_trace_node()`.

- [ ] **Step 4: Add manifest YAML**

Create `app/workflows/core_indicator_chat.yaml` with nodes:

- `request_received`
- `memory_load`
- `intent_detect`
- `rule_search`
- `effective_rule_resolve`
- `feedback_preview`
- `sql_generate`
- `sql_validate`
- `sql_trial_mcp`
- `diagnose_structure_mcp`
- `diagnose_rule_check`
- `diagnose_data_check_mcp`
- `metadata_sync_mcp`
- `llm_answer`
- `fact_guard`
- `final_response`

- [ ] **Step 5: Verify**

Run:

```powershell
python -B -m unittest tests.test_workflow_manifest -v
```

Expected: PASS.

---

### Task 2: TraceRecorder 结构化入参/出参与 Manifest 注解

**Files:**
- Modify: `app/observability/trace.py`
- Modify: `tests/test_observability_trace.py`

**Interfaces:**
- Changes: `TraceRecorder.record_node(..., input_data: dict[str, Any] | None = None, output_data: dict[str, Any] | None = None, config_data: dict[str, Any] | None = None) -> None`
- Changes: `TraceRecorder.get_trace(trace_id: str) -> dict[str, Any]`

- [ ] **Step 1: Add failing test**

Add to `TraceRecorderTest`:

```python
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
```

- [ ] **Step 2: Run failing test**

Run:

```powershell
python -B -m unittest tests.test_observability_trace -v
```

Expected: FAIL because `record_node()` does not accept structured data.

- [ ] **Step 3: Update TraceRecorder**

Update JSONL events to include `input_data`、`output_data`、`config_data`。DB 写入仍使用 existing summary columns。`get_trace()` reads JSONL by `node_id` and merges structured data into DB nodes, then calls `annotate_trace_node()`.

- [ ] **Step 4: Verify**

Run:

```powershell
python -B -m unittest tests.test_observability_trace -v
```

Expected: PASS.

---

### Task 3: 聊天链路写入结构化节点数据

**Files:**
- Modify: `app/agent/graph.py`
- Modify: `tests/test_agent_workflow.py`

**Interfaces:**
- Consumes: `TraceRecorder.record_node(..., input_data=..., output_data=..., config_data=...)`

- [ ] **Step 1: Add focused test**

Add to `AgentWorkflowTest`:

```python
    def test_run_chat_trace_nodes_have_manifest_metadata(self) -> None:
        with temp_kb_dir() as tmp:
            root = Path(tmp)
            make_minimal_kb(root)
            engine = _trace_runtime_engine()
            with patch("app.agent.graph.create_runtime_engine", return_value=engine), \
                 patch("app.agent.graph.DEFAULT_MEMORY_ROOT", Path(tmp) / "memory"):
                result = run_chat("急会诊及时到位率怎么算？", hospital_id="hospital_001", kb_root=root)

            trace = TraceRecorder(engine).get_trace(result["trace_id"])
            titles = [node.get("node_title") for node in trace["nodes"]]
            self.assertIn("识别用户意图", titles)
            self.assertIn("检索指标规则", titles)
            self.assertIn("生成最终回答", titles)
```

- [ ] **Step 2: Run focused test**

Run:

```powershell
python -B -m unittest tests.test_agent_workflow -v
```

Expected: FAIL until Trace annotation is wired.

- [ ] **Step 3: Add structured trace data**

In `app/agent/graph.py`, update `intent_detect`、`rule_search`、`final_response` record calls to pass structured data:

```python
input_data={"query": query, "session_memory": memory_context}
output_data={"intent": result.get("intent"), "retrieval_query": result.get("search", {}).get("query", query)}
config_data={"strategy": "规则兜底 + 可选 LLM 意图识别"}
```

For rule search:

```python
input_data={"retrieval_query": search_query, "hospital_id": hospital_id}
output_data={"rule_id": rule_id, "matched_count": len(search.get("results", []))}
config_data={"tool": "KnowledgeBaseTools.search", "priority": "医院口径 > 公司标准 > 国标"}
```

For final response:

```python
input_data={"intent": state.get("intent"), "rule_id": rule_id, "generation_method": generation_method}
output_data={"answer_preview": answer[:1000], "errors": errors}
```

- [ ] **Step 4: Verify**

Run:

```powershell
python -B -m unittest tests.test_agent_workflow -v
```

Expected: PASS.

---

### Task 4: 前端 Trace 节点详情视图

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: `GET /api/traces/{trace_id}` response nodes with `node_title`、`description`、`expected_inputs`、`expected_outputs`、`input_data`、`output_data`、`config_data`、`failure_hint`

- [ ] **Step 1: Add JSON formatter**

Add:

```javascript
function formatTraceValue(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback || "无";
  if (typeof value === "object") return JSON.stringify(value, null, 2);
  return String(value);
}
```

- [ ] **Step 2: Add section renderer**

Add:

```javascript
function appendTraceSection(parent, title, value, fallback) {
  var section = document.createElement("div");
  section.className = "trace-section";
  var heading = document.createElement("div");
  heading.className = "trace-section-title";
  heading.textContent = title;
  var body = document.createElement("pre");
  body.className = "trace-json";
  body.textContent = formatTraceValue(value, fallback);
  section.append(heading, body);
  parent.appendChild(section);
}
```

- [ ] **Step 3: Replace node card rendering**

Change `showTrace()` to display:

- `node.node_title || node.node_name`
- status
- type and duration
- description
- 本次入参
- 本次出参
- 节点配置
- 期望入参/期望出参
- failure_hint
- error_message

- [ ] **Step 4: Manual verification**

Open `http://127.0.0.1:8765`，问“急会诊及时到位率怎么算？”，点击“查看链路”。Expected: 节点卡片显示中文标题和 JSON 入参出参。

---

### Task 5: Full Verification

**Files:**
- No direct code change.

- [ ] **Step 1: Run focused tests**

```powershell
python -B -m unittest tests.test_workflow_manifest tests.test_observability_trace tests.test_agent_workflow -v
```

Expected: PASS.

- [ ] **Step 2: Run full tests**

```powershell
python -B -m unittest discover -s tests -v
```

Expected: PASS.

- [ ] **Step 3: Check staged and untracked files**

```powershell
git status --short --untracked-files=all
```

Expected: New manifest/spec/plan/test files are visible; ignored local files remain ignored.
