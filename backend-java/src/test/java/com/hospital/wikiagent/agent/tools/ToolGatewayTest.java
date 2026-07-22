package com.hospital.wikiagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

class ToolGatewayTest {
    private ToolGateway gateway;

    @AfterEach
    void closeGateway() {
        if (gateway != null) {
            gateway.close();
        }
    }

    @Test
    void policyIsTheEnforcementPoint() {
        AgentTool restricted = new AgentTool(
                "restricted",
                EchoInput.class,
                Set.of("admin"),
                Duration.ofSeconds(1),
                AgentTool.RiskLevel.PRIVILEGED,
                false,
                null,
                (input, context) -> ToolResult.success("OK", "不应执行", Map.of()));
        gateway = gateway(restricted);

        ToolResult result = gateway.execute(
                "restricted", Map.of("value", "x"), context(Set.of()), new AgentRunState()).join();

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void validatesArgumentsAndReusesIdenticalSuccessfulCall() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool echo = new AgentTool(
                "echo",
                EchoInput.class,
                Set.of(),
                Duration.ofSeconds(1),
                AgentTool.RiskLevel.READ_ONLY,
                false,
                null,
                (input, context) -> {
                    executions.incrementAndGet();
                    EchoInput echoInput = (EchoInput) input;
                    return ToolResult.success("ECHOED", "完成", Map.of("value", echoInput.value()));
                });
        gateway = gateway(echo);
        AgentRunState state = new AgentRunState();

        ToolResult invalid = gateway.execute("echo", Map.of(), context(Set.of()), state).join();
        ToolResult first = gateway.execute("echo", Map.of("value", "same"), context(Set.of()), state).join();
        ToolResult second = gateway.execute("echo", Map.of("value", "same"), context(Set.of()), state).join();

        assertThat(invalid.code()).isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(first.ok()).isTrue();
        assertThat(second.cacheReused()).isTrue();
        assertThat(executions).hasValue(1);
    }

    private ToolGateway gateway(AgentTool tool) {
        return new ToolGateway(
                new ToolRegistry(List.of(tool)),
                new PolicyDecisionService(),
                new ObjectMapper());
    }

    private static AgentRuntimeContext context(Set<String> permissions) {
        return new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", permissions, false, "session_001"),
                "request_001",
                "trace_001",
                "db_source_001");
    }

    record EchoInput(String value) {
        EchoInput {
            value = value == null ? "" : value.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("value 不能为空");
            }
        }
    }
}
