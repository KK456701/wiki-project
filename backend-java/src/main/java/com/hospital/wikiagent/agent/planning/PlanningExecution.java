package com.hospital.wikiagent.agent.planning;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;

/**
 * 定义 {@code PlanningExecution} 的不可变数据载体。
 */
public record PlanningExecution(
        RequestPlan requestPlan,
        CompiledPlanIR compiledPlan,
        PlanValidation validation,
        CapabilitySpecRegistry capabilityRegistry) {
}
