"""将 Agent 业务事件写入现有 TraceRecorder。"""

from __future__ import annotations

from typing import Any

_TRACE_SECRET_KEY_PARTS = (
    "password",
    "secret",
    "token",
    "authorization",
    "connection",
    "db_url",
    "patient_name",
    "id_card",
    "identity_number",
    "phone_number",
    "home_address",
    "姓名",
    "身份证",
    "病历",
    "住院号",
    "手机号",
    "地址",
)


def redact_trace_payload(value: Any, key: str = "") -> Any:
    """Keep diagnostic payloads complete except secrets and patient identifiers."""
    normalized_key = key.lower()
    if any(part in normalized_key for part in _TRACE_SECRET_KEY_PARTS):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {
            str(item_key): redact_trace_payload(item_value, str(item_key))
            for item_key, item_value in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [redact_trace_payload(item) for item in value]
    return value


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
        if event_name == "trace_node":
            self._record_stage(event)
            return
        if event_name not in {"tool_call", "tool_result"}:
            return

        safe = redact_trace_payload(event)
        tool_name = str(safe.get("tool_name") or "")
        step = int(safe.get("step") or 0)
        status = "success"
        result = safe.get("result") or {}
        if event_name == "tool_call":
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
            "tool_call": "tool_gateway",
            "tool_result": "tool_result",
        }
        self.recorder.record_node(
            trace_id=self.trace_id,
            node_name=node_names[event_name],
            node_type="tool",
            status=status,
            output_summary=str(
                result.get("code") if isinstance(result, dict) else ""
            ),
            tool_name=tool_name,
            duration_ms=(
                max(1, int(safe.get("duration_ms") or 0))
                if event_name == "tool_call"
                else max(0, int(safe.get("duration_ms") or 0))
            ),
            input_data=(
                {
                    "tool_name": tool_name,
                    "arguments": safe.get("arguments") or {},
                }
                if event_name == "tool_call"
                else {"tool_name": tool_name}
            ),
            output_data=(
                {
                    "gateway_status": "accepted",
                    "risk_level": safe.get("risk_level"),
                }
                if event_name == "tool_call"
                else {
                    "result": result,
                    "tool_result_code": (
                        result.get("code") if isinstance(result, dict) else None
                    ),
                    "evidence_source": evidence_sources,
                    "reused": bool(safe.get("reused")),
                }
            ),
            processing_data={
                "description": (
                    "统一工具网关校验权限、参数、风险等级和重复调用策略。"
                    if event_name == "tool_call"
                    else "接收工具完整安全结果，并把证据写入当前运行状态。"
                )
            },
            config_data={
                "orchestration": "plan_compile_control",
                "agent_step": step,
                "model_name": safe.get("model_name"),
                "risk_level": safe.get("risk_level"),
            },
        )

    def _record_stage(self, event: dict[str, Any]) -> None:
        safe = redact_trace_payload(event)
        self.recorder.record_node(
            trace_id=self.trace_id,
            node_name=str(safe.get("node_name") or "agent_stage"),
            node_type=str(safe.get("node_type") or "code"),
            status=str(safe.get("status") or "success"),
            input_summary=str(safe.get("input_summary") or ""),
            output_summary=str(safe.get("output_summary") or ""),
            error_code=str(safe.get("error_code") or ""),
            error_message=str(safe.get("error_message") or ""),
            tool_name=str(safe.get("tool_name") or ""),
            duration_ms=max(0, int(safe.get("duration_ms") or 0)),
            input_data=dict(safe.get("input_data") or {}),
            output_data=dict(safe.get("output_data") or {}),
            processing_data=dict(safe.get("processing_data") or {}),
            config_data=dict(safe.get("config_data") or {}),
        )

    def record_memory_failure(self, message: str) -> None:
        del message
        self._record_stage({
            "event": "trace_node",
            "node_name": "memory_save",
            "node_type": "storage",
            "status": "failed",
            "output_summary": "AGENT_MEMORY_SAVE_FAILED",
            "error_code": "AGENT_MEMORY_SAVE_FAILED",
            "error_message": "会话记忆保存失败，回答未受影响。",
            "output_data": {
                "problem_code": "AGENT_MEMORY_SAVE_FAILED",
                "storage_error": "[REDACTED]",
            },
            "processing_data": {"description": "保存结构化状态与最近对话。"},
            "config_data": {"orchestration": "plan_compile_control", "operation": "save"},
        })

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
