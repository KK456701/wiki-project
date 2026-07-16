# Agent 指标草稿与口径变更预览 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为工具调用型 Agent 增加本院指标设计草稿和本院口径变更预览能力，同时确定性阻止自动提交、审批或发布。

**Architecture:** 新 `preview_tools` 只适配现有 `CoreIndicatorOrchestrator.create_indicator_draft()` 和 `preview_feedback()`，前者允许持久化不参与正式查询的工作草稿，后者只生成内存差异预览。完整工具目录根据已验证规则和 active `sql_id` 调整可见性，始终不超过六个；回答守卫要求草稿/预览证据，并把任何正式提交、审批、发布声明视为永远缺少授权证据。

**Tech Stack:** Python 3.12.7、Pydantic 2.13.3、现有 IndicatorDraft/反馈预览服务、Agent Runtime/Tool Gateway、`unittest`/`pytest`。

## Global Constraints

- 默认使用中文注释、错误说明、提示和提交主题。
- 不修改旧聊天、API、SSE、指标草稿人工工作流和审批发布接口行为。
- 不新增模型可见的 submit、approve、publish、restore 或正式规则写工具。
- 草稿输入只接受指标业务描述；医院、创建人和权限从 `AgentRuntimeContext` 注入。
- 草稿结果不返回 `current_sql`、`sql_plan`、数据库对象、患者数据或内部异常。
- 变更预览输入只接受已验证 `rule_id` 和口径修改描述；不接受医院、审批人、版本号或跳过校验参数。
- 变更预览不得调用 `submit_change` 或任何审批、发布、回退服务。
- `PREVIEW_ONLY` 工具只向 `implementer`、`admin`、`developer` 角色且具有 `indicator_read` 权限的用户暴露。
- 初始状态暴露搜索和草稿；规则确定后隐藏草稿并暴露变更预览；每轮最多六个工具。
- 每个任务遵循 TDD，独立验证、中文 Conventional Commit 并推送。

## File Structure

```text
app/agent_tools/preview_tools.py       草稿与变更预览工具
app/agent_tools/catalog.py             组合八个工具并保持动态上限
app/agent_tools/sql_tools.py           active sql 时隐藏重复准备，失效时释放
app/agent_runtime/response_guard.py    草稿/预览/正式写声明守卫
app/agent_tools/__init__.py            公开导出
tests/test_agent_preview_tools.py
tests/test_agent_preview_catalog.py
tests/test_agent_preview_guard.py
tests/test_agent_preview_loop.py
```

---

### Task 1: 实现指标设计草稿工具

**Files:**
- Create: `app/agent_tools/preview_tools.py`
- Modify: `app/agent_tools/__init__.py`
- Create: `tests/test_agent_preview_tools.py`

**Interfaces:**
- Produces: `CreateIndicatorDraftInput`、`PreviewToolServices`、`create_indicator_draft()`、`build_preview_tools()`。
- Consumes: `orchestrator.create_indicator_draft(description, hospital_id, actor_id)`。

- [ ] **Step 1: 写失败测试**

覆盖：输入 `extra="forbid"`；context 注入医院和用户；成功只返回 `draft_id/status/current_version/index_name/index_desc/stat_cycle/numerator_rule/denominator_rule/filter_rule/exclude_rule/metric_type/metadata_requirements/missing_information`；删除 `sql_plan/current_sql/sql_params/trial_result`；证据事实为 `indicator_draft`；服务失败固定中文摘要；普通医生角色不可见。

主路径断言：

```python
result = create_indicator_draft(
    CreateIndicatorDraftInput(
        description="创建夜间急会诊15分钟到位率，分母为夜间急会诊总数"
    ),
    context(user_role="implementer"),
    AgentRunState(),
    services=PreviewToolServices(orchestrator=fake),
)
assert fake.calls == [(description, "h1", "u1")]
assert result.code == "INDICATOR_DRAFT_CREATED"
assert result.data["draft_id"] == "DRAFT_001"
assert "sql_plan" not in result.data
assert result.evidence[0].fact_types == ["indicator_draft"]
```

- [ ] **Step 2: 确认红灯**

Run: `python -m pytest tests/test_agent_preview_tools.py -q`

Expected: FAIL，缺少 `preview_tools`。

- [ ] **Step 3: 最小实现**

```python
class CreateIndicatorDraftInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    description: str = Field(min_length=10, max_length=5000)


@dataclass(frozen=True, slots=True)
class PreviewToolServices:
    orchestrator: Any
```

`missing_information` 根据空 `numerator_rule`、`denominator_rule`、`stat_cycle` 和空 `metadata_requirements` 生成中文字段名列表。成功时设置 `state.last_draft_id`，因此在 `AgentRunState` 增加 `last_draft_id: str | None = None`。

注册 `create_indicator_draft` 为 `PREVIEW_ONLY`、30 秒超时、`indicator_read` 权限；availability 要求角色允许且 `has_verified_rule(state)` 为假。

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/test_agent_preview_tools.py tests/test_indicator_draft_parser.py tests/test_indicator_drafts.py -q`

Expected: 全部通过。

- [ ] **Step 5: 提交**

```powershell
git add app/agent_runtime/contracts.py app/agent_tools/preview_tools.py app/agent_tools/__init__.py tests/test_agent_preview_tools.py
git commit -m "feat: 增加 Agent 指标设计草稿工具"
git push
```

### Task 2: 实现本院口径变更预览工具

**Files:**
- Modify: `app/agent_tools/preview_tools.py`
- Modify: `tests/test_agent_preview_tools.py`

**Interfaces:**
- Produces: `PreviewRuleChangeInput`、`preview_rule_change()`。
- Consumes: `orchestrator.prepare_rule_request(intent="feedback")` 和 `preview_feedback(prepared)`。

- [ ] **Step 1: 写失败测试**

覆盖：必须匹配已验证规则；context 注入医院；输入禁止医院、审批人、submit/approve 标记；结果白名单；不调用提交方法；影响摘要正确；服务失败不泄漏异常。

```python
assert result.data["impact"] == {
    "changed_fields": ["计算公式"],
    "affects_definition": False,
    "affects_formula": True,
    "requires_field_review": False,
    "requires_sql_regeneration": True,
    "requires_version_increment": True,
}
assert fake.submit_calls == []
assert result.evidence[0].fact_types == ["rule_change_preview"]
```

- [ ] **Step 2: 确认红灯**

Run: `python -m pytest tests/test_agent_preview_tools.py -q`

Expected: FAIL，新接口不存在。

- [ ] **Step 3: 最小实现**

```python
class PreviewRuleChangeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    rule_id: str = Field(min_length=1, max_length=128)
    change_description: str = Field(min_length=2, max_length=5000)
```

handler 固定调用：

```python
prepared = orchestrator.prepare_rule_request(
    query=arguments.change_description,
    hospital_id=context.hospital_id,
    intent="feedback",
    rule_id=arguments.rule_id,
)
preview = orchestrator.preview_feedback(prepared)
```

白名单只含 rule/name/target/current/requested/field_changes/impact/message。`requested.source_text` 删除。`field_changes` 只保留 `field/requested/current/changed`。任何 `change_id/status=pending/approval/published` 字段不进入 ToolResult。

- [ ] **Step 4: 运行测试并提交**

Run: `python -m pytest tests/test_agent_preview_tools.py tests/test_agent_orchestrator.py tests/test_agent_workflow.py -q`

```powershell
git add app/agent_tools/preview_tools.py tests/test_agent_preview_tools.py
git commit -m "feat: 增加 Agent 本院口径变更预览"
git push
```

### Task 3: 完成八工具动态目录和回答证据闭环

**Files:**
- Modify: `app/agent_tools/catalog.py`
- Modify: `app/agent_tools/sql_tools.py`
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_runtime/response_guard.py`
- Modify: `app/agent_runtime/__init__.py`
- Create: `tests/test_agent_preview_catalog.py`
- Create: `tests/test_agent_preview_guard.py`
- Create: `tests/test_agent_preview_loop.py`

**Interfaces:**
- `build_agent_tool_registry(read_services, sql_services, diagnosis_services, preview_services)` 注册八个工具。
- `missing_fact_types()` 新增 `indicator_draft`、`rule_change_preview`、`formal_change`。

- [ ] **Step 1: 写动态目录失败测试**

断言：空状态为 `[search_indicator_rules, create_indicator_draft]`；规则状态为六个 `[search,get,inspect,prepare,diagnose,preview]`；active SQL 状态为 `[search,get,inspect,trial,diagnose,preview]`；任何状态不超过六；医生角色看不到两个预览工具。

- [ ] **Step 2: 写回答守卫失败测试**

```python
assert missing_fact_types("已创建指标草稿 DRAFT_001。", []) == {"indicator_draft"}
assert missing_fact_types("已生成本院口径变更预览。", []) == {"rule_change_preview"}
assert missing_fact_types("已提交审批并发布本院版本。", []) == {"formal_change"}
```

即使 evidence 含所有现有事实类型，`formal_change` 仍缺失，因为没有任何工具能产生该事实。

- [ ] **Step 3: 实现动态可见性**

`prepare_indicator_sql` availability 改为“有规则且无 active SQL”。当 Store 返回 `SQL_OBJECT_NOT_FOUND/EXPIRED/NOT_VALIDATED/CORRUPTED` 时，从 `state.validated_sql_ids` 删除该 ID，使下一轮重新暴露准备工具。完整目录组合 read + sql + diagnosis + preview，注册总数八个、可见最多六个。

- [ ] **Step 4: 实现守卫和 Fake Model 闭环**

Fake Model 覆盖两条链：

```text
create_indicator_draft → 中文草稿回答
search_indicator_rules → preview_rule_change → 中文差异预览回答
```

断言 ToolResult 和消息不含 SQL、正式 change_id/pending 状态；无证据声明被拒绝；正式提交/审批/发布声明始终被拒绝。

- [ ] **Step 5: 阶段回归**

Run:

```powershell
python -m pytest tests/test_agent_preview_tools.py tests/test_agent_preview_catalog.py tests/test_agent_preview_guard.py tests/test_agent_preview_loop.py tests/test_agent_execution_catalog.py tests/test_agent_sql_tools.py tests/test_agent_response_guard.py tests/test_indicator_draft_parser.py tests/test_indicator_drafts.py tests/test_agent_orchestrator.py tests/test_agent_workflow.py -q
```

- [ ] **Step 6: 完整回归与提交**

Run: `python -m pytest -q`

```powershell
git diff --check
git add app/agent_tools/catalog.py app/agent_tools/sql_tools.py app/agent_runtime/contracts.py app/agent_runtime/response_guard.py app/agent_runtime/__init__.py tests/test_agent_preview_catalog.py tests/test_agent_preview_guard.py tests/test_agent_preview_loop.py
git commit -m "feat: 完成 Agent 草稿与变更预览闭环"
git push
```

## Completion Criteria

1. 草稿仅接受业务描述并绑定当前医院/用户，不能进入正式查询。
2. 草稿观察结果不含 SQL、表结构、患者数据和内部异常。
3. 口径工具只生成差异和影响预览，不提交审批。
4. 模型不可传医院、审批人、版本或跳过校验参数。
5. 总注册工具为八个，任一状态模型可见工具不超过六个。
6. 无草稿/预览证据不接受对应完成声明。
7. 任何“已提交、审批、发布、回退”声明都被确定性阻止。
8. 旧草稿 API、人工工作流、旧聊天和 Stage 0–4 行为不变。
9. 完整测试通过并合并推送 main。
