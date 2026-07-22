package com.hospital.wikiagent.agent.tools;

import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 定义 {@code ToolExecutionContext} 的不可变数据载体。
 */
public record ToolExecutionContext(
        AgentRuntimeContext agentContext,
        String subtaskId,
        AgentRunState runState,
        PolicyDecision policyDecision) {

    public ToolExecutionContext {
        if (agentContext == null || runState == null || policyDecision == null) {
            throw new IllegalArgumentException("工具执行上下文不完整");
        }
        subtaskId = subtaskId == null || subtaskId.isBlank()
                ? agentContext.requestId()
                : subtaskId;
    }
}
