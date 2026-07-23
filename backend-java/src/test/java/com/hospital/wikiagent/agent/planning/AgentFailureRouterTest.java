package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.planning.AgentFailureRouter.FailureAction;
import com.hospital.wikiagent.agent.runtime.AgentRunState;

class AgentFailureRouterTest {
    private final AgentFailureRouter router = new AgentFailureRouter(new ReplanPolicy());

    @Test
    void routesSemanticPlanFailureToOneReplan() {
        AgentRunState state = new AgentRunState();

        AgentFailureRouter.FailureRoute first = router.route(state, "PLAN_INTENT_MISMATCH");
        assertThat(first.action()).isEqualTo(FailureAction.REPLAN);
        assertThat(first.failureClass()).isEqualTo(FailureClass.SEMANTIC_PLAN_ERROR);

        router.recordReplan(state, "PLAN_OLD");
        assertThat(router.route(state, "PLAN_INTENT_MISMATCH").action())
                .isEqualTo(FailureAction.FALLBACK);
    }

    @Test
    void routesClarificationAndInfrastructureFailuresDirectlyToFallback() {
        AgentRunState state = new AgentRunState();

        assertThat(router.route(state, "TIME_RANGE_AMBIGUOUS").action())
                .isEqualTo(FailureAction.FALLBACK);
        assertThat(router.route(state, "DATABASE_UNAVAILABLE").action())
                .isEqualTo(FailureAction.FALLBACK);
        assertThat(router.route(state, "PERMISSION_DENIED").action())
                .isEqualTo(FailureAction.FALLBACK);
    }
}
