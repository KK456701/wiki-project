package com.hospital.wikiagent.agent.tools;

import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 定义 {@code ToolExecutionContext} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
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
