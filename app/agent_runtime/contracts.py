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

AgentRequestKind = Literal[
    "diagnosis",
    "trial_run",
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
    recent_history: str = ""
    step_count: int = 0
    tool_call_counts: dict[str, int] = Field(default_factory=dict)
    tool_result_cache: dict[str, dict[str, Any]] = Field(default_factory=dict)
    evidence: list[dict[str, Any]] = Field(default_factory=list)
    last_tool_results: list[dict[str, Any]] = Field(default_factory=list)
    current_rule_id: str | None = None
    current_rule_ids: list[str] = Field(default_factory=list, max_length=3)
    current_stat_start: str | None = None
    current_stat_end: str | None = None
    current_request_kind: AgentRequestKind | None = None
    validated_sql_ids: list[str] = Field(default_factory=list)
    last_run_id: str | None = None
    last_diagnosis_id: str | None = None
    last_draft_id: str | None = None
    current_upload_file_key: str | None = None
    stop_reason: AgentStopReason | None = None
    cancelled: bool = False
    replan_count: int = Field(default=0, ge=0)
    failed_plan_fingerprints: list[str] = Field(default_factory=list)
    fallback_category: str | None = None
    failure_code: str | None = None


class AgentToolCall(RuntimeContract):
    id: str | None = None
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class AgentModelResponse(RuntimeContract):
    content: str = ""
    tool_calls: list[AgentToolCall] = Field(default_factory=list)
    model: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)


class AgentRunResult(RuntimeContract):
    answer: str = ""
    stop_reason: AgentStopReason
    state: AgentRunState
    model: str | None = None
