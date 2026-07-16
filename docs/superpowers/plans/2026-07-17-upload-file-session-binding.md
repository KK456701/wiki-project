# Upload File Session Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the latest uploaded Excel `file_key` through the chat API into persisted Agent state so natural-language follow-ups can deterministically analyze that file.

**Architecture:** The browser keeps the latest upload reference for the active chat session and sends it as an optional chat payload field. The API and service bind it to `AgentRunState.current_upload_file_key`; deterministic dispatch reads that structured state before its legacy text fallback. Existing hospital-prefix authorization in the upload tool remains the final tenant boundary.

**Tech Stack:** Vanilla JavaScript, FastAPI, Pydantic, Agent conversation memory, pytest, Node-based frontend unit tests.

## Global Constraints

- Work directly on current `main`; do not create a worktree or subagent.
- Preserve Unicode filenames such as `无标题.xlsx` while rejecting path separators.
- Do not expose Excel row-level patient data to the model or public SSE.
- Update README and change documentation in the same batch.
- The user performs browser validation; automated validation remains required.

---

### Task 1: Carry the upload reference in the browser payload

**Files:**
- Modify: `tests/test_agent_frontend_ui.py`
- Modify: `web/agent-runtime.js`
- Modify: `web/index.html`

**Interfaces:**
- Consumes: upload response `file_key`.
- Produces: `buildChatPayload(query, sessionId, modelId, fileKey)` with optional `file_key`; `streamAgent({fileKey})` forwards it.

- [ ] Add a failing frontend test asserting the fourth payload argument becomes `file_key` and the field is omitted when empty.
- [ ] Run `pytest -q tests/test_agent_frontend_ui.py::test_chat_payload_carries_latest_uploaded_file_key` and confirm the field is missing.
- [ ] Extend `buildChatPayload` and `streamAgent`, keep `latestUploadedFileKey` in `index.html`, set it after upload, pass it to chat, and clear it on new session.
- [ ] Run `pytest -q tests/test_agent_frontend_ui.py` and confirm all frontend runtime tests pass.

### Task 2: Bind the reference to API and conversation state

**Files:**
- Modify: `tests/test_agent_api.py`
- Modify: `tests/test_agent_capabilities.py`
- Modify: `app/api/agent_routes.py`
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/agent_runtime/memory.py`

**Interfaces:**
- Consumes: optional `AgentChatRequest.file_key`.
- Produces: `AgentRunState.current_upload_file_key`, restored through `_safe_state_metadata` and `AgentConversationMemory.open`.

- [ ] Add failing API tests proving non-stream chat forwards a Unicode `file_key` and rejects values containing `/` or `\\`.
- [ ] Add a failing service test proving `file_key` is assigned before the runner receives the state.
- [ ] Add a failing memory round-trip test proving `current_upload_file_key` is included in safe state metadata and restored.
- [ ] Run those focused tests and confirm the new field/signatures are absent.
- [ ] Add the optional validated request field, forward it through both routes and service methods, update state before execution, and persist/restore it in safe metadata.
- [ ] Run `pytest -q tests/test_agent_api.py tests/test_agent_capabilities.py tests/test_agent_conversation_memory.py` and confirm the new behavior passes.

### Task 3: Prefer structured attachment state during deterministic dispatch

**Files:**
- Modify: `tests/test_agent_deterministic_dispatch.py`
- Modify: `app/agent_planning/dispatch.py`

**Interfaces:**
- Consumes: `AgentRunState.current_upload_file_key`.
- Produces: `AgentToolCall(name="analyze_uploaded_indicators", arguments={"file_key": ...})` without requiring the file number in user text.

- [ ] Add a failing test where the user says only“帮我分析刚上传的文件” and state contains a Unicode upload key.
- [ ] Add a precedence assertion showing a newly supplied structured key wins over an older file number in history.
- [ ] Run the focused test and confirm dispatch reports `UPLOAD_FILE_KEY_MISSING` or selects the historical key.
- [ ] Update `_file_key` to return the validated structured key first and keep text parsing as backward compatibility.
- [ ] Run `pytest -q tests/test_agent_deterministic_dispatch.py` and confirm all dispatch cases pass.

### Task 4: Documentation, full verification and delivery

**Files:**
- Modify: `README.md`
- Modify: `docs/operations/2026-07-16-changes.md`
- Modify: `docs/superpowers/plans/2026-07-17-upload-file-session-binding.md`

**Interfaces:**
- Consumes: final implementation and test evidence.
- Produces: current operational documentation and a deliverable commit.

- [ ] Document explicit upload reference binding and the “new upload replaces old reference; new session clears it” behavior.
- [ ] Run Ruff on changed Python files, `python -m compileall -q app`, the focused tests, then `pytest -q`.
- [ ] Inspect `git diff --check` and scan the diff for credentials or patient row data.
- [ ] Commit current changes, attempt `git push origin main` without force, and restart the 8765 service so it loads the new code.
