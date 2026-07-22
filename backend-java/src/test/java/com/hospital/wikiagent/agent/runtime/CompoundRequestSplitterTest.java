package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompoundRequestSplitterTest {
    private final CompoundRequestSplitter splitter = new CompoundRequestSplitter();

    @Test
    void splitsTwoIndicatorsAndBindsOneTimeExpression() {
        var result = splitter.split(
                "患者入院48小时内转科的比例从26年1月到现在的结果，还有急会诊及时到位率的结果",
                "");

        assertThat(result.compound()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.TRIAL_RUN);
        assertThat(result.commonTimeExpression()).contains("26年1月", "现在");
        assertThat(result.tasks()).extracting("target").containsExactly(
                "患者入院48小时内转科的比例", "急会诊及时到位率");
        assertThat(result.tasks()).allSatisfy(task ->
                assertThat(task.query()).contains("统计周期", "26年1月", "现在"));
    }

    @Test
    void doesNotSplitOrdinarySingleIndicatorSentence() {
        var result = splitter.split("急会诊及时到位率的分子和分母分别是什么意思？", "");

        assertThat(result.compound()).isFalse();
    }

    @Test
    void restoresCompoundTargetsForFollowupFromMergedHeadings() {
        String history = """
                助手：## 急会诊及时到位率
                回答一

                ---

                ## 患者入院48小时内转科的比例
                回答二
                """;

        var result = splitter.split("这两个指标的SQL怎么写？", history);

        assertThat(result.compound()).isTrue();
        assertThat(result.followup()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.SQL_PREPARE);
        assertThat(result.tasks()).extracting("target").containsExactly(
                "急会诊及时到位率", "患者入院48小时内转科的比例");
    }

    @Test
    void usesResolvedIndicatorsAsAuthoritativeCompoundTargets() {
        var first = new HybridIndicatorResolver.ResolvedIndicator(
                "急会诊到位", "急会诊及时到位率", "MQSI2025_005",
                "RULE:MQSI2025_005", "semantic", 0.9, 0, 5);
        var second = new HybridIndicatorResolver.ResolvedIndicator(
                "48小时转科", "患者入院 48 小时内转科的比例", "MQSI2025_001",
                "RULE:MQSI2025_001", "semantic", 0.9, 6, 12);

        var result = splitter.split(
                "急会诊到位和48小时转科从一月到现在结果", "", "hospital_001",
                java.util.List.of(first, second));

        assertThat(result.compound()).isTrue();
        assertThat(result.tasks()).extracting("target").containsExactly(
                "急会诊及时到位率", "患者入院 48 小时内转科的比例");
        assertThat(result.tasks()).extracting("resolvedIndicator")
                .containsExactly(first, second);
    }
}
