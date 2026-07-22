package com.hospital.wikiagent.agent.ir;

import java.util.List;
import java.util.Set;

/**
 * 定义 {@code CompiledPlanIR} 的不可变数据载体。
 */
public record CompiledPlanIR(
        String planId,
        String schemaVersion,
        String requestPlanVersion,
        String capabilityRegistryVersion,
        String promptVersion,
        String modelAdapterVersion,
        String verifierVersion,
        PlanIntent intent,
        String goal,
        List<PlanNode> nodes,
        Set<String> requiredFacts,
        Set<RequestedOutput> requestedOutputs) {

    public static final String VERSION = "compiled-plan-ir-v1";

    public CompiledPlanIR {
        nodes = List.copyOf(nodes);
        requiredFacts = Set.copyOf(requiredFacts);
        requestedOutputs = Set.copyOf(requestedOutputs);
    }

    public record PlanNode(
            PlanCapability capability,
            String capabilityVersion,
            Set<String> requires,
            Set<String> produces,
            String toolName,
            String policyAction,
            String verifier,
            String retryPolicy,
            String answerMode) {

        public PlanNode {
            requires = Set.copyOf(requires);
            produces = Set.copyOf(produces);
        }
    }
}
