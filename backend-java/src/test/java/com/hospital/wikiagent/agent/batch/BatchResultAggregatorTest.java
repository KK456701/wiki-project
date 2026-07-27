package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;

/**
 * 验证批量结果聚合器的 Markdown 渲染：汇总、目标值/达标列、无样本、失败清单与单位。
 */
class BatchResultAggregatorTest {
    private static final String START = "2026-01-01 00:00:00";
    private static final String END = "2026-04-01 00:00:00";

    private final BatchResultAggregator aggregator = new BatchResultAggregator();

    @Test
    void rendersSummaryHeaderWithCountsAndPeriod() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 92.5, 185L, 200L, "percent", 95, ">="),
                noSample("R2", "指标二"),
                IndicatorExecutionResult.failed("R3", "指标三", "TRIAL_RUN_FAILED", "试运行失败。")),
                START, END);

        assertThat(report).contains("共 3 项：1 项成功、1 项无样本、1 项失败");
        assertThat(report).contains("统计区间 " + START + " 至 " + END + "（左闭右开）");
    }

    @Test
    void rendersTableHeaderAndComplianceColumns() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 96.0, 192L, 200L, "percent", 95, ">="),
                success("R2", "指标二", 92.5, 185L, 200L, "percent", 95, ">=")),
                START, END);

        assertThat(report).contains("| 指标 | 结果值 | 计算构成 | 目标值 | 达标 | 统计区间 |");
        // 96.00% >= 95 达标；92.50% < 95 未达标。
        assertThat(report).contains("| 指标一 | 96.00% | 192/200 | 95.00% | 达标 |");
        assertThat(report).contains("| 指标二 | 92.50% | 185/200 | 95.00% | 未达标 |");
    }

    @Test
    void labelsMissingTargetAsNotConfigured() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 88.0, 88L, 100L, "percent", null, null)),
                START, END);

        assertThat(report).contains("| 指标一 | 88.00% | 88/100 | 未配置 | 不判定 |");
    }

    @Test
    void complianceRespectsLowerIsBetterDirection() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 3.0, 3L, 100L, "percent", 5, "<="),
                success("R2", "指标二", 8.0, 8L, 100L, "percent", 5, "<=")),
                START, END);

        assertThat(report).contains("| 指标一 | 3.00% | 3/100 | 5.00% | 达标 |");
        assertThat(report).contains("| 指标二 | 8.00% | 8/100 | 5.00% | 未达标 |");
    }

    @Test
    void rendersNoSampleRowInTable() {
        String report = aggregator.aggregate(List.of(noSample("R1", "指标一")), START, END);

        assertThat(report).contains("| 指标一 | 无样本 |");
        assertThat(report).doesNotContain("失败指标");
    }

    @Test
    void noSampleNeverProducesComplianceDecisionEvenWithTarget() {
        var result = new IndicatorExecutionResult(
                "R1", "指标一", Status.NO_SAMPLE, 0.0,
                0L, 0L, "percentage", "percent", null,
                0L, 95, ">=", START, END, "RUN_EMPTY", null, null, 10);

        String report = aggregator.aggregate(List.of(result), START, END);

        assertThat(report).contains(
                "| 指标一 | 无样本 | 0/0 | 95.00% | 不判定 |");
    }

    @Test
    void listsFailedIndicatorsBelowTable() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 90.0, 90L, 100L, "percent", null, null),
                IndicatorExecutionResult.failed("R2", "指标二", "TRIAL_RUN_FAILED", "数据库超时。")),
                START, END);

        assertThat(report).contains("| 指标一 | 90.00% |");
        assertThat(report).doesNotContain("| 指标二 |");
        assertThat(report).contains("**失败指标**");
        assertThat(report).contains("- 指标二（TRIAL_RUN_FAILED）：数据库超时。");
    }

    @Test
    void nonPercentUnitOmitsPercentSign() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 12.345, 12L, 100L, "count", 10, ">=")),
                START, END);

        assertThat(report).contains("| 指标一 | 12.35 | 12/100 | 10.00 | 达标 |");
    }

    @Test
    void rendersRatioAndMedianWithMetricSpecificFormats() {
        var ratio = new IndicatorExecutionResult(
                "HXZD-012-001", "并发症发生率比", Status.SUCCESS, 2.0,
                null, null, "rate_ratio", "ratio", "2.00% : 1.00%",
                null, null, "<=", START, END, "RUN_RATIO", null, null, 10);
        var median = new IndicatorExecutionResult(
                "HXZD-014-001", "危急值报告时间", Status.SUCCESS, 4.5,
                null, null, "median_duration", "minutes", "中位数，n=12",
                12L, 5, "lower_is_better", START, END, "RUN_MEDIAN", null, null, 10);

        String report = aggregator.aggregate(List.of(ratio, median), START, END);

        assertThat(report).contains(
                "| 并发症发生率比 | 2.00 倍 | 2.00% : 1.00% | 未配置 | 不判定 |");
        assertThat(report).contains(
                "| 危急值报告时间 | 4.50 分钟 | 中位数，n=12 | 5.00 分钟 | 达标 |");
        assertThat(report).contains("列说明：结果值是最终指标值");
    }

    @Test
    void targetConflictIsShownWithoutComplianceDecision() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 96.0, 96L, 100L,
                        "percent", "目标配置不一致", ">=")),
                START, END);

        assertThat(report).contains(
                "| 指标一 | 96.00% | 96/100 | 目标配置不一致 | 不判定 |");
    }

    @Test
    void emptyResultsProduceZeroSummaryWithoutTable() {
        String report = aggregator.aggregate(List.of(), START, END);

        assertThat(report).contains("共 0 项：0 项成功、0 项无样本、0 项失败");
        assertThat(report).doesNotContain("| 指标 |");
    }

    @Test
    void profileSummaryKeepsRequestedIndicatorScopeWhenDraftOnlyRuleHasNoTask() {
        var profile = success(
                "R1", "指标一", 96.0, 96L, 100L, "percent", 95, ">=")
                .withProfile("R1-P1", "已审批口径", "EVENT_1", "EXT_1", "COMPLETED");

        String report = aggregator.aggregateProfiles(
                List.of(profile), START, END, 2);

        assertThat(report).contains("共 2 项指标、1 个已审批口径");
        assertThat(report).doesNotContain("无可执行已审批口径");
    }

    private static IndicatorExecutionResult success(
            String ruleId, String ruleName, Double value, Long numerator, Long denominator,
            String unit, Object target, String direction) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.SUCCESS, value, numerator, denominator, unit,
                target, direction, START, END, "RUN_1", null, null, 10);
    }

    private static IndicatorExecutionResult noSample(String ruleId, String ruleName) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.NO_SAMPLE, null, null, null, "percent",
                null, null, START, END, "RUN_1", null, null, 10);
    }
}
