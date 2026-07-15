# 会话参数命令与 SQL 差异报告 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让统计时间修改成为确定性会话命令，并将粘贴 SQL 诊断收敛为用户 SQL 与当前生效 SQL 的双向比较。

**Architecture:** 复用现有结构化会话状态和 SQL 语义分析。编排层短路统计时间确认回复，诊断叙述层由程序生成稳定的双向结果表和差异表，LLM 仅润色且受输出守卫约束。

**Tech Stack:** Python、FastAPI 流式 Agent、Pydantic、pytest、Markdown

## Global Constraints

- 医院用户文案使用通俗中文。
- SQL 试运行保持只读、安全校验、超时和聚合结果限制。
- 不把患者明细、原始 SQL 或真实连接信息写入诊断回答。
- 每批改动测试通过后使用中文 Conventional Commit 并推送 `main`。

---

### Task 1: 统计时间更新确认

**Files:**
- Modify: `app/agent/graph.py`
- Test: `tests/test_agent_streaming.py`

**Interfaces:**
- Consumes: `_extract_stat_period_update(query)` 和已保存的 `ContextResolution`
- Produces: `_stat_period_update_answer(query, execution_context) -> str | None`

- [ ] **Step 1: 写失败测试**

测试连续请求“患者入院48小时内转科的比例怎么算”“统计时间改为2026-06-01至2026-08-01”，断言第二次回复包含新时间和后续用途，不包含“计算公式”“定义”，且不调用 LLM 流式回答。

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_agent_streaming.py -k stat_period -q`

Expected: FAIL，当前回复仍来自普通指标问答。

- [ ] **Step 3: 实现最小短路逻辑**

在结构化上下文保存成功后识别统计时间更新，使用 `execution_context.stat_period` 生成确认文案并直接完成本轮响应。

- [ ] **Step 4: 运行相关测试**

Run: `python -m pytest tests/test_agent_streaming.py tests/test_prompt_context.py tests/test_specialized_agents.py -q`

Expected: PASS。

- [ ] **Step 5: 提交并推送**

```powershell
git add app/agent/graph.py tests/test_agent_streaming.py
git commit -m "fix: 修复统计时间修改后重复回答"
git push origin main
```

### Task 2: 用户 SQL 与当前生效 SQL 双向报告

**Files:**
- Modify: `app/diagnose/pasted_diagnosis.py`
- Modify: `app/diagnose/narrator.py`
- Modify: `app/prompts/diagnosis_compose.txt`
- Test: `tests/test_diagnosis_narrator.py`
- Test: `tests/test_pasted_diagnosis.py`
- Test: `tests/test_pasted_diagnosis_e2e.py`

**Interfaces:**
- Consumes: `findings`、`execution_results.user`、`execution_results.hospital` 和口径来源
- Produces: 医生可读的双向结果表、逐项差异表和折叠技术依据

- [ ] **Step 1: 写失败测试**

断言主报告包含“用户 SQL”“当前生效 SQL”和差异表，不把“国标口径”放入主结果表；国标执行失败时不得生成“国标失败导致无法比较”的主结论。

- [ ] **Step 2: 运行测试确认失败**

Run: `python -m pytest tests/test_diagnosis_narrator.py tests/test_pasted_diagnosis.py tests/test_pasted_diagnosis_e2e.py -q`

Expected: FAIL，当前主表仍为三方比较。

- [ ] **Step 3: 实现确定性双向报告**

把 `hospital` 解释为当前生效 SQL，输出两方试运行表；将每个 `finding` 转为“比较项目/用户 SQL/当前生效 SQL/影响/建议”行。国标结果仅放入折叠技术依据，并加强 LLM 输出守卫。

- [ ] **Step 4: 运行诊断测试和完整测试**

Run: `python -m pytest tests/test_diagnosis_narrator.py tests/test_pasted_diagnosis.py tests/test_pasted_diagnosis_e2e.py tests/test_diagnose_agent.py -q`

Run: `python -m pytest -q`

Expected: 全部 PASS。

- [ ] **Step 5: 提交并推送**

```powershell
git add app/diagnose/pasted_diagnosis.py app/diagnose/narrator.py app/prompts/diagnosis_compose.txt tests/test_diagnosis_narrator.py tests/test_pasted_diagnosis.py tests/test_pasted_diagnosis_e2e.py
git commit -m "fix: 修正用户SQL差异诊断对象"
git push origin main
```
