package com.hospital.wikiagent.agent.runtime;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.contract.AgentClarification;

/**
 * 定义 {@code AgentRunResult} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record AgentRunResult(
        String answer,
        String stopReason,
        String traceId,
        String sessionId,
        int stepCount,
        RequestPlan requestPlan,
        CompiledPlanIR compiledPlan,
        AgentClarification clarification) {

    /**
     * 兼容现有成功、工具失败和测试构造代码；只有可恢复反问才携带 clarification。
     */
    public AgentRunResult(
            String answer,
            String stopReason,
            String traceId,
            String sessionId,
            int stepCount,
            RequestPlan requestPlan,
            CompiledPlanIR compiledPlan) {
        this(answer, stopReason, traceId, sessionId, stepCount, requestPlan, compiledPlan, null);
    }
}
