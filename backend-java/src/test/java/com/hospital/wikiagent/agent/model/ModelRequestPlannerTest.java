package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.ExplanationFocus;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import com.fasterxml.jackson.databind.ObjectMapper;

class ModelRequestPlannerTest {
    @Test
    void repairsMalformedPlanOnceWithoutCallingTools() {
        QueueInvoker invoker = new QueueInvoker(
                "not-json",
                """
                {
                  "schemaVersion": "request-plan-v2",
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
        assertThat(result.requestAudit().currentDate()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(result.requestAudit().messages()).hasSize(2);
        assertThat(result.requestAudit().systemPrompt()).contains("Planner");
        assertThat(result.requestAudit().userPrompt())
                .contains("结构化会话状态", "最近对话", "急会诊及时到位率")
                .contains("只修复格式");
        assertThat(result.requestAudit().repairAttempt()).isTrue();
        assertThat(invoker.calls).isEqualTo(2);
    }

    @Test
    void acceptsSnakeCaseFieldsExactlyAsDeclaredInPrompt() {
        // planner-system.txt 声明的字段是 snake_case（target_indicator、raw_name、start_time…），
        // 而 IR record 是 camelCase。严格照提示词输出的模型此前会被判为无效计划，
        // 实测 Qwen-Plus 就命中此处。这里要求一次成功，不依赖格式修复重试。
        QueueInvoker invoker = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v3",
                  "intent": "indicator_trial_run",
                  "goal": "计算指标结果",
                  "target_indicator": {"raw_name": "术者参加术前讨论率", "rule_id": "HXZD-008-002"},
                  "target_caliber": {"raw_text": "第一口径", "profile_id": null},
                  "time_expression": {
                    "raw_text": "2025-01-01 到 2026-01-01",
                    "start_time": "2025-01-01T00:00:00",
                    "end_time": "2026-01-01T00:00:00"
                  },
                  "requested_outputs": ["trial_result"],
                  "explanation_focuses": ["overview"],
                  "constraints": [],
                  "semantic_ambiguities": [{"field": "caliber", "description": "口径未指定"}],
                  "confidence": 0.9
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
                "术者参加术前讨论率 2025-01-01 到 2026-01-01",
                "ollama-test",
                LocalDate.of(2026, 7, 30),
                "{}",
                ""));

        assertThat(result.repaired()).isFalse();
        assertThat(invoker.calls).isEqualTo(1);
        assertThat(result.plan().intent()).isEqualTo(PlanIntent.INDICATOR_TRIAL_RUN);
        assertThat(result.plan().targetIndicator().rawName()).isEqualTo("术者参加术前讨论率");
        assertThat(result.plan().targetIndicator().ruleId()).isEqualTo("HXZD-008-002");
        assertThat(result.plan().targetCaliber().rawText()).isEqualTo("第一口径");
        assertThat(result.plan().timeExpression().startTime()).isEqualTo("2025-01-01T00:00:00");
        assertThat(result.plan().timeExpression().endTime()).isEqualTo("2026-01-01T00:00:00");
        assertThat(result.plan().requestedOutputs()).containsExactly(RequestedOutput.TRIAL_RESULT);
        assertThat(result.plan().explanationFocuses()).containsExactly(ExplanationFocus.OVERVIEW);
        assertThat(result.plan().semanticAmbiguities()).singleElement()
                .extracting(RequestPlan.SemanticAmbiguity::field).isEqualTo("caliber");
        // confidence 必须真读到，不能被当成缺字段而降级为 0.0 触发多余澄清。
        assertThat(result.plan().confidence()).isEqualTo(0.9);
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
