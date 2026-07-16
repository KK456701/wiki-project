"""Agent 对外业务事件协议。"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any


AgentEventCallback = Callable[[dict[str, Any]], None]

AGENT_EVENT_NAMES = frozenset({
    "agent_start",
    "model_start",
    "tool_call",
    "tool_result",
    "clarification_required",
    "assistant_message",
    "agent_done",
    "agent_error",
})


def public_agent_event(
    event: dict[str, Any],
    *,
    trace_id: str,
) -> dict[str, Any]:
    """投影为可发送给浏览器的业务事件，不透传参数和结果数据。"""
    event_name = str(event.get("event") or "agent_error")
    payload: dict[str, Any] = {
        "event": event_name,
        "trace_id": trace_id,
    }
    if event.get("step") is not None:
        payload["step"] = int(event.get("step") or 0)
    if event_name in {"tool_call", "tool_result"}:
        payload["tool_name"] = str(event.get("tool_name") or "")
    if event_name == "tool_call":
        payload["message"] = "正在调用受控业务工具。"
    elif event_name == "tool_result":
        result = event.get("result") or {}
        if not isinstance(result, dict):
            result = {}
        payload.update({
            "status": str(result.get("status") or "error"),
            "code": str(result.get("code") or "TOOL_EXECUTION_FAILED"),
            "message": str(result.get("summary") or "工具执行已结束。"),
            "retryable": bool(result.get("retryable")),
            "duration_ms": max(0, int(event.get("duration_ms") or 0)),
            "reused": bool(event.get("reused")),
        })
    elif event_name in {"clarification_required", "assistant_message"}:
        payload["message"] = str(event.get("message") or "")
    elif event_name in {"agent_done", "agent_error"}:
        payload["stop_reason"] = str(event.get("stop_reason") or "tool_error")
        if event.get("step_count") is not None:
            payload["step_count"] = int(event.get("step_count") or 0)
        if event_name == "agent_error":
            payload["message"] = str(
                event.get("message")
                or event.get("answer")
                or "Agent 运行未完成，请稍后重试或使用旧聊天入口。"
            )
    return payload


def emit_agent_event(
    callback: AgentEventCallback | None,
    event: str,
    **payload: Any,
) -> None:
    if callback is None or event not in AGENT_EVENT_NAMES:
        return
    try:
        callback({"event": event, **payload})
    except Exception:
        return
