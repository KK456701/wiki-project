from __future__ import annotations

from app.agent_runtime.contracts import AgentRunState, AgentToolCall

from .capability_registry import CapabilityDispatchError
from .controller import ControllerAction, ControllerDecision
from .runtime import PlanningExecution


DeterministicDispatchError = CapabilityDispatchError


def build_deterministic_tool_call(
    execution: PlanningExecution,
    decision: ControllerDecision,
    state: AgentRunState,
    *,
    user_message: str,
) -> AgentToolCall:
    capability = decision.capability
    if decision.action is not ControllerAction.EXECUTE_TOOL or capability is None:
        raise DeterministicDispatchError(
            "DISPATCH_ACTION_INVALID",
            "当前控制器决策不是工具执行步骤。",
        )
    spec = execution.capability_registry.get(capability)
    if (
        spec.tool_name is None
        or spec.argument_compiler is None
        or decision.tool_names != [spec.tool_name]
    ):
        raise DeterministicDispatchError(
            "DISPATCH_TOOL_INVALID",
            "当前业务能力没有唯一、受控的执行工具。",
        )
    arguments = spec.argument_compiler(execution, state, user_message)
    return AgentToolCall(
        id=f"server_{state.step_count}_{spec.tool_name}",
        name=spec.tool_name,
        arguments=arguments,
    )
