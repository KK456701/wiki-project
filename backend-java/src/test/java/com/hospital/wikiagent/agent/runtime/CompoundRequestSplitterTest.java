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
}
