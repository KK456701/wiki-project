from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Engine,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    inspect,
)


metadata = MetaData()
_id_type = BigInteger().with_variant(Integer, "sqlite")

hospital_user_table = Table(
    "med_hospital_user",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("user_id", String(64), nullable=False, unique=True),
    Column("account_id", String(64), nullable=False, unique=True),
    Column("hospital_id", String(64), nullable=False),
    Column("password_hash", String(128), nullable=False),
    Column("password_salt", String(64), nullable=False),
    Column("password_iterations", Integer, nullable=False),
    Column("must_change_password", Boolean, nullable=False, default=True),
    Column("status", String(32), nullable=False, default="active"),
    Column("failed_attempts", Integer, nullable=False, default=0),
    Column("locked_until", DateTime),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
)
Index("idx_hospital_user_scope", hospital_user_table.c.hospital_id, hospital_user_table.c.status)

hospital_permission_table = Table(
    "med_hospital_user_permission",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("user_id", String(64), nullable=False),
    Column("permission_code", String(64), nullable=False),
    Column("created_at", DateTime, nullable=False),
    UniqueConstraint("user_id", "permission_code", name="uq_hospital_user_permission"),
)

hospital_session_table = Table(
    "med_hospital_session",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("session_id", String(64), nullable=False, unique=True),
    Column("user_id", String(64), nullable=False),
    Column("token_hash", String(64), nullable=False, unique=True),
    Column("expires_at", DateTime, nullable=False),
    Column("revoked_at", DateTime),
    Column("created_at", DateTime, nullable=False),
    Column("last_seen_at", DateTime, nullable=False),
)
Index("idx_hospital_session_user", hospital_session_table.c.user_id, hospital_session_table.c.expires_at)

data_access_audit_table = Table(
    "med_data_access_audit",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("audit_id", String(64), nullable=False, unique=True),
    Column("user_id", String(64)),
    Column("hospital_id", String(64)),
    Column("rule_id", String(64)),
    Column("run_id", String(64)),
    Column("export_id", String(64)),
    Column("action", String(64), nullable=False),
    Column("result", String(32), nullable=False),
    Column("row_count", Integer),
    Column("request_id", String(64)),
    Column("reason", Text),
    Column("created_at", DateTime, nullable=False),
)
Index("idx_data_access_audit_scope", data_access_audit_table.c.hospital_id, data_access_audit_table.c.created_at)

AUTH_TABLES = (
    "med_hospital_user",
    "med_hospital_user_permission",
    "med_hospital_session",
    "med_data_access_audit",
)


def ensure_hospital_auth_schema(engine: Engine) -> dict[str, list[str]]:
    before = set(inspect(engine).get_table_names())
    metadata.create_all(engine, checkfirst=True)
    after = set(inspect(engine).get_table_names())
    return {
        "created_tables": [
            table_name
            for table_name in AUTH_TABLES
            if table_name in after and table_name not in before
        ]
    }
