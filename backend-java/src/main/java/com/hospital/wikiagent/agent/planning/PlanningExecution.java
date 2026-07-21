package com.hospital.wikiagent.agent.planning;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;

public record PlanningExecution(
        RequestPlan requestPlan,
        CompiledPlanIR compiledPlan,
        PlanValidation validation,
        CapabilitySpecRegistry capabilityRegistry) {
}
