# Indicator Generation Closed Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a safe, versioned workflow that turns a natural-language hospital indicator description into a metadata-confirmed, trial-run SQL and an approved existing-rule override or hospital-defined indicator.

**Architecture:** Drafts and immutable snapshots are isolated from active rules. The LLM produces typed caliber and SQL-plan data, while deterministic services resolve metadata, render parameterized single-table SQL, run existing validators and DBHub trials, then publish through an explicit administrator approval transaction.

**Tech Stack:** Python 3, FastAPI, Pydantic, SQLAlchemy, MySQL 8, Ollama, DBHub MCP, vanilla HTML/CSS/JavaScript, `unittest`.

## Global Constraints

- `med_index_standard` remains read-only.
- User-visible terms are “指标设计稿”“本院口径差异”“本院新增指标”.
- The LLM never persists arbitrary executable SQL.
- Version 1 supports `ratio` and `count` over one table only.
- Every executable SQL is one parameterized read-only `SELECT` with hospital and time filters.
- Draft edits invalidate generated SQL and trial results.
- Writes fail closed when MySQL is unavailable and never fall back to Wiki.
- Every batch follows RED-GREEN-REFACTOR, then a Chinese Conventional Commit and push.

---

### Task 1: Draft and hospital-defined rule storage

**Files:**
- Modify: `scripts/init_runtime_db.sql`
- Create: `app/indicators/contracts.py`
- Create: `app/indicators/repository.py`
- Create: `tests/test_indicator_drafts.py`

**Interfaces:**
- `IndicatorDraftRepository.create(spec, actor_id) -> IndicatorDraft`
- `save_version(draft_id, expected_version, changes, actor_id) -> IndicatorDraft`
- `transition(draft_id, expected_version, status, snapshot_changes, actor_id) -> IndicatorDraft`
- Current and immutable tables for drafts and hospital-defined rules.

- [ ] Write failing schema and lifecycle tests for create, optimistic version save, stale-version rejection, and immutable snapshots.
- [ ] Run `python -B -m unittest tests.test_indicator_drafts -v` and verify the tables/repository are missing.
- [ ] Add the four tables, status/version indexes, and nullable draft linkage columns on `med_generated_sql`.
- [ ] Implement repository methods with transaction boundaries and JSON snapshots.
- [ ] Run the focused tests, `git diff --check`, commit `feat: 增加指标设计稿版本存储`, and push.

### Task 2: Natural-language draft parsing

**Files:**
- Create: `app/indicators/parser.py`
- Modify: `app/agents/contracts.py`
- Modify: `app/agents/indicator_generation.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/agent/intent.py`
- Create: `tests/test_indicator_draft_parser.py`
- Modify: `tests/test_agent_orchestrator.py`

**Interfaces:**
- `IndicatorDraftParser.parse(query, hospital_id) -> IndicatorDraftSpec`
- Adds `create_indicator` intent owned by `indicator_generation`.
- The orchestrator exposes `create_indicator_draft(query, hospital_id, actor_id)`.

- [ ] Write failing tests for valid ratio/count JSON, malformed model output, unsupported joins, and existing-rule references.
- [ ] Verify RED with focused parser and orchestrator tests.
- [ ] Implement a Chinese Ollama JSON prompt, strict extraction, Pydantic validation, and readable errors.
- [ ] Generate provisional hospital codes without writing active rule tables.
- [ ] Verify focused tests, commit `feat: 支持自然语言生成指标设计稿`, and push.

### Task 3: Metadata confirmation and deterministic SQL plan

**Files:**
- Create: `app/indicators/metadata.py`
- Create: `app/indicators/sql_plan.py`
- Create: `tests/test_indicator_sql_plan.py`
- Modify: `app/agents/metadata_parsing.py`
- Modify: `app/agents/indicator_generation.py`
- Modify: `app/db/repositories.py`

**Interfaces:**
- `suggest_field_mappings(engine, draft) -> MappingSuggestionResult`
- `confirm_field_mappings(draft_id, expected_version, mappings, actor_id)`
- `render_indicator_sql(plan, mappings) -> {sql_text, params}`
- SQL conditions are typed and restricted to the design operator allowlist.

- [ ] Write failing tests for unique/ambiguous/missing metadata, multi-table rejection, ratio/count rendering, unknown operators, unconfirmed columns, and mandatory hospital/time filters.
- [ ] Verify RED.
- [ ] Implement metadata suggestions from runtime snapshots and draft-only mapping confirmation.
- [ ] Implement the deterministic SQL renderer, then call the existing validator and persist SQL with draft/version linkage.
- [ ] Verify focused and existing SQL safety tests, commit `feat: 完成指标设计稿元数据与SQL闭环`, and push.

### Task 4: Trial, approval, publication, and recovery

**Files:**
- Create: `app/indicators/service.py`
- Modify: `app/rules/repository.py`
- Modify: `app/api/main.py`
- Modify: `app/observability/workflow_nodes.py`
- Create: `tests/test_indicator_publish.py`
- Modify: `tests/test_api.py`
- Modify: `tests/test_rule_repository.py`

**Interfaces:**
- Draft endpoints for generate/list/detail/save/mapping/SQL/trial/submit/approve/reject.
- MySQL rule search accepts hospital context and resolves published hospital-defined indicators.
- Hospital-defined version list and append-only restore endpoints.

- [ ] Write failing end-to-end tests for submit-before-trial rejection, existing-rule publication, new-rule publication, search after publication, and append-only restore.
- [ ] Verify RED.
- [ ] Wire DBHub trial execution and enforce current-version trial evidence.
- [ ] Publish existing and new indicators in one transaction and copy confirmed mappings only at approval.
- [ ] Add Trace nodes and API authentication boundaries.
- [ ] Verify API, rule repository, SQL and trace tests; commit `feat: 完成指标设计稿审批发布闭环`, and push.

### Task 5: Lightweight indicator-design UI and acceptance

**Files:**
- Modify: `web/index.html`
- Modify: `README.md`
- Modify: `tests/test_api.py`
- Create: `tests/test_indicator_ui.py`

**Interfaces:**
- Top-bar “指标设计稿” button and one modal covering generation, editing, mappings, SQL, trial, submit and approval state.

- [ ] Write failing DOM/API wiring tests for the button, modal, status labels and workflow calls.
- [ ] Verify RED.
- [ ] Implement the compact modal using existing visual language and Chinese status text; keep technical JSON behind detail expansion.
- [ ] Document initialization, supported metric/operator scope, verification path and safety limits.
- [ ] Run `python -B -m unittest discover -s tests -v`, `python -B -m compileall -q app`, and `git diff --check`.
- [ ] Validate a real hospital-defined ratio indicator against local MySQL/DBHub and confirm it is searchable after approval.
- [ ] Commit `feat: 增加指标设计稿前端闭环` and push.

## Completion Gate

- Drafts remain invisible to active rule search before approval.
- Every save/transition has an immutable snapshot and optimistic version check.
- SQL plans cannot reference unconfirmed metadata or unsupported joins/operators.
- Trial evidence is tied to the exact draft version submitted for approval.
- Existing rules publish as hospital caliber differences; new rules publish as hospital-defined indicators.
- Published hospital-defined indicators work through chat, SQL, trial and diagnosis paths.
- Full regression and real local acceptance pass with a clean worktree.
