"""MySQL 元数据同步服务。"""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text
from app.db.repositories import log_sync_table, log_sync_column, log_sync_change


def sync_mysql_metadata(runtime_engine: Engine, business_engine: Engine,
                         hospital_id: str, db_name: str) -> dict[str, Any]:
    batch_id = uuid.uuid4().hex[:12]
    table_count = 0
    column_count = 0
    changes: list[str] = []

    with business_engine.connect() as conn:
        tables = conn.execute(
            text("SELECT TABLE_NAME, TABLE_COMMENT, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = :db"),
            {"db": db_name}).fetchall()
        table_count = len(tables)
        for t in tables:
            log_sync_table(runtime_engine, hospital_id, db_name, t[0], t[1] or "", t[2] or "", batch_id)
            cols = conn.execute(
                text("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = :db AND TABLE_NAME = :t ORDER BY ORDINAL_POSITION"),
                {"db": db_name, "t": t[0]}).fetchall()
            column_count += len(cols)
            for c in cols:
                log_sync_column(runtime_engine, hospital_id, db_name, t[0], c[0], c[1], c[2], c[3], c[4], c[5], c[6], batch_id)

    log_sync_change(runtime_engine, hospital_id, db_name, "", "", "full_sync", f"同步完成: {table_count} 表, {column_count} 列", batch_id)
    return {"hospital_id": hospital_id, "db_name": db_name, "table_count": table_count, "column_count": column_count, "batch_id": batch_id, "changes": changes}
