package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.PlanIntent;

import tools.jackson.databind.ObjectMapper;

class ModelRequestPlannerTest {
    @Test
    void repairsMalformedPlanOnceWithoutCallingTools() {
        QueueInvoker invoker = new QueueInvoker(
                "not-json",
                """
                {
                  "schemaVersion": "request-plan-v1",
                  "intent": "indicator_trial_run",
                  "goal": "计算指标结果",
                  "targetIndicator": {"rawName": "急会诊及时到位率"},
                  "timeExpression": {"rawText": "从一月到现在"},
                  "requestedOutputs": ["trial_result"],
                  "constraints": [],
                  "semanticAmbiguities": []
                }
                """);
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        ModelRequestPlanner planner = new ModelRequestPlanner(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = planner.plan(new ModelRequestPlanner.PlannerInput(
                "急会诊及时到位率从一月到现在是多少",
                "ollama-test",
                LocalDate.of(2026, 7, 22),
                "{}",
                ""));

        assertThat(result.repaired()).isTrue();
        assertThat(result.plan().intent()).isEqualTo(PlanIntent.INDICATOR_TRIAL_RUN);
        assertThat(result.plan().timeExpression().rawText()).isEqualTo("从一月到现在");
        assertThat(invoker.calls).isEqualTo(2);
    }

    private static class QueueInvoker implements AgentModelInvoker {
        private final Queue<String> values = new ArrayDeque<>();
        private int calls;

        QueueInvoker(String... values) {
            this.values.addAll(java.util.List.of(values));
        }

        @Override
        public ModelCompletion complete(
                String modelId, String systemPrompt, String userPrompt, java.time.Duration timeout) {
            calls++;
            return new ModelCompletion(modelId, values.remove());
        }
    }
}
