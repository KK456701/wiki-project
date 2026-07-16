# Deterministic Tool Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute Controller-selected tools without an Executor LLM round trip and reserve the Executor model for final answers.

**Architecture:** A pure dispatch compiler converts the validated business plan and runtime state into one `AgentToolCall`. `AgentRunner` sends that call through the unchanged `ToolGateway`, records a code Trace node, and invokes the answer model only after the Controller reports that plan facts are complete.

**Tech Stack:** Python, Pydantic, FastAPI Agent Runtime, pytest, YAML workflow manifest.

## Global Constraints

- Work directly on the current `main` branch; do not create a worktree or subagent.
- Preserve `ToolGateway` permission, validation, retry, timeout and audit boundaries.
- Do not expose raw model thinking.
- Update README and architecture documentation in the same batch.

---

### Task 1: Specify deterministic argument compilation

**Files:**
- Create: `tests/test_agent_deterministic_dispatch.py`
- Create: `app/agent_planning/dispatch.py`

**Interfaces:**
- Consumes: `PlanningExecution`, `ControllerDecision`, `AgentRunState`, user query.
- Produces: `build_deterministic_tool_call(...) -> AgentToolCall` or `DeterministicDispatchError`.

- [x] Write failing tests for search, effective rule, SQL preparation, trial run, diagnosis, change preview and upload arguments.
- [x] Run the focused test and confirm the dispatch module is missing.
- [x] Implement the minimal pure argument compiler and make the focused test pass.

### Task 2: Bypass Executor for planned tool steps

**Files:**
- Modify: `tests/test_agent_planned_runner.py`
- Modify: `app/agent_planning/runtime.py`
- Modify: `app/agent_runtime/runner.py`

**Interfaces:**
- Consumes: `ControllerAction.EXECUTE_TOOL` and the dispatch compiler.
- Produces: direct Gateway calls plus `deterministic_tool_dispatch` Trace nodes; one answer-model call after facts are complete.

- [x] Replace model-tool-call expectations with failing one-answer-call expectations.
- [x] Run focused Runner tests and confirm the existing loop still invokes the model for tools.
- [x] Add direct dispatch branching while reusing the current Gateway/result handling path.
- [x] Run focused Runner and controller tests to green.

### Task 3: Mark empty model actions and expose safe thinking metadata

**Files:**
- Modify: `tests/test_agent_planned_runner.py`
- Modify: `tests/test_ollama_tool_adapter.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/llm/ollama_tools.py`

**Interfaces:**
- Consumes: `AgentModelResponse.content`, `tool_calls`, Ollama `message.thinking` and response counters.
- Produces: `MODEL_EMPTY_ACTION` warning Trace and safe usage metadata without thinking text.

- [x] Write failing tests for empty-action warning and thinking metadata projection.
- [x] Implement one guarded retry for empty final answers and metadata-only thinking recording.
- [x] Run focused tests to green.

### Task 4: Align prompts, Trace manifest and documentation

**Files:**
- Modify: `app/prompts/agent_executor.txt`
- Modify: `app/prompts/agent_executor_step.txt`
- Modify: `app/prompts/README.md`
- Modify: `app/workflows/agent_runtime.yaml`
- Modify: `README.md`
- Modify: `docs/architecture/agent-runtime-current.md`
- Modify: `docs/operations/2026-07-16-changes.md`

**Interfaces:**
- Consumes: final runtime behavior.
- Produces: an accurate prompt catalog and bilingual Trace node definition.

- [x] Remove tool-routing instructions from Executor prompts.
- [x] Add the dispatch node and redefine Executor as final-answer-only in the workflow manifest.
- [x] Update README, architecture and change log.
- [x] Run prompt/manifest tests, Ruff, compileall and the complete pytest suite.
- [ ] Commit and push `main`; if GitHub remains unreachable, preserve the local commits without changing remote history.
