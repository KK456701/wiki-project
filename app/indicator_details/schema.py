from __future__ import annotations

from sqlalchemy import Engine, inspect, text


SQL_RUN_COLUMNS = {
    "numerator_count": "BIGINT NULL",
    "denominator_count": "BIGINT NULL",
    "run_context_json": "JSON NULL",
}


def ensure_indicator_detail_schema(engine: Engine) -> dict[str, list[str]]:
    inspector = inspect(engine)
    if not inspector.has_table("med_sql_run_log"):
        return {"added_run_columns": []}
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
    return {"added_run_columns": added}
