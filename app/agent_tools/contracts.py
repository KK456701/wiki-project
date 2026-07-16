"""模型可见工具和统一结果契约。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext


class ToolRiskLevel(str, Enum):
    READ = "read"
    CONTROLLED_EXECUTION = "controlled_execution"
    PREVIEW_ONLY = "preview_only"


class ToolContract(BaseModel):
    model_config = ConfigDict(extra="forbid", validate_assignment=True)


ToolStatus = Literal[
    "success",
    "not_found",
    "need_clarification",
    "preview_ready",
    "validation_failed",
    "forbidden",
    "unavailable",
    "timeout",
    "cancelled",
    "error",
]


class ToolEvidence(ToolContract):
    source: str
    source_id: str | None = None
    version: str | None = None
    fact_types: list[str] = Field(default_factory=list)


class ToolResult(ToolContract):
    ok: bool
    status: ToolStatus
    code: str
    summary: str
    data: dict[str, Any] = Field(default_factory=dict)
    evidence: list[ToolEvidence] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    retryable: bool = False


ToolHandler = Callable[[BaseModel, AgentRuntimeContext, AgentRunState], Any]
ToolAvailability = Callable[[AgentRuntimeContext, AgentRunState], bool]


@dataclass(frozen=True, slots=True)
class AgentTool:
    name: str
    description: str
    input_model: type[BaseModel]
    handler: ToolHandler
    risk_level: ToolRiskLevel
    timeout_seconds: float = 30.0
    required_permissions: frozenset[str] = field(default_factory=frozenset)
    availability: ToolAvailability | None = None

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z][a-z0-9_]{2,63}", self.name):
            raise ValueError("工具名称必须是 3 到 64 位小写 snake_case")
        if not self.description.strip():
            raise ValueError("工具描述不能为空")
        if self.timeout_seconds <= 0:
            raise ValueError("工具超时必须大于 0 秒")
        if not issubclass(self.input_model, BaseModel):
            raise ValueError("工具输入必须是 Pydantic BaseModel")
