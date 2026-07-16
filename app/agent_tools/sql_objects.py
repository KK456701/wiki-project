"""Agent 短期 SQL 对象及其安全读取边界。"""

from __future__ import annotations

import json
from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from sqlalchemy import (
    Column,
    Index,
    MetaData,
    String,
    Table,
    Text,
    delete,
    insert,
    inspect,
    select,
)
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from app.agent_runtime.contracts import AgentRuntimeContext


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


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


_METADATA = MetaData()
_SQL_OBJECT_TABLE = Table(
    "med_agent_sql_object",
    _METADATA,
    Column("sql_id", String(80), primary_key=True),
    Column("hospital_id", String(128), nullable=False),
    Column("user_id", String(128), nullable=False),
    Column("session_id", String(128), nullable=False),
    Column("rule_id", String(128), nullable=False),
    Column("dialect", String(32), nullable=False),
    Column("sql_text", Text, nullable=False),
    Column("params_json", Text, nullable=False),
    Column("stat_start", String(32), nullable=False),
    Column("stat_end", String(32), nullable=False),
    Column("context_snapshot_json", Text, nullable=False),
    Column("context_digest", String(64), nullable=False),
    Column("validation_status", String(32), nullable=False),
    Column("validation_message", Text, nullable=False, default=""),
    Column("created_at", String(40), nullable=False),
    Column("expires_at", String(40), nullable=False),
    Column("db_source_id", String(128), nullable=True),
    Index("ix_agent_sql_hospital_expiry", "hospital_id", "expires_at"),
    Index("ix_agent_sql_session_status", "session_id", "validation_status"),
)


def ensure_agent_sql_object_schema(engine: Engine) -> list[str]:
    existed = inspect(engine).has_table(_SQL_OBJECT_TABLE.name)
    _METADATA.create_all(engine, tables=[_SQL_OBJECT_TABLE])
    return [] if existed else [_SQL_OBJECT_TABLE.name]


class AgentSqlObjectStore:
    def __init__(
        self,
        engine: Engine,
        *,
        now_provider: Callable[[], datetime] = _utcnow,
    ) -> None:
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
        try:
            with self.engine.begin() as connection:
                connection.execute(insert(_SQL_OBJECT_TABLE).values(**payload))
        except IntegrityError as exc:
            raise SqlObjectAccessError(
                "SQL 对象标识已存在。",
                code="SQL_OBJECT_ALREADY_EXISTS",
            ) from exc

    def load_for_execution(
        self,
        sql_id: str,
        context: AgentRuntimeContext,
    ) -> PreparedSqlObject:
        with self.engine.connect() as connection:
            row = connection.execute(
                select(_SQL_OBJECT_TABLE).where(
                    _SQL_OBJECT_TABLE.c.sql_id == sql_id
                )
            ).mappings().first()
        if row is None:
            raise SqlObjectAccessError(
                "SQL 对象不存在。",
                code="SQL_OBJECT_NOT_FOUND",
            )

        checks = (
            (
                row["hospital_id"] == context.hospital_id,
                "SQL_OBJECT_TENANT_MISMATCH",
                "SQL 对象不属于当前医院。",
            ),
            (
                row["user_id"] == context.user_id,
                "SQL_OBJECT_OWNER_MISMATCH",
                "SQL 对象不属于当前用户。",
            ),
            (
                row["session_id"] == context.session_id,
                "SQL_OBJECT_SESSION_MISMATCH",
                "SQL 对象不属于当前会话。",
            ),
            (
                not row["db_source_id"]
                or row["db_source_id"] == context.db_source_id,
                "SQL_OBJECT_SOURCE_MISMATCH",
                "SQL 对象的数据源已变化。",
            ),
            (
                row["validation_status"] == "validated",
                "SQL_OBJECT_NOT_VALIDATED",
                "SQL 对象尚未通过安全校验。",
            ),
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
        except (TypeError, ValueError, json.JSONDecodeError, ValidationError) as exc:
            raise SqlObjectAccessError(
                "SQL 对象内容损坏。",
                code="SQL_OBJECT_CORRUPTED",
            ) from exc

        if value.expires_at <= self.now_provider():
            raise SqlObjectAccessError(
                "SQL 对象已过期，请重新准备。",
                code="SQL_OBJECT_EXPIRED",
            )
        return value

    def cleanup_expired(self) -> int:
        cutoff = self.now_provider().isoformat()
        with self.engine.begin() as connection:
            result = connection.execute(
                delete(_SQL_OBJECT_TABLE).where(
                    _SQL_OBJECT_TABLE.c.expires_at <= cutoff
                )
            )
        return int(result.rowcount or 0)
