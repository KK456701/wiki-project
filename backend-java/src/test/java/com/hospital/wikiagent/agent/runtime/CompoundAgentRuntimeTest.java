package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.auth.HospitalPrincipal;

class CompoundAgentRuntimeTest {
    @Test
    void runsApiSubtasksInParallelButMergesInInputOrderAndKeepsPartialSuccess() {
        AgentRunner single = mock(AgentRunner.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(single.run(any(AgentRunRequest.class), any(AgentRunObserver.class)))
                .thenAnswer(invocation -> {
                    AgentRunRequest request = invocation.getArgument(0);
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(request.query().contains("转科") ? 80 : 20);
                    } finally {
                        active.decrementAndGet();
                    }
                    boolean failed = request.query().contains("急会诊");
                    return new AgentRunResult(
                            failed ? "急会诊子任务失败" : "转科指标完成",
                            failed ? "tool_error" : "final_answer",
                            request.traceId(), request.sessionId(), 2, null, null);
                });
        AgentModelProperties properties = properties("api", "openai-compatible");
        CompoundAgentRuntime runtime = new CompoundAgentRuntime(
                single, new CompoundRequestSplitter(), new AgentModelRegistry(properties),
                properties, AgentConversationMemory.noop());
        List<java.util.Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runtime.run(request("api"), events::add);
        runtime.close();

        assertThat(maximum.get()).isEqualTo(2);
        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).startsWith("## 患者入院48小时内转科的比例")
                .contains("## 急会诊及时到位率", "急会诊子任务失败");
        assertThat(events).filteredOn(event -> "assistant_message".equals(event.get("event")))
                .hasSize(1);
    }

    @Test
    void keepsOllamaCompoundExecutionSerial() {
        AgentRunner single = mock(AgentRunner.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(single.run(any(AgentRunRequest.class), any(AgentRunObserver.class)))
                .thenAnswer(invocation -> {
                    AgentRunRequest request = invocation.getArgument(0);
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(30);
                    } finally {
                        active.decrementAndGet();
                    }
                    return new AgentRunResult(
                            "完成", "final_answer", request.traceId(), request.sessionId(), 1,
                            null, null);
                });
        AgentModelProperties properties = properties("local", "ollama");
        CompoundAgentRuntime runtime = new CompoundAgentRuntime(
                single, new CompoundRequestSplitter(), new AgentModelRegistry(properties),
                properties, AgentConversationMemory.noop());

        AgentRunResult result = runtime.run(request("local"));
        runtime.close();

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(maximum.get()).isEqualTo(1);
    }

    private static AgentRunRequest request(String modelId) {
        return new AgentRunRequest(
                "患者入院48小时内转科的比例从26年1月到现在的结果，还有急会诊及时到位率的结果",
                "session_compound", modelId, null, "REQ_PARENT", "TRACE_PARENT", null,
                "{}", "", new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "AUTH_1"));
    }

    private static AgentModelProperties properties(String id, String provider) {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setDefaultModel(id);
        properties.setCompoundApiConcurrency(2);
        properties.setCompoundOllamaConcurrency(1);
        properties.setCompoundTimeout(Duration.ofSeconds(30));
        ModelDefinition definition = new ModelDefinition();
        definition.setId(id);
        definition.setName(id);
        definition.setProvider(provider);
        definition.setModel("test-model");
        definition.setBaseUrl("http://127.0.0.1:1");
        definition.setApiKey("test-key");
        properties.setModels(List.of(definition));
        return properties;
    }
}
