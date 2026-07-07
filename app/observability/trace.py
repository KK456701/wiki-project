from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text


def _now() -> str:
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat(sep=" ", timespec="seconds")


class TraceRecorder:
    def __init__(self, runtime_engine: Engine, jsonl_path: Path | None = None):
        self.runtime_engine = runtime_engine
        self.jsonl_path = jsonl_path or Path("runtime") / "trace_events.jsonl"

    def _write_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

    def start_trace(self, trace_id: str, session_id: str | None, hospital_id: str | None, user_query: str | None) -> None:
        now = _now()
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_agent_trace
                          (trace_id, session_id, hospital_id, user_query, final_status, started_at, created_at)
                        VALUES (:tid, :sid, :hid, :q, 'running', :now, :now)
                        """
                    ),
                    {"tid": trace_id, "sid": session_id, "hid": hospital_id, "q": user_query or "", "now": now},
                )
        finally:
            self._write_jsonl(
                {
                    "event": "trace_started",
                    "trace_id": trace_id,
                    "session_id": session_id,
                    "hospital_id": hospital_id,
                    "user_query": user_query,
                    "time": now,
                }
            )

    def record_node(
        self,
        trace_id: str,
        node_name: str,
        node_type: str,
        status: str,
        input_summary: str = "",
        output_summary: str = "",
        error_code: str = "",
        error_message: str = "",
        tool_name: str = "",
        db_source: str = "",
        sql_id: str = "",
        run_id: str = "",
        rule_id: str = "",
        duration_ms: int = 0,
    ) -> None:
        now = _now()
        node_id = f"NODE_{uuid.uuid4().hex[:12]}"
        payload = {
            "trace_id": trace_id,
            "node_id": node_id,
            "node_name": node_name,
            "node_type": node_type,
            "status": status,
            "input_summary": input_summary,
            "output_summary": output_summary,
            "error_code": error_code,
            "error_message": error_message,
            "tool_name": tool_name,
            "db_source": db_source,
            "sql_id": sql_id,
            "run_id": run_id,
            "rule_id": rule_id,
            "duration_ms": duration_ms,
            "now": now,
        }
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_agent_trace_node
                          (trace_id, node_id, node_name, node_type, status, input_summary, output_summary,
                           error_code, error_message, tool_name, db_source, sql_id, run_id, rule_id,
                           started_at, ended_at, duration_ms, created_at)
                        VALUES
                          (:trace_id, :node_id, :node_name, :node_type, :status, :input_summary, :output_summary,
                           :error_code, :error_message, :tool_name, :db_source, :sql_id, :run_id, :rule_id,
                           :now, :now, :duration_ms, :now)
                        """
                    ),
                    payload,
                )
        finally:
            event = dict(payload)
            event["event"] = "trace_node"
            event.pop("now", None)
            self._write_jsonl(event)

    def finish_trace(
        self,
        trace_id: str,
        final_status: str,
        final_answer_summary: str = "",
        intent: str = "",
        error_count: int = 0,
        fallback_count: int = 0,
    ) -> None:
        now = _now()
        try:
            with self.runtime_engine.begin() as conn:
                conn.execute(
                    text(
                        """
                        UPDATE med_agent_trace
                        SET final_status=:status, final_answer_summary=:answer, intent=:intent,
                            error_count=:errors, fallback_count=:fallbacks, ended_at=:now,
                            duration_ms=0
                        WHERE trace_id=:tid
                        """
                    ),
                    {
                        "tid": trace_id,
                        "status": final_status,
                        "answer": final_answer_summary[:2000],
                        "intent": intent,
                        "errors": error_count,
                        "fallbacks": fallback_count,
                        "now": now,
                    },
                )
        finally:
            self._write_jsonl(
                {
                    "event": "trace_finished",
                    "trace_id": trace_id,
                    "final_status": final_status,
                    "intent": intent,
                    "error_count": error_count,
                    "fallback_count": fallback_count,
                    "time": now,
                }
            )

    def get_trace(self, trace_id: str) -> dict[str, Any]:
        with self.runtime_engine.connect() as conn:
            trace = conn.execute(
                text("SELECT * FROM med_agent_trace WHERE trace_id=:tid"),
                {"tid": trace_id},
            ).mappings().first()
            nodes = conn.execute(
                text("SELECT * FROM med_agent_trace_node WHERE trace_id=:tid ORDER BY id"),
                {"tid": trace_id},
            ).mappings().all()
        if not trace:
            return {"trace_id": trace_id, "nodes": []}
        result = dict(trace)
        result["nodes"] = [dict(row) for row in nodes]
        return result
