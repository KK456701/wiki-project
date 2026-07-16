# Agent Follow-up Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复二轮时间选择导致 Planner 无有效计划的问题，并同步项目文档约束。

**Architecture:** 会话层提供有界历史，Planner 输入边界确定性归一化“后者”式选择并兼容 4B 常见 JSON 形状；现有计划编译、状态控制和工具链保持不变。

**Tech Stack:** Python、Pydantic、pytest、Markdown

## Global Constraints

- 不新增 LLM 调用或工具。
- 结构化状态始终优先于历史原文。
- SQL 与患者数据安全边界不变。
- 直接在当前 `main` 分支实施，不使用 worktree 或子 Agent。

---

### Task 1: Planner 追问上下文

**Files:**
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_runtime/memory.py`
- Modify: `app/agent_planning/planner.py`
- Test: `tests/test_agent_planned_runner.py`
- Test: `tests/test_agent_conversation_memory.py`

**Interfaces:**
- Consumes: `PromptContext.recent_history: str`
- Produces: `AgentRunState.recent_history: str` 与 Planner 归一化后的用户输入

- [x] **Step 1: 写入失败测试**

覆盖最近历史传入 Planner、末尾“这个”选择最后一个时间选项、字符串歧义项规范化。

- [x] **Step 2: 运行测试并确认失败**

Run: `pytest -q tests/test_agent_planned_runner.py tests/test_agent_conversation_memory.py`

Expected: 新断言因缺少历史字段或归一化行为失败。

- [x] **Step 3: 最小实现**

在会话打开时复制已压缩历史；Planner 只在明确的“或 + 后者指代”模式下提取最后一个时间范围，并把字符串歧义转换为 `SemanticAmbiguity` 对象。

- [x] **Step 4: 运行专项测试**

Run: `pytest -q tests/test_agent_planned_runner.py tests/test_agent_conversation_memory.py tests/test_agent_plan_validator.py`

Expected: PASS。

### Task 2: README 与项目协作约束

**Files:**
- Modify: `README.md`
- Modify: `agent.md`
- Modify: `docs/architecture/agent-runtime-current.md`

**Interfaces:**
- Consumes: 当前生产代码与模型注册配置
- Produces: 与生产架构一致的启动、模型、API 和会话说明

- [x] **Step 1: 删除 README 中的旧入口与旧编排描述**

将 `/api/chat*`、旧 LangGraph/Dify-lite 主链和单 Ollama 模型描述替换为 `/api/agent/chat/stream`、计划控制架构与四模型选择器。

- [x] **Step 2: 增加批次文档规则**

在 `agent.md` 规定每批可验证改动完成后必须检查并同步 README，随后提交和推送。

- [x] **Step 3: 完整验证、提交与推送**

Run: `pytest -q`

Expected: 全部测试通过；`git diff --check` 无错误；推送 `main` 成功。
