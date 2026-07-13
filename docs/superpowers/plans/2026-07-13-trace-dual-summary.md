# 执行链路双层摘要实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将执行链路升级为默认展示业务结果和性能结论、展开后查看技术输入输出与排障信息的双层摘要界面。

**Architecture:** 继续复用现有 Trace API 与 workflow manifest。第一批校准观测契约和总耗时，第二批在现有 `showTrace()` 渲染边界内建立业务摘要与技术详情分层，第三批增加阶段时间轴和耗时占比；不改变工作流执行和 Trace 存储。

**Tech Stack:** YAML workflow manifest、原生 JavaScript/CSS、Python `unittest`、本地浏览器验收。

## Global Constraints

- 全程在当前会话执行，不启动子智能体。
- 保留“点击具体节点后才展开详情”的交互。
- 不展示患者明细、数据库密码或绑定后的 SQL。
- 总耗时只使用 Trace `duration_ms`；缺失时显示“未计时”。
- 正常节点不显示故障定位建议；异常节点必须保留问题码、错误和定位建议。
- 每批先运行失败测试，再写最小实现，验证后独立提交并推送。

---

### Task 1: 修复耗时统计与契约假告警

**Files:**
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `web/index.html`
- Modify: `tests/test_workflow_manifest.py`
- Create: `tests/test_trace_ui.py`

**Interfaces:**
- Consumes: `/api/traces/{trace_id}` 返回的顶层 `duration_ms` 和节点安全输出字段。
- Produces: `renderTraceTimingSummary(nodes, totalDurationMs)`。

- [ ] **Step 1: 写 manifest 观测契约失败测试**

```python
def test_core_trace_contract_matches_safe_runtime_payloads(self) -> None:
    search = get_workflow_node("core_indicator_chat", "rule_search")
    effective = get_workflow_node("core_indicator_chat", "effective_rule_resolve")
    final = get_workflow_node("core_indicator_chat", "final_response")
    self.assertEqual(search["outputs"], ["rule_id", "matched_count", "context_source"])
    self.assertEqual(
        effective["required_outputs"],
        ["rule_id", "effective_level", "rule_source"],
    )
    self.assertIn("answer_preview", final["outputs"])
```

- [ ] **Step 2: 运行测试确认旧 manifest 失败**

Run: `python -m unittest tests.test_workflow_manifest.WorkflowManifestTest.test_core_trace_contract_matches_safe_runtime_payloads -v`

Expected: FAIL，旧输出仍包含 `matched_documents` 或 `effective_rule`。

- [ ] **Step 3: 校准三个节点的 outputs/required_outputs**

`rule_search` 使用 `[rule_id, matched_count, context_source]`；`effective_rule_resolve` 使用 `[rule_id, effective_level, rule_source, national_version, hospital_version, overridden_fields, fallback_chain, warnings]`，必要输出为 `[rule_id, effective_level, rule_source]`；`final_response` 使用 `[answer_preview, trace_id, final_status]`。

- [ ] **Step 4: 写真实总耗时失败测试**

```python
def test_trace_summary_uses_wall_clock_duration(self) -> None:
    html = WEB_INDEX.read_text(encoding="utf-8")
    self.assertIn("function renderTraceTimingSummary(nodes, totalDurationMs)", html)
    self.assertIn("renderTraceTimingSummary(nodes, data.duration_ms)", html)
    self.assertIn('name.textContent = "执行耗时"', html)
    self.assertIn('"已记录阶段耗时："', html)
```

- [ ] **Step 5: 运行测试确认旧总览失败**

Run: `python -m unittest tests.test_trace_ui.TraceUiTest.test_trace_summary_uses_wall_clock_duration -v`

Expected: FAIL，旧函数只接收 `nodes` 并将节点求和显示为总耗时。

- [ ] **Step 6: 修改耗时总览并运行第一批测试**

总览主徽标调用 `formatTraceDuration(totalDurationMs)`；节点求和只显示为“已记录阶段耗时”；调用方传入 `data.duration_ms`。

Run: `python -m unittest tests.test_workflow_manifest tests.test_trace_ui tests.test_api.ApiTest.test_trace_modal_uses_readable_debug_labels -v`

Expected: PASS。

- [ ] **Step 7: 提交并推送第一批**

```powershell
git add app/workflows/core_indicator_chat.yaml web/index.html tests/test_workflow_manifest.py tests/test_trace_ui.py
git commit -m "fix: 修正执行链路耗时与字段检查"
git push
```

### Task 2: 重做节点摘要与技术详情层级

**Files:**
- Modify: `web/index.html`
- Modify: `tests/test_trace_ui.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Produces: `traceStatusText(status)`、`traceBusinessSummary(node)`、`traceBusinessFields(node)`、`appendTraceJsonDisclosure(parent, node)`。

- [ ] **Step 1: 写双层摘要失败测试**

```python
def test_trace_nodes_have_business_summary_and_layered_details(self) -> None:
    html = WEB_INDEX.read_text(encoding="utf-8")
    for marker in (
        "function traceStatusText", "function traceBusinessSummary",
        "function traceBusinessFields", "处理结果", "原始输入输出",
        "开发与排障", "trace-json-disclosure", "trace-node-summary",
    ):
        self.assertIn(marker, html)
    self.assertIn('node.status === "success" && node.contract_status === "ok"', html)
```

- [ ] **Step 2: 运行测试确认分层尚未实现**

Run: `python -m unittest tests.test_trace_ui.TraceUiTest.test_trace_nodes_have_business_summary_and_layered_details -v`

Expected: FAIL。

- [ ] **Step 3: 实现中文状态和五节点业务摘要**

状态映射为成功、失败、已回退、需关注、进行中、未知。摘要读取安全字段：记忆上下文、意图、命中指标、口径层级/来源/版本、最终回答状态；未知节点回退 `output_summary` 或职责描述。

- [ ] **Step 4: 重组节点 DOM**

折叠卡片显示序号、标题、业务摘要、中文状态、耗时。详情显示“处理结果”；原始输入、输出、配置放入 `<details class="trace-json-disclosure">`；Agent、节点 ID、类型、工具、数据源、必要字段与问题码放入“开发与排障”。

只有失败、回退、警告、错误信息或字段检查异常时显示缺少内容和定位建议；异常节点默认展开第一层详情，正常节点保持折叠。

- [ ] **Step 5: 运行第二批测试与现有 UI 回归**

Run: `python -m unittest tests.test_trace_ui tests.test_api.ApiTest.test_trace_modal_uses_readable_debug_labels tests.test_workbench_ui -v`

Expected: PASS。

- [ ] **Step 6: 提交并推送第二批**

```powershell
git add web/index.html tests/test_trace_ui.py tests/test_api.py
git commit -m "feat: 增加执行链路双层节点摘要"
git push
```

### Task 3: 增加阶段时间轴与耗时占比

**Files:**
- Modify: `web/index.html`
- Modify: `tests/test_trace_ui.py`
- Modify: `README.md`

**Interfaces:**
- Produces: `renderTraceTimeline(nodes, totalDurationMs)` 和 `traceDurationPercent(node, totalDurationMs)`。

- [ ] **Step 1: 写时间轴与性能提示失败测试**

```python
def test_trace_timeline_shows_duration_share_and_slowest_stage(self) -> None:
    html = WEB_INDEX.read_text(encoding="utf-8")
    for marker in (
        "function renderTraceTimeline", "function traceDurationPercent",
        "trace-timeline", "trace-timeline-item", "trace-duration-bar",
        "性能提示", "最慢",
    ):
        self.assertIn(marker, html)
    self.assertIn("@media (max-width: 760px)", html)
```

- [ ] **Step 2: 运行测试确认时间轴不存在**

Run: `python -m unittest tests.test_trace_ui.TraceUiTest.test_trace_timeline_shows_duration_share_and_slowest_stage -v`

Expected: FAIL。

- [ ] **Step 3: 实现时间轴和性能规则**

时间轴按节点顺序渲染；已计时节点显示耗时和占 Trace 总耗时百分比，未计时节点显示“未计时”。最慢节点添加标记；耗时超过总耗时 50% 或超过 3000ms 时显示性能提示。

- [ ] **Step 4: 完成响应式样式和 README**

桌面时间轴横向网格，移动端单列；进度条宽度使用计算后的百分比且限制在 0% 至 100%。README 说明默认摘要、二级 JSON、字段检查和性能提示。

- [ ] **Step 5: 运行完整回归与浏览器验收**

Run: `python -B -m unittest discover -s tests -v`

Expected: 全部 PASS。

浏览器使用“急会诊及时到位率怎么算？”验证真实总耗时、5/5 节点、意图识别最慢、本院口径无假告警、二级 JSON 折叠，以及桌面/移动无横向溢出。

- [ ] **Step 6: 提交并推送第三批**

```powershell
git add web/index.html tests/test_trace_ui.py README.md
git commit -m "feat: 增加执行链路阶段时间轴"
git push
```
