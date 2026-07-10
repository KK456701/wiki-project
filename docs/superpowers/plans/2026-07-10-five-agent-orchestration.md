# Five-Agent Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the existing mixed workflow into five explicit specialized agents coordinated by one reusable orchestrator without changing current API, SSE, Trace-detail expansion, or four-indicator results.

**Architecture:** Domain agents wrap existing rule, metadata, SQL, diagnosis, and interaction capabilities behind narrow methods. `CoreIndicatorOrchestrator` owns intent routing and shared preparation; `app/agent/graph.py` and FastAPI remain adapters and delegate domain work to the orchestrator. Existing `SQLGenerationAgent` and `DiagnoseAgent` remain execution engines.

**Tech Stack:** Python 3.11+, FastAPI, SQLAlchemy, LangGraph optional adapter, unittest, YAML workflow manifest.

## Global Constraints

- Keep the four-indicator scope and `hospital_001` demo behavior.
- MySQL remains the rule primary store; Wiki remains read-only fallback.
- Do not rename existing HTTP routes or remove response fields.
- Keep Trace nodes collapsed by default and details click-to-expand.
- Do not perform a full LangGraph rewrite in this batch.
- Use TDD for each behavior change.

---

### Task 1: Specialized Agent Boundaries

**Files:**
- Create: `app/agents/__init__.py`
- Create: `app/agents/metadata_parsing.py`
- Create: `app/agents/indicator_generation.py`
- Create: `app/agents/caliber_adaptation.py`
- Create: `app/agents/root_cause_diagnosis.py`
- Create: `app/agents/human_interaction.py`
- Test: `tests/test_specialized_agents.py`

**Interfaces:**
- `MetadataParsingAgent.sync(provider, hospital_id, db_name) -> dict[str, Any]`
- `MetadataParsingAgent.precheck(hospital_id, rule_id) -> dict[str, Any]`
- `IndicatorGenerationAgent.generate(**kwargs) -> dict[str, Any]`
- `CaliberAdaptationAgent.search(query, limit=5) -> dict[str, Any]`
- `CaliberAdaptationAgent.resolve(rule_id, hospital_id) -> dict[str, Any]`
- `CaliberAdaptationAgent.field_mapping(rule_id, hospital_id) -> dict[str, Any]`
- `CaliberAdaptationAgent.preview_feedback(rule_id, hospital_id, query) -> dict[str, Any]`
- `RootCauseDiagnosisAgent.run(**kwargs) -> dict[str, Any]`
- `HumanInteractionAgent.understand(query, memory_context=None) -> dict[str, Any]`
- `HumanInteractionAgent.answer(query, effective_rule) -> tuple[str, str]`

- [ ] **Step 1: Write failing boundary tests**

Add tests that inject small fakes and assert each wrapper calls only its owned dependency, exposes its stable `agent_id`, and returns the underlying structured result unchanged.

- [ ] **Step 2: Run boundary tests and verify RED**

Run: `python -B -m unittest tests.test_specialized_agents -v`

Expected: import failure because `app.agents` does not exist.

- [ ] **Step 3: Implement minimal wrappers**

Each class owns one dependency and delegates through the interfaces listed above. Human interaction moves intent parsing and answer generation out of `graph.py`; compatibility functions in `graph.py` delegate to the class.

- [ ] **Step 4: Run boundary tests and verify GREEN**

Run: `python -B -m unittest tests.test_specialized_agents tests.test_sqlgen -v`

Expected: all selected tests pass.

### Task 2: Core Orchestrator and Routing

**Files:**
- Create: `app/agents/orchestrator.py`
- Test: `tests/test_agent_orchestrator.py`

**Interfaces:**
- `PreparedRequest` dataclass carries query, intent, retrieval query, hospital, rule ID, effective rule, field mapping, custom filters, and errors.
- `CoreIndicatorOrchestrator.owner_for_intent(intent) -> str`
- `CoreIndicatorOrchestrator.prepare(query, hospital_id, memory_context=None) -> PreparedRequest`
- `CoreIndicatorOrchestrator.generate_indicator(prepared, **period_options) -> dict[str, Any]`
- `CoreIndicatorOrchestrator.diagnose(prepared, **diagnose_options) -> dict[str, Any]`
- `CoreIndicatorOrchestrator.sync_metadata(provider, hospital_id, db_name) -> dict[str, Any]`

- [ ] **Step 1: Write failing routing tests**

Assert routing ownership:

```python
expected = {
    "chat": "human_interaction",
    "query": "human_interaction",
    "feedback": "caliber_adaptation",
    "generate_sql": "indicator_generation",
    "trial_run": "indicator_generation",
    "diagnose": "root_cause_diagnosis",
    "metadata_sync": "metadata_parsing",
}
```

Also assert `prepare` calls interaction first, skips rule lookup for chat, and resolves search/effective rule/mapping once for indicator intents.

- [ ] **Step 2: Run routing tests and verify RED**

Run: `python -B -m unittest tests.test_agent_orchestrator -v`

Expected: import failure because the orchestrator does not exist.

- [ ] **Step 3: Implement the orchestrator**

Use constructor injection for all five agents. Keep routing deterministic and return structured state; do not add HTTP, SSE, Trace, or persistence code to the orchestrator.

- [ ] **Step 4: Run routing tests and verify GREEN**

Run: `python -B -m unittest tests.test_agent_orchestrator -v`

Expected: all routing tests pass.

### Task 3: Chat and Stream Adapters

**Files:**
- Modify: `app/agent/graph.py`
- Modify: `tests/test_agent_workflow.py`

**Interfaces:**
- Keep `detect_intent`, `run_chat`, and `run_chat_stream` signatures compatible.
- Add optional `orchestrator: CoreIndicatorOrchestrator | None = None` injection to chat entry points.
- `run_chat` and `run_chat_stream` use the same orchestrator routing and specialist methods.

- [ ] **Step 1: Write failing adapter tests**

Add tests injecting a fake orchestrator and assert both non-stream and stream paths use the same owner and preparation result. Keep existing event names and final payload assertions.

- [ ] **Step 2: Run adapter tests and verify RED**

Run: `python -B -m unittest tests.test_agent_workflow -v`

Expected: failure because chat functions do not accept or use the orchestrator.

- [ ] **Step 3: Wire the orchestrator minimally**

Build a default orchestrator from existing dependencies when one is not injected. Replace direct search/effective-rule/SQL/diagnosis calls with specialist calls while retaining existing Trace and SSE formatting adapters.

- [ ] **Step 4: Run adapter tests and verify GREEN**

Run: `python -B -m unittest tests.test_agent_workflow -v`

Expected: all workflow tests pass.

### Task 4: FastAPI Direct Workflows

**Files:**
- Modify: `app/api/main.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Add `_create_agent_orchestrator(...)` factory at the API composition boundary.
- `/api/sql/generate` calls `orchestrator.generate_indicator`.
- `/api/diagnose/run` calls `orchestrator.diagnose`.
- `/api/metadata/sync` calls `orchestrator.sync_metadata` while retaining recovery-task and Trace behavior.

- [ ] **Step 1: Write failing API delegation tests**

Patch `_create_agent_orchestrator` with a fake and assert each direct endpoint delegates to the correct specialist through the orchestrator.

- [ ] **Step 2: Run API tests and verify RED**

Run: `python -B -m unittest tests.test_api -v`

Expected: failure because the factory and delegation do not exist.

- [ ] **Step 3: Wire API composition**

Keep authentication, request validation, recovery tasks, Trace recording, and response schemas in FastAPI. Move only domain execution behind the orchestrator.

- [ ] **Step 4: Run API tests and verify GREEN**

Run: `python -B -m unittest tests.test_api -v`

Expected: all API tests pass.

### Task 5: Agent Ownership Observability and Documentation

**Files:**
- Modify: `app/workflows/core_indicator_chat.yaml`
- Modify: `app/workflows/manifest.py`
- Modify: `app/observability/trace.py`
- Modify: `web/index.html`
- Modify: `README.md`
- Modify: `tests/test_workflow_manifest.py`
- Modify: `tests/test_observability_trace.py`
- Modify: `tests/test_api.py`

**Interfaces:**
- Manifest nodes may declare `agent_owner`.
- Annotated Trace nodes expose `agent_owner` in details.
- Frontend detail view labels it `负责 Agent`; the summary remains unchanged.

- [ ] **Step 1: Write failing ownership tests**

Assert every required workflow node has a valid owner from the five fixed IDs, Trace annotations preserve it, and HTML renders `负责 Agent` inside the hidden detail panel without adding it to the summary metadata line.

- [ ] **Step 2: Run ownership tests and verify RED**

Run: `python -B -m unittest tests.test_workflow_manifest tests.test_observability_trace tests.test_api -v`

Expected: failures for missing `agent_owner`.

- [ ] **Step 3: Add ownership metadata and README architecture**

Add the five IDs to manifest nodes, propagate them through annotation, render only in expanded details, and document the unified orchestrator and compatibility strategy.

- [ ] **Step 4: Run ownership tests and verify GREEN**

Run: `python -B -m unittest tests.test_workflow_manifest tests.test_observability_trace tests.test_api -v`

Expected: all selected tests pass.

### Task 6: Full Regression and Local Acceptance

**Files:**
- Modify only files needed to fix regressions found by the checks.

- [ ] **Step 1: Run the full suite**

Run: `python -B -m unittest discover -s tests -v`

Expected: all tests pass.

- [ ] **Step 2: Check diffs**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; runtime-generated pending Markdown and `tmp/` remain untracked and unstaged.

- [ ] **Step 3: Local API acceptance**

Verify ordinary query, feedback preview, SQL generation, diagnosis, and metadata sync. Confirm four SQL trial results remain `25.00`, `66.67`, `75.00`, and `50.00`.

- [ ] **Step 4: UI acceptance**

Open a Trace, confirm summaries remain collapsed, then expand one node and confirm `负责 Agent` appears only in details.
