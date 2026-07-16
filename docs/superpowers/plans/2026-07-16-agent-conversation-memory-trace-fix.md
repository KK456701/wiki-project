# Agent Conversation Memory and Trace Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复工具调用型 Agent 的跨轮会话、重复工具调用、Trace 查看、公式控制符和无证据方向推断问题。

**Architecture:** 复用现有 `ConversationMemory` 与 `ConversationContext`，新增一个仅负责 Agent 安全状态装载/保存的适配层；`AgentRuntimeService` 在每次运行前后调用该适配层。工具 Gateway 只缓存本轮安全 `ToolResult`，Trace 使用稳定节点 manifest 和登录态 Agent Run API，回答在证据校验前执行最小 LaTeX 规范化。

**Tech Stack:** Python 3.12、FastAPI、Pydantic v2、SQLite + JSONL、PyYAML、Ollama tools、原生 JavaScript、pytest、Node.js 静态 UI 测试。

## Global Constraints

- 不保存或展示思维链、患者明细、连接串、Token、密码、SQL 正文或原始敏感工具结果。
- 同一公开 `session_id` 必须按 `hospital_id + user_id` 隔离。
- 保存全部消息原文；模型提示仅使用最近 8 轮，并继续受 `ollama_prompt_budget_tokens` 限制。
- 结构化状态优先于历史文本；生效规则每轮重新读取，不能把历史公式当作当前事实。
- SQL、运行、诊断和草稿对象继续由领域存储校验医院、用户、会话与 TTL。
- 不删除旧聊天，不新增正式提交、审批、发布或回退写操作。
- 所有生产代码必须先有可复现失败测试；每个任务独立提交并推送。
- 用户已指定当前会话内联执行，不使用子智能体。

---

## File Map

- Create `app/agent_runtime/memory.py`: Agent 专用会话键、历史提示、结构化状态和安全对象引用装载/保存。
- Modify `app/agent_runtime/service.py`: 在 chat/stream 生命周期接入会话记忆，并把记忆故障写入 Trace。
- Modify `app/agent_runtime/contracts.py`: 增加仅本轮使用的工具结果缓存，不持久化该字段。
- Modify `app/agent_runtime/prompts.py`: 构建带当前日期、结构化摘要、最近历史和纯文本公式约束的系统提示。
- Modify `app/agent_runtime/response_guard.py`: 最小 LaTeX 到普通文本规范化。
- Modify `app/agent_runtime/runner.py`: 使用规范化回答并保留装载后的历史状态。
- Modify `app/agent_tools/read_tools.py`: 成功读取生效规则后设置当前规则 ID。
- Modify `app/agent_tools/gateway.py`: 相同参数只执行一次，复用本轮缓存并记录重复事件。
- Modify `app/agent_runtime/tracing.py`: 使用稳定节点 ID，并记录会话记忆故障。
- Create `app/workflows/agent_runtime.yaml`: 新 Agent 模型、工具调用、工具结果和记忆故障节点说明。
- Modify `app/workflows/manifest.py`: manifest 缺失时返回空 manifest，Trace 不再 HTTP 500。
- Modify `web/index.html`: Agent 回答改用登录态 `/api/agent/runs/{trace_id}`。
- Modify `web/chat-markdown.js`: 将独立 `---` 渲染为 `<hr>`。
- Add/modify focused tests under `tests/` for memory, loop, API, Trace and frontend behavior.

---

### Task 1: Agent 安全会话记忆

**Files:**
- Create: `app/agent_runtime/memory.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/agent_runtime/prompts.py`
- Modify: `app/agent_tools/read_tools.py`
- Create: `tests/test_agent_conversation_memory.py`
- Modify: `tests/test_agent_execution_loop.py`
- Modify: `tests/test_agent_api.py`
- Modify: `tests/test_agent_sse_api.py`

**Interfaces:**
- Produces: `AgentConversationMemory.open(context) -> AgentMemorySession`
- Produces: `AgentMemorySession.append_user(query) -> None`
- Produces: `AgentMemorySession.complete(query, answer, state) -> None`
- Produces: `build_agent_system_prompt(structured_summary, recent_history, now) -> str`
- Consumes: `ConversationMemory`, `ConversationContext`, `build_prompt_context`, `AgentRunState`

- [ ] **Step 1: Write failing memory isolation and eight-turn tests**

```python
def test_memory_restores_rule_and_only_prompts_last_eight_turns(tmp_path):
    store = ConversationMemory(tmp_path)
    memory = AgentConversationMemory(store=store, max_turns=8)
    context = _context(user_id="u1", hospital_id="h1", session_id="s1")
    first = memory.open(context)
    for index in range(9):
        first.append_user(f"问题{index}")
        first.complete(f"问题{index}", f"回答{index}", _state(rule_id="MQSI2025_005"))
    restored = memory.open(context)
    assert restored.state.current_rule_id == "MQSI2025_005"
    assert "问题0" not in restored.state.messages[0]["content"]
    assert "问题1" in restored.state.messages[0]["content"]
    assert len(store.recent_messages(restored.storage_session_id, 100)) == 18

def test_same_public_session_is_isolated_by_hospital_and_user(tmp_path):
    memory = AgentConversationMemory(store=ConversationMemory(tmp_path))
    a = memory.open(_context(user_id="u1", hospital_id="h1", session_id="same"))
    b = memory.open(_context(user_id="u2", hospital_id="h1", session_id="same"))
    c = memory.open(_context(user_id="u1", hospital_id="h2", session_id="same"))
    assert len({a.storage_session_id, b.storage_session_id, c.storage_session_id}) == 3
```

- [ ] **Step 2: Run tests and verify RED**

Run: `python -m pytest tests/test_agent_conversation_memory.py -q`

Expected: collection/import failure because `app.agent_runtime.memory` does not exist.

- [ ] **Step 3: Implement the minimal memory adapter**

Implement `AgentMemorySession` and `AgentConversationMemory` with these exact rules:

```python
storage_session_id = f"agent:{context.hospital_id}:{context.user_id}:{context.session_id}"
prompt_context = build_prompt_context(
    store.recent_messages(storage_session_id, limit=max_turns * 2),
    structured_context,
    max_turns=max_turns,
    token_budget=token_budget,
)
state = AgentRunState(
    messages=[{"role": "system", "content": build_agent_system_prompt(...)}],
    current_rule_id=structured_context.active_rule.rule_id or None,
)
```

If a current rule exists, restore only this evidence:

```python
state.evidence.append({
    "source": "conversation_memory",
    "source_id": state.current_rule_id,
    "fact_types": ["rule_identity"],
})
```

Restore only `validated_sql_ids`, `last_run_id`, `last_diagnosis_id`, and `last_draft_id` from the newest assistant metadata. Save those fields back under an `agent_state` metadata object; do not save `messages`, `last_tool_results`, `evidence`, `tool_call_counts`, or `tool_result_cache`.

On completion, update `ConversationContext.active_rule` from `state.current_rule_id`, rule name from the newest successful effective-rule result, and `stat_period` from the newest successful tool result containing `stat_start` and `stat_end`. Save with `expected_version=context.context_version`.

- [ ] **Step 4: Make effective-rule reads set the active rule**

After a successful `get_effective_rule`, add:

```python
state.current_rule_id = arguments.rule_id
```

Do not set it on not-found or failed results.

- [ ] **Step 5: Wire memory into AgentRuntimeService and verify RED becomes GREEN**

Add optional constructor dependency:

```python
memory_factory: Callable[[], AgentConversationMemory] | None = None
history_turns: int = 8
```

For both `chat` and `stream`:

1. open memory after creating `AgentRuntimeContext`;
2. append the user message before starting the runner;
3. pass `memory_session.state` to `runner.run`;
4. on terminal event, call `complete` once before finishing the Trace;
5. convert read failure to `AgentRuntimeUnavailable("会话状态暂不可用，请稍后重试。")`;
6. record save failure through the Trace bridge without replacing a valid answer.

Run: `python -m pytest tests/test_agent_conversation_memory.py tests/test_agent_api.py tests/test_agent_sse_api.py tests/test_agent_execution_loop.py -q`

Expected: all focused memory and service tests pass.

- [ ] **Step 6: Commit and push Task 1**

```powershell
git add app/agent_runtime/memory.py app/agent_runtime/service.py app/agent_runtime/prompts.py app/agent_tools/read_tools.py tests/test_agent_conversation_memory.py tests/test_agent_api.py tests/test_agent_sse_api.py tests/test_agent_execution_loop.py
git commit -m "fix: 恢复 Agent 八轮会话上下文"
git push
```

---

### Task 2: 重复调用复用与回答格式约束

**Files:**
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_tools/gateway.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/agent_runtime/prompts.py`
- Modify: `app/agent_runtime/response_guard.py`
- Modify: `web/chat-markdown.js`
- Modify: `tests/test_agent_tool_gateway.py`
- Modify: `tests/test_agent_execution_loop.py`
- Modify: `tests/test_chat_markdown_ui.py`

**Interfaces:**
- Produces: `normalize_agent_answer(text: str) -> str`
- Adds: `AgentRunState.tool_result_cache: dict[str, dict[str, Any]]`
- Consumes: `tool_call_fingerprint(tool_name, arguments)`

- [ ] **Step 1: Write failing duplicate-result reuse tests**

```python
async def test_duplicate_tool_call_reuses_first_result_without_reexecution():
    calls = 0
    events = []
    gateway = _gateway(handler=_counting_handler, trace_callback=events.append)
    first = await gateway.execute("search_indicator_rules", {"query": "急会诊"}, context, state)
    second = await gateway.execute("search_indicator_rules", {"query": "急会诊"}, context, state)
    assert calls == 1
    assert second.model_dump(mode="json") == first.model_dump(mode="json")
    assert any(event.get("reused") is True for event in events)
```

Also assert a third identical call returns `AGENT_REPEATED_TOOL_CALL`, emits a `tool_result` event, and does not call the handler.

- [ ] **Step 2: Run duplicate tests and verify RED**

Run: `python -m pytest tests/test_agent_tool_gateway.py -q`

Expected: duplicate call currently returns `AGENT_REPEATED_TOOL_CALL` instead of the cached first result.

- [ ] **Step 3: Implement current-run ToolResult cache**

Add `tool_result_cache` to `AgentRunState`. In Gateway:

```python
fingerprint = tool_call_fingerprint(tool_name, raw_arguments)
if decision is RepeatDecision.DUPLICATE and fingerprint in state.tool_result_cache:
    reused = ToolResult.model_validate(state.tool_result_cache[fingerprint])
    emit tool_result with reused=True and reused.model_dump(...)
    return reused
```

After a real execution, save only `result.model_dump(mode="json")` into the in-memory cache. On STOP, emit a redacted `tool_result` containing `AGENT_REPEATED_TOOL_CALL` before returning. Never persist this cache through `AgentConversationMemory`.

- [ ] **Step 4: Write failing LaTeX normalization tests**

```python
def test_normalize_agent_answer_converts_simple_latex_formula():
    raw = "$$\n\\frac{\\text{及时次数}}{\\text{总次数}} \\times 100\\%\n$$"
    assert normalize_agent_answer(raw) == "及时次数 ÷ 总次数 × 100%"

def test_normalize_agent_answer_keeps_plain_markdown():
    raw = "### 公式\n急会诊及时到位率 = 及时次数 ÷ 总次数 × 100%"
    assert normalize_agent_answer(raw) == raw
```

- [ ] **Step 5: Run normalization tests and verify RED**

Run: `python -m pytest tests/test_agent_execution_loop.py -q`

Expected: import or assertion failure because normalization is not implemented.

- [ ] **Step 6: Implement minimal normalization and prompt guard**

`normalize_agent_answer` performs only these substitutions:

```python
text = re.sub(r"(?m)^\s*(?:\$\$|\\\[|\\\])\s*$", "", text)
text = re.sub(r"\\text\{([^{}]*)\}", r"\1", text)
text = re.sub(r"\\frac\{([^{}]*)\}\{([^{}]*)\}", r"\1 ÷ \2", text)
text = text.replace(r"\times", "×").replace(r"\%", "%")
```

Normalize a no-tool model response before Chinese/evidence checks and before returning it. Extend the system prompt with plain formula format and this rule: without deterministic comparison or trial evidence, say only “口径不同，结果不可直接比较”.

- [ ] **Step 7: Add `<hr>` renderer test and implementation**

Node assertion:

```javascript
const html = renderer.renderAssistantMarkdown('第一段\n\n---\n\n第二段');
assert(html.includes('<hr>'));
assert(!html.includes('<p>---</p>'));
```

In `renderAssistantMarkdown`, handle `/^\s*---+\s*$/` before generic paragraph rendering.

Run: `python -m pytest tests/test_agent_tool_gateway.py tests/test_agent_execution_loop.py tests/test_chat_markdown_ui.py -q`

Expected: all duplicate, answer and renderer tests pass.

- [ ] **Step 8: Commit and push Task 2**

```powershell
git add app/agent_runtime/contracts.py app/agent_tools/gateway.py app/agent_runtime/runner.py app/agent_runtime/prompts.py app/agent_runtime/response_guard.py web/chat-markdown.js tests/test_agent_tool_gateway.py tests/test_agent_execution_loop.py tests/test_chat_markdown_ui.py
git commit -m "fix: 稳定 Agent 工具循环与公式输出"
git push
```

---

### Task 3: Agent Trace 登录态查看

**Files:**
- Create: `app/workflows/agent_runtime.yaml`
- Modify: `app/agent_runtime/tracing.py`
- Modify: `app/workflows/manifest.py`
- Modify: `web/index.html`
- Modify: `tests/test_agent_trace_bridge.py`
- Modify: `tests/test_agent_api.py`
- Modify: `tests/test_agent_frontend_ui.py`
- Create: `tests/test_agent_trace_manifest.py`

**Interfaces:**
- Produces stable trace node names: `agent_model`, `agent_tool_call`, `agent_tool_result`, `agent_memory`
- Consumes existing authenticated endpoint: `GET /api/agent/runs/{trace_id}`

- [ ] **Step 1: Write failing Trace API and manifest tests**

```python
def test_agent_runtime_trace_manifest_is_valid():
    result = validate_workflow_manifest("agent_runtime")
    assert result["ok"] is True
    assert result["node_count"] == 4

def test_missing_manifest_degrades_to_unregistered_node(tmp_path, monkeypatch):
    node = annotate_trace_node({"node_name": "unknown", "status": "success"}, "missing")
    assert node["contract_status"] == "warning"
```

Add an API regression using a real/fake recorder proving an `agent_runtime` run returns HTTP 200 and another hospital still receives 403 from `/api/agent/runs/{trace_id}`.

- [ ] **Step 2: Run Trace tests and verify RED**

Run: `python -m pytest tests/test_agent_trace_bridge.py tests/test_agent_trace_manifest.py tests/test_agent_api.py -q`

Expected: missing manifest raises `FileNotFoundError` or manifest validation fails.

- [ ] **Step 3: Stabilize Trace node IDs and add manifest**

Map events in `AgentTraceBridge.handle`:

```python
node_name = {
    "model_start": "agent_model",
    "tool_call": "agent_tool_call",
    "tool_result": "agent_tool_result",
}[event_name]
```

Add `record_memory_failure(message)` producing `agent_memory` with problem code `AGENT_MEMORY_SAVE_FAILED`. The manifest defines Chinese titles, inputs/outputs, failure policy and `human_interaction` owner for the four nodes.

In `load_workflow_manifest`, when the YAML path does not exist return an empty manifest with the requested workflow ID; `get_workflow_node` then returns its existing unregistered-node metadata.

- [ ] **Step 4: Write failing frontend authenticated Trace test**

Assert `web/index.html` contains:

```javascript
showTrace(traceId, {agentRun: true})
fetch('/api/agent/runs/' + encodeURIComponent(traceId), {
  headers: {Authorization: 'Bearer ' + hospitalAuthToken}
})
```

and retains the legacy `/api/traces/` path for old answers.

- [ ] **Step 5: Implement endpoint selection in the existing Trace renderer**

Change signatures without duplicating rendering code:

```javascript
function attachTraceButton(message, traceId, options) { ... }
async function showTrace(traceId, options) {
  var agentRun = options && options.agentRun;
  var url = agentRun ? "/api/agent/runs/" + encodeURIComponent(traceId)
                     : "/api/traces/" + encodeURIComponent(traceId);
  var fetchOptions = agentRun
    ? {headers:{Authorization:"Bearer " + hospitalAuthToken}}
    : undefined;
  var resp = await fetch(url, fetchOptions);
}
```

Only `streamToolCallingChat` passes `{agentRun:true}`.

Run: `python -m pytest tests/test_agent_trace_bridge.py tests/test_agent_trace_manifest.py tests/test_agent_api.py tests/test_agent_frontend_ui.py -q`

Expected: all Trace backend, isolation and frontend tests pass.

- [ ] **Step 6: Commit and push Task 3**

```powershell
git add app/workflows/agent_runtime.yaml app/agent_runtime/tracing.py app/workflows/manifest.py web/index.html tests/test_agent_trace_bridge.py tests/test_agent_trace_manifest.py tests/test_agent_api.py tests/test_agent_frontend_ui.py
git commit -m "fix: 修复 Agent 登录态执行链路"
git push
```

---

### Task 4: 集成回归、真实双轮验收和交接

**Files:**
- Modify: `docs/PROJECT_HANDOFF_2026-07-16.md`
- Modify: `docs/operations/agent-tool-calling-rollout.md`
- Test: all Agent-focused tests and full suite

**Interfaces:**
- Consumes completed memory, loop, formula and Trace behavior from Tasks 1–3.

- [ ] **Step 1: Run all Agent-focused tests**

```powershell
python -m pytest `
  tests/test_agent_conversation_memory.py `
  tests/test_agent_execution_loop.py `
  tests/test_agent_tool_gateway.py `
  tests/test_agent_api.py `
  tests/test_agent_sse_api.py `
  tests/test_agent_trace_bridge.py `
  tests/test_agent_trace_manifest.py `
  tests/test_agent_frontend_ui.py `
  tests/test_chat_markdown_ui.py -q
```

Expected: all focused tests pass with no unexpected warning or skipped regression.

- [ ] **Step 2: Restart local FastAPI and run real browser two-turn acceptance**

With existing local `tool_calling` configuration, log in as the authorized hospital user and send in the same session:

```text
急会诊及时到位率怎么算？
我想看今年6月到现在的这个指标是多少怎么算
```

Verify from visible UI and Trace:

- mode remains “工具协作模式”;
- second turn reuses `MQSI2025_005` and does not search unrelated indicators;
- formula contains no `$$`, `\text`, `\frac`;
- actual value is only stated after SQL preparation and read-only trial evidence;
- both Trace buttons open successfully through the Agent Run endpoint;
- no horizontal overflow at 390 px.

- [ ] **Step 3: Run fixed evaluation and full regression**

```powershell
python -B scripts/run_agent_evaluation.py --reference
python -m pytest -q
git diff --check
```

Expected: evaluation exits 0, full suite exits 0, diff check is clean.

- [ ] **Step 4: Update handoff and rollout documentation**

Document exactly:

- new Agent now restores structured state plus recent 8 turns;
- internal storage key is scoped by hospital and user;
- duplicate tool results are reused only within the current run;
- Agent Trace uses the authenticated hospital-scoped endpoint;
- restarting FastAPI is required after changing Agent configuration.

- [ ] **Step 5: Commit, push, auto-merge and verify main**

```powershell
git add docs/PROJECT_HANDOFF_2026-07-16.md docs/operations/agent-tool-calling-rollout.md
git commit -m "docs: 更新 Agent 会话与链路说明"
git push
```

Then follow the user's standing integration choice: merge the feature branch into `main` with a Chinese merge commit, run the full suite on `main`, push `main`, verify `main == origin/main`, and remove only the clean local feature worktree and local feature branch. Keep the remote feature branch.
