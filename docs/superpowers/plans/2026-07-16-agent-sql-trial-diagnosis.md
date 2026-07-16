# Agent SQL、试运行与诊断工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有工具调用型 Agent 中增加受控 SQL 准备、只接受服务端 `sql_id` 的只读试运行和安全诊断工具，完成 Stage 4 的确定性业务闭环。

**Architecture:** 现有 `CoreIndicatorOrchestrator`、`SQLGenerationAgent`、`run_sql_trial` 和诊断服务继续作为唯一业务实现；新工具只负责参数契约、权限/状态校验、安全结果投影和证据登记。新增短期 SQL 对象表保存服务端执行快照并绑定医院、用户、会话和 30 分钟 TTL，模型永远不能提交待执行 SQL 文本；AgentRunner 使用事实类型守卫阻止没有 SQL、试运行或诊断证据的对应结论。

**Tech Stack:** Python 3.12.7、Pydantic 2.13.3、SQLAlchemy、现有 Agent Runtime/Tool Gateway、现有 SQL/DBHub/诊断服务、`unittest`/`pytest`。

## Global Constraints

- 默认使用中文注释、错误说明、系统提示、测试语义和提交主题。
- 不修改旧 `/api/chat`、`/api/chat/stream` 和 `app/agent/graph.py` 的行为。
- 不新增固定意图分类、关键词路由或模型可控的医院、连接、表名、模板和跳过校验参数。
- `prepare_indicator_sql` 只调用现有确定性生成链路，并且不向模型返回完整 SQL。
- `trial_run_indicator_sql` 的模型输入只能包含 `sql_id`；SQL 文本、统计区间、参数和执行上下文只能从服务端 SQL 对象读取。
- SQL 对象绑定 `hospital_id + user_id + session_id`，默认 TTL 为 30 分钟；跨租户、跨用户、跨会话、过期和上下文失效都不得执行。
- 试运行前必须再次执行只读 SQL 校验；继续复用现有 DBHub 超时、只读和聚合结果逻辑，不返回患者明细。
- 诊断工具复用现有粘贴 SQL 安全链和三层诊断，返回白名单摘要，不返回原始 SQL、患者明细、内部异常或连接信息。
- 工具继续只经过 `ToolGateway` 执行，每轮动态暴露工具数量不超过六个。
- 本阶段不接入新 API、SSE、前端、Shadow、正式审批、发布、回退或任何生产写操作。
- 每个任务遵循 TDD：先看到预期失败，再写最小实现、运行回归、独立提交并推送。

## File Structure

```text
app/agent_tools/sql_objects.py       短期 SQL 对象契约、Schema、租户读取和 TTL
app/agent_tools/state_facts.py       从已验证 ToolResult 提取规则和 SQL 状态
app/agent_tools/sql_tools.py         SQL 准备与 sql_id 试运行工具
app/agent_tools/diagnosis_tools.py   现有诊断链路的安全工具适配
app/agent_tools/catalog.py           组合首批六个动态工具
app/agent_runtime/response_guard.py  SQL、试运行和诊断事实类型守卫
app/agent_runtime/contracts.py       增加已验证 sql_id 和结果引用状态
app/agent_runtime/runner.py          接入事实守卫和上下文冲突停止
app/agent_tools/__init__.py          公开新工具与服务
scripts/init_runtime_db.sql          正式运行库 SQL 对象表

tests/test_agent_sql_objects.py
tests/test_agent_sql_tools.py
tests/test_agent_diagnosis_tool.py
tests/test_agent_execution_catalog.py
tests/test_agent_response_guard.py
tests/test_agent_execution_loop.py
```

---

### Task 1: 建立短期 SQL 对象生命周期

**Files:**
- Create: `app/agent_tools/sql_objects.py`
- Modify: `scripts/init_runtime_db.sql`
- Create: `tests/test_agent_sql_objects.py`

**Interfaces:**
- Produces: `PreparedSqlObject`、`AgentSqlObjectStore.save()`、`AgentSqlObjectStore.load_for_execution()`、`ensure_agent_sql_object_schema()`。
- Consumes: SQLAlchemy `Engine` 和 `AgentRuntimeContext`；后续 SQL 工具只通过 Store 读取私有 SQL 文本与快照。

- [ ] **Step 1: 写入 SQL 对象失败测试**

创建 `tests/test_agent_sql_objects.py`，写入以下主路径测试：

```python
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine, inspect

from app.agent_runtime import AgentRuntimeContext
from app.agent_tools.sql_objects import (
    AgentSqlObjectStore,
    PreparedSqlObject,
    SqlObjectAccessError,
    ensure_agent_sql_object_schema,
)


NOW = datetime(2026, 7, 16, 2, 0, tzinfo=timezone.utc)


def context(**updates):
    values = {
        "user_id": "u1",
        "hospital_id": "h1",
        "session_id": "s1",
        "user_role": "implementer",
        "permissions": frozenset({"indicator_read"}),
        "request_id": "r1",
        "trace_id": "t1",
        "db_source_id": "hospital_db",
    }
    values.update(updates)
    return AgentRuntimeContext(**values)


def sql_object(**updates):
    values = {
        "sql_id": "SQL_001",
        "hospital_id": "h1",
        "user_id": "u1",
        "session_id": "s1",
        "rule_id": "MQSI2025_005",
        "dialect": "sqlserver",
        "sql_text": "SELECT 1 AS index_value",
        "params": {"threshold_minutes": 10},
        "stat_start": "2026-07-01 00:00:00",
        "stat_end": "2026-07-31 23:59:59",
        "context_snapshot": {"rule": {"rule_id": "MQSI2025_005"}},
        "context_digest": "digest-1",
        "validation_status": "validated",
        "created_at": NOW,
        "expires_at": NOW + timedelta(minutes=30),
        "db_source_id": "hospital_db",
    }
    values.update(updates)
    return PreparedSqlObject(**values)


def test_schema_is_idempotent_and_store_round_trips_private_snapshot():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    ensure_agent_sql_object_schema(engine)
    assert inspect(engine).has_table("med_agent_sql_object")
    store = AgentSqlObjectStore(engine, now_provider=lambda: NOW)
    store.save(sql_object())
    loaded = store.load_for_execution("SQL_001", context())
    assert loaded.sql_text == "SELECT 1 AS index_value"
    assert loaded.params == {"threshold_minutes": 10}


@pytest.mark.parametrize(
    ("changed", "code"),
    [
        ({"hospital_id": "h2"}, "SQL_OBJECT_TENANT_MISMATCH"),
        ({"user_id": "u2"}, "SQL_OBJECT_OWNER_MISMATCH"),
        ({"session_id": "s2"}, "SQL_OBJECT_SESSION_MISMATCH"),
        ({"db_source_id": "other_db"}, "SQL_OBJECT_SOURCE_MISMATCH"),
    ],
)
def test_store_rejects_scope_mismatch(changed, code):
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    store = AgentSqlObjectStore(engine, now_provider=lambda: NOW)
    store.save(sql_object())
    with pytest.raises(SqlObjectAccessError) as raised:
        store.load_for_execution("SQL_001", context(**changed))
    assert raised.value.code == code


def test_store_rejects_expired_or_unvalidated_object():
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    store = AgentSqlObjectStore(engine, now_provider=lambda: NOW)
    store.save(sql_object(expires_at=NOW - timedelta(seconds=1)))
    with pytest.raises(SqlObjectAccessError) as expired:
        store.load_for_execution("SQL_001", context())
    assert expired.value.code == "SQL_OBJECT_EXPIRED"
```

再补充 `validation_status != "validated"`、不存在对象和损坏 JSON 的拒绝测试。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_sql_objects.py -q`

Expected: FAIL，缺少 `app.agent_tools.sql_objects`。

- [ ] **Step 3: 实现 SQL 对象契约和 Store**

创建 `app/agent_tools/sql_objects.py`：

```python
class SqlObjectAccessError(RuntimeError):
    def __init__(self, message: str, *, code: str) -> None:
        super().__init__(message)
        self.code = code


class PreparedSqlObject(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sql_id: str
    hospital_id: str
    user_id: str
    session_id: str
    rule_id: str
    dialect: str
    sql_text: str
    params: dict[str, Any] = Field(default_factory=dict)
    stat_start: str
    stat_end: str
    context_snapshot: dict[str, Any]
    context_digest: str
    validation_status: str
    validation_message: str = ""
    created_at: datetime
    expires_at: datetime
    db_source_id: str | None = None


def ensure_agent_sql_object_schema(engine: Engine) -> list[str]:
    existed = inspect(engine).has_table("med_agent_sql_object")
    _METADATA.create_all(engine, tables=[_SQL_OBJECT_TABLE])
    return [] if existed else ["med_agent_sql_object"]


class AgentSqlObjectStore:
    def __init__(self, engine: Engine, *, now_provider=_utcnow) -> None:
        self.engine = engine
        self.now_provider = now_provider

    def save(self, value: PreparedSqlObject) -> None:
        payload = value.model_dump(mode="json")
        payload["params_json"] = json.dumps(
            payload.pop("params"), ensure_ascii=False, sort_keys=True
        )
        payload["context_snapshot_json"] = json.dumps(
            payload.pop("context_snapshot"), ensure_ascii=False, sort_keys=True
        )
        with self.engine.begin() as connection:
            connection.execute(
                delete(_SQL_OBJECT_TABLE).where(
                    _SQL_OBJECT_TABLE.c.sql_id == value.sql_id
                )
            )
            connection.execute(insert(_SQL_OBJECT_TABLE).values(**payload))

    def load_for_execution(
        self, sql_id: str, context: AgentRuntimeContext
    ) -> PreparedSqlObject:
        with self.engine.connect() as connection:
            row = connection.execute(
                select(_SQL_OBJECT_TABLE).where(
                    _SQL_OBJECT_TABLE.c.sql_id == sql_id
                )
            ).mappings().first()
        if row is None:
            raise SqlObjectAccessError(
                "SQL 对象不存在。", code="SQL_OBJECT_NOT_FOUND"
            )
        checks = (
            (row["hospital_id"] == context.hospital_id,
             "SQL_OBJECT_TENANT_MISMATCH", "SQL 对象不属于当前医院。"),
            (row["user_id"] == context.user_id,
             "SQL_OBJECT_OWNER_MISMATCH", "SQL 对象不属于当前用户。"),
            (row["session_id"] == context.session_id,
             "SQL_OBJECT_SESSION_MISMATCH", "SQL 对象不属于当前会话。"),
            (not row["db_source_id"] or row["db_source_id"] == context.db_source_id,
             "SQL_OBJECT_SOURCE_MISMATCH", "SQL 对象的数据源已变化。"),
            (row["validation_status"] == "validated",
             "SQL_OBJECT_NOT_VALIDATED", "SQL 对象尚未通过安全校验。"),
        )
        for allowed, code, message in checks:
            if not allowed:
                raise SqlObjectAccessError(message, code=code)
        try:
            payload = dict(row)
            payload["params"] = json.loads(payload.pop("params_json"))
            payload["context_snapshot"] = json.loads(
                payload.pop("context_snapshot_json")
            )
            value = PreparedSqlObject.model_validate(payload)
        except (TypeError, ValueError, json.JSONDecodeError) as exc:
            raise SqlObjectAccessError(
                "SQL 对象内容损坏。", code="SQL_OBJECT_CORRUPTED"
            ) from exc
        if value.expires_at <= self.now_provider():
            raise SqlObjectAccessError(
                "SQL 对象已过期，请重新准备。", code="SQL_OBJECT_EXPIRED"
            )
        return value

    def cleanup_expired(self) -> int:
        with self.engine.begin() as connection:
            result = connection.execute(
                delete(_SQL_OBJECT_TABLE).where(
                    _SQL_OBJECT_TABLE.c.expires_at
                    <= self.now_provider().isoformat()
                )
            )
        return int(result.rowcount or 0)
```

`_SQL_OBJECT_TABLE` 必须显式声明 `PreparedSqlObject` 的标量字段，并用 `params_json`、`context_snapshot_json` 两个 `Text` 列替代字典字段；`sql_id` 为主键，`sql_text` 为 `Text`，`created_at`/`expires_at` 为最多 40 字符的 ISO-8601 字符串。为 `hospital_id + expires_at` 和 `session_id + validation_status` 建组合索引。

Store 必须使用 SQLAlchemy 参数绑定，JSON 使用 `json.dumps(payload, ensure_ascii=False)`，反序列化或 Pydantic 校验失败统一抛 `SQL_OBJECT_CORRUPTED`，不得把原始内容放进异常。

- [ ] **Step 4: 更新正式建库脚本**

在 `scripts/init_runtime_db.sql` 增加 `med_agent_sql_object`，字段与 Python Schema 一致，并为 `(hospital_id, expires_at)`、`(session_id, validation_status)` 建索引。SQL 文本列使用 `MEDIUMTEXT`，快照和参数使用 `JSON`，但不新增外键以兼容现有部署的分批迁移。

- [ ] **Step 5: 运行测试并确认通过**

Run: `python -m pytest tests/test_agent_sql_objects.py tests/test_runtime_migrations.py -q`

Expected: 全部通过。

- [ ] **Step 6: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/sql_objects.py scripts/init_runtime_db.sql tests/test_agent_sql_objects.py
git commit -m "feat: 增加 Agent 短期 SQL 对象"
git push
```

### Task 2: 建立经过验证的运行状态事实

**Files:**
- Create: `app/agent_tools/state_facts.py`
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_tools/read_tools.py`
- Modify: `app/agent_tools/__init__.py`
- Create: `tests/test_agent_state_facts.py`

**Interfaces:**
- Produces: `verified_rule_ids(state)`、`has_verified_rule(state, rule_id=None)`、`has_active_sql(state, sql_id=None)`。
- Produces: `AgentRunState.current_rule_id`、`validated_sql_ids`、`last_run_id`、`last_diagnosis_id`。
- Consumes: 只读取 `ok=True` 的 ToolResult 和成功证据；不得把失败工具结果升级为状态事实。

- [ ] **Step 1: 写入状态事实失败测试**

```python
from app.agent_runtime import AgentRunState
from app.agent_tools.state_facts import (
    has_active_sql,
    has_verified_rule,
    verified_rule_ids,
)


def test_verified_rule_ids_ignore_failed_result_evidence():
    state = AgentRunState(last_tool_results=[{
        "ok": False,
        "data": {"rule_id": "BAD"},
        "evidence": [{"source_id": "BAD", "fact_types": ["rule_identity"]}],
    }])
    assert verified_rule_ids(state) == set()
    assert not has_verified_rule(state)


def test_state_tracks_only_explicitly_validated_sql_ids():
    state = AgentRunState(validated_sql_ids=["SQL_001"])
    assert has_active_sql(state)
    assert has_active_sql(state, "SQL_001")
    assert not has_active_sql(state, "SQL_002")
```

同一测试文件增加四个独立测试：成功搜索证据、成功规则 `data`、重复 ID 去重和空 `source_id`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_state_facts.py -q`

Expected: FAIL，缺少状态字段和 `state_facts` 模块。

- [ ] **Step 3: 实现状态事实并复用到只读工具**

`state_facts.py` 只解析：

```python
def verified_rule_ids(state: AgentRunState) -> set[str]:
    result: set[str] = set()
    for item in [*state.last_tool_results, *state.evidence]:
        if not isinstance(item, dict):
            continue
        if "ok" in item and item.get("ok") is not True:
            continue
        data = item.get("data") if isinstance(item.get("data"), dict) else item
        rule_id = str(data.get("resolved_rule_id") or data.get("rule_id") or "")
        if rule_id:
            result.add(rule_id)
        evidence_items = item.get("evidence") or []
        if "fact_types" in item:
            evidence_items = [item, *evidence_items]
        for evidence in evidence_items:
            if not isinstance(evidence, dict):
                continue
            source_id = str(evidence.get("source_id") or "")
            if source_id and "rule_identity" in (evidence.get("fact_types") or []):
                result.add(source_id)
    return result


def has_verified_rule(
    state: AgentRunState, rule_id: str | None = None
) -> bool:
    ids = verified_rule_ids(state)
    return bool(ids) if rule_id is None else rule_id in ids


def has_active_sql(state: AgentRunState, sql_id: str | None = None) -> bool:
    return (
        bool(state.validated_sql_ids)
        if sql_id is None
        else sql_id in state.validated_sql_ids
    )
```

`read_tools._state_has_verified_rule` 改为调用 `has_verified_rule(state)`，删除重复解析逻辑。`AgentRunState` 新字段全部使用安全默认值：

```python
current_rule_id: str | None = None
validated_sql_ids: list[str] = Field(default_factory=list)
last_run_id: str | None = None
last_diagnosis_id: str | None = None
```

- [ ] **Step 4: 运行相关测试并确认通过**

Run: `python -m pytest tests/test_agent_state_facts.py tests/test_agent_read_tools.py tests/test_agent_read_tool_catalog.py tests/test_agent_runtime_contracts.py -q`

Expected: 全部通过。

- [ ] **Step 5: 检查并提交**

```powershell
git diff --check
git add app/agent_runtime/contracts.py app/agent_tools/state_facts.py app/agent_tools/read_tools.py app/agent_tools/__init__.py tests/test_agent_state_facts.py
git commit -m "refactor: 统一 Agent 已验证状态事实"
git push
```

### Task 3: 实现 SQL 准备和 sql_id 试运行工具

**Files:**
- Create: `app/agent_tools/sql_tools.py`
- Modify: `app/agent_tools/__init__.py`
- Create: `tests/test_agent_sql_tools.py`

**Interfaces:**
- Produces: `PrepareIndicatorSqlInput`、`TrialRunIndicatorSqlInput`、`SqlToolServices`、`prepare_indicator_sql()`、`trial_run_indicator_sql()`、`build_sql_tools()`。
- Consumes: `CoreIndicatorOrchestrator.prepare_rule_request()` / `generate_indicator()`、`AgentSqlObjectStore`、`validate_select_sql()`、`run_sql_trial()`。

- [ ] **Step 1: 写入 SQL 工具失败测试**

创建 Fake Orchestrator、SQLite Store 和 Fake Trial Executor，覆盖：

```python
def test_prepare_sql_persists_private_object_without_returning_sql_text():
    state = verified_rule_state("MQSI2025_005")
    result = prepare_indicator_sql(
        PrepareIndicatorSqlInput(
            rule_id="MQSI2025_005",
            stat_start_time="2026-07-01T00:00:00",
            stat_end_time="2026-08-01T00:00:00",
        ),
        context(),
        state,
        services=services(),
    )
    assert result.ok
    assert result.code == "SQL_OBJECT_PREPARED"
    assert result.data["sql_id"] == "SQL_001"
    assert "sql_text" not in result.data
    assert "SQL_001" in state.validated_sql_ids
    assert any("sql_validation" in item.fact_types for item in result.evidence)


def test_trial_input_schema_accepts_only_sql_id():
    with pytest.raises(ValidationError):
        TrialRunIndicatorSqlInput(
            sql_id="SQL_001", sql_text="SELECT * FROM patient"
        )


def test_trial_reloads_server_snapshot_and_returns_aggregate_only():
    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        context(),
        AgentRunState(validated_sql_ids=["SQL_001"]),
        services=services_with_prepared_object(),
    )
    assert result.ok
    assert result.data == {
        "sql_id": "SQL_001",
        "run_id": "RUN_001",
        "status": "success",
        "result_value": 92.5,
        "numerator_count": 37,
        "denominator_count": 40,
        "no_sample": False,
        "duration_ms": 18,
        "source": "hospital_db",
        "stat_start": "2026-07-01 00:00:00",
        "stat_end": "2026-08-01 00:00:00",
    }
```

再覆盖：未验证规则不能准备、开始时间不早于结束时间、字段预检失败、SQL 校验失败、对象过期/越权、上下文 digest 变化、二次只读校验失败、DBHub 失败不泄漏异常、试运行结果中的额外行和 SQL 字段被丢弃。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_sql_tools.py -q`

Expected: FAIL，缺少 `sql_tools`。

- [ ] **Step 3: 实现输入与服务契约**

```python
class PrepareIndicatorSqlInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    rule_id: str = Field(min_length=1, max_length=128)
    stat_start_time: datetime
    stat_end_time: datetime

    @model_validator(mode="after")
    def validate_period(self):
        if self.stat_start_time >= self.stat_end_time:
            raise ValueError("统计开始时间必须早于结束时间")
        return self


class TrialRunIndicatorSqlInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sql_id: str = Field(pattern=r"^SQL_[A-Za-z0-9_-]{1,64}$")


@dataclass(frozen=True, slots=True)
class SqlToolServices:
    orchestrator: Any
    store: AgentSqlObjectStore
    runtime_engine: Engine
    business_db: Any
    ttl: timedelta = timedelta(minutes=30)
    now_provider: Callable[[], datetime] = _utcnow
    trial_executor: Callable[..., dict[str, Any]] = run_sql_trial
    sql_validator: Callable[..., dict[str, Any]] = validate_select_sql
```

所有日期统一格式化为 `YYYY-MM-DD HH:MM:SS`。上下文 digest 对以下规范化 JSON 做 SHA-256：当前生效规则完整契约、应用 `execution_context` 后的字段映射、统计区间、非敏感参数和 `db_source_id`。

- [ ] **Step 4: 实现 SQL 准备工具**

固定流程：

```text
确认 rule_id 已由成功工具证据验证
→ prepare_rule_request(intent="generate_sql")
→ generate_indicator(trial_run=False, generated_by=context.user_id)
→ 检查 precheck、validation.ok、sql_status 和 sql_id/sql_text
→ 构造 PreparedSqlObject 并保存
→ state.current_rule_id / validated_sql_ids 更新
→ 只返回 sql_id、规则、方言、统计区间、校验状态、过期时间和警告
```

字段预检失败返回 `FIELD_PRECHECK_FAILED`；安全校验失败返回 `SQL_VALIDATION_FAILED`；两者都使用 `status="validation_failed"` 且不产生 `sql_validation` 证据。

- [ ] **Step 5: 实现 sql_id 试运行工具**

固定流程：

```text
确认 sql_id 在本次已验证状态中
→ Store 按医院/用户/会话/数据源/TTL 读取
→ 重新解析当前规则和字段映射并比较 digest
→ 再次调用 validate_select_sql
→ 使用对象内 sql_text、params、统计区间和 run_context 调用 run_sql_trial
→ 白名单投影聚合结果
→ state.last_run_id 更新
```

上下文变化时设置 `state.stop_reason="context_conflict"`，返回 `SQL_CONTEXT_STALE`；DBHub/执行失败返回 `TRIAL_RUN_FAILED`，只使用固定中文摘要。成功证据：

```python
ToolEvidence(
    source=result_source or object.db_source_id or "hospital_business_db",
    source_id=run_id,
    version=object.context_digest,
    fact_types=["trial_run", "aggregate_result"],
)
```

- [ ] **Step 6: 构建动态工具并运行测试**

`build_sql_tools()` 注册：

- `prepare_indicator_sql`：`CONTROLLED_EXECUTION`、`indicator_read`、有已验证规则时可见、30 秒超时。
- `trial_run_indicator_sql`：`CONTROLLED_EXECUTION`、`indicator_read`、有 active `sql_id` 时可见、60 秒超时。

Run: `python -m pytest tests/test_agent_sql_tools.py tests/test_agent_tool_gateway.py tests/test_sqlgen.py -q`

Expected: 全部通过。

- [ ] **Step 7: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/sql_tools.py app/agent_tools/__init__.py tests/test_agent_sql_tools.py
git commit -m "feat: 增加 Agent SQL 准备与试运行工具"
git push
```

### Task 4: 实现安全诊断工具和完整动态目录

**Files:**
- Create: `app/agent_tools/diagnosis_tools.py`
- Create: `app/agent_tools/catalog.py`
- Modify: `app/agent_tools/__init__.py`
- Create: `tests/test_agent_diagnosis_tool.py`
- Create: `tests/test_agent_execution_catalog.py`

**Interfaces:**
- Produces: `DiagnoseIndicatorIssueInput`、`DiagnosisToolServices`、`diagnose_indicator_issue()`、`build_diagnosis_tools()`。
- Produces: `build_agent_tool_registry(read_services, sql_services, diagnosis_services)`。
- Consumes: `CoreIndicatorOrchestrator.prepare_rule_request()` / `diagnose()` 和 Tasks 2–3 的状态事实。

- [ ] **Step 1: 写入诊断工具失败测试**

```python
def test_diagnosis_reuses_orchestrator_and_returns_safe_projection():
    result = diagnose_indicator_issue(
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="为什么本月结果下降？",
            pasted_sql="SELECT index_value FROM safe_view",
            stat_period="2026-07-01~2026-07-31",
        ),
        context(),
        verified_rule_state("MQSI2025_005"),
        services=services(),
    )
    assert result.ok
    assert result.code == "INDICATOR_DIAGNOSED"
    assert result.data["report_id"] == "DR_001"
    serialized = json.dumps(result.data, ensure_ascii=False)
    assert "SELECT index_value" not in serialized
    assert "patient_name" not in serialized
    assert "connection" not in serialized
    assert any("diagnosis" in item.fact_types for item in result.evidence)


def test_diagnosis_input_forbids_tenant_and_connection_fields():
    with pytest.raises(ValidationError):
        DiagnoseIndicatorIssueInput(
            rule_id="MQSI2025_005",
            issue_description="结果异常",
            hospital_id="other",
        )
```

再覆盖：规则未验证、粘贴 SQL 超过 20,000 字符、诊断失败固定摘要、无 report_id 时仍返回安全状态、`state.last_diagnosis_id` 只在有 ID 时更新。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_diagnosis_tool.py -q`

Expected: FAIL，缺少诊断工具模块。

- [ ] **Step 3: 实现诊断输入和安全投影**

```python
class DiagnoseIndicatorIssueInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    rule_id: str = Field(min_length=1, max_length=128)
    issue_description: str = Field(min_length=1, max_length=1000)
    pasted_sql: str | None = Field(default=None, max_length=20_000)
    declared_params: dict[str, Any] = Field(default_factory=dict)
    stat_period: str | None = Field(default=None, max_length=64)


@dataclass(frozen=True, slots=True)
class DiagnosisToolServices:
    orchestrator: Any
```

handler 将用户明确提供的 SQL 放入现有诊断 query 文本，调用：

```python
prepared = orchestrator.prepare_rule_request(
    query=query_text,
    hospital_id=context.hospital_id,
    intent="diagnose",
    rule_id=arguments.rule_id,
)
result = orchestrator.diagnose(
    prepared,
    trigger="agent_tool",
    stat_period=arguments.stat_period,
)
```

返回白名单仅含 `diagnose_status`、`report_id`、`summary`、`user_summary`、每层的 `layer/layer_name/ok` 和每项检查的 `status/message/repair_suggest`。不得返回 `evidence.raw_text`、`sql_text`、`execution_results`、记录行、Trace 原始数据或异常。

- [ ] **Step 4: 写入完整目录失败测试**

验证动态工具名称：

```python
assert names(empty_state) == ["search_indicator_rules"]
assert names(rule_state) == [
    "search_indicator_rules",
    "get_effective_rule",
    "inspect_indicator_implementation",
    "prepare_indicator_sql",
    "diagnose_indicator_issue",
]
assert names(sql_state) == [
    "search_indicator_rules",
    "get_effective_rule",
    "inspect_indicator_implementation",
    "prepare_indicator_sql",
    "trial_run_indicator_sql",
    "diagnose_indicator_issue",
]
```

同时验证权限缺失时 Registry 不暴露任何工具、最大可见数量为六、Gateway 直接调用不可见工具仍返回 `TOOL_UNAVAILABLE`。

- [ ] **Step 5: 实现完整目录并运行回归**

`build_agent_tool_registry()` 按固定顺序组合 `build_read_tools()`、`build_sql_tools()`、`build_diagnosis_tools()`；不做关键词判断。

Run: `python -m pytest tests/test_agent_diagnosis_tool.py tests/test_agent_execution_catalog.py tests/test_agent_read_tool_catalog.py tests/test_agent_tool_gateway.py tests/test_diagnose_agent.py -q`

Expected: 全部通过。

- [ ] **Step 6: 检查并提交**

```powershell
git diff --check
git add app/agent_tools/diagnosis_tools.py app/agent_tools/catalog.py app/agent_tools/__init__.py tests/test_agent_diagnosis_tool.py tests/test_agent_execution_catalog.py
git commit -m "feat: 增加 Agent 安全诊断与动态工具目录"
git push
```

### Task 5: 增加事实类型回答守卫和 Stage 4 闭环

**Files:**
- Create: `app/agent_runtime/response_guard.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/agent_runtime/prompts.py`
- Modify: `app/agent_runtime/__init__.py`
- Create: `tests/test_agent_response_guard.py`
- Create: `tests/test_agent_execution_loop.py`

**Interfaces:**
- Produces: `missing_fact_types(answer, evidence) -> set[str]`、`evidence_correction_prompt(missing) -> str`。
- Consumes: `AgentRunState.evidence[*].fact_types`，Runner 在接受中文最终回答前调用。

- [ ] **Step 1: 写入回答守卫失败测试**

```python
@pytest.mark.parametrize(
    ("answer", "required"),
    [
        ("该 SQL 已校验通过，可以执行。", {"sql_validation"}),
        ("本次试运行分子 37、分母 40，指标值 92.5%。", {"trial_run"}),
        ("诊断发现根因是字段映射缺失。", {"diagnosis"}),
    ],
)
def test_claims_require_matching_fact_types(answer, required):
    assert missing_fact_types(answer, []) == required


def test_matching_evidence_authorizes_claim():
    evidence = [{"fact_types": ["trial_run", "aggregate_result"]}]
    assert missing_fact_types(
        "本次试运行分子 37、分母 40，指标值 92.5%。", evidence
    ) == set()


def test_rule_formula_does_not_require_trial_evidence():
    assert missing_fact_types(
        "公式为及时到位例数除以急会诊总例数乘以 100%。",
        [{"fact_types": ["definition", "formula"]}],
    ) == set()
```

再覆盖 SQL、试运行、诊断多种中文表达和空回答。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m pytest tests/test_agent_response_guard.py -q`

Expected: FAIL，缺少 `response_guard`。

- [ ] **Step 3: 实现最小确定性事实守卫**

使用保守、可测试的中文 claim pattern：

```python
CLAIM_RULES = (
    ClaimRule("sql_validation", (r"SQL.{0,12}(?:校验通过|已验证|可以执行|可执行)",)),
    ClaimRule("trial_run", (
        r"(?:试运行|本次运行).{0,30}(?:指标值|分子|分母|样本量|结果)",
        r"(?:分子|分母|样本量|指标值)\s*(?:为|是|[:：])?\s*\d",
    )),
    ClaimRule("diagnosis", (r"(?:诊断|排查).{0,30}(?:发现|结论|根因|异常原因)",)),
)
```

守卫只阻止明确的完成性事实声明，不把规则公式中的 `100%` 误判成医院运行结果。缺证据时 Runner 追加 system 纠正提示并继续；达到最大步骤后按现有 `max_steps` 停止。

- [ ] **Step 4: Runner 处理上下文冲突**

Gateway 返回后若 `run_state.stop_reason == "context_conflict"`，Runner 立即返回：

```python
AgentRunResult(
    answer=result.summary,
    stop_reason="context_conflict",
    state=run_state,
    model=model_name,
)
```

不得继续让模型尝试执行旧 `sql_id`。

- [ ] **Step 5: 写入 Stage 4 Agent 闭环测试**

Fake Model 固定执行：

```text
search_indicator_rules
→ get_effective_rule
→ prepare_indicator_sql
→ trial_run_indicator_sql
→ 中文聚合回答
```

断言：

- 每轮可见工具满足动态目录且不超过六个；
- 试运行调用参数只有 `sql_id`；
- 最终回答有 `sql_validation` 和 `trial_run` 证据；
- 模型消息与 ToolResult 不含 `sql_text`、患者行或连接信息；
- SQL 上下文失效时停止原因为 `context_conflict`；
- 没有试运行证据的具体分子/分母回答不会被接受。

- [ ] **Step 6: 运行 Stage 4 相关回归**

Run:

```powershell
python -m pytest `
  tests/test_agent_sql_objects.py `
  tests/test_agent_state_facts.py `
  tests/test_agent_sql_tools.py `
  tests/test_agent_diagnosis_tool.py `
  tests/test_agent_execution_catalog.py `
  tests/test_agent_response_guard.py `
  tests/test_agent_execution_loop.py `
  tests/test_agent_runner.py `
  tests/test_agent_runner_controls.py `
  tests/test_agent_read_tools.py `
  tests/test_agent_tool_gateway.py `
  tests/test_sqlgen.py `
  tests/test_diagnose_agent.py -q
```

Expected: 全部通过。

- [ ] **Step 7: 运行完整测试套件**

Run: `python -m pytest -q`

Expected: 全部通过，真实 Ollama 探针默认跳过；旧 Agent、API、流式接口、SQL 生成、试运行和诊断行为不变。

- [ ] **Step 8: 检查并提交**

```powershell
git diff --check
git status --short
git add app/agent_runtime/response_guard.py app/agent_runtime/runner.py app/agent_runtime/prompts.py app/agent_runtime/__init__.py tests/test_agent_response_guard.py tests/test_agent_execution_loop.py
git commit -m "feat: 完成 Agent SQL 试运行证据闭环"
git push
```

## Completion Criteria

1. SQL 对象服务端保存并绑定医院、用户、会话、数据源、创建时间和 30 分钟 TTL。
2. SQL 对象只在 `validated`、未过期、同一安全范围且执行上下文未变化时可用。
3. `prepare_indicator_sql` 复用现有确定性生成链路，不返回完整 SQL。
4. `trial_run_indicator_sql` 模型参数只能包含 `sql_id`，执行前重新做只读校验。
5. 试运行只返回聚合结果和安全摘要，不返回患者明细或完整查询结果。
6. 诊断工具复用现有粘贴 SQL与三层诊断安全链，只返回白名单结论。
7. 无规则证据不能准备 SQL；无 SQL 校验证据不能声称 SQL 可执行；无试运行证据不能输出具体医院业务数值；无诊断证据不能声称已找到根因。
8. SQL 上下文失效确定性停止为 `context_conflict`，不继续执行旧对象。
9. 动态工具目录初始只暴露搜索，规则确定后最多暴露六个相关工具。
10. 不增加固定意图路由，不修改旧聊天、API、SSE 和正式审批发布行为。
11. 相关回归和完整测试都有新鲜通过结果。
