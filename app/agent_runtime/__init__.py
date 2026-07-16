from typing import TYPE_CHECKING, Any

from .contracts import (
    AgentModelResponse,
    AgentRunResult,
    AgentRunState,
    AgentRuntimeContext,
    AgentStopReason,
    AgentToolCall,
)
from .model_adapter import AgentModelAdapter, AgentModelError
from .events import (
    AGENT_EVENT_NAMES,
    AgentEventCallback,
    emit_agent_event,
    public_agent_event,
)
from .response_guard import evidence_correction_prompt, missing_fact_types

if TYPE_CHECKING:
    from .runner import AgentRunner


def __getattr__(name: str) -> Any:
    if name == "AgentRunner":
        from .runner import AgentRunner

        return AgentRunner
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

__all__ = [
    "AgentModelResponse",
    "AGENT_EVENT_NAMES",
    "AgentEventCallback",
    "AgentModelAdapter",
    "AgentModelError",
    "AgentRunResult",
    "AgentRunState",
    "AgentRuntimeContext",
    "AgentStopReason",
    "AgentToolCall",
    "AgentRunner",
    "evidence_correction_prompt",
    "emit_agent_event",
    "public_agent_event",
    "missing_fact_types",
]
