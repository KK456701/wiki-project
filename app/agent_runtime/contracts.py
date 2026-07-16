"""工具调用型 Agent 的服务端运行契约。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


AgentStopReason = Literal[
    "final_answer",
    "need_clarification",
    "max_steps",
    "repeated_tool_call",
    "tool_error",
    "request_timeout",
    "cancelled",
    "context_conflict",
]


class RuntimeContract(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)


class AgentRuntimeContext(RuntimeContract):
    model_config = ConfigDict(extra="forbid", frozen=True)

    user_id: str
    hospital_id: str
    session_id: str
    user_role: str
    permissions: frozenset[str] = Field(default_factory=frozenset)
    request_id: str
    trace_id: str
    db_source_id: str | None = None


class AgentRunState(RuntimeContract):
    messages: list[dict[str, Any]] = Field(default_factory=list)
    step_count: int = 0
    tool_call_counts: dict[str, int] = Field(default_factory=dict)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    last_tool_results: list[dict[str, Any]] = Field(default_factory=list)
    stop_reason: AgentStopReason | None = None
    cancelled: bool = False


class AgentToolCall(RuntimeContract):
    id: str | None = None
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class AgentModelResponse(RuntimeContract):
    content: str = ""
    tool_calls: list[AgentToolCall] = Field(default_factory=list)
    model: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)
