"""运行库数据访问层。"""

from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import Engine, text


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _uid(prefix: str = "") -> str:
    return f"{prefix}{uuid.uuid4().hex[:12]}"


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
        conn.execute(
            text("INSERT INTO med_generated_sql (sql_id, hospital_id, rule_id, dialect, sql_text, sql_status, validation_message, generated_by, generated_at) VALUES (:s, :h, :r, :d, :t, :st, :v, :b, NOW())"),
            {"s": sql_id, "h": hospital_id, "r": rule_id, "d": dialect, "t": sql_text, "st": sql_status, "v": validation_message or "", "b": generated_by})
        conn.commit()


def insert_sql_run_log(engine: Engine, run_id: str, sql_id: str, hospital_id: str,
                       rule_id: str, stat_start: str, stat_end: str, run_status: str,
                       result_value: float | None, error_message: str, duration_ms: int,
                       run_by: str) -> None:
    with engine.connect() as conn:
        conn.execute(
            text("INSERT INTO med_sql_run_log (run_id, sql_id, hospital_id, rule_id, stat_start_time, stat_end_time, run_status, result_value, error_message, duration_ms, run_by, run_time) VALUES (:rid, :sid, :h, :r, :ss, :se, :rs, :rv, :e, :d, :b, NOW())"),
            {"rid": run_id, "sid": sql_id, "h": hospital_id, "r": rule_id, "ss": stat_start, "se": stat_end, "rs": run_status, "rv": result_value, "e": error_message or "", "d": duration_ms, "b": run_by})
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
                      created_at: datetime | str | None = None) -> None:
    started_at_value = _normalize_datetime(started_at) or datetime.now().isoformat(sep=" ", timespec="milliseconds")
    ended_at_value = _normalize_datetime(ended_at) or started_at_value
    created_at_value = _normalize_datetime(created_at) or started_at_value
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_agent_trace_node
                  (trace_id, node_id, node_name, node_type, status, input_summary, output_summary,
                   error_code, error_message, tool_name, db_source, sql_id, run_id, rule_id,
                   llm_model, started_at, ended_at, duration_ms, created_at)
                VALUES
                  (:trace_id, :node_id, :node_name, :node_type, :status, :input_summary,
                   :output_summary, :error_code, :error_message, :tool_name, :db_source, :sql_id,
                   :run_id, :rule_id, :llm_model, :started_at, :ended_at, :duration_ms, :created_at)
                """
            ),
            {
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
            },
        )


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
        conn.execute(
            text("INSERT INTO med_index_run_result (hospital_id, rule_id, stat_period, result_value, previous_value, change_rate, run_id, created_at) VALUES (:h, :r, :sp, :rv, :pv, :cr, :rid, NOW())"),
            {"h": hospital_id, "r": rule_id, "sp": stat_period, "rv": result_value, "pv": previous_value, "cr": change_rate, "rid": run_id})
        conn.commit()
