package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.agent.runtime.CompoundAgentRuntime;
import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.contract.AgentChatRequest;

class AgentRunControllerTest {
    @Test
    void authenticatesAndKeepsFrozenChatResponseShape() {
        HospitalAuthService auth = mock(HospitalAuthService.class);
        CompoundAgentRuntime runner = mock(CompoundAgentRuntime.class);
        AgentTraceService traces = mock(AgentTraceService.class);
        HospitalPrincipal principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001");
        when(auth.authenticate("token")).thenReturn(principal);
        when(traces.observer(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(runner.run(any(), any())).thenReturn(new AgentRunResult(
                "已完成", "final_answer", "trace_001", "session_001", 2, null, null));
        AgentRunController controller = new AgentRunController(auth, runner, traces);

        try {
            var response = controller.chat(
                    "Bearer token", "request_001",
                    new AgentChatRequest("急会诊怎么算", "session_001", "ollama-test", null));
            assertThat(response.answer()).isEqualTo("已完成");
            assertThat(response.stopReason()).isEqualTo("final_answer");
            assertThat(response.stepCount()).isEqualTo(2);
            verify(auth).authenticate("token");
            verify(runner).run(any(), any());
        } finally {
            controller.close();
        }
    }
}
