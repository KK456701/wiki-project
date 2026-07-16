import json
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine

from app.agent_runtime import AgentRunState, AgentRuntimeContext
from app.agent_tools.sql_objects import (
    AgentSqlObjectStore,
    PreparedSqlObject,
    ensure_agent_sql_object_schema,
)
from app.agent_tools.sql_tools import (
    PrepareIndicatorSqlInput,
    SqlToolServices,
    TrialRunIndicatorSqlInput,
    build_sql_tools,
    prepare_indicator_sql,
    trial_run_indicator_sql,
)
from app.agents.contracts import EffectiveRule, FieldMapping, PreparedRequest


NOW = datetime(2026, 7, 16, 2, 0, tzinfo=timezone.utc)


def _context(**updates) -> AgentRuntimeContext:
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


def _rule_state(rule_id="MQSI2025_005") -> AgentRunState:
    return AgentRunState(evidence=[{
        "source": "mysql",
        "source_id": rule_id,
        "fact_types": ["rule_identity"],
    }])


class FakeOrchestrator:
    def __init__(self, generation=None) -> None:
        self.definition = "急会诊在规定时间内到位的比例。"
        self.mapping_status = "confirmed"
        self.generation = generation
        self.prepare_calls = []
        self.generate_calls = []
        self.metadata = FakeMetadata()

    def prepare_rule_request(self, **kwargs):
        self.prepare_calls.append(kwargs)
        return PreparedRequest(
            query=kwargs["query"],
            hospital_id=kwargs["hospital_id"],
            intent=kwargs["intent"],
            rule_id=kwargs["rule_id"],
            effective_rule=EffectiveRule.model_validate({
                "rule_id": kwargs["rule_id"],
                "rule_name": "急会诊及时到位率",
                "definition": self.definition,
                "formula": "及时到位例数 / 急会诊总例数 × 100%",
                "standard_sql": "SELECT protected_rule_template",
                "effective_level": "hospital",
                "national_version": "2025",
                "hospital_version": 2,
                "calculation_definition": {"measure": "ratio"},
            }),
            field_mapping=FieldMapping.model_validate({
                "rule_id": kwargs["rule_id"],
                "hospital_id": kwargs["hospital_id"],
                "db_name": "hospital_db",
                "main_table": "consult_record",
                "dialect": "sqlserver",
                "status": self.mapping_status,
                "fields": {"arrival_time": "arrival_time"},
            }),
        )

    def generate_indicator(self, prepared, **kwargs):
        self.generate_calls.append({"prepared": prepared, **kwargs})
        if self.generation is not None:
            return self.generation
        return {
            "status": "success",
            "sql_id": "SQL_001",
            "sql_text": "SELECT 92.5 AS index_value",
            "sql_status": "validated",
            "validation": {"ok": True, "message": "只读校验通过"},
            "precheck": {"ok": True},
            "dialect": "sqlserver",
            "params": {"threshold_minutes": 10},
            "field_mapping": prepared.field_mapping.model_dump(by_alias=True),
            "calculation_definition": {"measure": "ratio"},
            "execution_context": {},
        }


class FakeMetadata:
    def __init__(self) -> None:
        self.result = {"ok": True}
        self.calls = []

    def precheck_contract(self, hospital_id, rule_id, **kwargs):
        self.calls.append({
            "hospital_id": hospital_id,
            "rule_id": rule_id,
            **kwargs,
        })
        return dict(self.result)


class TrialRecorder:
    def __init__(self, result=None) -> None:
        self.calls = []
        self.result = result or {
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
            "rows": [{"patient_name": "不应返回"}],
            "sql_text": "SELECT secret",
        }

    def __call__(self, **kwargs):
        self.calls.append(kwargs)
        return dict(self.result)


def _services(
    *,
    orchestrator=None,
    trial=None,
    validator=None,
    now=NOW,
):
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    store = AgentSqlObjectStore(engine, now_provider=lambda: now)
    return SqlToolServices(
        orchestrator=orchestrator or FakeOrchestrator(),
        store=store,
        runtime_engine=engine,
        business_db=object(),
        now_provider=lambda: now,
        trial_executor=trial or TrialRecorder(),
        sql_validator=validator or (
            lambda sql_text, hospital_id, main_table: {
                "ok": True,
                "message": "只读校验通过",
            }
        ),
    )


def _prepare(services, state=None):
    state = state or _rule_state()
    result = prepare_indicator_sql(
        PrepareIndicatorSqlInput(
            rule_id="MQSI2025_005",
            stat_start_time="2026-07-01T00:00:00",
            stat_end_time="2026-08-01T00:00:00",
        ),
        _context(),
        state,
        services=services,
    )
    return state, result


def test_prepare_sql_persists_private_object_without_returning_sql_text() -> None:
    services = _services()
    state, result = _prepare(services)

    assert result.ok
    assert result.code == "SQL_OBJECT_PREPARED"
    assert result.data["sql_id"] == "SQL_001"
    assert "sql_text" not in result.data
    assert "SQL_001" in state.validated_sql_ids
    assert state.current_rule_id == "MQSI2025_005"
    assert any("sql_validation" in item.fact_types for item in result.evidence)

    stored = services.store.load_for_execution("SQL_001", _context())
    assert stored.sql_text == "SELECT 92.5 AS index_value"
    assert stored.params == {"threshold_minutes": 10}
    assert stored.expires_at == NOW + timedelta(minutes=30)
    rule_snapshot = stored.context_snapshot["effective_rule"]
    assert "standard_sql" not in rule_snapshot
    assert len(rule_snapshot["standard_sql_sha256"]) == 64
    assert "protected_rule_template" not in json.dumps(
        stored.context_snapshot, ensure_ascii=False
    )


def test_prepare_requires_matching_verified_rule() -> None:
    _, result = _prepare(_services(), AgentRunState())

    assert not result.ok
    assert result.code == "RULE_NOT_VERIFIED"


@pytest.mark.parametrize(
    ("generation", "code"),
    [
        (
            {
                "status": "field_precheck_failed",
                "precheck": {"ok": False, "missing_mappings": ["arrival_time"]},
                "message": "字段映射缺失",
            },
            "FIELD_PRECHECK_FAILED",
        ),
        (
            {
                "status": "success",
                "sql_id": "SQL_002",
                "sql_text": "DELETE FROM patient",
                "sql_status": "invalid",
                "validation": {"ok": False, "message": "只允许 SELECT"},
            },
            "SQL_VALIDATION_FAILED",
        ),
    ],
)
def test_prepare_rejects_precheck_or_validation_failure(generation, code) -> None:
    services = _services(orchestrator=FakeOrchestrator(generation))
    state, result = _prepare(services)

    assert not result.ok
    assert result.status == "validation_failed"
    assert result.code == code
    assert state.validated_sql_ids == []
    assert result.evidence == []


def test_prepare_period_must_be_in_order() -> None:
    with pytest.raises(ValidationError):
        PrepareIndicatorSqlInput(
            rule_id="MQSI2025_005",
            stat_start_time="2026-08-01T00:00:00",
            stat_end_time="2026-07-01T00:00:00",
        )


def test_trial_input_schema_accepts_only_sql_id() -> None:
    with pytest.raises(ValidationError):
        TrialRunIndicatorSqlInput(
            sql_id="SQL_001",
            sql_text="SELECT * FROM patient",
        )


def test_trial_reloads_server_snapshot_and_returns_aggregate_only() -> None:
    trial = TrialRecorder()
    services = _services(trial=trial)
    state, prepared = _prepare(services)
    assert prepared.ok

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        state,
        services=services,
    )

    assert result.ok
    assert result.code == "TRIAL_RUN_COMPLETED"
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
    assert state.last_run_id == "RUN_001"
    assert trial.calls[0]["sql_text"] == "SELECT 92.5 AS index_value"
    assert trial.calls[0]["params"] == {"threshold_minutes": 10}
    assert any("trial_run" in item.fact_types for item in result.evidence)


def test_trial_requires_sql_id_in_validated_state() -> None:
    services = _services()
    _prepare(services)

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        AgentRunState(),
        services=services,
    )

    assert not result.ok
    assert result.code == "SQL_OBJECT_NOT_ACTIVE"


def test_trial_maps_expired_object_to_safe_unavailable_result() -> None:
    services = _services(now=NOW)
    services.store.save(PreparedSqlObject(
        sql_id="SQL_EXPIRED",
        hospital_id="h1",
        user_id="u1",
        session_id="s1",
        rule_id="MQSI2025_005",
        dialect="sqlserver",
        sql_text="SELECT 1",
        params={},
        stat_start="2026-07-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
        context_snapshot={},
        context_digest="old",
        validation_status="validated",
        created_at=NOW - timedelta(hours=1),
        expires_at=NOW - timedelta(minutes=30),
        db_source_id="hospital_db",
    ))

    state = AgentRunState(validated_sql_ids=["SQL_EXPIRED"])
    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_EXPIRED"),
        _context(),
        state,
        services=services,
    )

    assert not result.ok
    assert result.status == "unavailable"
    assert result.code == "SQL_OBJECT_EXPIRED"
    assert "SELECT" not in result.summary
    assert state.validated_sql_ids == []
    prepare_tool = build_sql_tools(services)[0]
    assert prepare_tool.availability(_context(), state) is False

    state.evidence.extend(_rule_state().evidence)
    assert prepare_tool.availability(_context(), state) is True


def test_trial_stops_when_rule_or_mapping_context_changes() -> None:
    orchestrator = FakeOrchestrator()
    services = _services(orchestrator=orchestrator)
    state, prepared = _prepare(services)
    assert prepared.ok
    orchestrator.definition = "已变更的指标定义。"

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        state,
        services=services,
    )

    assert not result.ok
    assert result.code == "SQL_CONTEXT_STALE"
    assert state.stop_reason == "context_conflict"


def test_trial_rejects_sql_that_fails_second_validation() -> None:
    services = _services(validator=lambda *_: {
        "ok": False,
        "message": "只允许只读查询",
    })
    state, prepared = _prepare(services)
    assert prepared.ok

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        state,
        services=services,
    )

    assert not result.ok
    assert result.code == "SQL_REVALIDATION_FAILED"


def test_trial_stops_when_current_metadata_precheck_fails() -> None:
    orchestrator = FakeOrchestrator()
    services = _services(orchestrator=orchestrator)
    state, prepared = _prepare(services)
    assert prepared.ok
    orchestrator.metadata.result = {
        "ok": False,
        "missing_columns": ["arrival_time"],
    }

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        state,
        services=services,
    )

    assert not result.ok
    assert result.code == "SQL_CONTEXT_STALE"
    assert result.data == {"missing_columns": ["arrival_time"]}
    assert state.stop_reason == "context_conflict"


def test_trial_failure_hides_internal_error_and_has_no_evidence() -> None:
    trial = TrialRecorder({
        "run_id": "RUN_FAILED",
        "status": "failed",
        "error_message": "password=secret connection=db.internal",
        "source": "hospital_db",
    })
    services = _services(trial=trial)
    state, prepared = _prepare(services)
    assert prepared.ok

    result = trial_run_indicator_sql(
        TrialRunIndicatorSqlInput(sql_id="SQL_001"),
        _context(),
        state,
        services=services,
    )

    assert not result.ok
    assert result.code == "TRIAL_RUN_FAILED"
    assert "secret" not in result.summary
    assert "internal" not in result.summary
    assert result.evidence == []


def test_sql_tool_visibility_depends_on_verified_rule_and_active_sql() -> None:
    tools = build_sql_tools(_services())
    prepare_tool, trial_tool = tools

    assert prepare_tool.availability(_context(), AgentRunState()) is False
    assert prepare_tool.availability(_context(), _rule_state()) is True
    active_state = _rule_state()
    active_state.validated_sql_ids.append("SQL_001")
    assert prepare_tool.availability(_context(), active_state) is False
    assert trial_tool.availability(_context(), AgentRunState()) is False
    assert trial_tool.availability(
        _context(), AgentRunState(validated_sql_ids=["SQL_001"])
    ) is True
