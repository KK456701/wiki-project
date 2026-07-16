"""Tool Gateway 使用的确定性安全策略。"""

from __future__ import annotations

import hashlib
import json
from enum import Enum
from typing import Any

from app.agent_runtime.contracts import AgentRunState


_SENSITIVE_KEY_PARTS = (
    "password",
    "secret",
    "token",
    "authorization",
    "connection",
    "db_url",
    "sql_text",
)


class RepeatDecision(str, Enum):
    ALLOW = "allow"
    DUPLICATE = "duplicate"
    STOP = "stop"


def tool_call_fingerprint(tool_name: str, arguments: dict[str, Any]) -> str:
    canonical = json.dumps(
        arguments,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    )
    return hashlib.sha256(f"{tool_name}:{canonical}".encode("utf-8")).hexdigest()


def redact_payload(value: Any, key: str = "") -> Any:
    normalized_key = key.lower()
    if any(part in normalized_key for part in _SENSITIVE_KEY_PARTS):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {
            str(item_key): redact_payload(item_value, str(item_key))
            for item_key, item_value in value.items()
        }
    if isinstance(value, list):
        return [redact_payload(item) for item in value]
    if isinstance(value, tuple):
        return [redact_payload(item) for item in value]
    return value


class ToolExecutionPolicy:
    def note_call(
        self,
        state: AgentRunState,
        tool_name: str,
        arguments: dict[str, Any],
    ) -> RepeatDecision:
        fingerprint = tool_call_fingerprint(tool_name, arguments)
        previous = state.tool_call_counts.get(fingerprint, 0)
        state.tool_call_counts[fingerprint] = previous + 1
        if previous == 0:
            return RepeatDecision.ALLOW
        if previous == 1:
            return RepeatDecision.DUPLICATE
        state.stop_reason = "repeated_tool_call"
        return RepeatDecision.STOP
