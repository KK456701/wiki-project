# Remove Legacy Chat Prompts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the obsolete legacy chat prompts and every production code path that loads them.

**Architecture:** Keep `HumanInteractionAgent` as a deterministic domain helper used by non-chat business APIs. Remove its unused optional LLM classifier/composer while leaving the current Planner/Executor Agent Runtime untouched.

**Tech Stack:** Python, FastAPI domain services, pytest, Markdown.

## Global Constraints

- Work directly on the current `main` branch; do not create a worktree or subagent.
- Do not change current Agent Runtime prompts or model behavior.
- Update README and prompt catalog in the same batch.

---

### Task 1: Specify the deleted prompt surface

**Files:**
- Modify: `tests/test_prompt_registry.py`
- Modify: `tests/test_legacy_chat_removed.py`
- Modify: `tests/test_prompt_context.py`
- Modify: `tests/test_specialized_agents.py`

**Interfaces:**
- Consumes: `app.prompts`, `HumanInteractionAgent`.
- Produces: regression checks that legacy prompt files/loaders and optional LLM behavior are absent.

- [x] Replace legacy prompt loadability assertions with absence assertions.
- [x] Remove tests for the deleted intent prompt and optional LLM override.
- [x] Run focused tests and verify they fail before production deletion.

### Task 2: Delete prompts and unused LLM branches

**Files:**
- Delete: `app/prompts/legacy_chat_intent.txt`
- Delete: `app/prompts/legacy_chat_answer.txt`
- Modify: `app/prompts/__init__.py`
- Modify: `app/agents/human_interaction.py`

**Interfaces:**
- Consumes: deterministic query text, memory context and effective rule dictionaries.
- Produces: deterministic `understand`, `answer`, contextual rewrite and fact guard behavior.

- [x] Delete the prompt files and dedicated loader functions.
- [x] Remove optional `llm_client`, prompt builders and JSON extraction code.
- [x] Keep deterministic behavior and run focused tests to green.

### Task 3: Synchronize documentation and verify

**Files:**
- Modify: `app/prompts/README.md`
- Modify: `README.md`
- Modify: `docs/architecture/agent-runtime-current.md`

**Interfaces:**
- Consumes: final source tree.
- Produces: an accurate inventory containing only active production prompts.

- [x] Remove the legacy prompt catalog and document deterministic `HumanInteractionAgent` behavior.
- [x] Run reference scans, formatting checks, focused tests and the full test suite.
- [ ] Commit, push `main`, restart the local service and verify health.
