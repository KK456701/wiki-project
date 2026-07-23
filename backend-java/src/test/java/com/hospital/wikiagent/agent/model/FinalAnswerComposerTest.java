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
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import com.fasterxml.jackson.databind.ObjectMapper;

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
        assertThat(result.content()).contains(
                "## 结果速览", "| 分子 | 3 |", "| 分母 | 68 |", "**4.41%**");
    }

    @Test
    void injectsOnlyTheTemplateSelectedForCurrentIntent() {
        CapturingInvoker invoker = new CapturingInvoker("""
                # 急会诊及时到位率 · 统计结果

                > **结论速览**
                > 指标率为 **4.41%**。

                ## 结果速览

                | 统计项 | 结果 |
                |---|---:|
                | 分子 | 3 |
                | 分母 | 68 |
                | 指标率 | **4.41%** |

                ## 计算口径

                指标率 = 分子 ÷ 分母 × 100%。

                ## 数据依据

                数据来自已验证试运行对象 RUN_001。
                """);
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
                PlanIntent.INDICATOR_TRIAL_RUN,
                List.of(RequestedOutput.TRIAL_RESULT),
                "ollama-test",
                LocalDate.of(2026, 7, 22),
                "",
                List.of(verifiedEvidence())));

        assertThat(result.corrected()).isFalse();
        assertThat(result.contractValidated()).isTrue();
        assertThat(result.templateId()).isEqualTo("indicator-trial-result");
        assertThat(result.templateVersion()).isEqualTo("v1");
        assertThat(invoker.prompts.get(0))
                .contains(
                        "本轮回答模板：indicator-trial-result@v1",
                        "<answer_template>",
                        "## 结果速览",
                        "## 计算口径",
                        "## 数据依据")
                .doesNotContain("## 文件概览", "## 变更摘要");
    }

    @Test
    void rejectsTemplateAnswerThatDropsVerifiedTrialNumbers() {
        String incomplete = """
                # 急会诊及时到位率 · 统计结果

                ## 结果速览

                当前暂无结果。

                ## 计算口径

                指标率 = 分子 ÷ 分母 × 100%。

                ## 数据依据

                依据本轮证据。
                """;
        CapturingInvoker invoker = new CapturingInvoker(incomplete, incomplete);
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
                PlanIntent.INDICATOR_TRIAL_RUN,
                List.of(RequestedOutput.TRIAL_RESULT),
                "ollama-test",
                LocalDate.of(2026, 7, 22),
                "",
                List.of(verifiedEvidence())));

        assertThat(result.corrected()).isTrue();
        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content()).contains(
                "| 分子 | 3 |", "| 分母 | 68 |", "**4.41%**");
        assertThat(invoker.prompts.get(1))
                .contains("回答未保留已验证数值", "indicator-trial-result@v1");
    }

    @Test
    void rejectsUnsupportedNationalCaliberClaimAndUsesReadableFallback() {
        String unsupported = """
                # 患者入院 48 小时内转科的比例

                > **口径速览**
                > 当前采用国家口径，本院与国标一致。

                ## 口径摘要

                当前采用国家口径。

                ## 计算口径

                指标率 = 分子 ÷ 分母 × 100%。

                ## 实施信息

                本院与国标口径一致。
                """;
        CapturingInvoker invoker = new CapturingInvoker(unsupported, unsupported);
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.compose(new FinalAnswerComposer.FinalAnswerInput(
                "这个指标按什么口径计算",
                "解释本院当前生效口径",
                PlanIntent.RULE_EXPLANATION,
                List.of(RequestedOutput.DEFINITION, RequestedOutput.FORMULA),
                "ollama-test",
                LocalDate.of(2026, 7, 22),
                "",
                List.of(verifiedRuleEvidence())));

        assertThat(result.corrected()).isTrue();
        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content())
                .contains(
                        "## 口径摘要",
                        "## 计算口径",
                        "## 实施信息",
                        "本院生效口径",
                        "当前证据未提供国标对比结论")
                .doesNotContain("当前采用国家口径", "本院与国标一致");
        assertThat(invoker.prompts.get(1))
                .contains("错误表述为当前国家口径", "rule-explanation@v1");
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

    private static VerifiedEvidence verifiedRuleEvidence() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        EvidenceEnvelope evidence = new EvidenceEnvelope(
                EvidenceEnvelope.VERSION, "EVD_RULE_001", "trace_001", "subtask_001",
                "effective_rule", "hospital_001", "MQSI2025_001", "4",
                "", "", "get_effective_rule", "MQSI2025_001", "input", "result",
                "internal", now, now.plusSeconds(3600), "MQSI2025_001",
                Map.of(
                        "rule_id", "MQSI2025_001",
                        "rule_name", "患者入院 48 小时内转科的比例",
                        "definition", "入院 48 小时内转科患者人次数占同期入院患者总人次数的比例。",
                        "formula", "分子 ÷ 分母 × 100%",
                        "numerator_rule", "入院后 0 至 48 小时内非 ICU 转科人次数",
                        "denominator_rule", "同期入院患者总人次数",
                        "effective_level", "hospital",
                        "hospital_version", 4));
        EvidenceVerification verification = new EvidenceVerification(
                EvidenceVerification.VERSION, "EVV_RULE_001", "EVD_RULE_001",
                "trace_001", "subtask_001", "hospital_001", "plan-verifier-v1",
                "verified", "PLAN_VERIFIED", "", now);
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
