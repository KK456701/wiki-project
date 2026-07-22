package com.hospital.wikiagent.agent.runtime;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;

/**
 * 定义 {@code AgentRunResult} 的不可变数据载体。
 */
public record AgentRunResult(
        String answer,
        String stopReason,
        String traceId,
        String sessionId,
        int stepCount,
        RequestPlan requestPlan,
        CompiledPlanIR compiledPlan) {
}
