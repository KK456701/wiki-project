from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    Engine,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    inspect,
    text,
)


metadata = MetaData()
_id_type = BigInteger().with_variant(Integer, "sqlite")

detail_snapshot_table = Table(
    "med_indicator_detail_snapshot",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("snapshot_id", String(64), nullable=False, unique=True),
    Column("run_id", String(64), nullable=False, unique=True),
    Column("hospital_id", String(64), nullable=False),
    Column("rule_id", String(64), nullable=False),
    Column("relative_path", String(512), nullable=False),
    Column("file_sha256", String(64)),
    Column("denominator_count", Integer),
    Column("numerator_count", Integer),
    Column("unmatched_count", Integer),
    Column("column_schema_json", Text),
    Column("status", String(32), nullable=False),
    Column("created_by", String(64), nullable=False),
    Column("created_at", DateTime, nullable=False),
    Column("expires_at", DateTime, nullable=False),
    Column("error_message", Text),
)
Index(
    "idx_detail_snapshot_scope",
    detail_snapshot_table.c.hospital_id,
    detail_snapshot_table.c.expires_at,
)

indicator_export_table = Table(
    "med_indicator_export",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("export_id", String(64), nullable=False, unique=True),
    Column("snapshot_id", String(64), nullable=False),
    Column("run_id", String(64), nullable=False),
    Column("hospital_id", String(64), nullable=False),
    Column("rule_id", String(64), nullable=False),
    Column("relative_path", String(512), nullable=False),
    Column("file_name", String(255), nullable=False),
    Column("file_sha256", String(64)),
    Column("status", String(32), nullable=False),
    Column("row_count", Integer, nullable=False),
    Column("created_by", String(64), nullable=False),
    Column("created_at", DateTime, nullable=False),
    Column("expires_at", DateTime, nullable=False),
    Column("download_count", Integer, nullable=False, default=0),
    Column("last_downloaded_at", DateTime),
    Column("error_message", Text),
)
Index(
    "idx_indicator_export_scope",
    indicator_export_table.c.hospital_id,
    indicator_export_table.c.expires_at,
)


SQL_RUN_COLUMNS = {
    "numerator_count": "BIGINT NULL",
    "denominator_count": "BIGINT NULL",
    "run_context_json": "JSON NULL",
}


def ensure_indicator_detail_schema(engine: Engine) -> dict[str, list[str]]:
    inspector = inspect(engine)
    before = set(inspector.get_table_names())
    metadata.create_all(engine, checkfirst=True)
    created_tables = [
        table_name
        for table_name in ("med_indicator_detail_snapshot", "med_indicator_export")
        if table_name not in before
    ]
    inspector = inspect(engine)
    if not inspector.has_table("med_sql_run_log"):
        return {"created_tables": created_tables, "added_run_columns": []}
    existing = {
        str(column["name"])
        for column in inspector.get_columns("med_sql_run_log")
    }
    added: list[str] = []
    with engine.begin() as conn:
        for column_name, column_ddl in SQL_RUN_COLUMNS.items():
            if column_name in existing:
                continue
            conn.execute(
                text(
                    "ALTER TABLE med_sql_run_log "
                    f"ADD COLUMN {column_name} {column_ddl}"
                )
            )
            added.append(column_name)
    return {"created_tables": created_tables, "added_run_columns": added}
