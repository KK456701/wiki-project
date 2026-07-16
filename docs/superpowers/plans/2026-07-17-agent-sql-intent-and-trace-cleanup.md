# Agent SQL 意图与链路精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 SQL 准备和 SQL 试运行严格按用户请求分流，并精简、修正完整执行链路。

**Architecture:** Planner 输出业务意图和 requested outputs，PlanCompiler 以 requested outputs 作为确定性执行边界。SQL 工具只返回经过验证的预览；试运行连接故障由工具层重试和安全分类；Trace 保留完整安全参数并正确表示澄清状态。

**Tech Stack:** Python、Pydantic、FastAPI、原生 JavaScript、pytest、DeepSeek OpenAI 兼容适配器

## Global Constraints

- 当前 `main` 分支直接实施，不使用 worktree 或子 Agent。
- SQL 必须经过字段预检和只读安全校验后才能展示。
- 公开 SSE 不得返回 SQL、工具参数或患者数据。
- 每批同步 README、测试、提交并推送。

---

### Task 1: SQL 准备与试运行确定性分流

**Files:**
- Modify: `app/agent_planning/contracts.py`
- Modify: `app/agent_planning/compiler.py`
- Modify: `app/agent_planning/validator.py`
- Modify: `app/prompts/agent_planner.txt`
- Test: `tests/test_agent_plan_compiler.py`
- Test: `tests/test_agent_plan_validator.py`

**Interfaces:**
- Produces: `PlanIntent.INDICATOR_SQL_PREPARE`
- Rule: `RequestedOutput.TRIAL_RESULT` 是编译 `EXECUTE_TRIAL_RUN` 的唯一条件

- [x] **Step 1: 写失败测试**：模型意图错误为 `indicator_trial_run`、但只请求 `prepared_sql_handle` 时不得编译试运行；SQL 准备仍解析统计时间。
- [x] **Step 2: 运行专项测试并确认因当前 intent 优先级失败。**
- [x] **Step 3: 新增 SQL 准备意图，调整编译器和校验器，更新集中提示词。**
- [x] **Step 4: 运行编译器、校验器和 Planner 测试。**

### Task 2: 已验证 SQL 预览和连接故障反馈

**Files:**
- Modify: `app/agent_tools/sql_tools.py`
- Modify: `app/prompts/agent_executor.txt`
- Test: `tests/test_agent_sql_tools.py`

**Interfaces:**
- Produces: `SQL_OBJECT_PREPARED.data.sql_preview`、`parameters`
- Produces: 连接中断失败的 `failure_kind`、`run_id`、`sql_id`

- [x] **Step 1: 写失败测试**：准备结果返回已验证 SQL；临时连接中断只重试一次并输出安全原因。
- [x] **Step 2: 运行测试确认当前不返回 SQL 且不重试。**
- [x] **Step 3: 仅在验证成功后返回 SQL 预览；增加连接中断判定和一次重试。**
- [x] **Step 4: 运行 SQL 工具和执行循环测试。**

### Task 3: Trace 参数和澄清状态

**Files:**
- Modify: `app/agent_tools/gateway.py`
- Modify: `app/agent_runtime/tracing.py`
- Modify: `app/agent_runtime/runner.py`
- Test: `tests/test_agent_tool_gateway.py`
- Test: `tests/test_agent_trace_bridge.py`
- Test: `tests/test_agent_planned_runner.py`

**Interfaces:**
- Tool result event carries `arguments: dict`
- Clarification controller trace status is `warning`

- [x] **Step 1: 写失败测试**：结果节点保留参数；用户澄清不标失败。
- [x] **Step 2: 运行测试确认现有事件缺参数且 fallback 一律失败。**
- [x] **Step 3: 透传并安全记录工具参数，按 fallback 类别映射 Trace 状态。**
- [x] **Step 4: 运行网关、Runner 和 Trace 测试。**

### Task 4: 删除冗余开发区块

**Files:**
- Modify: `web/index.html`
- Modify: `tests/test_agent_frontend_ui.py`

**Interfaces:**
- Keeps: 处理结果、完整节点数据、输入参数、输出参数、数据处理、节点配置
- Removes: 开发与排障区块

- [x] **Step 1: 写失败测试**：断言页面不包含“开发与排障”，完整节点数据仍存在。
- [x] **Step 2: 运行测试确认红灯。**
- [x] **Step 3: 删除区块 DOM 构造代码和失效变量。**
- [x] **Step 4: 运行前端测试并做页面视觉检查。**

### Task 5: 文档、真实回归与发布

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/agent-runtime-current.md`
- Modify: `docs/superpowers/plans/2026-07-17-agent-sql-intent-and-trace-cleanup.md`

- [x] **Step 1: 更新 SQL 准备/试运行边界和 Trace 展示说明。**
- [x] **Step 2: 运行相关专项测试、`pytest -q` 和 `git diff --check`。**
- [x] **Step 3: 使用 DeepSeek 真实验证“SQL 怎么写 → 从 1 月到现在 → 不运行先写出来”。**
- [ ] **Step 4: 提交、推送 `main`，重启 8765 并检查健康状态。**
