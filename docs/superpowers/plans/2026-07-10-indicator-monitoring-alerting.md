# Indicator Monitoring and Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为首批四个指标实现持久化运行计划、定时与手动重算、环比/可选同比波动预警、自动三层诊断和完整运行审计。

**Architecture:** 新增独立 `app/monitoring` 领域包，Repository 管理 MySQL 计划、结果、租约和预警，`IndicatorRunService` 复用现有统一编排器执行字段预检、确定性 SQL 和 DBHub 只读查询，`MonitoringScheduler` 仅使用 APScheduler 触发计划 ID。FastAPI 通过独立 Router 暴露管理 API，并在生命周期中启动调度器；数据库租约和稳定运行键提供多进程幂等保障。

**Tech Stack:** Python 3.12、FastAPI、Pydantic v2、SQLAlchemy 2、APScheduler 3.10、MySQL、DBHub MCP、unittest

## Global Constraints

- 首批运行范围仅为 `MQSI2025_001/005/014/035`，但接口和表结构不得硬编码单一指标。
- 环比默认启用且阈值为 `20%`；同比可独立启停，默认启用且阈值为 `30%`。
- 只有绝对变化率严格大于阈值才预警；等于阈值不预警。
- 同比必须比较去年同一自然周期；不足一年历史时返回 `baseline_insufficient`，不得误报。
- 日计划计算最近一个完整自然日，月计划计算最近一个完整自然月，统一使用半开区间 `[start_time, end_time)` 和 `Asia/Shanghai`。
- 业务库访问必须经过 `BusinessDBClient`/DBHub，只允许单条只读 `SELECT`。
- 定时运行不得调用 LLM 编写 SQL，不保存患者明细，不在结果、预警或 Trace 中保存绑定后的 SQL。
- 完整监控页面并入第六批；本批只实现后端闭环、API、Trace、系统自检和 README 验收步骤。
- 每个任务结束后运行指定测试，只暂存相关文件，使用中文 Conventional Commit，并推送 `main`。

---

### Task 1: 监控数据模型、幂等迁移与 Repository

**Files:**
- Create: `app/monitoring/__init__.py`
- Create: `app/monitoring/contracts.py`
- Create: `app/monitoring/schema.py`
- Create: `app/monitoring/repository.py`
- Modify: `app/db/migrations.py`
- Modify: `scripts/migrate_runtime_schema.py`
- Modify: `scripts/init_runtime_db.sql`
- Test: `tests/test_monitoring_repository.py`
- Test: `tests/test_runtime_migrations.py`

**Interfaces:**
- Produces: `RunPlan`, `RunResult`, `IndicatorAlert` Pydantic contracts.
- Produces: `ensure_monitoring_schema(engine: Engine) -> dict[str, list[str]]`.
- Produces: `MonitoringRepository` with plan CRUD, lease, result, baseline and alert methods.
- Consumes: existing `med_index_run_result` and runtime database engine.

- [ ] **Step 1: Write failing schema and Repository tests**

Add tests that create an old `med_index_run_result`, call the migration twice, and assert the new plan/alert tables plus all result audit columns exist. Add Repository tests for plan defaults, hospital-scoped listing, lease contention, scheduled run-key idempotency, retry linkage and alert deduplication.

```python
def test_plan_defaults_and_lease_are_persistent(self):
    repository = MonitoringRepository(_monitoring_engine())
    plan = repository.create_plan({
        "hospital_id": "hospital_001",
        "rule_id": "MQSI2025_005",
        "plan_name": "急会诊月报",
        "frequency": "monthly",
        "run_time": "02:00",
        "day_of_month": 1,
        "created_by": "admin",
    })
    self.assertEqual(plan["mom_threshold_pct"], 20.0)
    self.assertEqual(plan["yoy_threshold_pct"], 30.0)
    now = datetime(2026, 8, 1, 2, 0, 0)
    self.assertTrue(repository.try_acquire_lease(plan["plan_id"], "worker-a", now))
    self.assertFalse(repository.try_acquire_lease(plan["plan_id"], "worker-b", now))
```

```python
def test_retry_uses_new_run_key_and_links_failed_result(self):
    failed = repository.create_run_result(
        _failed_result(run_key="PLAN_001:2026-07-01:2026-08-01")
    )
    retry = repository.create_run_result(
        _failed_result(
            run_key="RETRY_REQ_001",
            trigger_type="retry",
            retry_of_result_id=failed["id"],
        )
    )
    self.assertNotEqual(retry["run_key"], failed["run_key"])
    self.assertEqual(retry["retry_of_result_id"], failed["id"])
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```powershell
python -B -m unittest tests.test_monitoring_repository tests.test_runtime_migrations -v
```

Expected: FAIL because `app.monitoring` and `ensure_monitoring_schema` do not exist.

- [ ] **Step 3: Implement contracts and SQLAlchemy schema**

Define these contracts in `app/monitoring/contracts.py`:

```python
class RunPlan(BaseModel):
    plan_id: str
    hospital_id: str
    rule_id: str
    plan_name: str
    frequency: Literal["daily", "monthly"]
    run_time: str = "02:00"
    day_of_month: int = 1
    timezone: str = "Asia/Shanghai"
    mom_enabled: bool = True
    mom_threshold_pct: float = 20.0
    yoy_enabled: bool = True
    yoy_threshold_pct: float = 30.0
    status: Literal["enabled", "disabled"] = "enabled"
    next_run_at: datetime | None = None
    last_run_at: datetime | None = None
    locked_until: datetime | None = None
    locked_by: str = ""

class RunResult(BaseModel):
    id: int | None = None
    run_key: str
    plan_id: str | None = None
    retry_of_result_id: int | None = None
    hospital_id: str
    rule_id: str
    trigger_type: Literal["scheduled", "manual", "retry"]
    stat_start_time: datetime
    stat_end_time: datetime
    stat_period: str
    run_status: Literal["running", "success", "failed", "no_sample"]
    result_value: float | None = None
    effective_level: str = ""
    national_version: str | None = None
    hospital_version: int | None = None
    data_source: str = ""
    duration_ms: int = 0
    error_code: str = ""
    error_message: str = ""
    mom_baseline_result_id: int | None = None
    mom_change_rate: float | None = None
    yoy_baseline_result_id: int | None = None
    yoy_change_rate: float | None = None
    wave_status: str = "baseline_insufficient"
    is_abnormal: bool = False

class IndicatorAlert(BaseModel):
    alert_id: str
    hospital_id: str
    rule_id: str
    result_id: int
    alert_type: Literal["wave", "execution_failed"]
    conclusion_code: str
    diagnose_status: str = "pending"
    diagnose_report_id: str | None = None
    status: Literal["open", "acknowledged", "closed"] = "open"
```

Use SQLAlchemy `MetaData`/`Table` definitions in `schema.py` for new tables so both MySQL and SQLite tests are supported. Use inspector-driven fixed-column `ALTER TABLE` statements only for extending an existing `med_index_run_result`.

- [ ] **Step 4: Implement `MonitoringRepository`**

Required public methods:

```python
create_plan(payload: dict[str, Any]) -> dict[str, Any]
update_plan(plan_id: str, payload: dict[str, Any]) -> dict[str, Any]
get_plan(plan_id: str) -> dict[str, Any] | None
list_plans(hospital_id: str) -> list[dict[str, Any]]
list_enabled_plans() -> list[dict[str, Any]]
list_due_plans(now: datetime) -> list[dict[str, Any]]
set_plan_status(plan_id: str, status: str) -> dict[str, Any]
set_plan_next_run(plan_id: str, next_run_at: datetime | None) -> None
try_acquire_lease(plan_id: str, worker_id: str, now: datetime, lease_seconds: int = 600) -> bool
release_lease(plan_id: str, worker_id: str, last_run_at: datetime, next_run_at: datetime | None) -> None
create_run_result(payload: dict[str, Any]) -> dict[str, Any]
get_result_by_run_key(run_key: str) -> dict[str, Any] | None
get_result(result_id: int, hospital_id: str) -> dict[str, Any] | None
get_result_for_retry(result_id: int) -> dict[str, Any] | None
list_results(hospital_id: str, rule_id: str | None = None, limit: int = 100) -> list[dict[str, Any]]
find_success_result(hospital_id: str, rule_id: str, stat_start: datetime, stat_end: datetime) -> dict[str, Any] | None
update_wave_result(result_id: int, payload: dict[str, Any]) -> dict[str, Any]
create_alert(payload: dict[str, Any]) -> dict[str, Any]
get_alert(alert_id: str, hospital_id: str) -> dict[str, Any] | None
list_alerts(hospital_id: str, status: str | None = None, limit: int = 100) -> list[dict[str, Any]]
update_alert(alert_id: str, hospital_id: str, payload: dict[str, Any]) -> dict[str, Any]
```

Lease acquisition must use one conditional `UPDATE` and `rowcount`, not read-then-write. Alert uniqueness is `(result_id, alert_type, conclusion_code)`.

- [ ] **Step 5: Extend migration scripts and initial DDL**

`ensure_monitoring_schema()` must return:

```python
{
    "created_tables": ["med_indicator_run_plan", "med_indicator_alert"],
    "added_result_columns": [
        "plan_id", "run_key", "retry_of_result_id", "trigger_type",
        "stat_start_time", "stat_end_time", "run_status", "no_sample",
        "effective_level", "national_version", "hospital_version",
        "data_source", "duration_ms", "error_code", "error_message",
        "mom_baseline_result_id", "mom_change_rate",
        "yoy_baseline_result_id", "yoy_change_rate", "wave_status",
    ],
}
```

Update `scripts/migrate_runtime_schema.py` to run both diagnose and monitoring migrations and print both results. Mirror the final MySQL DDL in `scripts/init_runtime_db.sql`.

- [ ] **Step 6: Verify GREEN and commit**

Run:

```powershell
python -B -m unittest tests.test_monitoring_repository tests.test_runtime_migrations -v
python -B scripts\migrate_runtime_schema.py
python -B scripts\migrate_runtime_schema.py
git diff --check
```

Expected: tests pass; first migration adds missing objects, second reports no changes.

Commit and push:

```powershell
git add app/monitoring app/db/migrations.py scripts/migrate_runtime_schema.py scripts/init_runtime_db.sql tests/test_monitoring_repository.py tests/test_runtime_migrations.py
git commit -m "feat: 增加指标监控数据模型"
git push origin main
```

---

### Task 2: 统计周期、基线定位与波动判断

**Files:**
- Create: `app/monitoring/periods.py`
- Create: `app/monitoring/wave.py`
- Test: `tests/test_monitoring_wave.py`

**Interfaces:**
- Produces: `ResolvedPeriod` with start/end/label/frequency.
- Produces: `resolve_run_period(frequency, stat_period=None, now=None, timezone_name="Asia/Shanghai") -> ResolvedPeriod`.
- Produces: `comparison_period(period, comparison: Literal["mom", "yoy"]) -> ResolvedPeriod`.
- Produces: `detect_wave(current_value, mom_value, yoy_value, mom_enabled, mom_threshold_pct, yoy_enabled, yoy_threshold_pct, no_sample=False) -> dict[str, Any]`.
- Consumes: Task 1 contracts only; no database access.

- [ ] **Step 1: Write failing period tests**

```python
def test_monthly_plan_uses_previous_complete_month(self):
    period = resolve_run_period(
        "monthly", now=datetime(2026, 8, 10, 12, 0, 0)
    )
    self.assertEqual(period.start_text, "2026-07-01 00:00:00")
    self.assertEqual(period.end_text, "2026-08-01 00:00:00")
    self.assertEqual(
        comparison_period(period, "yoy").start_text,
        "2025-07-01 00:00:00",
    )
```

Cover daily month/year boundaries, leap day behavior, explicit inclusive date range, invalid/reversed range and timezone validation.

- [ ] **Step 2: Write failing wave tests**

```python
def test_threshold_is_strict_and_yoy_is_optional(self):
    equal = detect_wave(60, 50, None, True, 20, False, 30)
    exceeded = detect_wave(60.01, 50, None, True, 20, False, 30)
    self.assertEqual(equal["conclusion_code"], "within_threshold")
    self.assertEqual(exceeded["conclusion_code"], "mom_threshold_exceeded")
```

Also cover both thresholds exceeded, missing year history, zero baseline, negative change and no sample.

- [ ] **Step 3: Run tests to verify RED**

```powershell
python -B -m unittest tests.test_monitoring_wave -v
```

Expected: FAIL because `periods.py` and `wave.py` do not exist.

- [ ] **Step 4: Implement pure period functions**

Use `zoneinfo.ZoneInfo`, calendar boundaries and half-open intervals. Date-only explicit end values advance by one day; datetime endpoints remain exact. Leap-day同比 uses the same calendar date when valid and February 28 when the prior year lacks February 29.

- [ ] **Step 5: Implement pure wave detector**

Use:

```python
def _change_rate(current: float, baseline: float | None) -> float | None:
    if baseline is None or baseline == 0:
        return None
    return round((current - baseline) / abs(baseline) * 100, 2)
```

Evaluate `abs(rate) > threshold`. Return rates, baseline values, `conclusion_code`, `is_abnormal` and human-readable Chinese summary without accessing the database.

- [ ] **Step 6: Verify GREEN and commit**

```powershell
python -B -m unittest tests.test_monitoring_wave -v
git diff --check
git add app/monitoring/periods.py app/monitoring/wave.py tests/test_monitoring_wave.py
git commit -m "feat: 实现指标波动基线判断"
git push origin main
```

---

### Task 3: 指标重算、预警与自动诊断闭环

**Files:**
- Create: `app/monitoring/service.py`
- Create: `app/monitoring/factory.py`
- Modify: `app/sqlgen/agent.py`
- Modify: `app/agents/orchestrator.py`
- Modify: `app/db/repositories.py`
- Test: `tests/test_monitoring_service.py`
- Test: `tests/test_sqlgen.py`
- Test: `tests/test_agent_orchestrator.py`

**Interfaces:**
- Produces: `IndicatorRunService.run_plan(plan_id, stat_period=None, trigger_type="scheduled", request_id=None, retry_of_result_id=None) -> dict[str, Any]`.
- Produces: `create_monitoring_service(runtime_engine=None) -> IndicatorRunService`.
- Extends: `CoreIndicatorOrchestrator.generate_indicator()` with keyword `persist_run_result: bool = True`.
- Consumes: Task 1 Repository, Task 2 period/wave functions and existing orchestrator diagnosis.

- [ ] **Step 1: Write failing successful-run tests**

Use a fake orchestrator that returns a prepared rule with versions, a successful DBHub trial result `66.67`, and a diagnosis report. Seed a previous monthly result `50.0`.

```python
result = service.run_plan(
    "PLAN_001",
    stat_period="2026-07-01~2026-07-31",
    trigger_type="manual",
    request_id="REQ_001",
)
self.assertEqual(result["run_status"], "success")
self.assertEqual(result["wave_status"], "mom_threshold_exceeded")
self.assertTrue(result["is_abnormal"])
self.assertEqual(result["alert"]["diagnose_status"], "completed")
self.assertEqual(result["alert"]["diagnose_report_id"], "DR_AUTO_001")
```

Assert the stored result contains effective level, national/hospital versions, source and duration, while serialized result/alert/Trace payloads contain neither `SELECT` nor `patient_id`.

- [ ] **Step 2: Write failing edge and failure tests**

Cover:

- first run returns `baseline_insufficient` and no alert;
- no sample returns `no_sample` and no alert;
- missing同比 history does not block valid环比 detection;
- SQL/DBHub failure creates `execution_failed` alert and `indicator_recompute` recovery task;
- diagnosis failure leaves alert open with `diagnose_status=failed`;
- repeated scheduled run key returns the existing result without a second DBHub call;
- retry creates a new result linked by `retry_of_result_id`.

- [ ] **Step 3: Run tests to verify RED**

```powershell
python -B -m unittest tests.test_monitoring_service tests.test_sqlgen tests.test_agent_orchestrator -v
```

Expected: FAIL because `IndicatorRunService` and `persist_run_result` do not exist.

- [ ] **Step 4: Prevent duplicate legacy result persistence**

Extend the existing generation boundary:

```python
def generate_indicator(
    self,
    prepared: PreparedRequest,
    *,
    stat_start_time: str,
    stat_end_time: str,
    trial_run: bool = False,
    generated_by: str = "agent",
    persist_run_result: bool = True,
) -> dict[str, Any]:
```

Pass the flag to `SQLGenerationAgent.generate()`. Existing chat/API behavior keeps `True`; monitoring passes `False` and writes one rich `med_index_run_result` through `MonitoringRepository`.

- [ ] **Step 5: Implement `IndicatorRunService`**

The service must:

1. load and validate the plan;
2. acquire a lease for scheduled runs;
3. resolve the period and run key;
4. return an existing scheduled result when the run key already exists;
5. call `prepare_rule_request()` and `generate_indicator(trial_run=True, persist_run_result=False)`;
6. persist success/no-sample/failure audit data;
7. query exact环比/同比 periods through Repository;
8. run `detect_wave()` and update the result;
9. create a wave alert when abnormal;
10. call `orchestrator.diagnose(prepared, trigger="abnormal_result", related_sql_id=trial_sql_id, stat_period=period.label)` and update the alert;
11. create an execution-failure alert plus recovery task on failures;
12. release the lease in `finally`.

Use a stable scheduled key and unique manual/retry keys:

```python
scheduled_key = f"{plan_id}:{period.start_text}:{period.end_text}"
manual_key = f"{trigger_type}:{request_id or uuid.uuid4().hex}"
```

- [ ] **Step 6: Add recovery task support**

Create `indicator_recompute` recovery payloads containing only plan ID, stat period, failed result ID, hospital ID and rule ID. Never store SQL text. Add an execution callback used later by the recovery endpoint:

```python
def retry_result(self, result_id: int, request_id: str) -> dict[str, Any]:
    failed = self.repository.get_result_for_retry(result_id)
    return self.run_plan(
        failed["plan_id"],
        stat_period=failed["stat_period"],
        trigger_type="retry",
        request_id=request_id,
        retry_of_result_id=result_id,
    )
```

- [ ] **Step 7: Verify GREEN and commit**

```powershell
python -B -m unittest tests.test_monitoring_service tests.test_sqlgen tests.test_agent_orchestrator -v
git diff --check
git add app/monitoring/service.py app/monitoring/factory.py app/sqlgen/agent.py app/agents/orchestrator.py app/db/repositories.py tests/test_monitoring_service.py tests/test_sqlgen.py tests/test_agent_orchestrator.py
git commit -m "feat: 完成指标重算与自动诊断闭环"
git push origin main
```

---

### Task 4: APScheduler、计划恢复与系统自检

**Files:**
- Modify: `requirements.txt`
- Replace: `app/tasks/scheduler.py`
- Create: `app/monitoring/runtime.py`
- Modify: `app/api/main.py`
- Modify: `config.example.yaml`
- Test: `tests/test_monitoring_scheduler.py`
- Test: `tests/test_api.py`

**Interfaces:**
- Produces: `MonitoringScheduler.start()`, `shutdown()`, `reload_plans()`, `sync_plan(plan_id)`, `scan_due(now=None)` and `status()`.
- Produces: runtime singleton helpers `set_monitoring_scheduler`, `set_monitoring_scheduler_error`, `get_monitoring_scheduler`, `monitoring_scheduler_status`.
- Consumes: Task 3 `create_monitoring_service()` and Task 1 Repository.

- [ ] **Step 1: Write failing scheduler tests**

Inject a fake `BackgroundScheduler` and assert enabled plans become jobs with IDs `monitor:{plan_id}`, disabled plans are removed, jobs use `coalesce=True` and `max_instances=1`, and shutdown is idempotent.

```python
scheduler.start()
self.assertEqual(fake_backend.started, 1)
self.assertIn("monitor:PLAN_001", fake_backend.jobs)
repository.set_plan_status("PLAN_001", "disabled")
scheduler.sync_plan("PLAN_001")
self.assertNotIn("monitor:PLAN_001", fake_backend.jobs)
```

Add a lease-contention test in which two scheduler instances fire the same plan and only one service reaches DBHub.

- [ ] **Step 2: Write failing lifecycle and health tests**

Patch scheduler construction, call FastAPI startup/shutdown handlers, and assert health summary includes:

```python
{
    "key": "monitoring_scheduler",
    "status": "ok",
    "enabled_plan_count": 1,
}
```

When scheduler startup fails, FastAPI remains available and health reports `MONITORING_SCHEDULER_UNAVAILABLE` as degraded.

- [ ] **Step 3: Run tests to verify RED**

```powershell
python -B -m unittest tests.test_monitoring_scheduler tests.test_api -v
```

Expected: FAIL because the scheduler remains a placeholder.

- [ ] **Step 4: Add APScheduler and implement the adapter**

Add `APScheduler>=3.10,<4.0` to `requirements.txt`. Use `BackgroundScheduler(timezone="Asia/Shanghai")` and `CronTrigger`:

```python
if plan["frequency"] == "daily":
    trigger = CronTrigger(hour=hour, minute=minute, timezone=timezone_name)
else:
    trigger = CronTrigger(
        day=plan["day_of_month"],
        hour=hour,
        minute=minute,
        timezone=timezone_name,
    )
```

Register jobs with `replace_existing=True`, `coalesce=True`, `max_instances=1`, and a 10-minute misfire grace period. The job callback calls only `service_factory().run_plan(plan_id)`.

- [ ] **Step 5: Integrate FastAPI lifecycle and runtime status**

Add explicit functions to `app/api/main.py` so tests can call them directly:

```python
def start_monitoring_scheduler() -> None:
    try:
        engine = create_runtime_engine()
        ensure_monitoring_schema(engine)
        scheduler = MonitoringScheduler(
            MonitoringRepository(engine),
            service_factory=lambda: create_monitoring_service(engine),
        )
        scheduler.start()
        set_monitoring_scheduler(scheduler)
    except Exception as exc:
        set_monitoring_scheduler_error(str(exc))

def stop_monitoring_scheduler() -> None:
    scheduler = get_monitoring_scheduler()
    if scheduler is not None:
        scheduler.shutdown()
```

Register them with startup/shutdown events. Startup runs `ensure_monitoring_schema`, marks stale recovery tasks interrupted, starts the scheduler when `monitoring_scheduler_enabled=true`, and catches startup errors into runtime status instead of crashing FastAPI.

Add configuration:

```yaml
monitoring_scheduler_enabled: true
monitoring_scheduler_timezone: Asia/Shanghai
monitoring_scheduler_lease_seconds: 600
```

- [ ] **Step 6: Verify GREEN and commit**

```powershell
python -B -m unittest tests.test_monitoring_scheduler tests.test_api -v
git diff --check
git add requirements.txt app/tasks/scheduler.py app/monitoring/runtime.py app/api/main.py config.example.yaml tests/test_monitoring_scheduler.py tests/test_api.py
git commit -m "feat: 接入指标运行调度器"
git push origin main
```

---

### Task 5: 管理 API、恢复重试、Trace 与工作流 manifest

**Files:**
- Create: `app/api/monitoring.py`
- Create: `app/workflows/indicator_monitoring.yaml`
- Modify: `app/api/main.py`
- Modify: `app/observability/workflow_nodes.py`
- Modify: `tests/test_api.py`
- Modify: `tests/test_workflow_manifest.py`

**Interfaces:**
- Produces: `/api/monitoring/plans`, `/results`, `/alerts` and scheduler scan endpoints.
- Produces: `record_monitoring_trace_nodes(recorder: TraceRecorder, trace_id: str, events: list[dict[str, Any]]) -> None`.
- Extends: recovery retry handler for `indicator_recompute`.
- Consumes: Tasks 1-4 services, Repository and runtime scheduler.

- [ ] **Step 1: Write failing plan API tests**

Login with the existing admin endpoint, then test create/list/update/enable/disable/run. Assert unauthenticated plan mutation returns 401 and invalid threshold/time/frequency returns 422 or 400.

```python
created = client.post(
    "/api/monitoring/plans",
    headers=headers,
    json={
        "hospital_id": "hospital_001",
        "rule_id": "MQSI2025_005",
        "plan_name": "急会诊月报",
        "frequency": "monthly",
        "run_time": "02:00",
    },
)
self.assertEqual(created.status_code, 200)
self.assertEqual(created.json()["mom_threshold_pct"], 20.0)
```

- [ ] **Step 2: Write failing result, alert and recovery tests**

Test hospital filtering, alert acknowledge/close transitions, manual diagnosis retry, and `indicator_recompute` recovery retry. A request for a different hospital's result or alert must return 404.

- [ ] **Step 3: Write failing Trace and manifest tests**

The manifest node order must be:

```python
[
    "monitor_plan_load",
    "monitor_lease_acquire",
    "monitor_period_resolve",
    "monitor_indicator_execute_mcp",
    "monitor_wave_detect",
    "monitor_alert_create",
    "monitor_auto_diagnose",
]
```

Trace assertions must verify result value, period, versions, rates, alert/report IDs and real durations are visible after node expansion, while `SELECT` and `patient_id` are absent.

- [ ] **Step 4: Run tests to verify RED**

```powershell
python -B -m unittest tests.test_api tests.test_workflow_manifest -v
```

Expected: FAIL because monitoring routes, recovery branch and manifest do not exist.

- [ ] **Step 5: Implement the Router**

Follow the existing `app/api/indicator_drafts.py` lazy dependency pattern. All monitoring endpoints require the existing admin token in this backend-only batch. Validate:

- `run_time` matches `HH:mm`;
- `day_of_month` is 1-28;
- thresholds are greater than zero and at most 10000;
- status transitions are explicit;
- every read includes `hospital_id`.

Return stable error codes in HTTP details for lease conflict, duplicate run, invalid period, DBHub failure and missing resources.

- [ ] **Step 6: Implement recovery and observability**

Add `indicator_recompute` to `_retry_recovery_task()` and call `create_monitoring_service(runtime_engine).retry_result(result_id=int(payload["failed_result_id"]), request_id=str(task["request_id"] or uuid.uuid4().hex))`. Add monitoring trace recording and the new manifest. The execute node uses `execute_sql_hospital_demo_data`; alert/diagnosis nodes include IDs but not SQL.

- [ ] **Step 7: Verify GREEN and commit**

```powershell
python -B -m unittest tests.test_api tests.test_workflow_manifest tests.test_monitoring_service -v
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/indicator_monitoring/validate
git diff --check
git add app/api/monitoring.py app/api/main.py app/observability/workflow_nodes.py app/workflows/indicator_monitoring.yaml tests/test_api.py tests/test_workflow_manifest.py
git commit -m "feat: 增加指标监控管理接口"
git push origin main
```

---

### Task 6: README、真实 MySQL/DBHub 验收与最终回归

**Files:**
- Modify: `README.md`
- Modify: `tests/test_agent_workflow.py` only if the full suite exposes a compatibility regression.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: operator-facing startup, migration, API verification and cleanup instructions.

- [ ] **Step 1: Update README**

Document:

- `python -B scripts\migrate_runtime_schema.py`;
- APScheduler startup and configuration;
- plan create/enable/disable/run APIs;
-环比、同比 and baseline-insufficient meanings;
- execution-failure recovery center behavior;
- full monitoring UI deferred to batch six;
- front-end verification through system self-check and trace only.

- [ ] **Step 2: Install dependency and apply migration**

```powershell
python -m pip install "APScheduler>=3.10,<4.0"
python -B scripts\migrate_runtime_schema.py
```

Expected: migration succeeds and a second run reports no schema changes.

- [ ] **Step 3: Restart FastAPI and verify health**

Restart the existing server on `127.0.0.1:8765`, then run:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/api/health/summary
Invoke-RestMethod http://127.0.0.1:8765/api/workflows/indicator_monitoring/validate
```

Expected: health contains a normal “指标调度器” item and manifest validation returns `ok=true`.

- [ ] **Step 4: Run real plan and manual recompute**

Login, create a temporary disabled monthly plan for `hospital_001/MQSI2025_005`, insert one aggregate June baseline `50.0` through `MonitoringRepository` with a test-specific run key, and call the plan run endpoint for `2026-07-01~2026-07-31`.

Expected:

- current result is `66.67`;
-环比 rate is `33.34%` and exceeds 20%;
- one wave alert is created;
- auto diagnosis returns a report ID;
- result contains hospital version `1`, DBHub source and nonzero duration;
- report/Trace contain neither SQL nor patient details.

Delete only the temporary plan, baseline, result, alert and diagnosis records identified by their test-specific IDs after evidence is captured.

- [ ] **Step 5: Verify scheduler idempotency and restart recovery**

Enable the temporary plan, invoke `/api/monitoring/scheduler/scan` twice at the same injected due time, and assert only one scheduled `run_key` exists. Restart FastAPI and assert the enabled plan is loaded again, then disable and delete the temporary plan.

- [ ] **Step 6: Run final verification**

```powershell
python -B -m unittest discover -s tests
git diff --check
git status --short
git log -6 --oneline
```

Expected: all tests pass, no unrelated files are modified, and the five implementation commits are present.

- [ ] **Step 7: Commit and push documentation**

```powershell
git add README.md
git commit -m "docs: 完善指标监控验收说明"
git push origin main
```

## Final Verification Checklist

- [ ] `python -B -m unittest discover -s tests` has zero failures.
- [ ] `python -B scripts\migrate_runtime_schema.py` is idempotent.
- [ ] `/api/health/summary` reports the scheduler status.
- [ ] `/api/workflows/indicator_monitoring/validate` returns `ok=true`.
- [ ] Real indicator five recompute returns `66.67` through DBHub.
- [ ]环比 `33.34%` creates one alert and one automatic diagnosis report.
- [ ] Missing同比 history does not create a同比 alert.
- [ ] Two workers cannot execute one scheduled plan concurrently.
- [ ] Scheduled run keys are idempotent; retry attempts preserve the failed original result.
- [ ] Result, alert, diagnosis and Trace payloads contain no bound SQL or patient details.
- [ ] The temporary real-environment acceptance data is removed.
- [ ] `git status --short` is empty and `HEAD == origin/main`.
