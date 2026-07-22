"""运行库数据访问层。"""

from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, inspect, text


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _uid(prefix: str = "") -> str:
    return f"{prefix}{uuid.uuid4().hex[:12]}"


def _current_timestamp(engine: Engine) -> str:
    return "CURRENT_TIMESTAMP" if engine.dialect.name == "sqlite" else "NOW()"


def _normalize_datetime(value: datetime | str | None) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.isoformat(sep=" ", timespec="milliseconds")
    if isinstance(value, str):
        return value
    return str(value)


def _parse_datetime(value: datetime | str | None) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        normalized = value.replace("T", " ")
        try:
            return datetime.fromisoformat(normalized)
        except ValueError:
            return None
    return None


def log_sync_table(engine: Engine, hospital_id: str, db_name: str, table_name: str,
                   table_comment: str, table_type: str, batch_id: str) -> None:
    with engine.connect() as conn:
        if engine.dialect.name == "sqlite":
            conn.execute(
                text("DELETE FROM med_metadata_table WHERE hospital_id=:h AND db_name=:d AND table_name=:t"),
                {"h": hospital_id, "d": db_name, "t": table_name},
            )
            conn.execute(
                text("""INSERT INTO med_metadata_table (hospital_id, db_name, table_name, table_comment, table_type, sync_batch_id, sync_time)
                         VALUES (:h, :d, :t, :c, :ty, :b, CURRENT_TIMESTAMP)"""),
                {"h": hospital_id, "d": db_name, "t": table_name, "c": table_comment or "", "ty": table_type or "", "b": batch_id})
        else:
            conn.execute(
                text("""INSERT INTO med_metadata_table (hospital_id, db_name, table_name, table_comment, table_type, sync_batch_id, sync_time)
                         VALUES (:h, :d, :t, :c, :ty, :b, NOW())
                         ON DUPLICATE KEY UPDATE table_comment=VALUES(table_comment), table_type=VALUES(table_type), sync_batch_id=VALUES(sync_batch_id), sync_time=NOW()"""),
                {"h": hospital_id, "d": db_name, "t": table_name, "c": table_comment or "", "ty": table_type or "", "b": batch_id})
        conn.commit()


def log_sync_column(engine: Engine, hospital_id: str, db_name: str, table_name: str,
                    col_name: str, data_type: str, col_type: str, is_nullable: str,
                    col_key: str, col_default: str, col_comment: str, batch_id: str) -> None:
    with engine.connect() as conn:
        params = {"h": hospital_id, "d": db_name, "t": table_name, "cn": col_name, "dt": data_type or "", "ct": col_type or "",
                  "n": is_nullable or "", "k": col_key or "", "cd": str(col_default or ""), "cc": col_comment or "", "b": batch_id}
        if engine.dialect.name == "sqlite":
            conn.execute(
                text("DELETE FROM med_metadata_column WHERE hospital_id=:h AND db_name=:d AND table_name=:t AND column_name=:cn"),
                {"h": hospital_id, "d": db_name, "t": table_name, "cn": col_name},
            )
            conn.execute(
                text("""INSERT INTO med_metadata_column (hospital_id, db_name, table_name, column_name, data_type, column_type, is_nullable, column_key, column_default, column_comment, sync_batch_id, sync_time)
                         VALUES (:h, :d, :t, :cn, :dt, :ct, :n, :k, :cd, :cc, :b, CURRENT_TIMESTAMP)"""),
                params)
        else:
            conn.execute(
                text("""INSERT INTO med_metadata_column (hospital_id, db_name, table_name, column_name, data_type, column_type, is_nullable, column_key, column_default, column_comment, sync_batch_id, sync_time)
                         VALUES (:h, :d, :t, :cn, :dt, :ct, :n, :k, :cd, :cc, :b, NOW())
                         ON DUPLICATE KEY UPDATE data_type=VALUES(data_type), column_type=VALUES(column_type), is_nullable=VALUES(is_nullable), column_key=VALUES(column_key), column_default=VALUES(column_default), column_comment=VALUES(column_comment), sync_batch_id=VALUES(sync_batch_id), sync_time=NOW()"""),
                params)
        conn.commit()


def log_sync_change(engine: Engine, hospital_id: str, db_name: str, table_name: str,
                    field_name: str, change_type: str, change_desc: str, batch_id: str) -> None:
    with engine.connect() as conn:
        conn.execute(
            text("INSERT INTO med_metadata_sync_log (hospital_id, db_name, table_name, field_name, change_type, change_desc, sync_batch_id, sync_time) VALUES (:h, :d, :t, :f, :c, :cd, :b, CURRENT_TIMESTAMP)" if engine.dialect.name == "sqlite" else "INSERT INTO med_metadata_sync_log (hospital_id, db_name, table_name, field_name, change_type, change_desc, sync_batch_id, sync_time) VALUES (:h, :d, :t, :f, :c, :cd, :b, NOW())"),
            {"h": hospital_id, "d": db_name, "t": table_name or "", "f": field_name or "", "c": change_type, "cd": change_desc or "", "b": batch_id})
        conn.commit()


def insert_field_mapping(engine: Engine, hospital_id: str, rule_id: str, business_field: str,
                         db_name: str, table_name: str, column_name: str, data_type: str,
                         updated_by: str = "") -> None:
    with engine.connect() as conn:
        if engine.dialect.name == "sqlite":
            conn.execute(
                text("DELETE FROM med_field_mapping WHERE hospital_id=:h AND rule_id=:r AND business_field=:b"),
                {"h": hospital_id, "r": rule_id, "b": business_field},
            )
            conn.execute(
                text("""INSERT INTO med_field_mapping (hospital_id, rule_id, business_field, db_name, table_name, column_name, data_type, status, updated_by, updated_at)
                         VALUES (:h, :r, :b, :d, :t, :c, :dt, 'confirmed', :u, CURRENT_TIMESTAMP)"""),
                {"h": hospital_id, "r": rule_id, "b": business_field, "d": db_name, "t": table_name, "c": column_name, "dt": data_type or "", "u": updated_by},
            )
            conn.commit()
            return
        conn.execute(
            text("""INSERT INTO med_field_mapping (hospital_id, rule_id, business_field, db_name, table_name, column_name, data_type, status, updated_by, updated_at)
                     VALUES (:h, :r, :b, :d, :t, :c, :dt, 'confirmed', :u, NOW())
                     ON DUPLICATE KEY UPDATE db_name=VALUES(db_name), table_name=VALUES(table_name), column_name=VALUES(column_name), data_type=VALUES(data_type), updated_by=VALUES(updated_by), updated_at=NOW()"""),
            {"h": hospital_id, "r": rule_id, "b": business_field, "d": db_name, "t": table_name, "c": column_name, "dt": data_type or "", "u": updated_by})
        conn.commit()


def insert_generated_sql(engine: Engine, sql_id: str, hospital_id: str, rule_id: str,
                         dialect: str, sql_text: str, sql_status: str,
                         validation_message: str, generated_by: str) -> None:
    with engine.connect() as conn:
        now = _current_timestamp(engine)
        conn.execute(
            text(f"INSERT INTO med_generated_sql (sql_id, hospital_id, rule_id, dialect, sql_text, sql_status, validation_message, generated_by, generated_at) VALUES (:s, :h, :r, :d, :t, :st, :v, :b, {now})"),
            {"s": sql_id, "h": hospital_id, "r": rule_id, "d": dialect, "t": sql_text, "st": sql_status, "v": validation_message or "", "b": generated_by})
        conn.commit()


def insert_sql_run_log(engine: Engine, run_id: str, sql_id: str, hospital_id: str,
                       rule_id: str, stat_start: str, stat_end: str, run_status: str,
                       result_value: float | None, error_message: str, duration_ms: int,
                       run_by: str, *, numerator_count: int | None = None,
                       denominator_count: int | None = None,
                       run_context: dict[str, Any] | None = None) -> None:
    columns = {
        str(column["name"])
        for column in inspect(engine).get_columns("med_sql_run_log")
    }
    with engine.connect() as conn:
        now = _current_timestamp(engine)
        params = {"rid": run_id, "sid": sql_id, "h": hospital_id, "r": rule_id, "ss": stat_start, "se": stat_end, "rs": run_status, "rv": result_value, "e": error_message or "", "d": duration_ms, "b": run_by}
        if {"numerator_count", "denominator_count", "run_context_json"} <= columns:
            conn.execute(
                text(f"INSERT INTO med_sql_run_log (run_id, sql_id, hospital_id, rule_id, stat_start_time, stat_end_time, run_status, result_value, error_message, duration_ms, run_by, run_time, numerator_count, denominator_count, run_context_json) VALUES (:rid, :sid, :h, :r, :ss, :se, :rs, :rv, :e, :d, :b, {now}, :numerator_count, :denominator_count, :run_context_json)"),
                {
                    **params,
                    "numerator_count": numerator_count,
                    "denominator_count": denominator_count,
                    "run_context_json": json.dumps(run_context, ensure_ascii=False) if run_context else None,
                },
            )
        else:
            conn.execute(
                text(f"INSERT INTO med_sql_run_log (run_id, sql_id, hospital_id, rule_id, stat_start_time, stat_end_time, run_status, result_value, error_message, duration_ms, run_by, run_time) VALUES (:rid, :sid, :h, :r, :ss, :se, :rs, :rv, :e, :d, :b, {now})"),
                params,
            )
        conn.commit()


def start_trace_record(engine: Engine, trace_id: str, session_id: str | None,
                       hospital_id: str | None, user_query: str | None,
                       started_at: datetime | str | None = None,
                       user_id: str | None = None, intent: str | None = None) -> None:
    started_at_value = _normalize_datetime(started_at) or datetime.now().isoformat(sep=" ", timespec="milliseconds")
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_agent_trace
                  (trace_id, session_id, hospital_id, user_id, user_query, intent, final_status,
                   started_at, created_at)
                VALUES
                  (:trace_id, :session_id, :hospital_id, :user_id, :user_query, :intent, 'running',
                   :started_at, :created_at)
                """
            ),
            {
                "trace_id": trace_id,
                "session_id": session_id,
                "hospital_id": hospital_id,
                "user_id": user_id,
                "user_query": user_query or "",
                "intent": intent,
                "started_at": started_at_value,
                "created_at": started_at_value,
            },
        )


def insert_trace_node(engine: Engine, trace_id: str, node_id: str, node_name: str, node_type: str,
                      status: str, input_summary: str = "", output_summary: str = "",
                      error_code: str = "", error_message: str = "", tool_name: str = "",
                      db_source: str = "", sql_id: str = "", run_id: str = "", rule_id: str = "",
                      llm_model: str = "", started_at: datetime | str | None = None,
                      ended_at: datetime | str | None = None, duration_ms: int | None = None,
                      created_at: datetime | str | None = None,
                      parent_node_id: str = "", subtask_id: str = "", sequence: int | None = None,
                      started_offset_ms: int | None = None, exclusive_duration_ms: int | None = None,
                      capability: str = "", model_id: str = "", failure_class: str = "",
                      input_tokens: int | None = None, output_tokens: int | None = None,
                      cache_reused: bool = False, retry_count: int | None = None) -> None:
    started_at_value = _normalize_datetime(started_at) or datetime.now().isoformat(sep=" ", timespec="milliseconds")
    ended_at_value = _normalize_datetime(ended_at) or started_at_value
    created_at_value = _normalize_datetime(created_at) or started_at_value
    payload = {
                "trace_id": trace_id,
                "node_id": node_id,
                "node_name": node_name,
                "node_type": node_type,
                "status": status,
                "input_summary": input_summary or "",
                "output_summary": output_summary or "",
                "error_code": error_code or "",
                "error_message": error_message or "",
                "tool_name": tool_name or "",
                "db_source": db_source or "",
                "sql_id": sql_id or "",
                "run_id": run_id or "",
                "rule_id": rule_id or "",
                "llm_model": llm_model or "",
                "started_at": started_at_value,
                "ended_at": ended_at_value,
                "duration_ms": duration_ms if duration_ms is not None else 0,
                "created_at": created_at_value,
                "parent_node_id": parent_node_id or None,
                "subtask_id": subtask_id or None,
                "sequence": sequence,
                "started_offset_ms": started_offset_ms,
                "exclusive_duration_ms": exclusive_duration_ms,
                "capability": capability or None,
                "model_id": model_id or None,
                "failure_class": failure_class or None,
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cache_reused": 1 if cache_reused else 0,
                "retry_count": retry_count,
            }
    available = {column["name"] for column in inspect(engine).get_columns("med_agent_trace_node")}
    columns = [name for name in payload if name in available]
    statement = text(
        "INSERT INTO med_agent_trace_node ("
        + ", ".join(columns)
        + ") VALUES ("
        + ", ".join(f":{name}" for name in columns)
        + ")"
    )
    with engine.begin() as conn:
        conn.execute(statement, {name: payload[name] for name in columns})


def finish_trace_record(engine: Engine, trace_id: str, final_status: str,
                        final_answer_summary: str = "", intent: str = "",
                        error_count: int = 0, fallback_count: int = 0,
                        ended_at: datetime | str | None = None,
                        duration_ms: int | None = None) -> None:
    ended_at_value = _normalize_datetime(ended_at) or datetime.now().isoformat(sep=" ", timespec="milliseconds")
    if duration_ms is None:
        with engine.connect() as conn:
            started_row = conn.execute(
                text("SELECT started_at FROM med_agent_trace WHERE trace_id=:tid"),
                {"tid": trace_id},
            ).mappings().first()
        started_at_value = _parse_datetime(started_row["started_at"]) if started_row else None
        ended_at_dt = _parse_datetime(ended_at_value)
        if started_at_value is not None and ended_at_dt is not None:
            duration_ms = max(0, int((ended_at_dt - started_at_value).total_seconds() * 1000))
        else:
            duration_ms = 0
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                UPDATE med_agent_trace
                SET final_status=:final_status,
                    final_answer_summary=:final_answer_summary,
                    intent=:intent,
                    error_count=:error_count,
                    fallback_count=:fallback_count,
                    ended_at=:ended_at,
                    duration_ms=:duration_ms
                WHERE trace_id=:trace_id
                """
            ),
            {
                "trace_id": trace_id,
                "final_status": final_status,
                "final_answer_summary": final_answer_summary[:2000],
                "intent": intent,
                "error_count": error_count,
                "fallback_count": fallback_count,
                "ended_at": ended_at_value,
                "duration_ms": duration_ms,
            },
        )


def get_trace_record(engine: Engine, trace_id: str) -> dict[str, Any] | None:
    with engine.connect() as conn:
        trace = conn.execute(
            text("SELECT * FROM med_agent_trace WHERE trace_id=:tid"),
            {"tid": trace_id},
        ).mappings().first()
        if not trace:
            return None
        nodes = conn.execute(
            text("SELECT * FROM med_agent_trace_node WHERE trace_id=:tid ORDER BY id"),
            {"tid": trace_id},
        ).mappings().all()
    result = dict(trace)
    result["nodes"] = [dict(row) for row in nodes]
    return result


def list_trace_records(
    engine: Engine,
    *,
    hospital_id: str,
    started_after: str | None = None,
    started_before: str | None = None,
    status: str | None = None,
    model_id: str | None = None,
    tool_name: str | None = None,
    failure_class: str | None = None,
    limit: int = 100,
) -> list[dict[str, Any]]:
    clauses = ["t.hospital_id = :hospital_id"]
    params: dict[str, Any] = {"hospital_id": hospital_id, "limit": max(1, min(500, limit))}
    if started_after:
        clauses.append("t.started_at >= :started_after")
        params["started_after"] = started_after
    if started_before:
        clauses.append("t.started_at < :started_before")
        params["started_before"] = started_before
    if status:
        clauses.append("t.final_status = :status")
        params["status"] = status
    node_columns = {column["name"] for column in inspect(engine).get_columns("med_agent_trace_node")}
    node_filters = []
    if model_id:
        if "model_id" in node_columns:
            node_filters.append("n.model_id = :model_id")
            params["model_id"] = model_id
        elif "llm_model" in node_columns:
            node_filters.append("n.llm_model = :model_id")
            params["model_id"] = model_id
        else:
            node_filters.append("1 = 0")
    if tool_name:
        node_filters.append("n.tool_name = :tool_name")
        params["tool_name"] = tool_name
    if failure_class:
        if "failure_class" in node_columns:
            node_filters.append("n.failure_class = :failure_class")
            params["failure_class"] = failure_class
        else:
            node_filters.append("1 = 0")
    if node_filters:
        clauses.append(
            "EXISTS (SELECT 1 FROM med_agent_trace_node n WHERE n.trace_id=t.trace_id AND "
            + " AND ".join(node_filters)
            + ")"
        )
    query = text(
        "SELECT t.trace_id, t.session_id, t.hospital_id, t.intent, "
        "t.final_status, t.error_count, t.fallback_count, t.started_at, t.ended_at, t.duration_ms "
        "FROM med_agent_trace t WHERE "
        + " AND ".join(clauses)
        + " ORDER BY t.started_at DESC LIMIT :limit"
    )
    with engine.connect() as connection:
        return [dict(row) for row in connection.execute(query, params).mappings().all()]


def trace_nodes_for_records(engine: Engine, trace_ids: list[str]) -> list[dict[str, Any]]:
    if not trace_ids:
        return []
    params = {f"trace_{index}": value for index, value in enumerate(trace_ids)}
    placeholders = ", ".join(f":trace_{index}" for index in range(len(trace_ids)))
    with engine.connect() as connection:
        rows = connection.execute(
            text(f"SELECT * FROM med_agent_trace_node WHERE trace_id IN ({placeholders})"),
            params,
        ).mappings().all()
    return [dict(row) for row in rows]


def prune_trace_records(engine: Engine, *, before: str) -> int:
    """Delete expired Trace rows and their nodes from the existing runtime DB."""
    with engine.begin() as connection:
        connection.execute(
            text(
                "DELETE FROM med_agent_trace_node WHERE trace_id IN "
                "(SELECT trace_id FROM med_agent_trace WHERE started_at < :before)"
            ),
            {"before": before},
        )
        result = connection.execute(
            text("DELETE FROM med_agent_trace WHERE started_at < :before"),
            {"before": before},
        )
    return max(0, int(result.rowcount or 0))


def ensure_recovery_task_table(engine: Engine) -> None:
    if engine.dialect.name == "sqlite":
        ddl = """
        CREATE TABLE IF NOT EXISTS med_recovery_task (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          task_id TEXT NOT NULL UNIQUE,
          task_type TEXT NOT NULL,
          task_name TEXT NOT NULL,
          status TEXT NOT NULL,
          current_step TEXT,
          trace_id TEXT,
          request_id TEXT,
          hospital_id TEXT,
          rule_id TEXT,
          payload_json TEXT,
          result_json TEXT,
          error_message TEXT,
          retry_count INTEGER DEFAULT 0,
          recoverable_action TEXT,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          completed_at TEXT
        )
        """
    else:
        ddl = """
        CREATE TABLE IF NOT EXISTS med_recovery_task (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          task_id VARCHAR(64) NOT NULL UNIQUE,
          task_type VARCHAR(64) NOT NULL,
          task_name VARCHAR(255) NOT NULL,
          status VARCHAR(32) NOT NULL,
          current_step VARCHAR(128),
          trace_id VARCHAR(64),
          request_id VARCHAR(64),
          hospital_id VARCHAR(64),
          rule_id VARCHAR(64),
          payload_json TEXT,
          result_json TEXT,
          error_message TEXT,
          retry_count INT DEFAULT 0,
          recoverable_action VARCHAR(64),
          created_at DATETIME NOT NULL,
          updated_at DATETIME NOT NULL,
          completed_at DATETIME,
          INDEX idx_recovery_status (status),
          INDEX idx_recovery_type (task_type),
          INDEX idx_recovery_trace (trace_id)
        ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        """
    with engine.begin() as conn:
        conn.execute(text(ddl))


def _json_text(value: Any) -> str:
    return json.dumps(value or {}, ensure_ascii=False, default=str)


def _parse_json_text(value: Any) -> Any:
    if not value:
        return {}
    try:
        return json.loads(str(value))
    except json.JSONDecodeError:
        return {}


def create_recovery_task(
    engine: Engine,
    task_type: str,
    task_name: str,
    current_step: str,
    payload: dict[str, Any] | None = None,
    trace_id: str = "",
    request_id: str = "",
    hospital_id: str = "",
    rule_id: str = "",
    recoverable_action: str = "retry",
) -> str:
    ensure_recovery_task_table(engine)
    task_id = _uid("RT_")
    now = _now()
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_recovery_task
                  (task_id, task_type, task_name, status, current_step, trace_id, request_id,
                   hospital_id, rule_id, payload_json, result_json, error_message,
                   retry_count, recoverable_action, created_at, updated_at, completed_at)
                VALUES
                  (:task_id, :task_type, :task_name, 'running', :current_step, :trace_id,
                   :request_id, :hospital_id, :rule_id, :payload_json, '', '', 0,
                   :recoverable_action, :created_at, :updated_at, NULL)
                """
            ),
            {
                "task_id": task_id,
                "task_type": task_type,
                "task_name": task_name,
                "current_step": current_step,
                "trace_id": trace_id or "",
                "request_id": request_id or "",
                "hospital_id": hospital_id or "",
                "rule_id": rule_id or "",
                "payload_json": _json_text(payload),
                "recoverable_action": recoverable_action or "",
                "created_at": now,
                "updated_at": now,
            },
        )
    return task_id


def update_recovery_task(
    engine: Engine,
    task_id: str,
    status: str | None = None,
    current_step: str | None = None,
    result: dict[str, Any] | None = None,
    error_message: str | None = None,
    increment_retry: bool = False,
) -> None:
    ensure_recovery_task_table(engine)
    updates = ["updated_at=:updated_at"]
    params: dict[str, Any] = {"task_id": task_id, "updated_at": _now()}
    if status is not None:
        updates.append("status=:status")
        params["status"] = status
        if status == "completed":
            updates.append("completed_at=:completed_at")
            params["completed_at"] = params["updated_at"]
    if current_step is not None:
        updates.append("current_step=:current_step")
        params["current_step"] = current_step
    if result is not None:
        updates.append("result_json=:result_json")
        params["result_json"] = _json_text(result)
    if error_message is not None:
        updates.append("error_message=:error_message")
        params["error_message"] = error_message
    if increment_retry:
        updates.append("retry_count=COALESCE(retry_count, 0) + 1")
    with engine.begin() as conn:
        conn.execute(
            text(f"UPDATE med_recovery_task SET {', '.join(updates)} WHERE task_id=:task_id"),
            params,
        )


def complete_recovery_task(engine: Engine, task_id: str, result: dict[str, Any] | None = None) -> None:
    update_recovery_task(engine, task_id, status="completed", result=result or {})


def fail_recovery_task(engine: Engine, task_id: str, error_message: str, status: str = "failed_retryable") -> None:
    update_recovery_task(engine, task_id, status=status, error_message=error_message)


def ignore_recovery_task(engine: Engine, task_id: str) -> dict[str, Any] | None:
    update_recovery_task(engine, task_id, status="ignored")
    return get_recovery_task(engine, task_id)


def mark_running_recovery_tasks_interrupted(engine: Engine) -> int:
    ensure_recovery_task_table(engine)
    with engine.begin() as conn:
        result = conn.execute(
            text(
                """
                UPDATE med_recovery_task
                SET status='interrupted',
                    error_message=CASE WHEN error_message='' OR error_message IS NULL THEN '服务上次中断，任务未正常收尾。' ELSE error_message END,
                    updated_at=:updated_at
                WHERE status='running'
                """
            ),
            {"updated_at": _now()},
        )
        return int(result.rowcount or 0)


def get_recovery_task(engine: Engine, task_id: str) -> dict[str, Any] | None:
    ensure_recovery_task_table(engine)
    with engine.connect() as conn:
        row = conn.execute(
            text("SELECT * FROM med_recovery_task WHERE task_id=:task_id"),
            {"task_id": task_id},
        ).mappings().first()
    return _recovery_row_to_dict(row) if row else None


def list_recovery_tasks(engine: Engine, include_completed: bool = False) -> list[dict[str, Any]]:
    ensure_recovery_task_table(engine)
    where = "" if include_completed else "WHERE status NOT IN ('completed', 'ignored')"
    with engine.connect() as conn:
        rows = conn.execute(
            text(f"SELECT * FROM med_recovery_task {where} ORDER BY id DESC")
        ).mappings().all()
    return [_recovery_row_to_dict(row) for row in rows]


def _recovery_row_to_dict(row: Any) -> dict[str, Any]:
    result = dict(row)
    result["payload"] = _parse_json_text(result.pop("payload_json", ""))
    result["result"] = _parse_json_text(result.pop("result_json", ""))
    return result


def insert_diagnose_report(engine: Engine, report_id: str, hospital_id: str, rule_id: str,
                           diagnose_type: str, problem_detail: str, repair_suggest: str,
                           repair_sql: str, trigger_type: str = "manual",
                           related_sql_id: str | None = None,
                           layer_results: Any | None = None,
                           diagnose_status: str = "healthy",
                           stat_period: str | None = None) -> None:
    params = {
        "rid": report_id,
        "h": hospital_id,
        "r": rule_id,
        "dt": diagnose_type,
        "p": problem_detail or "",
        "rs": repair_suggest or "",
        "sql": repair_sql or "",
        "tr": trigger_type or "manual",
        "sid": related_sql_id or "",
        "layers": json.dumps(layer_results or [], ensure_ascii=False),
        "ds": diagnose_status or "healthy",
        "sp": stat_period or "",
    }
    with engine.connect() as conn:
        try:
            conn.execute(
                text("""INSERT INTO med_index_diagnose_report
                         (report_id, hospital_id, rule_id, diagnose_type, problem_detail, repair_suggest, repair_sql,
                          diagnose_time, status, trigger_type, related_sql_id, layer_results, diagnose_status, stat_period)
                         VALUES (:rid, :h, :r, :dt, :p, :rs, :sql, CURRENT_TIMESTAMP, 0, :tr, :sid, :layers, :ds, :sp)"""),
                params,
            )
        except Exception:
            conn.rollback()
            conn.execute(
                text("""INSERT INTO med_index_diagnose_report
                         (report_id, hospital_id, rule_id, diagnose_type, problem_detail, repair_suggest, repair_sql, diagnose_time, status)
                         VALUES (:rid, :h, :r, :dt, :p, :rs, :sql, CURRENT_TIMESTAMP, 0)"""),
                params,
            )
        conn.commit()


def insert_run_result(engine: Engine, hospital_id: str, rule_id: str, stat_period: str,
                      result_value: float, run_id: str, previous_value: float | None = None) -> None:
    change_rate = None
    if previous_value is not None and previous_value != 0:
        change_rate = round((result_value - previous_value) / previous_value * 100, 2)
    with engine.connect() as conn:
        now = _current_timestamp(engine)
        conn.execute(
            text(f"INSERT INTO med_index_run_result (hospital_id, rule_id, stat_period, result_value, previous_value, change_rate, run_id, created_at) VALUES (:h, :r, :sp, :rv, :pv, :cr, :rid, {now})"),
            {"h": hospital_id, "r": rule_id, "sp": stat_period, "rv": result_value, "pv": previous_value, "cr": change_rate, "rid": run_id})
        conn.commit()
