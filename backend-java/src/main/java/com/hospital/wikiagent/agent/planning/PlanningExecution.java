package com.hospital.wikiagent.agent.planning;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;

/**
 * 定义 {@code PlanningExecution} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record PlanningExecution(
        RequestPlan requestPlan,
        CompiledPlanIR compiledPlan,
        PlanValidation validation,
        CapabilitySpecRegistry capabilityRegistry) {
}
