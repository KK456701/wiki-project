from __future__ import annotations

import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from sqlalchemy import Engine

from app.agent_planning.failures import classify_failure
from app.db.repositories import (
    finish_trace_record,
    get_trace_record,
    insert_trace_node,
    list_trace_records,
    prune_trace_records,
    start_trace_record,
    trace_nodes_for_records,
)
from app.observability.schema import ensure_trace_enhancement_schema
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
    _last_pruned_at: datetime | None = None

    def __init__(
        self,
        runtime_engine: Engine,
        jsonl_path: Path | None = None,
        *,
        retention_days: int = 30,
    ):
        self.runtime_engine = runtime_engine
        self.jsonl_path = jsonl_path or Path("runtime") / "trace_events.jsonl"
        self._started_at: dict[str, datetime] = {}
        self._workflow_ids: dict[str, str] = {}
        self._sequences: dict[str, int] = {}
        try:
            ensure_trace_enhancement_schema(runtime_engine)
        except Exception:
            pass
        self._prune_if_due(max(1, int(retention_days)))

    def _prune_if_due(self, retention_days: int) -> None:
        now = _now()
        last_pruned = type(self)._last_pruned_at
        if last_pruned is not None and now - last_pruned < timedelta(hours=1):
            return
        try:
            cutoff = now - timedelta(days=retention_days)
            prune_trace_records(
                self.runtime_engine,
                before=_format_time(cutoff),
            )
            type(self)._last_pruned_at = now
        except Exception:
            # Trace 清理失败不能影响业务请求；下一次仍会重试。
            pass

    def _write_jsonl(self, event: dict[str, Any]) -> None:
        self.jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with self.jsonl_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")

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
            self._write_jsonl({
                "event": "trace_started",
                "trace_id": trace_id,
                "session_id": session_id,
                "hospital_id": hospital_id,
                "user_query": user_query,
                "workflow_id": workflow_id,
                "started_at": _format_time(started_at),
            })

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
        exclusive_duration_ms: int | None = None,
        input_data: dict[str, Any] | None = None,
        output_data: dict[str, Any] | None = None,
        processing_data: dict[str, Any] | None = None,
        config_data: dict[str, Any] | None = None,
        parent_node_id: str = "",
        subtask_id: str = "",
        capability: str = "",
        model_id: str = "",
        failure_class: str = "",
        input_tokens: int | None = None,
        output_tokens: int | None = None,
        cache_reused: bool = False,
        retry_count: int = 0,
    ) -> str:
        ended_at = _now()
        duration_ms = max(0, int(duration_ms or 0))
        started_at = ended_at - timedelta(milliseconds=duration_ms)
        node_id = f"NODE_{uuid.uuid4().hex[:12]}"
        sequence = self._sequences.get(trace_id, 0) + 1
        self._sequences[trace_id] = sequence
        trace_started = self._started_at.get(trace_id)
        started_offset_ms = (
            max(0, int((started_at - trace_started).total_seconds() * 1000))
            if trace_started is not None
            else 0
        )
        if not error_code and status in {"failed", "error"}:
            error_code = default_failure_code_for_node(
                node_name,
                self._workflow_ids.get(trace_id, "core_indicator_chat"),
            )
        if not failure_class and error_code:
            value = classify_failure(error_code).value
            failure_class = "" if value == "unknown" else value
        payload = {
            "trace_id": trace_id,
            "node_id": node_id,
            "parent_node_id": parent_node_id or None,
            "subtask_id": subtask_id or "root",
            "sequence": sequence,
            "started_offset_ms": started_offset_ms,
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
            "exclusive_duration_ms": (
                duration_ms
                if exclusive_duration_ms is None
                else max(0, int(exclusive_duration_ms))
            ),
            "capability": capability or None,
            "model_id": model_id or None,
            "failure_class": failure_class or None,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "cache_reused": bool(cache_reused),
            "retry_count": max(0, int(retry_count or 0)),
            "started_at": _format_time(started_at),
            "ended_at": _format_time(ended_at),
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
                llm_model=model_id,
                started_at=started_at,
                ended_at=ended_at,
                duration_ms=duration_ms,
                created_at=started_at,
                parent_node_id=parent_node_id,
                subtask_id=subtask_id or "root",
                sequence=sequence,
                started_offset_ms=started_offset_ms,
                exclusive_duration_ms=(
                    duration_ms
                    if exclusive_duration_ms is None
                    else max(0, int(exclusive_duration_ms))
                ),
                capability=capability,
                model_id=model_id,
                failure_class=failure_class,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cache_reused=cache_reused,
                retry_count=retry_count,
            )
        except Exception:
            pass
        finally:
            self._write_jsonl({"event": "trace_node", **payload})
        return node_id

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
                started_at = _parse_time(self._get_trace_from_jsonl(trace_id).get("started_at"))
        duration_ms = max(0, int((ended_at - started_at).total_seconds() * 1000)) if started_at else 0
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
            self._write_jsonl({
                "event": "trace_finished",
                "trace_id": trace_id,
                "final_status": final_status,
                "intent": intent,
                "error_count": error_count,
                "fallback_count": fallback_count,
                "ended_at": _format_time(ended_at),
                "duration_ms": duration_ms,
            })

    def get_trace(self, trace_id: str) -> dict[str, Any]:
        try:
            trace = get_trace_record(self.runtime_engine, trace_id)
        except Exception:
            trace = None
        if not trace:
            return self._enhance_trace(self._annotate_trace(self._get_trace_from_jsonl(trace_id)))
        trace["trace_storage"] = "runtime_db"
        self._merge_jsonl_node_payloads(trace)
        return self._enhance_trace(self._annotate_trace(trace))

    def _annotate_trace(self, trace: dict[str, Any]) -> dict[str, Any]:
        trace_id = str(trace.get("trace_id") or "")
        workflow_id = str(trace.get("workflow_id") or self._workflow_ids.get(trace_id) or "core_indicator_chat")
        trace["workflow_id"] = workflow_id
        trace["nodes"] = [annotate_trace_node(dict(node), workflow_id) for node in trace.get("nodes", [])]
        return trace

    def _enhance_trace(self, trace: dict[str, Any]) -> dict[str, Any]:
        nodes = list(trace.get("nodes") or [])
        trace_started = _parse_time(trace.get("started_at"))
        previous_by_lane: dict[str, str] = {}
        totals = {"llm": 0, "tool": 0, "code": 0, "storage": 0}
        versions: dict[str, set[str]] = {"ir": set(), "capability": set(), "prompt": set(), "model": set(), "verifier": set()}
        evidence_ids: set[str] = set()
        for index, node in enumerate(nodes, start=1):
            node.setdefault("sequence", index)
            node.setdefault("subtask_id", "root")
            lane = str(node.get("subtask_id") or "root")
            if not node.get("parent_node_id"):
                node["parent_node_id"] = previous_by_lane.get(lane)
            previous_by_lane[lane] = str(node.get("node_id") or "")
            started = _parse_time(node.get("started_at"))
            if node.get("started_offset_ms") is None:
                node["started_offset_ms"] = max(0, int((started - trace_started).total_seconds() * 1000)) if started and trace_started else 0
            node.setdefault("exclusive_duration_ms", int(node.get("duration_ms") or 0))
            node.setdefault("input_tokens", None)
            node.setdefault("output_tokens", None)
            node.setdefault("cache_reused", False)
            node.setdefault("retry_count", 0)
            if not node.get("failure_class") and node.get("error_code"):
                value = classify_failure(str(node.get("error_code"))).value
                node["failure_class"] = None if value == "unknown" else value
            node_type = str(node.get("manifest_type") or node.get("node_type") or "code").lower()
            bucket = "llm" if "llm" in node_type else "tool" if node_type in {"tool", "database"} else "storage" if node_type in {"storage", "memory"} else "code"
            totals[bucket] += int(
                node.get("exclusive_duration_ms")
                if node.get("exclusive_duration_ms") is not None
                else node.get("duration_ms") or 0
            )
            config = node.get("config_data") or {}
            output = node.get("output_data") or {}
            for key, version_key in (("ir_version", "ir"), ("capability_registry_version", "capability"), ("prompt_version", "prompt"), ("verifier_version", "verifier")):
                if config.get(key):
                    versions[version_key].add(str(config[key]))
            model = node.get("model_id") or node.get("llm_model") or output.get("model")
            if model:
                node["model_id"] = str(model)
                versions["model"].add(str(model))
            for value in output.get("evidence_ids") or output.get("verified_evidence_ids") or []:
                evidence_ids.add(str(value))
        trace["nodes"] = nodes
        trace["timing"] = {"total_ms": int(trace.get("duration_ms") or sum(totals.values())), **{f"{key}_ms": value for key, value in totals.items()}}
        trace["versions"] = {key: sorted(value) for key, value in versions.items()}
        trace["evidence_ids"] = sorted(evidence_ids)
        return trace

    def _merge_jsonl_node_payloads(self, trace: dict[str, Any]) -> None:
        jsonl_trace = self._get_trace_from_jsonl(str(trace.get("trace_id") or ""))
        if jsonl_trace.get("workflow_id"):
            trace["workflow_id"] = jsonl_trace["workflow_id"]
        payloads = {node.get("node_id"): node for node in jsonl_trace.get("nodes", []) if node.get("node_id")}
        for node in trace.get("nodes", []):
            payload = payloads.get(node.get("node_id"))
            if payload:
                node.update(payload)

    def _get_trace_from_jsonl(self, trace_id: str) -> dict[str, Any]:
        if not self.jsonl_path.exists():
            return {"trace_id": trace_id, "nodes": [], "trace_storage": "none"}
        started = None
        finished = None
        nodes = []
        for line in self.jsonl_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("trace_id") != trace_id:
                continue
            if event.get("event") == "trace_started":
                started = event
            elif event.get("event") == "trace_node":
                nodes.append({key: value for key, value in event.items() if key != "event"})
            elif event.get("event") == "trace_finished":
                finished = event
        result: dict[str, Any] = {"trace_id": trace_id, "nodes": nodes, "trace_storage": "jsonl" if started or nodes or finished else "none"}
        if started:
            result.update({key: started.get(key) for key in ("session_id", "hospital_id", "user_query", "workflow_id", "started_at")})
        if finished:
            result.update({key: finished.get(key) for key in ("final_status", "intent", "error_count", "fallback_count", "ended_at", "duration_ms")})
        return result

    def list_runs(self, *, hospital_id: str, **filters: Any) -> list[dict[str, Any]]:
        return list_trace_records(self.runtime_engine, hospital_id=hospital_id, **filters)

    def metrics(self, *, hospital_id: str, **filters: Any) -> dict[str, Any]:
        runs = self.list_runs(hospital_id=hospital_id, limit=500, **filters)
        nodes = trace_nodes_for_records(self.runtime_engine, [str(item.get("trace_id") or "") for item in runs])
        durations = sorted(int(item.get("duration_ms") or 0) for item in runs)

        def percentile(fraction: float) -> int:
            if not durations:
                return 0
            return durations[min(len(durations) - 1, int((len(durations) - 1) * fraction))]

        status_counts: dict[str, int] = {}
        tool_stats: dict[str, dict[str, int]] = {}
        model_stats: dict[str, dict[str, int]] = {}
        trend: dict[str, dict[str, int]] = {}
        trace_subtasks: dict[str, set[str]] = {}
        replanned_traces: set[str] = set()
        repeated_stops: set[str] = set()
        for item in runs:
            status = str(item.get("final_status") or "unknown")
            status_counts[status] = status_counts.get(status, 0) + 1
            day = str(item.get("started_at") or "")[:10]
            trend.setdefault(day, {"planner_ms": 0, "final_answer_ms": 0, "requests": 0})["requests"] += 1
        for node in nodes:
            trace_id = str(node.get("trace_id") or "")
            day = str(node.get("started_at") or "")[:10]
            daily = trend.setdefault(day, {"planner_ms": 0, "final_answer_ms": 0, "requests": 0})
            name = str(node.get("node_name") or "")
            duration = int(node.get("duration_ms") or 0)
            if name == "planner_llm":
                daily["planner_ms"] += duration
            if name == "plan_replan":
                replanned_traces.add(trace_id)
            if name in {"final_answer_llm", "executor_llm"}:
                daily["final_answer_ms"] += duration
            tool = str(node.get("tool_name") or "")
            # 一次工具调用同时有 gateway 与 result 两个节点，只用结果节点计数。
            if tool and name == "tool_result":
                value = tool_stats.setdefault(tool, {"calls": 0, "failures": 0, "duration_ms": 0})
                value["calls"] += 1
                value["duration_ms"] += duration
                if str(node.get("status") or "") in {"failed", "error"}:
                    value["failures"] += 1
            model = str(node.get("model_id") or node.get("llm_model") or "")
            if model:
                value = model_stats.setdefault(model, {"calls": 0, "input_tokens": 0, "output_tokens": 0, "timeouts": 0, "duration_ms": 0})
                value["calls"] += 1
                value["duration_ms"] += duration
                value["input_tokens"] += int(node.get("input_tokens") or 0)
                value["output_tokens"] += int(node.get("output_tokens") or 0)
                if str(node.get("error_code") or "") in {"MODEL_TIMEOUT", "TOOL_TIMEOUT"}:
                    value["timeouts"] += 1
            trace_subtasks.setdefault(trace_id, set()).add(str(node.get("subtask_id") or "root"))
            if str(node.get("error_code") or "") == "AGENT_REPEATED_TOOL_CALL":
                repeated_stops.add(trace_id)
        total = len(runs)
        success = status_counts.get("success", 0)
        compound_trace_ids = {
            trace_id
            for trace_id, values in trace_subtasks.items()
            if len(values - {"root"}) > 1
        }
        duration_by_trace = {
            str(item.get("trace_id") or ""): int(item.get("duration_ms") or 0)
            for item in runs
        }
        return {
            "hospital_id": hospital_id,
            "request_count": total,
            "success_rate": round(success / total, 4) if total else 0,
            "incomplete_rate": round((total - success) / total, 4) if total else 0,
            "latency_ms": {"average": round(sum(durations) / total) if total else 0, "p50": percentile(0.50), "p95": percentile(0.95), "p99": percentile(0.99)},
            "status_counts": status_counts,
            "trend": [{"date": day, **value} for day, value in sorted(trend.items())],
            "tools": [{"tool_name": name, **value} for name, value in sorted(tool_stats.items())],
            "models": [{"model_id": name, **value} for name, value in sorted(model_stats.items())],
            "repeated_call_stop_rate": round(len(repeated_stops) / total, 4) if total else 0,
            "replan_rate": round(len(replanned_traces) / total, 4) if total else 0,
            "compound_request_count": len(compound_trace_ids),
            "compound_average_duration_ms": (
                round(sum(duration_by_trace[value] for value in compound_trace_ids) / len(compound_trace_ids))
                if compound_trace_ids
                else 0
            ),
        }
