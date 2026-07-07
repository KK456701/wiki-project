from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from sqlalchemy import Engine

from app.db.repositories import (
    finish_trace_record,
    get_trace_record,
    insert_trace_node,
    start_trace_record,
)


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _format_time(value: datetime) -> str:
    return value.isoformat(sep=" ", timespec="milliseconds")


def _parse_time(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("T", " "))
        except ValueError:
            return None
    return None


class TraceRecorder:
    def __init__(self, runtime_engine: Engine, jsonl_path: Path | None = None):
        self.runtime_engine = runtime_engine
        self.jsonl_path = jsonl_path or Path("runtime") / "trace_events.jsonl"
        self._started_at: dict[str, datetime] = {}

    def _write_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

    def start_trace(self, trace_id: str, session_id: str | None, hospital_id: str | None, user_query: str | None) -> None:
        started_at = _now()
        self._started_at[trace_id] = started_at
        try:
            start_trace_record(
                self.runtime_engine,
                trace_id=trace_id,
                session_id=session_id,
                hospital_id=hospital_id,
                user_query=user_query,
                started_at=started_at,
            )
        finally:
            self._write_jsonl(
                {
                    "event": "trace_started",
                    "trace_id": trace_id,
                    "session_id": session_id,
                    "hospital_id": hospital_id,
                    "user_query": user_query,
                    "started_at": _format_time(started_at),
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
        started_at = _now()
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
            "started_at": _format_time(started_at),
        }
        try:
            insert_trace_node(
                self.runtime_engine,
                trace_id=trace_id,
                node_id=node_id,
                node_name=node_name,
                node_type=node_type,
                status=status,
                input_summary=input_summary,
                output_summary=output_summary,
                error_code=error_code,
                error_message=error_message,
                tool_name=tool_name,
                db_source=db_source,
                sql_id=sql_id,
                run_id=run_id,
                rule_id=rule_id,
                started_at=started_at,
                ended_at=started_at,
                duration_ms=duration_ms,
                created_at=started_at,
            )
        finally:
            event = dict(payload)
            event["event"] = "trace_node"
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
        ended_at = _now()
        started_at = self._started_at.get(trace_id)
        if started_at is None:
            trace = get_trace_record(self.runtime_engine, trace_id)
            if trace is not None:
                started_at = _parse_time(trace.get("started_at"))
        duration_ms = 0
        if started_at is not None:
            duration_ms = max(0, int((ended_at - started_at).total_seconds() * 1000))
        try:
            finish_trace_record(
                self.runtime_engine,
                trace_id=trace_id,
                final_status=final_status,
                final_answer_summary=final_answer_summary,
                intent=intent,
                error_count=error_count,
                fallback_count=fallback_count,
                ended_at=ended_at,
                duration_ms=duration_ms,
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
                    "ended_at": _format_time(ended_at),
                    "duration_ms": duration_ms,
                }
            )

    def get_trace(self, trace_id: str) -> dict[str, Any]:
        trace = get_trace_record(self.runtime_engine, trace_id)
        if not trace:
            return {"trace_id": trace_id, "nodes": []}
        return trace
