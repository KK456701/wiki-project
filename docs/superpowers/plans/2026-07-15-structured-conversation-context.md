# 结构化会话上下文 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让“按入区时间计算”等会话临时口径被可靠保存，并在 SQL 生成、试运行和后续动作中复用，同时把本地 Ollama 上下文调整为可配置的 16K。

**Architecture:** 在现有 `ConversationMemory` 上增加版本化 `session_contexts`，由独立的上下文服务把当前消息转换成经过校验的状态增量，再生成不可变执行快照。`graph.py` 只负责调用上下文服务和编排，Ollama 提示词使用结构化摘要与最近 8 轮裁剪历史，SQL 执行不直接依赖模型历史。

**Tech Stack:** Python 3、SQLite、Pydantic、Ollama HTTP API、pytest、现有 TraceRecorder。

## Global Constraints

- 会话临时口径不能直接修改 MySQL 本院正式口径。
- 关键语义有歧义或医院字段未映射时必须阻止 SQL 生成和试运行。
- 上下文优先级为：当前明确要求 > 会话临时口径 > 本院生效口径 > 公司标准 > 国标。
- 默认 `ollama_num_ctx=16384`、最近历史 8 轮、提示词预算 12000 tokens、输出预留 1200 tokens。
- 完整 SQL、患者明细和大段工具结果不得进入模型历史提示词。
- 所有新增用户可见文案使用通俗中文。

---

### Task 1: 版本化结构化会话状态存储

**Files:**
- Create: `app/memory/contracts.py`
- Modify: `app/memory/store.py`
- Test: `tests/test_conversation_context.py`

**Interfaces:**
- Produces: `ConversationContext`、`ContextOverride`、`PendingClarification` Pydantic 契约。
- Produces: `ConversationMemory.load_context(session_id) -> ConversationContext`。
- Produces: `ConversationMemory.save_context(session_id, context, expected_version) -> ConversationContext`。

- [ ] **Step 1: Write failing persistence tests**

```python
def test_context_round_trip_and_version_conflict(tmp_path):
    memory = ConversationMemory(tmp_path)
    session_id = memory.ensure_session("session-1", "hospital_001")
    context = memory.load_context(session_id)
    context.active_rule.rule_id = "MQSI2025_001"
    saved = memory.save_context(session_id, context, expected_version=0)
    assert saved.context_version == 1
    assert memory.load_context(session_id).active_rule.rule_id == "MQSI2025_001"
    with pytest.raises(ContextVersionConflict):
        memory.save_context(session_id, context, expected_version=0)
```

- [ ] **Step 2: Run RED test**

Run: `pytest tests/test_conversation_context.py -q`

Expected: FAIL because context contracts and storage APIs do not exist.

- [ ] **Step 3: Implement contracts and SQLite storage**

Create typed models with these fields:

```python
class ContextOverride(BaseModel):
    key: str
    business_value: Any
    hospital_field: str | None = None
    status: Literal["ready", "pending_mapping", "pending_clarification"]
    source_message_id: int | None = None
    source_text: str = ""

class ConversationContext(BaseModel):
    schema_version: int = 1
    context_version: int = 0
    active_rule: ActiveRuleContext = Field(default_factory=ActiveRuleContext)
    stat_period: StatPeriodContext = Field(default_factory=StatPeriodContext)
    working_caliber: WorkingCaliberContext = Field(default_factory=WorkingCaliberContext)
    pending_clarifications: list[PendingClarification] = Field(default_factory=list)
    last_action: str = ""
```

Add a `session_contexts` table and optimistic version update. On invalid JSON return the last valid empty context and raise a storage error on save; never claim the change was remembered.

- [ ] **Step 4: Run GREEN tests**

Run: `pytest tests/test_conversation_context.py tests/test_agent_workflow.py -q`

Expected: PASS.

- [ ] **Step 5: Commit the storage unit**

```powershell
git add app/memory/contracts.py app/memory/store.py tests/test_conversation_context.py
git commit -m "feat: 增加结构化会话状态存储"
git push origin main
```

### Task 2: 上下文增量解析、歧义确认与执行快照

**Files:**
- Create: `app/memory/context_service.py`
- Modify: `app/agents/contracts.py`
- Modify: `app/agents/human_interaction.py`
- Modify: `app/prompts/intent.txt`
- Test: `tests/test_context_service.py`
- Test: `tests/test_agent_contracts.py`

**Interfaces:**
- Produces: `ContextDelta`，包含规则、统计区间、会话口径增量、清除动作和澄清选择。
- Produces: `ExecutionContextSnapshot`，包含最终口径、来源、阻断原因和状态版本。
- Produces: `ConversationContextService.resolve(message, context, effective_rule, field_mapping) -> ContextResolution`。

- [ ] **Step 1: Write failing behavior tests**

```python
def test_ambiguous_ward_entry_request_requires_clarification():
    result = service.resolve("按入区时间算", context, rule, mapping)
    assert result.blocked is True
    assert result.clarification.code == "WARD_ENTRY_SCOPE_REQUIRED"

def test_confirmed_elapsed_start_is_saved_but_unmapped_field_blocks_execution():
    result = service.resolve("48小时从入区时间开始算", context, rule, mapping_without_ward_entry)
    override = result.context.working_caliber.get("elapsed_time_start")
    assert override.business_value == "ward_entry_time"
    assert override.status == "pending_mapping"
    assert result.snapshot.executable is False
```

- [ ] **Step 2: Run RED tests**

Run: `pytest tests/test_context_service.py tests/test_agent_contracts.py -q`

Expected: FAIL because the typed delta, resolver and snapshot do not exist.

- [ ] **Step 3: Implement deterministic-first context service**

Implement exact handling for:

```text
按入区时间算 -> pending clarification
统计范围按入区时间 -> period_time_field=ward_entry_time
48小时从入区时间开始算 -> elapsed_time_start=ward_entry_time
两者都按入区时间 -> both overrides
恢复本院口径 / 清除刚才的调整 -> clear current rule overrides
```

LLM extraction may add candidate deltas, but the service must validate keys, values, target indicator and mapping status. Build the execution snapshot from explicit priority order and include `source_level` per field.

- [ ] **Step 4: Run GREEN tests**

Run: `pytest tests/test_context_service.py tests/test_agent_contracts.py tests/test_agent_workflow.py -q`

Expected: PASS.

- [ ] **Step 5: Commit the context service**

```powershell
git add app/memory/context_service.py app/agents/contracts.py app/agents/human_interaction.py app/prompts/intent.txt tests/test_context_service.py tests/test_agent_contracts.py
git commit -m "feat: 增加会话口径解析与执行快照"
git push origin main
```

### Task 3: 对话工作流接入结构化上下文

**Files:**
- Modify: `app/agent/graph.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/agents/indicator_generation.py`
- Modify: `app/indicator_details/service.py`
- Modify: `app/indicator_details/snapshot.py`
- Test: `tests/test_agent_workflow.py`
- Test: `tests/test_agent_orchestrator.py`
- Test: `tests/test_indicator_detail_snapshot.py`

**Interfaces:**
- Consumes: `ConversationContextService` and `ExecutionContextSnapshot` from Task 2.
- Produces: `context_resolve` and `working_caliber_apply` trace nodes.
- Passes: session overrides and snapshot ID into SQL generation and trial execution.

- [ ] **Step 1: Write failing workflow regression tests**

```python
def test_confirmed_ward_entry_context_is_reused_by_generate_sql():
    first = run_chat("患者入院48小时内转科的比例怎么算", session_id="ctx-session")
    run_chat("48小时从入区时间开始算", session_id=first["session_id"])
    events = list(run_chat_stream("生成 SQL", session_id=first["session_id"]))
    done = next(data for event, data in events if event == "done")
    assert done["execution_context"]["overrides"]["elapsed_time_start"] == "ward_entry_time"

def test_unmapped_ward_entry_never_falls_back_to_admitted_at():
    done = run_context_scenario(mapping_without_ward_entry)
    assert done["status"] == "context_blocked"
    assert "ADMITTED_AT" not in done.get("sql_text", "")

def test_new_session_does_not_inherit_working_caliber():
    save_ward_entry_override(session_id="old-session")
    result = run_chat("生成患者入院48小时内转科的比例 SQL", session_id="new-session")
    assert result["execution_context"]["working_caliber"] == {}

def test_detail_and_export_reuse_the_run_execution_snapshot():
    run = create_run_with_snapshot("SNAPSHOT_001", elapsed_time_start="ward_entry_time")
    detail = detail_service.get_detail(run.run_id)
    export = detail_service.export(run.run_id)
    assert detail.execution_snapshot_id == "SNAPSHOT_001"
    assert export.execution_snapshot_id == "SNAPSHOT_001"
```

- [ ] **Step 2: Run RED workflow tests**

Run: `pytest tests/test_agent_workflow.py tests/test_agent_orchestrator.py -q`

Expected: new tests FAIL because graph generation ignores working caliber.

- [ ] **Step 3: Wire context resolution before rule execution**

Load context at request start, append the current user message, resolve the context after the rule and mapping are available, save validated changes, then pass the immutable snapshot to `generate_indicator`. Persist the snapshot ID with the run record and make indicator detail and export services load that exact snapshot. Return a clarification response or mapping-blocked response before SQL generation when `snapshot.executable` is false.

For compatibility, migrate legacy rule/date metadata into an empty structured context once; do not infer old free-text caliber changes.

- [ ] **Step 4: Run GREEN workflow tests**

Run: `pytest tests/test_agent_workflow.py tests/test_agent_orchestrator.py tests/test_indicator_detail_snapshot.py tests/test_api.py -q`

Expected: PASS.

- [ ] **Step 5: Commit workflow integration**

```powershell
git add app/agent/graph.py app/agents/orchestrator.py app/agents/indicator_generation.py app/indicator_details/service.py app/indicator_details/snapshot.py tests/test_agent_workflow.py tests/test_agent_orchestrator.py tests/test_indicator_detail_snapshot.py
git commit -m "feat: 在执行链路应用会话临时口径"
git push origin main
```

### Task 4: Ollama 16K 预算与最近 8 轮语言历史

**Files:**
- Create: `app/memory/prompt_context.py`
- Modify: `app/llm/ollama.py`
- Modify: `app/config.py`
- Modify: `config.example.yaml`
- Modify: `README.md`
- Modify: `app/agents/human_interaction.py`
- Test: `tests/test_ollama_client.py`
- Test: `tests/test_prompt_context.py`

**Interfaces:**
- Produces: `build_prompt_history(messages, context, max_turns, token_budget) -> PromptContext`.
- `OllamaClient` sends `options.num_ctx` in both generate methods.
- Exposes prompt budget observations without storing message contents in traces.

- [ ] **Step 1: Write failing Ollama and prompt budget tests**

```python
def test_ollama_requests_use_configured_num_ctx(fake_urlopen):
    OllamaClient(num_ctx=16384).generate("hello")
    payload = fake_urlopen.last_json
    assert payload["options"]["num_ctx"] == 16384

def test_prompt_budget_keeps_structured_state_and_drops_oldest_messages():
    result = build_prompt_history(long_messages, context, max_turns=8, token_budget=12000)
    assert "ward_entry_time" in result.text
    assert result.trimmed_message_count > 0
    assert "oldest-message" not in result.text
```

- [ ] **Step 2: Run RED tests**

Run: `pytest tests/test_ollama_client.py tests/test_prompt_context.py -q`

Expected: FAIL because the configuration and builder do not exist.

- [ ] **Step 3: Implement bounded prompt assembly**

Add constructor/config values `num_ctx`, `history_turns`, `prompt_budget_tokens`, and `output_reserve_tokens`. Estimate tokens conservatively from Unicode text, keep structured state first, and remove oldest messages before trimming nonessential rule prose. Replace full SQL/tool output in history with metadata summaries.

- [ ] **Step 4: Run GREEN tests**

Run: `pytest tests/test_ollama_client.py tests/test_prompt_context.py tests/test_agent_workflow.py -q`

Expected: PASS.

- [ ] **Step 5: Commit Ollama context controls**

```powershell
git add app/memory/prompt_context.py app/llm/ollama.py app/config.py config.example.yaml README.md app/agents/human_interaction.py tests/test_ollama_client.py tests/test_prompt_context.py
git commit -m "feat: 配置本地模型上下文预算"
git push origin main
```

### Task 5: 链路展示、完整回归与前端验证

**Files:**
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `app/observability/workflow_nodes.py`
- Modify: `web/index.html`
- Modify: `tests/test_observability_trace.py`
- Modify: `tests/test_trace_ui.py`
- Modify: `README.md`

**Interfaces:**
- Consumes: context trace payloads from Task 3 and prompt observations from Task 4.
- Produces: doctor-readable context summary and technical detail drawer.

- [ ] **Step 1: Write failing trace and UI tests**

```python
def test_trace_explains_session_caliber_source():
    trace = run_ward_entry_trace()
    node = trace.node("working_caliber_apply")
    assert node.output_data["time_basis"] == "入区时间"
    assert node.output_data["source_level"] == "当前会话临时调整"
    assert node.output_data["changes_official_rule"] is False
```

Add UI assertions for “当前会话临时调整”“待确认医院字段”“未修改本院正式口径”。

- [ ] **Step 2: Run RED tests**

Run: `pytest tests/test_observability_trace.py tests/test_trace_ui.py -q`

Expected: FAIL because the new trace nodes and labels are absent.

- [ ] **Step 3: Implement trace manifest and readable rendering**

Show a compact summary by default and keep raw structured state in the detail view. Never render patient data, complete SQL or sensitive parameter values in context nodes.

- [ ] **Step 4: Run focused and full verification**

Run:

```powershell
pytest tests/test_conversation_context.py tests/test_context_service.py tests/test_prompt_context.py tests/test_ollama_client.py tests/test_agent_workflow.py tests/test_agent_orchestrator.py tests/test_observability_trace.py tests/test_trace_ui.py -q
pytest -q
```

Expected: all tests PASS with zero failures.

- [ ] **Step 5: Verify the original browser scenario**

Use one session and enter:

```text
患者入院48小时内转科的比例怎么算
按入区时间算
48小时从入区时间开始算
生成 SQL
```

Expected: the second message asks which scope to adjust; the third saves `elapsed_time_start=ward_entry_time`; generation either uses the confirmed hospital field or stops with a field-mapping prompt, and never silently uses `ADMITTED_AT`.

- [ ] **Step 6: Commit and push final integration**

```powershell
git add app/workflows/core_indicator_chat.yaml app/observability/workflow_nodes.py web/index.html tests/test_observability_trace.py tests/test_trace_ui.py README.md
git commit -m "feat: 完善会话口径链路展示"
git push origin main
```
