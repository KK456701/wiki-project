package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.evidence.EvidenceEnvelope;
import com.hospital.wikiagent.agent.evidence.EvidenceVerification;
import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;

import tools.jackson.databind.ObjectMapper;

class FinalAnswerComposerTest {
    @Test
    void consumesVerifiedSafeEvidenceAndRepairsToolProtocolLeak() {
        CapturingInvoker invoker = new CapturingInvoker(
                "<｜｜DSML｜｜tool_calls>",
                "统计区间内分子为 3，分母为 68，指标率为 4.41%。");
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.compose(new FinalAnswerComposer.FinalAnswerInput(
                "急会诊结果是多少",
                "计算急会诊及时到位率",
                "ollama-test",
                LocalDate.of(2026, 7, 22),
                "",
                List.of(verifiedEvidence())));

        assertThat(result.corrected()).isTrue();
        assertThat(result.content()).contains("4.41%");
        assertThat(invoker.prompts.get(0)).contains("RUN_001", "numerator_count");
        assertThat(invoker.prompts.get(0)).doesNotContain("SELECT", "patient_id");
    }

    @Test
    void fallsBackToDeterministicVerifiedEvidenceWhenRepairStillLeaksProtocol() {
        CapturingInvoker invoker = new CapturingInvoker(
                "<｜｜DSML｜｜tool_calls>",
                "tool_calls: []");
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.compose(new FinalAnswerComposer.FinalAnswerInput(
                "急会诊结果是多少", "计算急会诊及时到位率", "ollama-test",
                LocalDate.of(2026, 7, 22), "", List.of(verifiedEvidence())));

        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content()).contains("分子：3", "分母：68", "指标值：4.41%");
    }

    private static VerifiedEvidence verifiedEvidence() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        EvidenceEnvelope evidence = new EvidenceEnvelope(
                EvidenceEnvelope.VERSION, "EVD_001", "trace_001", "subtask_001",
                "trial_run", "hospital_001", "MQSI2025_005", "1",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00",
                "trial_run_indicator_sql", "RUN_001", "input", "result",
                "sensitive_reference", now, now.plusSeconds(3600), "RUN_001",
                Map.of("run_id", "RUN_001", "numerator_count", 3,
                        "denominator_count", 68, "result_value", 4.41));
        EvidenceVerification verification = new EvidenceVerification(
                EvidenceVerification.VERSION, "EVV_001", "EVD_001", "trace_001",
                "subtask_001", "hospital_001", "plan-verifier-v1", "verified",
                "PLAN_VERIFIED", "", now);
        return new VerifiedEvidence(evidence, verification);
    }

    private static class CapturingInvoker implements AgentModelInvoker {
        private final Queue<String> values = new ArrayDeque<>();
        private final List<String> prompts = new java.util.ArrayList<>();

        CapturingInvoker(String... values) {
            this.values.addAll(List.of(values));
        }

        @Override
        public ModelCompletion complete(
                String modelId, String systemPrompt, String userPrompt, java.time.Duration timeout) {
            prompts.add(userPrompt);
            return new ModelCompletion(modelId, values.remove());
        }
    }
}
