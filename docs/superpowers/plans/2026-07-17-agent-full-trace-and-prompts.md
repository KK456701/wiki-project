# Agent Full Trace and Prompt Centralization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复普通寒暄计划失败，并为当前 Agent 增加可查看完整安全参数的全阶段链路，同时集中管理所有 LLM 提示词。

**Architecture:** Planner、PlanningRuntime、Runner、ToolGateway 和 Memory 通过内部 Trace 事件记录真实阶段，`AgentTraceBridge` 将事件写入现有 TraceRecorder；前端使用认证后的 Agent run API 渲染类型化节点。提示词全部由 `app/prompts` 加载，节点配置记录提示词文件和版本。

**Tech Stack:** Python、Pydantic、FastAPI、JSONL/SQLAlchemy TraceRecorder、原生 JavaScript/CSS、pytest、Node.js DOM 静态测试

## Global Constraints

- 当前 `main` 分支直接实施，不使用 worktree 或子 Agent。
- 公开 SSE 不得透传完整参数。
- 密码、令牌、连接串、患者行级明细和隐藏思维链不得写入 Trace。
- 同医院认证与现有权限隔离必须保留。
- 每批改动同步 README、测试、提交并推送。

---

### Task 1: Planner 空容器兼容

**Files:**
- Modify: `tests/test_agent_planned_runner.py`
- Modify: `app/agent_planning/planner.py`

**Interfaces:**
- Consumes: Planner JSON 中 `target_indicator`、`time_expression` 的 `null`
- Produces: `_normalize_container_shapes(value: dict) -> dict` 将两者变成 `{}`

- [x] **Step 1: 写失败测试**：新增普通寒暄计划返回两个 `null` 仍生成 `general_chat` 计划的测试。
- [x] **Step 2: 运行测试确认因 Pydantic 容器校验失败而红灯。**
- [x] **Step 3: 仅在两个可选容器值为 `None` 时归一化为空对象。**
- [x] **Step 4: 运行 Planner 与编译器专项测试确认绿灯。**

### Task 2: 提示词集中化

**Files:**
- Modify: `app/prompts/__init__.py`
- Create: `app/prompts/agent_planner.txt`
- Create: `app/prompts/agent_planner_context.txt`
- Create: `app/prompts/agent_planner_repair.txt`
- Create: `app/prompts/agent_replanner.txt`
- Create: `app/prompts/agent_executor.txt`
- Create: `app/prompts/agent_executor_context.txt`
- Create: `app/prompts/agent_executor_step.txt`
- Create: `app/prompts/agent_executor_corrections.txt`
- Create: `app/prompts/indicator_draft_parser.txt`
- Create: `app/prompts/indicator_draft_repair.txt`
- Modify: `app/agent_planning/planner.py`
- Modify: `app/agent_runtime/prompts.py`
- Modify: `app/agent_runtime/response_guard.py`
- Modify: `app/agent_planning/runtime.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/indicators/parser.py`
- Modify: `app/diagnose/evidence.py`
- Test: `tests/test_prompt_registry.py`

**Interfaces:**
- Produces: `load_prompt(name: str) -> str`、`format_prompt(name: str, **values) -> str`、`prompt_version(name: str) -> str`

- [x] **Step 1: 写失败测试**：枚举生产提示词文件并断言加载、格式化、版本号以及代码无大段内联提示。
- [x] **Step 2: 运行测试确认缺少文件和公共加载接口。**
- [x] **Step 3: 创建提示词文件和统一加载器，迁移所有生产调用点。**
- [x] **Step 4: 运行提示词、Planner、Runner、指标草稿和诊断测试。**

### Task 3: 全阶段 Trace 后端

**Files:**
- Modify: `app/agent_runtime/events.py`
- Modify: `app/agent_runtime/tracing.py`
- Modify: `app/observability/trace.py`
- Modify: `app/agent_planning/planner.py`
- Modify: `app/agent_planning/runtime.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/workflows/agent_runtime.yaml`
- Test: `tests/test_agent_trace_bridge.py`
- Test: `tests/test_agent_api.py`

**Interfaces:**
- Consumes: 内部事件 `trace_node_started`、`trace_node_finished`
- Produces: 带 `node_name`、`node_type`、`duration_ms`、`input_data`、`output_data`、`processing_data`、`config_data` 的 Trace 节点

- [x] **Step 1: 写失败测试**：覆盖 Planner 失败节点、完整上下文、节点类型、耗时、处理数据、配置以及敏感字段脱敏。
- [x] **Step 2: 运行测试确认现有 Bridge 只记录三个摘要节点。**
- [x] **Step 3: 增加类型化阶段事件和单调时钟计时，记录计划、控制、执行、工具、校验、回答与记忆节点。**
- [x] **Step 4: 扩展 `agent_runtime.yaml` 中英文元数据并让 TraceRecorder 合并 `processing_data`。**
- [x] **Step 5: 运行 Trace、API、SSE 安全测试，确认 SSE 仍无完整参数。**

### Task 4: 查看链路界面

**Files:**
- Modify: `web/index.html`
- Modify: `web/agent-runtime.css`
- Modify: `web/agent-runtime.js`
- Modify: `tests/test_agent_frontend_ui.py`

**Interfaces:**
- Consumes: `/api/agent/runs/{trace_id}` 的类型化节点
- Produces: 成功/失败消息链路按钮、彩色轨道、完整节点详情和复制操作

- [x] **Step 1: 写失败测试**：断言错误消息也附加按钮、四类节点样式、中英文标题和四个详情区。
- [x] **Step 2: 运行测试确认当前失败消息无按钮且节点无类型样式。**
- [x] **Step 3: 实现类型标签和安全 JSON 格式化。**
- [x] **Step 4: 重构弹窗 DOM/CSS，增加轨道、图例、节点类型颜色、完整详情和复制按钮。**
- [x] **Step 5: 运行前端测试并在 127.0.0.1:8765 做成功/失败视觉验收。**

### Task 5: 文档、全量验证与发布

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/agent-runtime-current.md`
- Modify: `docs/superpowers/plans/2026-07-17-agent-full-trace-and-prompts.md`

- [x] **Step 1: 更新 README 的链路节点、完整参数安全边界和提示词目录。**
- [x] **Step 2: 更新架构文档中的 LLM 节点与提示词文件映射。**
- [x] **Step 3: 运行 `pytest -q` 与 `git diff --check`。**
- [ ] **Step 4: 提交、推送 `main`，重启 8765 并检查 `/api/health`。**
