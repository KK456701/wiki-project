"""元数据同步服务。"""

from __future__ import annotations

import uuid
from typing import Any, Callable

from sqlalchemy import Engine

from app.db.repositories import log_sync_change, log_sync_column, log_sync_table


class DBHubMetadataProvider:
    def __init__(self, execute_sql: Callable[[str], list[dict[str, Any]]]):
        self._execute_sql = execute_sql

    def list_tables(self, db_name: str) -> list[dict[str, Any]]:
        sql = (
            "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_TYPE "
            "FROM INFORMATION_SCHEMA.TABLES "
            f"WHERE TABLE_SCHEMA = '{_escape_sql_string(db_name)}'"
        )
        return [_normalize_keys(row) for row in self._execute_sql(sql)]

    def list_columns(self, db_name: str, table_name: str) -> list[dict[str, Any]]:
        sql = (
            "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
            "FROM INFORMATION_SCHEMA.COLUMNS "
            f"WHERE TABLE_SCHEMA = '{_escape_sql_string(db_name)}' "
            f"AND TABLE_NAME = '{_escape_sql_string(table_name)}' "
            "ORDER BY ORDINAL_POSITION"
        )
        return [_normalize_keys(row) for row in self._execute_sql(sql)]


def sync_mysql_metadata(
    runtime_engine: Engine,
    metadata_provider: DBHubMetadataProvider,
    hospital_id: str,
    db_name: str,
) -> dict[str, Any]:
    batch_id = uuid.uuid4().hex[:12]
    table_count = 0
    column_count = 0
    changes: list[str] = []

    tables = metadata_provider.list_tables(db_name)
    table_count = len(tables)
    for table in tables:
        table_name = _get_value(table, "TABLE_NAME")
        if not table_name:
            continue
        log_sync_table(
            runtime_engine,
            hospital_id,
            db_name,
            table_name,
            _get_value(table, "TABLE_COMMENT"),
            _get_value(table, "TABLE_TYPE"),
            batch_id,
        )
        columns = metadata_provider.list_columns(db_name, table_name)
        column_count += len(columns)
        for column in columns:
            log_sync_column(
                runtime_engine,
                hospital_id,
                db_name,
                table_name,
                _get_value(column, "COLUMN_NAME"),
                _get_value(column, "DATA_TYPE"),
                _get_value(column, "COLUMN_TYPE"),
                _get_value(column, "IS_NULLABLE"),
                _get_value(column, "COLUMN_KEY"),
                _get_value(column, "COLUMN_DEFAULT"),
                _get_value(column, "COLUMN_COMMENT"),
                batch_id,
            )

    log_sync_change(runtime_engine, hospital_id, db_name, "", "", "full_sync", f"同步完成: {table_count} 张表, {column_count} 个字段", batch_id)
    return {
        "hospital_id": hospital_id,
        "db_name": db_name,
        "metadata_source": "dbhub",
        "table_count": table_count,
        "column_count": column_count,
        "batch_id": batch_id,
        "changes": changes,
    }


def _escape_sql_string(value: str) -> str:
    return str(value).replace("'", "''")


def _normalize_keys(row: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(row)
    for key, value in list(row.items()):
        normalized[str(key).upper()] = value
    return normalized


def _get_value(row: dict[str, Any], key: str) -> str:
    value = row.get(key) if key in row else row.get(key.lower())
    return "" if value is None else str(value)
