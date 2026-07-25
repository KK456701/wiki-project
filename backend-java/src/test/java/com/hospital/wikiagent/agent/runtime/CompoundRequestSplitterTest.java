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

    @Test
    void inheritsSqlIntentFromRecentHistoryForFollowupSelection() {
        var first = new HybridIndicatorResolver.ResolvedIndicator(
                "48小时转科", "患者入院 48 小时内转科的比例", "MQSI2025_001",
                "RULE:MQSI2025_001", "exact", 1.0, 0, 5);
        var second = new HybridIndicatorResolver.ResolvedIndicator(
                "急会诊有效率", "急会诊有效率", "MQSI2025_006",
                "RULE:MQSI2025_006", "exact", 1.0, 6, 12);
        String history = "用户：这两个指标的 SQL 怎么写？\n"
                + "助手：用户说'这两个指标'但未明确指标名称，且会话上下文无历史记录，无法确定具体指标。";

        var result = splitter.split(
                "我选择的指标是：患者入院 48小时内转科的比例（MQSI2025_001）、急会诊有效率（MQSI2025_006）",
                history, "hospital_001", java.util.List.of(first, second));

        assertThat(result.compound()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.SQL_PREPARE);
        assertThat(result.tasks()).allSatisfy(task ->
                assertThat(task.query()).contains("受控 SQL"));
    }

    @Test
    void reexpandsCompoundWhenFollowupOnlySuppliesTimeRange() {
        String history = """
                用户：这两个指标现在各是多少？
                助手：## 患者入院48小时内转科的比例
                请明确需要统计的开始时间和结束时间。

                ---

                ## 急会诊及时到位率
                请明确需要统计的开始时间和结束时间。
                """;

        var result = splitter.split("从26年一月份到现在", history);

        assertThat(result.compound()).isTrue();
        assertThat(result.followup()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.TRIAL_RUN);
        assertThat(result.tasks()).extracting("target").containsExactly(
                "患者入院48小时内转科的比例", "急会诊及时到位率");
        assertThat(result.tasks()).allSatisfy(task ->
                assertThat(task.query()).contains("统计周期", "26年一月", "现在"));
    }

    @Test
    void reexpandsCompoundForRelativeMonthSupplement() {
        String history = """
                用户：这两个指标现在各是多少？
                助手：## 患者入院48小时内转科的比例
                请明确需要统计的开始时间和结束时间。

                ---

                ## 急会诊及时到位率
                请明确需要统计的开始时间和结束时间。
                """;

        var result = splitter.split("本月", history);

        assertThat(result.compound()).isTrue();
        assertThat(result.followup()).isTrue();
        assertThat(result.tasks()).allSatisfy(task ->
                assertThat(task.query()).contains("统计周期本月"));
    }

    @Test
    void doesNotReexpandTimeSupplementWithoutCompoundHistory() {
        var result = splitter.split("本月", "用户：急会诊及时到位率是多少？");

        assertThat(result.compound()).isFalse();
    }

    @Test
    void reexpandsCompoundFromRememberedTargetsWhenHistoryTruncated() {
        // 历史被长 SQL 截断，只剩第一个 ## 小节，historyTargets 无法恢复整批；
        // 此时应回退到结构态记住的整批指标重新展开为复合。
        String truncatedHistory = """
                用户：执行一下这两个sql
                助手：## 患者入院48小时内转科的比例
                WITH eligible AS (SELECT ... 很长的受控 SQL，第二个指标的小节已被截断丢失
                """;

        var result = splitter.split(
                "统计时间为：2026-01-01 至 2026-07-24", truncatedHistory, "hospital_001",
                java.util.List.of(),
                java.util.List.of("患者入院48小时内转科的比例", "急会诊及时到位率"));

        assertThat(result.compound()).isTrue();
        assertThat(result.followup()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.TRIAL_RUN);
        assertThat(result.tasks()).extracting("target").containsExactly(
                "患者入院48小时内转科的比例", "急会诊及时到位率");
    }

    @Test
    void classifiesExecuteSqlAsTrialRunNotPrepare() {
        // “执行一下这两个sql”虽含“sql”一词，但意图是算出数值，应归为试运行。
        String history = """
                助手：## 患者入院48小时内转科的比例
                回答一

                ---

                ## 急会诊及时到位率
                回答二
                """;

        var result = splitter.split("执行一下这两个sql", history);

        assertThat(result.compound()).isTrue();
        assertThat(result.followup()).isTrue();
        assertThat(result.kind()).isEqualTo(CompoundRequestSplitter.RequestKind.TRIAL_RUN);
    }
}
