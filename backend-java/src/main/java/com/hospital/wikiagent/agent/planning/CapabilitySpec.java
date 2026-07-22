package com.hospital.wikiagent.agent.planning;

import java.util.Map;
import java.util.Set;

import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 定义 {@code CapabilitySpec} 的不可变数据载体。
 */
public record CapabilitySpec(
        PlanCapability capability,
        String version,
        Set<String> requires,
        Set<String> produces,
        String toolName,
        String policyAction,
        ArgumentCompiler argumentCompiler,
        String verifierName,
        FactVerifier verifier,
        String retryPolicy,
        String answerMode,
        String completionFact) {

    public CapabilitySpec {
        requires = Set.copyOf(requires);
        produces = Set.copyOf(produces);
    }

    @FunctionalInterface
    public interface ArgumentCompiler {
        Map<String, Object> compile(
                PlanningExecution execution,
                AgentRunState state,
                String userMessage);
    }

    @FunctionalInterface
    public interface FactVerifier {
        boolean verify(Set<String> facts, CapabilitySpec spec);
    }
}
