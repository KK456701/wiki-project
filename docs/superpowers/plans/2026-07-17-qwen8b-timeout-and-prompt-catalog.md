# Qwen3 8B 超时与提示词目录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让本地 Qwen3 8B 思考模型不再命中统一 120 秒整轮超时，并让所有提示词的角色和调用关系一目了然。

**Architecture:** ModelRegistry 保存模型级调用超时、整轮超时和 Planner 思考开关；Service 为 Planner 与 Executor 分别构建适配器。提示词仍由统一加载器读取，但旧流程文件改用明确名称并由目录 README 建立索引。

**Tech Stack:** Python、Ollama、Pydantic/dataclass、pytest、Markdown。

## Global Constraints

- 当前 `main` 分支直接实施，不创建 worktree，不使用子 Agent。
- Executor 保持 Qwen3 8B 思考模式，Planner 显式关闭思考。
- 不记录或展示隐藏思维过程。
- README、架构文档、测试、提交和推送必须在同一批次完成。

---

### Task 1: 模型角色与超时配置

**Files:**
- Modify: `app/llm/model_registry.py`
- Modify: `app/llm/ollama.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `config.yaml`
- Modify: `config.example.yaml`
- Test: `tests/test_model_registry.py`
- Test: `tests/test_ollama_client.py`

- [x] **Step 1: 写失败测试**：Planner/Executor 获得不同 thinking 值，模型级调用和整轮超时可解析。
- [x] **Step 2: 运行测试并确认因接口尚不存在而失败。**
- [x] **Step 3: 最小实现模型级配置和角色化适配器。**
- [x] **Step 4: 运行模型注册和 Ollama 客户端测试。**

### Task 2: 提示词角色清单与明确命名

**Files:**
- Rename: `app/prompts/intent.txt` to `app/prompts/legacy_chat_intent.txt`
- Rename: `app/prompts/answer.txt` to `app/prompts/legacy_chat_answer.txt`
- Create: `app/prompts/README.md`
- Modify: `app/prompts/__init__.py`
- Modify: `tests/test_prompt_registry.py`

- [x] **Step 1: 写失败测试**：要求新文件名、目录清单和旧文件不存在。
- [x] **Step 2: 运行测试确认红灯。**
- [x] **Step 3: 重命名并更新加载器，编写角色清单。**
- [x] **Step 4: 运行提示词注册测试。**

### Task 3: 文档、真实验证与发布

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/agent-runtime-current.md`
- Modify: `docs/superpowers/plans/2026-07-17-qwen8b-timeout-and-prompt-catalog.md`

- [x] **Step 1: 更新模型超时和提示词目录说明。**
- [x] **Step 2: 运行专项测试、全量 `pytest -q`、Ruff 和 `git diff --check`。**
- [x] **Step 3: 使用 Qwen3 8B 做真实 Agent 回归并检查 Trace。**
- [ ] **Step 4: 提交、推送 `main`，重启 8765 并检查健康状态。**
