from __future__ import annotations

import importlib
import importlib.util

from sqlalchemy import create_engine, inspect, text


def test_sql_run_detail_columns_are_added_idempotently() -> None:
    assert importlib.util.find_spec("app.indicator_details.schema") is not None, (
        "指标明细运行快照迁移尚未实现"
    )
    schema = importlib.import_module("app.indicator_details.schema")
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_sql_run_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL UNIQUE,
                  sql_id TEXT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  run_status TEXT NOT NULL,
                  result_value REAL,
                  run_time TEXT NOT NULL
                )
                """
            )
        )

    first = schema.ensure_indicator_detail_schema(engine)
    second = schema.ensure_indicator_detail_schema(engine)
    columns = {item["name"] for item in inspect(engine).get_columns("med_sql_run_log")}

    assert first["added_run_columns"] == [
        "numerator_count",
        "denominator_count",
        "run_context_json",
    ]
    assert second["added_run_columns"] == []
    assert {"numerator_count", "denominator_count", "run_context_json"} <= columns
