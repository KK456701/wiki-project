"""将 Agent 业务事件写入现有 TraceRecorder。"""

from __future__ import annotations

from typing import Any

from app.agent_tools.policy import redact_payload


class AgentTraceBridge:
    def __init__(self, recorder: Any, trace_id: str) -> None:
        self.recorder = recorder
        self.trace_id = trace_id
        self._finished = False

    def start(
        self,
        *,
        session_id: str,
        hospital_id: str,
        user_query: str,
    ) -> None:
        self.recorder.start_trace(
            trace_id=self.trace_id,
            session_id=session_id,
            hospital_id=hospital_id,
            user_query=user_query,
            workflow_id="agent_runtime",
        )

    def handle(self, event: dict[str, Any]) -> None:
        event_name = str(event.get("event") or "")
        if event_name in {"agent_done", "agent_error"}:
            self._finish(event)
            return
        if event_name not in {"model_start", "tool_call", "tool_result"}:
            return

        safe = redact_payload(event)
        tool_name = str(safe.get("tool_name") or "")
        step = int(safe.get("step") or 0)
        status = "success"
        result = safe.get("result") or {}
        if event_name == "model_start":
            status = "running"
        elif event_name == "tool_call":
            status = "running"
        elif isinstance(result, dict) and result.get("ok") is not True:
            status = "failed"

        evidence_sources = []
        if isinstance(result, dict):
            evidence_sources = sorted({
                str(item.get("source") or "")
                for item in (result.get("evidence") or [])
                if isinstance(item, dict) and item.get("source")
            })
        node_names = {
            "model_start": "agent_model",
            "tool_call": "agent_tool_call",
            "tool_result": "agent_tool_result",
        }
        self.recorder.record_node(
            trace_id=self.trace_id,
            node_name=node_names[event_name],
            node_type=f"agent_{event_name}",
            status=status,
            output_summary=str(
                result.get("code") if isinstance(result, dict) else ""
            ),
            tool_name=tool_name,
            duration_ms=int(safe.get("duration_ms") or 0),
            input_data=(
                {"arguments": safe.get("arguments") or {}}
                if event_name == "tool_call"
                else {}
            ),
            output_data={
                "tool_result_code": (
                    result.get("code") if isinstance(result, dict) else None
                ),
                "evidence_source": evidence_sources,
            },
            config_data={
                "agent_mode": "tool_calling",
                "agent_step": step,
                "model_name": safe.get("model_name"),
                "risk_level": safe.get("risk_level"),
            },
        )

    def record_memory_failure(self, message: str) -> None:
        del message
        self.recorder.record_node(
            trace_id=self.trace_id,
            node_name="agent_memory",
            node_type="agent_memory",
            status="failed",
            output_summary="AGENT_MEMORY_SAVE_FAILED",
            error_code="AGENT_MEMORY_SAVE_FAILED",
            error_message="会话记忆保存失败，回答未受影响。",
            output_data={"problem_code": "AGENT_MEMORY_SAVE_FAILED"},
            config_data={"agent_mode": "tool_calling", "operation": "save"},
        )

    def _finish(self, event: dict[str, Any]) -> None:
        if self._finished:
            return
        self._finished = True
        stop_reason = str(event.get("stop_reason") or "tool_error")
        final_status = (
            "success"
            if stop_reason in {"final_answer", "need_clarification"}
            else "failed"
        )
        self.recorder.finish_trace(
            trace_id=self.trace_id,
            final_status=final_status,
            final_answer_summary=stop_reason,
            intent="agent_tool_calling",
            error_count=0 if final_status == "success" else 1,
        )
