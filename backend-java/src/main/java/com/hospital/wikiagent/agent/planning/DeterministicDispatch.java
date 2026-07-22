package com.hospital.wikiagent.agent.planning;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 把已批准的业务能力编译成唯一工具调用，模型不能直接指定工具名或参数。
 *
 * <p>工具名、参数编译器和可见工具集合必须同时与 CapabilitySpec 一致，否则拒绝执行。
 * 这层只生成 ToolCall，不访问数据库；实际权限、超时、并发与 Evidence 由 ToolGateway 处理。</p>
 */
@Component
public class DeterministicDispatch {

    /**
     * 根据当前 IR 节点和已完成事实构造唯一合法的工具调用。
     */
    public ToolCall buildToolCall(
            PlanningExecution execution,
            ControllerDecision decision,
            AgentRunState state,
            String userMessage) {
        PlanCapability capability = decision.capability();
        if (decision.action() != ControllerAction.EXECUTE_TOOL || capability == null) {
            throw new CapabilityDispatchException(
                    "DISPATCH_ACTION_INVALID", "当前控制器决策不是工具执行步骤。");
        }
        CapabilitySpec spec = execution.capabilityRegistry().require(capability);
        if (spec.toolName() == null
                || spec.argumentCompiler() == null
                || !decision.toolNames().equals(java.util.List.of(spec.toolName()))) {
            throw new CapabilityDispatchException(
                    "DISPATCH_TOOL_INVALID", "当前业务能力没有唯一、受控的执行工具。");
        }
        Map<String, Object> arguments = spec.argumentCompiler()
                .compile(execution, state, userMessage);
        return new ToolCall(
                "server_" + state.stepCount() + "_" + spec.toolName(),
                spec.toolName(),
                arguments);
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
        public ToolCall {
            arguments = Map.copyOf(arguments);
        }
    }
}
