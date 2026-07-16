# Agent Plan Compile Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a model-independent request plan contract, deterministic plan compiler/controller, evidence verifier, and constrained tool exposure for the tool-calling Agent runtime.

**Architecture:** The model produces only semantic `RequestPlan` data. Server-side components validate and compile it into capability nodes, resolve time expressions, select the next legal capability, map capabilities to tools, and verify evidence before final answers. Existing AgentRunner behavior remains available when no planning runtime is injected; AgentRuntimeService enables the new path.

**Tech Stack:** Python 3.11+, Pydantic v2, FastAPI, pytest, existing AgentRuntime/ToolRegistry contracts.

## Global Constraints

- Work directly on the current branch; do not create a worktree.
- Do not use subagents.
- Planner output must not contain `steps`, `proposed_steps`, or tool names.
- SQL text remains server-side; model-visible output uses `prepared_sql_handle`/`sql_id` only.
- Replanning defaults to at most one attempt.
- Existing legacy chat, SSE, model selection, and upload behavior remain compatible.

---

### Task 1: Planning Contracts and Compiler

**Files:**
- Create: `app/agent_planning/contracts.py`
- Create: `app/agent_planning/compiler.py`
- Create: `app/agent_planning/__init__.py`
- Test: `tests/test_agent_plan_compiler.py`

**Interfaces:**
- Produces: `RequestPlan`, `CompiledPlan`, `PlanNode`, `PlanIntent`, `RequestedOutput`, `PlanCompiler.compile(plan, state)`.

- [ ] **Step 1: Write failing contract tests** proving extra step fields are rejected and trial-run output compiles into capability dependencies.
- [ ] **Step 2: Run** `python -m pytest tests/test_agent_plan_compiler.py -q` and confirm failures are caused by missing planning modules.
- [ ] **Step 3: Implement strict Pydantic contracts** with `extra="forbid"`; do not define a steps field on `RequestPlan`.
- [ ] **Step 4: Implement deterministic compilation** from intent/requested outputs to ordered capability nodes and required fact types.
- [ ] **Step 5: Run** `python -m pytest tests/test_agent_plan_compiler.py -q` and confirm all tests pass.

### Task 2: Time Resolver and Plan Validation

**Files:**
- Create: `app/agent_planning/time_resolver.py`
- Create: `app/agent_planning/validator.py`
- Test: `tests/test_agent_plan_validator.py`

**Interfaces:**
- Consumes: `RequestPlan`.
- Produces: `PlanValidation`, `ResolvedTimeRange`, `PlanValidator.validate(plan, context, now)`.

- [ ] **Step 1: Write failing tests** for 本月、上月、今年至今、今年 N 月到现在, explicit ISO dates, conflicting no-database/result constraints, and unknown output values.
- [ ] **Step 2: Run** `python -m pytest tests/test_agent_plan_validator.py -q` and confirm expected failures.
- [ ] **Step 3: Implement left-closed/right-open time resolution** with explicit timezone-aware datetimes.
- [ ] **Step 4: Implement target-conflict and policy validation** returning deterministic fallback categories.
- [ ] **Step 5: Run** the focused test file and confirm all tests pass.

### Task 3: Capability Controller and Tool Router

**Files:**
- Create: `app/agent_planning/controller.py`
- Modify: `app/agent_tools/registry.py`
- Test: `tests/test_agent_plan_controller.py`

**Interfaces:**
- Consumes: `CompiledPlan`, `AgentRunState`, `AgentRuntimeContext`, `ToolRegistry`.
- Produces: `ControllerDecision`, `AgentStateController.next_decision(...)`, `ToolRegistry.list_for_names(...)`.

- [ ] **Step 1: Write failing tests** showing a trial-run plan exposes search first, effective-rule next, prepare next, trial-run next, and never more than two tools.
- [ ] **Step 2: Run** `python -m pytest tests/test_agent_plan_controller.py -q` and confirm expected failures.
- [ ] **Step 3: Implement capability-to-tool bindings and fact-based completion checks.**
- [ ] **Step 4: Implement deterministic fallback decisions** for clarification, implementation support, operator failure, and security denial.
- [ ] **Step 5: Run** the focused tests and confirm all pass.

### Task 4: Evidence Envelope and Verifier

**Files:**
- Create: `app/agent_planning/verifier.py`
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_tools/sql_tools.py`
- Test: `tests/test_agent_plan_verifier.py`

**Interfaces:**
- Produces: `EvidenceEnvelope`, `VerificationResult`, `PlanVerifier.verify(...)`.

- [ ] **Step 1: Write failing tests** for missing required facts, numerator/denominator/rate mismatch, denominator zero, and SQL/result context mismatch.
- [ ] **Step 2: Run** `python -m pytest tests/test_agent_plan_verifier.py -q` and confirm expected failures.
- [ ] **Step 3: Implement evidence envelopes and Decimal-based numeric verification.**
- [ ] **Step 4: Expose existing SQL context digest and rule/period identifiers in safe structured tool results.**
- [ ] **Step 5: Run** focused verifier and SQL tool tests and confirm all pass.

### Task 5: Model Planner and Runtime Integration

**Files:**
- Create: `app/agent_planning/planner.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/agent_runtime/prompts.py`
- Modify: `app/agent_runtime/contracts.py`
- Test: `tests/test_agent_planned_runner.py`
- Test: `tests/test_agent_capabilities.py`

**Interfaces:**
- Produces: `RequestPlanner.plan(...)`, optional planned execution dependencies on `AgentRunner`.

- [ ] **Step 1: Write failing tests** proving planner JSON cannot contain steps/tool names, controller narrows tools, ordinary greetings use no tool, and verifier blocks inconsistent final answers.
- [ ] **Step 2: Run** focused tests and confirm expected failures.
- [ ] **Step 3: Implement the planner prompt and strict JSON parsing** with one repair attempt and no tool schemas.
- [ ] **Step 4: Integrate optional planned execution into AgentRunner** while retaining legacy construction behavior for existing tests and Shadow.
- [ ] **Step 5: Enable planned execution from AgentRuntimeService** and expose `orchestration="plan_compile_control"` in capabilities.
- [ ] **Step 6: Run** planned-runner, capabilities, execution-loop, SSE, and frontend API tests.

### Task 6: Replan Limits, Fallback Projection, and Regression Verification

**Files:**
- Modify: `app/agent_runtime/events.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `docs/operations/2026-07-16-changes.md`
- Test: `tests/test_agent_planned_runner.py`
- Test: `tests/test_agent_runtime_events.py`

**Interfaces:**
- Consumes: controller/verifier failure codes.
- Produces: public clarification/fallback events without sensitive internal details.

- [ ] **Step 1: Write failing tests** for one-replan maximum, failed-plan fingerprint blocking, and deterministic fallback categories.
- [ ] **Step 2: Run** focused tests and confirm expected failures.
- [ ] **Step 3: Implement replan state and public fallback projection.**
- [ ] **Step 4: Update operations documentation** with the new orchestration flow and rollout switch.
- [ ] **Step 5: Run targeted planning/runtime suites, then** `python -m pytest -q`.
- [ ] **Step 6: Review `git diff`, confirm no unrelated user changes are staged, commit, and push `main`.**
