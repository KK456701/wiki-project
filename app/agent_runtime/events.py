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
