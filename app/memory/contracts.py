"""会话结构化状态契约。"""

from __future__ import annotations

from typing import Any, Literal
import uuid

from pydantic import BaseModel, ConfigDict, Field


class ContextVersionConflict(RuntimeError):
    """保存会话状态时版本已被其他请求更新。"""


class ContextStorageError(RuntimeError):
    """会话状态无法安全读取或写入。"""


class ContextModel(BaseModel):
    model_config = ConfigDict(validate_assignment=True)


class ActiveRuleContext(ContextModel):
    rule_id: str = ""
    rule_name: str = ""
    hospital_id: str = ""


class StatPeriodContext(ContextModel):
    start_time: str = ""
    end_time: str = ""
    source_message_id: int | None = None


class ContextOverride(ContextModel):
    key: str
    business_value: Any
    hospital_field: str | None = None
    status: Literal["ready", "pending_mapping", "pending_clarification"]
    source_message_id: int | None = None
    source_text: str = ""


class WorkingCaliberContext(ContextModel):
    scope: Literal["session"] = "session"
    overrides: list[ContextOverride] = Field(default_factory=list)

    def get(self, key: str) -> ContextOverride | None:
        return next((item for item in self.overrides if item.key == key), None)


class PendingClarification(ContextModel):
    code: str
    question: str
    options: list[str] = Field(default_factory=list)
    source_message_id: int | None = None


class ConversationContext(ContextModel):
    schema_version: int = 1
    context_version: int = 0
    active_rule: ActiveRuleContext = Field(default_factory=ActiveRuleContext)
    stat_period: StatPeriodContext = Field(default_factory=StatPeriodContext)
    working_caliber: WorkingCaliberContext = Field(default_factory=WorkingCaliberContext)
    pending_clarifications: list[PendingClarification] = Field(default_factory=list)
    last_action: str = ""


class ContextDelta(ContextModel):
    overrides: list[ContextOverride] = Field(default_factory=list)
    clear_working_caliber: bool = False
    clarification: PendingClarification | None = None


class ExecutionBlocker(ContextModel):
    code: str
    message: str
    key: str = ""


class ExecutionContextSnapshot(ContextModel):
    snapshot_id: str = Field(default_factory=lambda: f"CTX_{uuid.uuid4().hex[:12]}")
    context_version: int = 0
    rule_id: str = ""
    overrides: dict[str, Any] = Field(default_factory=dict)
    resolved_fields: dict[str, str] = Field(default_factory=dict)
    source_levels: dict[str, str] = Field(default_factory=dict)
    executable: bool = True
    blockers: list[ExecutionBlocker] = Field(default_factory=list)


class ContextResolution(ContextModel):
    context: ConversationContext
    delta: ContextDelta = Field(default_factory=ContextDelta)
    snapshot: ExecutionContextSnapshot
    clarification: PendingClarification | None = None

    @property
    def blocked(self) -> bool:
        return not self.snapshot.executable
