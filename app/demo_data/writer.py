"""将模拟业务数据安全写入本地演示数据库。"""

from __future__ import annotations

from typing import Any

from sqlalchemy import Engine, MetaData, Table, delete

from app.demo_data.generator import TABLE_TIME_FIELDS, summarize_demo_rows


def validate_demo_database_name(database_name: str | None) -> str:
    name = str(database_name or "").strip()
    if not name.endswith("_demo_data"):
        raise ValueError("只允许写入名称以 _demo_data 结尾的演示数据库")
    return name


def replace_demo_rows(
    engine: Engine,
    rows: dict[str, list[dict[str, Any]]],
    *,
    batch_size: int = 1000,
) -> dict[str, Any]:
    validate_demo_database_name(engine.url.database)
    metadata = MetaData()
    tables = {
        name: Table(name, metadata, autoload_with=engine)
        for name in TABLE_TIME_FIELDS
    }
    with engine.begin() as connection:
        for name in TABLE_TIME_FIELDS:
            connection.execute(delete(tables[name]))
        for name, table_rows in rows.items():
            table = tables[name]
            for start in range(0, len(table_rows), batch_size):
                connection.execute(table.insert(), table_rows[start:start + batch_size])
    return summarize_demo_rows(rows)
