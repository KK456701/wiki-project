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
from app.workflows.manifest import annotate_trace_node, default_failure_code_for_node


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
        self._workflow_ids: dict[str, str] = {}

    def _write_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

    def start_trace(
        self,
        trace_id: str,
        session_id: str | None,
        hospital_id: str | None,
        user_query: str | None,
        workflow_id: str = "core_indicator_chat",
    ) -> None:
        started_at = _now()
        self._started_at[trace_id] = started_at
        self._workflow_ids[trace_id] = workflow_id
        try:
            start_trace_record(
                self.runtime_engine,
                trace_id=trace_id,
                session_id=session_id,
                hospital_id=hospital_id,
                user_query=user_query,
                started_at=started_at,
            )
        except Exception:
            pass
        finally:
            self._write_jsonl(
                {
                    "event": "trace_started",
                    "trace_id": trace_id,
                    "session_id": session_id,
                    "hospital_id": hospital_id,
                    "user_query": user_query,
                    "workflow_id": workflow_id,
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
        input_data: dict[str, Any] | None = None,
        output_data: dict[str, Any] | None = None,
        processing_data: dict[str, Any] | None = None,
        config_data: dict[str, Any] | None = None,
    ) -> None:
        started_at = _now()
        node_id = f"NODE_{uuid.uuid4().hex[:12]}"
        if not error_code and status in {"failed", "error"}:
            error_code = default_failure_code_for_node(
                node_name,
                self._workflow_ids.get(trace_id, "core_indicator_chat"),
            )
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
            "input_data": input_data or {},
            "output_data": output_data or {},
            "processing_data": processing_data or {},
            "config_data": config_data or {},
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
        except Exception:
            pass
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
            try:
                trace = get_trace_record(self.runtime_engine, trace_id)
                if trace is not None:
                    started_at = _parse_time(trace.get("started_at"))
            except Exception:
                trace = self._get_trace_from_jsonl(trace_id)
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
        except Exception:
            pass
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
        try:
            trace = get_trace_record(self.runtime_engine, trace_id)
        except Exception:
            trace = None
        if not trace:
            return self._annotate_trace(self._get_trace_from_jsonl(trace_id))
        trace["trace_storage"] = "runtime_db"
        self._merge_jsonl_node_payloads(trace)
        return self._annotate_trace(trace)

    def _annotate_trace(self, trace: dict[str, Any]) -> dict[str, Any]:
        trace_id = str(trace.get("trace_id") or "")
        workflow_id = str(
            trace.get("workflow_id")
            or self._workflow_ids.get(trace_id)
            or "core_indicator_chat"
        )
        trace["workflow_id"] = workflow_id
        trace["nodes"] = [
            annotate_trace_node(dict(node), workflow_id)
            for node in trace.get("nodes", [])
        ]
        return trace

    def _merge_jsonl_node_payloads(self, trace: dict[str, Any]) -> None:
        jsonl_trace = self._get_trace_from_jsonl(str(trace.get("trace_id") or ""))
        if jsonl_trace.get("workflow_id"):
            trace["workflow_id"] = jsonl_trace["workflow_id"]
        payloads = {
            node.get("node_id"): node
            for node in jsonl_trace.get("nodes", [])
            if node.get("node_id")
        }
        for node in trace.get("nodes", []):
            payload = payloads.get(node.get("node_id"))
            if not payload:
                continue
            for key in ("input_data", "output_data", "processing_data", "config_data"):
                if key in payload:
                    node[key] = payload[key]

    def _get_trace_from_jsonl(self, trace_id: str) -> dict[str, Any]:
        if not self.jsonl_path.exists():
            return {"trace_id": trace_id, "nodes": [], "trace_storage": "none"}

        started: dict[str, Any] | None = None
        finished: dict[str, Any] | None = None
        nodes: list[dict[str, Any]] = []
        for line in self.jsonl_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("trace_id") != trace_id:
                continue
            event_type = event.get("event")
            if event_type == "trace_started":
                started = event
            elif event_type == "trace_node":
                nodes.append({k: v for k, v in event.items() if k != "event"})
            elif event_type == "trace_finished":
                finished = event

        result: dict[str, Any] = {
            "trace_id": trace_id,
            "nodes": nodes,
            "trace_storage": "jsonl" if started or nodes or finished else "none",
        }
        if started:
            result.update(
                {
                    "session_id": started.get("session_id"),
                    "hospital_id": started.get("hospital_id"),
                    "user_query": started.get("user_query"),
                    "workflow_id": started.get("workflow_id")
                    or "core_indicator_chat",
                    "started_at": started.get("started_at"),
                }
            )
        if finished:
            result.update(
                {
                    "final_status": finished.get("final_status"),
                    "intent": finished.get("intent"),
                    "error_count": finished.get("error_count", 0),
                    "fallback_count": finished.get("fallback_count", 0),
                    "ended_at": finished.get("ended_at"),
                    "duration_ms": finished.get("duration_ms", 0),
                }
            )
        return result
