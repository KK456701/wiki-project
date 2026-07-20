from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext


class PolicyDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    decision: Literal["allow", "deny"]
    reason_code: str
    display_message: str
    policy_version: str = "agent-tool-policy-v1"

    @property
    def allowed(self) -> bool:
        return self.decision == "allow"


class ToolExecutionContext(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, arbitrary_types_allowed=True)

    agent_context: AgentRuntimeContext
    subtask_id: str
    run_state: AgentRunState
    policy_decision: PolicyDecision

    @property
    def user_id(self) -> str:
        return self.agent_context.user_id

    @property
    def hospital_id(self) -> str:
        return self.agent_context.hospital_id

    @property
    def session_id(self) -> str:
        return self.agent_context.session_id

    @property
    def user_role(self) -> str:
        return self.agent_context.user_role

    @property
    def permissions(self) -> frozenset[str]:
        return self.agent_context.permissions

    @property
    def request_id(self) -> str:
        return self.agent_context.request_id

    @property
    def trace_id(self) -> str:
        return self.agent_context.trace_id

    @property
    def db_source_id(self) -> str | None:
        return self.agent_context.db_source_id


class PolicyDecisionService:
    version = "agent-tool-policy-v1"

    def decide(
        self,
        tool: Any,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> PolicyDecision:
        if not tool.required_permissions.issubset(context.permissions):
            return PolicyDecision(
                decision="deny",
                reason_code="PERMISSION_DENIED",
                display_message="当前用户没有执行该工具所需的权限。",
            )
        if tool.availability is not None:
            try:
                available = bool(tool.availability(context, state))
            except Exception:
                available = False
            if not available:
                return PolicyDecision(
                    decision="deny",
                    reason_code="TOOL_UNAVAILABLE",
                    display_message="当前运行状态不允许执行该工具。",
                )
        return PolicyDecision(
            decision="allow",
            reason_code="POLICY_ALLOWED",
            display_message="允许执行。",
        )
