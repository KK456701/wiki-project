# Remove Legacy Chat and Add Qwen3 8B Thinking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the obsolete stable/legacy chat path and register a local Qwen3 8B thinking model without changing the controlled Agent architecture.

**Architecture:** The browser always calls the authenticated Agent SSE endpoint. `AgentRuntimeService` remains the only chat orchestration service. Ollama thinking is a model-registry property propagated into the Ollama request but never projected to public responses.

**Tech Stack:** FastAPI, Pydantic, vanilla JavaScript, Ollama HTTP API, pytest.

## Global Constraints

- Work directly on the current `main` branch; do not create a worktree or subagent.
- Delete the legacy chat and Shadow production path, but retain `app/agents/` components still imported by current tools and APIs.
- Keep Qwen3 4B as the default model.
- Do not expose raw model thinking in SSE, Trace summaries, or final answers.

---

### Task 1: Specify the removed legacy surface

**Files:**
- Create: `tests/test_legacy_chat_removed.py`
- Modify: `tests/test_agent_frontend_ui.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Consumes: FastAPI `app.routes` and the browser source files.
- Produces: regression assertions that old routes, old mode labels, and old fallback calls are absent.

- [ ] **Step 1: Write failing tests** asserting `/api/chat`, `/api/chat/stream`, `streamLegacyChat`, `canFallbackToLegacy`, and the visible `稳定流程` badge are absent.
- [ ] **Step 2: Run the focused tests** with `python -m pytest tests/test_legacy_chat_removed.py tests/test_agent_frontend_ui.py -q`; expect failures against current code.
- [ ] **Step 3: Keep these tests red** until Tasks 2 and 3 remove the production surface.

### Task 2: Specify and implement Qwen3 8B thinking configuration

**Files:**
- Modify: `tests/test_model_registry.py`
- Modify: `tests/test_ollama_client.py`
- Modify: `app/llm/model_registry.py`
- Modify: `app/llm/ollama.py`
- Modify: `config.yaml`
- Modify: `config.example.yaml`

**Interfaces:**
- Consumes: `ModelInfo`, `ModelRegistry.build_adapter`, and `OllamaClient.chat`.
- Produces: `ModelInfo.thinking: bool` and Ollama request field `think: true` for the configured 8B model.

- [ ] **Step 1: Write failing tests** that parse `thinking: true`, pass it to `OllamaClient`, include `think: true` for the 8B request, and omit it for non-thinking models.
- [ ] **Step 2: Run the focused tests** and verify they fail because the field is unsupported.
- [ ] **Step 3: Add the minimal model field and request propagation** without exposing returned thinking text.
- [ ] **Step 4: Add `ollama-qwen3-8b-thinking` to both configuration files**, leaving `default_model: ollama-qwen3` unchanged.
- [ ] **Step 5: Re-run the focused tests** and expect them to pass.

### Task 3: Delete the legacy backend and Shadow path

**Files:**
- Delete: `app/agent/graph.py`
- Delete: `app/agent/__init__.py`
- Delete: `app/agent_runtime/shadow.py`
- Delete: `tests/test_agent_workflow.py`
- Delete: `tests/test_agent_shadow.py`
- Delete: `tests/test_agent_shadow_api.py`
- Delete: `docs/operations/agent-tool-calling-rollout.md`
- Modify: `app/api/main.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/agent_tools/catalog.py`
- Modify: `app/agent_tools/__init__.py`
- Modify: `config.example.yaml`

**Interfaces:**
- Consumes: authenticated `/api/agent/*` routes.
- Produces: one chat backend with no LangGraph/legacy/Shadow import or configuration mode.

- [ ] **Step 1: Remove old chat endpoints, request model, imports, Shadow scheduling, and LangGraph health checks.**
- [ ] **Step 2: Remove Shadow-only registry builders and service mode branching.**
- [ ] **Step 3: Delete obsolete source, tests, and rollout documentation.**
- [ ] **Step 4: Run route/import tests** and fix only dependencies caused by the removal.

### Task 4: Make the frontend Agent-only

**Files:**
- Modify: `web/agent-runtime.js`
- Modify: `web/index.html`
- Modify: `tests/test_agent_frontend_ui.py`

**Interfaces:**
- Consumes: `/api/agent/capabilities` and `/api/agent/chat/stream`.
- Produces: a single authenticated streaming path and model selector, with no mode badge or legacy retry.

- [ ] **Step 1: Remove legacy mode state, mode selection helpers, badge rendering, and legacy fallback messages.**
- [ ] **Step 2: Remove `streamLegacyChat` and make `streamChat` call the Agent stream directly.**
- [ ] **Step 3: Render an explicit authentication/service error instead of retrying another endpoint.**
- [ ] **Step 4: Run frontend source-contract tests** and expect them to pass.

### Task 5: Verify, document, and run locally

**Files:**
- Modify: `docs/operations/2026-07-16-changes.md`
- Create: `docs/architecture/agent-runtime-current.md`

**Interfaces:**
- Consumes: final source tree and actual prompt constants.
- Produces: current Mermaid architecture plus an inventory of LLM call sites and prompt text.

- [ ] **Step 1: Search for remaining production references** to `/api/chat`, stable/legacy mode, Shadow, and `app.agent.graph`; allow only historical design documents where explicitly marked obsolete.
- [ ] **Step 2: Document the current flow and every production LLM call site** using prompt constants copied from source.
- [ ] **Step 3: Run `python -m compileall -q app` and the full `python -m pytest -q`.**
- [ ] **Step 4: Restart port 8765, verify `/api/health`, capabilities, and the presence of the 8B model.**
- [ ] **Step 5: Commit and push the implementation to `origin/main`.**

