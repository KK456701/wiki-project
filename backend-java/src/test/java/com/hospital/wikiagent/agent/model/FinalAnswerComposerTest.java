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
import com.hospital.wikiagent.agent.ir.ExplanationFocus;
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
        assertThat(invoker.prompts.get(0)).contains("RUN_001", "numeratorCount");
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
    void fallsBackToVerifiedEvidenceWhenAnswerModelTimesOut() {
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                (modelId, systemPrompt, userPrompt, timeout) -> {
                    throw new AgentModelUnavailableException(
                            "MODEL_CALL_FAILED", "模型调用超时");
                },
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
    void deterministicCompositionDoesNotInvokeModel() {
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                (modelId, systemPrompt, userPrompt, timeout) -> {
                    throw new AssertionError("deterministic composition must not invoke model");
                },
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.composeDeterministic(
                new FinalAnswerComposer.FinalAnswerInput(
                        "急会诊结果是多少", "计算急会诊及时到位率", "ollama-test",
                        LocalDate.of(2026, 7, 22), "", List.of(verifiedEvidence())));

        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content()).contains("| 分子 | 3 |", "| 分母 | 68 |");
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
    void rejectsZeroPercentClaimWhenVerifiedPeriodHasNoSample() {
        String invalid = """
                # 该指标 · 统计结果

                > **结论速览**
                > 指标率为 **0.0%**。

                ## 结果速览

                分子0，分母0，0 ÷ 0 × 100% = 0.0%。

                ## 计算口径

                指标率 = 分子 ÷ 分母 × 100%。

                ## 数据依据

                来源于本轮双库试运行。
                """;
        CapturingInvoker invoker = new CapturingInvoker(invalid, invalid);
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.compose(new FinalAnswerComposer.FinalAnswerInput(
                "统计这个指标",
                "计算指标",
                PlanIntent.INDICATOR_TRIAL_RUN,
                List.of(RequestedOutput.TRIAL_RESULT),
                "ollama-test",
                LocalDate.of(2026, 7, 26),
                "",
                List.of(noSampleEvidence())));

        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content())
                .contains("指标率不适用", "| 指标率 | **不适用** |")
                .doesNotContain("0 ÷ 0");
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
                        "本院覆盖口径")
                .doesNotContain("## 实施信息")
                .doesNotContain("当前采用国家口径", "本院与国标一致");
        assertThat(invoker.prompts.get(1))
                .contains("错误表述为当前国家口径", "rule-explanation@v1");
    }

    @Test
    void focusedNumeratorAnswerRejectsFullReportAndFallsBackToNumeratorOnly() {
        String fullReport = """
                # 患者入院 48 小时内转科的比例

                ## 口径摘要

                完整口径。

                ## 分子口径

                入院后 0 至 48 小时内非 ICU 转科人次数。

                ## 分母口径

                同期入院患者总人次数。
                """;
        CapturingInvoker invoker = new CapturingInvoker(fullReport, fullReport);
        AgentModelProperties properties = AgentModelRegistryTest.properties();
        FinalAnswerComposer composer = new FinalAnswerComposer(
                invoker,
                new AgentModelRegistry(properties),
                properties,
                new PromptCatalog(),
                new ObjectMapper());

        var result = composer.compose(new FinalAnswerComposer.FinalAnswerInput(
                "分子是什么口径",
                "解释当前指标分子口径",
                PlanIntent.RULE_EXPLANATION,
                List.of(RequestedOutput.EXPLANATION),
                List.of(ExplanationFocus.NUMERATOR),
                "ollama-test",
                LocalDate.of(2026, 7, 26),
                "",
                List.of(verifiedRuleEvidence())));

        assertThat(result.templateId()).isEqualTo("rule-numerator");
        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.content())
                .contains("## 分子口径", "入院后 0 至 48 小时内非 ICU 转科人次数")
                .doesNotContain("## 口径摘要", "## 分母口径", "## 实施信息");
        assertThat(invoker.prompts.get(1))
                .contains("用户未请求的章节", "rule-numerator@v1");
    }

    private static VerifiedEvidence verifiedEvidence() {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        EvidenceEnvelope evidence = new EvidenceEnvelope(
                EvidenceEnvelope.VERSION, "EVD_001", "trace_001", "subtask_001",
                "trial_run", "hospital_001", "MQSI2025_005", "1",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00",
                "trial_run_indicator_sql", "RUN_001", "input", "result",
                "sensitive_reference", now, now.plusSeconds(3600), "RUN_001",
                Map.of("runId", "RUN_001", "numeratorCount", 3,
                        "denominatorCount", 68, "resultValue", 4.41));
        EvidenceVerification verification = new EvidenceVerification(
                EvidenceVerification.VERSION, "EVV_001", "EVD_001", "trace_001",
                "subtask_001", "hospital_001", "plan-verifier-v1", "verified",
                "PLAN_VERIFIED", "", now);
        return new VerifiedEvidence(evidence, verification);
    }

    private static VerifiedEvidence noSampleEvidence() {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        EvidenceEnvelope evidence = new EvidenceEnvelope(
                EvidenceEnvelope.VERSION, "EVD_EMPTY", "trace_empty", "subtask_empty",
                "trial_run", "hospital_001", "HXZD-001-001", "4",
                "2026-01-01 00:00:00", "2026-02-01 00:00:00",
                "execute_indicator_dual_store", "RUN_EMPTY", "input", "result",
                "sensitive_reference", now, now.plusSeconds(3600), "RUN_EMPTY",
                Map.of(
                        "runId", "RUN_EMPTY",
                        "numeratorCount", 0,
                        "denominatorCount", 0,
                        "resultValue", 0.0,
                        "noSample", true,
                        "comparisonStatus", "matched",
                        "businessResult", Map.of(
                                "numeratorCount", 0,
                                "denominatorCount", 0,
                                "resultValue", 0.0),
                        "realResult", Map.of(
                                "numeratorCount", 0,
                                "denominatorCount", 0,
                                "resultValue", 0.0)));
        EvidenceVerification verification = new EvidenceVerification(
                EvidenceVerification.VERSION, "EVV_EMPTY", "EVD_EMPTY", "trace_empty",
                "subtask_empty", "hospital_001", "plan-verifier-v1", "verified",
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
                        "ruleId", "MQSI2025_001",
                        "ruleName", "患者入院 48 小时内转科的比例",
                        "definition", "入院 48 小时内转科患者人次数占同期入院患者总人次数的比例。",
                        "formula", "分子 ÷ 分母 × 100%",
                        "numeratorRule", "入院后 0 至 48 小时内非 ICU 转科人次数",
                        "denominatorRule", "同期入院患者总人次数",
                        "effectiveLevel", "hospital",
                        "hospitalVersion", 4));
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
