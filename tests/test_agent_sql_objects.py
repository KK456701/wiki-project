from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from sqlalchemy import create_engine, inspect, text

from app.agent_runtime import AgentRuntimeContext
from app.agent_tools.sql_objects import (
    AgentSqlObjectStore,
    PreparedSqlObject,
    SqlObjectAccessError,
    ensure_agent_sql_object_schema,
)


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


def _sql_object(**updates) -> PreparedSqlObject:
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


def _store(*, now=NOW):
    engine = create_engine("sqlite+pysqlite:///:memory:")
    ensure_agent_sql_object_schema(engine)
    return engine, AgentSqlObjectStore(engine, now_provider=lambda: now)


def test_schema_is_idempotent_and_store_round_trips_private_snapshot() -> None:
    engine = create_engine("sqlite+pysqlite:///:memory:")
    first = ensure_agent_sql_object_schema(engine)
    second = ensure_agent_sql_object_schema(engine)

    assert first == ["med_agent_sql_object"]
    assert second == []
    assert inspect(engine).has_table("med_agent_sql_object")

    store = AgentSqlObjectStore(engine, now_provider=lambda: NOW)
    store.save(_sql_object())
    loaded = store.load_for_execution("SQL_001", _context())

    assert loaded.sql_text == "SELECT 1 AS index_value"
    assert loaded.params == {"threshold_minutes": 10}
    assert loaded.context_snapshot == {"rule": {"rule_id": "MQSI2025_005"}}


@pytest.mark.parametrize(
    ("changed", "code"),
    [
        ({"hospital_id": "h2"}, "SQL_OBJECT_TENANT_MISMATCH"),
        ({"user_id": "u2"}, "SQL_OBJECT_OWNER_MISMATCH"),
        ({"session_id": "s2"}, "SQL_OBJECT_SESSION_MISMATCH"),
        ({"db_source_id": "other_db"}, "SQL_OBJECT_SOURCE_MISMATCH"),
    ],
)
def test_store_rejects_scope_mismatch(changed, code) -> None:
    _, store = _store()
    store.save(_sql_object())

    with pytest.raises(SqlObjectAccessError) as raised:
        store.load_for_execution("SQL_001", _context(**changed))

    assert raised.value.code == code


@pytest.mark.parametrize(
    ("updates", "code"),
    [
        ({"expires_at": NOW - timedelta(seconds=1)}, "SQL_OBJECT_EXPIRED"),
        ({"validation_status": "invalid"}, "SQL_OBJECT_NOT_VALIDATED"),
    ],
)
def test_store_rejects_expired_or_unvalidated_object(updates, code) -> None:
    _, store = _store()
    store.save(_sql_object(**updates))

    with pytest.raises(SqlObjectAccessError) as raised:
        store.load_for_execution("SQL_001", _context())

    assert raised.value.code == code


def test_store_rejects_missing_object_without_leaking_identifier() -> None:
    _, store = _store()

    with pytest.raises(SqlObjectAccessError) as raised:
        store.load_for_execution("SQL_secret_identifier", _context())

    assert raised.value.code == "SQL_OBJECT_NOT_FOUND"
    assert "secret_identifier" not in str(raised.value)


def test_store_rejects_corrupted_json_with_standardized_error() -> None:
    engine, store = _store()
    store.save(_sql_object())
    with engine.begin() as connection:
        connection.execute(text(
            "UPDATE med_agent_sql_object "
            "SET params_json = '{not-json' WHERE sql_id = 'SQL_001'"
        ))

    with pytest.raises(SqlObjectAccessError) as raised:
        store.load_for_execution("SQL_001", _context())

    assert raised.value.code == "SQL_OBJECT_CORRUPTED"
    assert "not-json" not in str(raised.value)


def test_store_rejects_duplicate_sql_id_instead_of_overwriting() -> None:
    _, store = _store()
    store.save(_sql_object())

    with pytest.raises(SqlObjectAccessError) as raised:
        store.save(_sql_object(hospital_id="h2"))

    assert raised.value.code == "SQL_OBJECT_ALREADY_EXISTS"
    assert store.load_for_execution("SQL_001", _context()).hospital_id == "h1"


def test_cleanup_expired_removes_only_expired_objects() -> None:
    _, store = _store()
    store.save(_sql_object(sql_id="SQL_EXPIRED", expires_at=NOW - timedelta(seconds=1)))
    store.save(_sql_object(sql_id="SQL_ACTIVE"))

    assert store.cleanup_expired() == 1
    assert store.load_for_execution("SQL_ACTIVE", _context()).sql_id == "SQL_ACTIVE"


def test_runtime_init_script_declares_agent_sql_object_table_and_indexes() -> None:
    script = (
        Path(__file__).resolve().parents[1] / "scripts" / "init_runtime_db.sql"
    ).read_text(encoding="utf-8")

    assert "CREATE TABLE IF NOT EXISTS med_agent_sql_object" in script
    assert "ix_agent_sql_hospital_expiry" in script
    assert "ix_agent_sql_session_status" in script
