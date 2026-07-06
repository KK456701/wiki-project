"""SQL 只读试运行器。"""

import time
import uuid
from typing import Any

from sqlalchemy import Engine, text
from app.config import get_bool, get_int
from app.db.repositories import insert_sql_run_log


ALLOW_TRIAL_RUN = get_bool("allow_sql_trial_run", True)
SQL_RUN_TIMEOUT_SECONDS = get_int("sql_run_timeout_seconds", 10)


def run_sql_trial(runtime_engine: Engine, business_engine: Engine,
                   sql_id: str, sql_text: str, hospital_id: str, rule_id: str,
                   stat_start: str, stat_end: str, params: dict, run_by: str) -> dict[str, Any]:
    if not ALLOW_TRIAL_RUN:
        return {"status": "skipped", "message": "试运行已关闭"}

    run_id = f"RUN_{uuid.uuid4().hex[:12]}"
    start = time.time()
    result_value: float | None = None
    error_message: str | None = None
    run_status = "success"

    try:
        with business_engine.connect() as conn:
            if SQL_RUN_TIMEOUT_SECONDS > 0 and "mysql" in business_engine.dialect.name.lower():
                timeout_ms = max(1, int(SQL_RUN_TIMEOUT_SECONDS * 1000))
                try:
                    conn.execute(text(f"SET SESSION MAX_EXECUTION_TIME={timeout_ms}"))
                except Exception:
                    # Some MySQL-compatible databases do not support MAX_EXECUTION_TIME.
                    pass
            stmt = text(sql_text)
            bound_params = {"hospital_id": hospital_id, "start_time": stat_start, "end_time": stat_end, **params}
            row = conn.execute(stmt, bound_params).fetchone()
            if row:
                result_value = float(row[0]) if row[0] is not None else None
    except Exception as exc:
        run_status = "failed"
        error_message = str(exc)
        result_value = None

    duration_ms = int((time.time() - start) * 1000)

    insert_sql_run_log(runtime_engine, run_id, sql_id, hospital_id, rule_id,
                        stat_start, stat_end, run_status, result_value, error_message or "",
                        duration_ms, run_by)

    return {
        "run_id": run_id, "sql_id": sql_id, "status": run_status,
        "result_value": result_value, "error_message": error_message,
        "duration_ms": duration_ms,
    }
