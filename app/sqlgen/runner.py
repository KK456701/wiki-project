"""SQL 只读试运行器。"""

from __future__ import annotations

import re
import time
import uuid
from typing import Any

from sqlalchemy import Engine

from app.config import get_bool
from app.db.repositories import insert_sql_run_log
from app.db_access.business_db import BusinessDBClient


ALLOW_TRIAL_RUN = get_bool("allow_sql_trial_run", True)


def _sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    escaped = str(value).replace("'", "''")
    return f"'{escaped}'"


def _bind_sql_params(sql_text: str, params: dict[str, Any]) -> str:
    def replace(match: re.Match[str]) -> str:
        name = match.group(1)
        if name not in params:
            raise ValueError(f"SQL 参数缺失: {name}")
        return _sql_literal(params[name])

    return re.sub(r":([A-Za-z_][A-Za-z0-9_]*)", replace, sql_text)


def run_sql_trial(
    runtime_engine: Engine,
    business_db: BusinessDBClient,
    sql_id: str,
    sql_text: str,
    hospital_id: str,
    rule_id: str,
    stat_start: str,
    stat_end: str,
    params: dict[str, Any] | None = None,
    run_by: str = "agent",
) -> dict[str, Any]:
    if not ALLOW_TRIAL_RUN:
        return {"status": "skipped", "message": "试运行已关闭"}

    run_id = f"RUN_{uuid.uuid4().hex[:12]}"
    start = time.time()
    result_value: float | None = None
    error_message: str | None = None
    run_status = "success"

    try:
        bound_params = {
            "hospital_id": hospital_id,
            "start_time": stat_start,
            "end_time": stat_end,
            **(params or {}),
        }
        executable_sql = _bind_sql_params(sql_text, bound_params)
        query_result = business_db.execute_select(executable_sql)
        first_row = query_result.rows[0] if query_result.rows else {}
        first_value = next(iter(first_row.values()), None) if first_row else None
        result_value = float(first_value) if first_value is not None else None
        run_status = "success" if result_value is not None else "empty"
    except Exception as exc:
        run_status = "failed"
        error_message = str(exc)
        result_value = None

    duration_ms = int((time.time() - start) * 1000)

    insert_sql_run_log(
        runtime_engine,
        run_id,
        sql_id,
        hospital_id,
        rule_id,
        stat_start,
        stat_end,
        run_status,
        result_value,
        error_message or "",
        duration_ms,
        run_by,
    )

    return {
        "run_id": run_id,
        "sql_id": sql_id,
        "status": run_status,
        "result_value": result_value,
        "error_message": error_message,
        "duration_ms": duration_ms,
    }
