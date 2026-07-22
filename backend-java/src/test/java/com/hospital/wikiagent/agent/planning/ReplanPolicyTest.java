package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.runtime.AgentRunState;

class ReplanPolicyTest {
    private final ReplanPolicy policy = new ReplanPolicy();

    @Test
    void allowsOnlyOneSemanticDirectionReplan() {
        AgentRunState state = new AgentRunState();

        assertThat(policy.canReplan(state, "TASK_TYPE_MISMATCH")).isTrue();
        policy.recordFailure(state, "PLAN_OLD");

        assertThat(policy.accepts(state, "PLAN_OLD")).isFalse();
        assertThat(policy.accepts(state, "PLAN_NEW")).isTrue();
        assertThat(policy.canReplan(state, "TASK_TYPE_MISMATCH")).isFalse();
    }

    @Test
    void rejectsInfrastructurePermissionAndEvidenceFailures() {
        AgentRunState state = new AgentRunState();

        assertThat(policy.canReplan(state, "DATABASE_UNAVAILABLE")).isFalse();
        assertThat(policy.canReplan(state, "PERMISSION_DENIED")).isFalse();
        assertThat(policy.canReplan(state, "EVIDENCE_RULE_MISMATCH")).isFalse();
        assertThat(policy.canReplan(state, "TIME_RANGE_AMBIGUOUS")).isFalse();
    }
}
