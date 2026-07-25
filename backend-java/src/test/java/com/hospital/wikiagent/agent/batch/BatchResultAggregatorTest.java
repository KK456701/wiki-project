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

        assertThat(report).contains("| 指标 | 结果值 | 分子/分母 | 目标值 | 达标 | 统计区间 |");
        // 96.00% >= 95 达标；92.50% < 95 未达标。
        assertThat(report).contains("| 指标一 | 96.00% | 192/200 | 95 | 达标 |");
        assertThat(report).contains("| 指标二 | 92.50% | 185/200 | 95 | 未达标 |");
    }

    @Test
    void showsDashWhenTargetValueMissing() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 88.0, 88L, 100L, "percent", null, null)),
                START, END);

        assertThat(report).contains("| 指标一 | 88.00% | 88/100 | — | — |");
    }

    @Test
    void complianceRespectsLowerIsBetterDirection() {
        String report = aggregator.aggregate(List.of(
                success("R1", "指标一", 3.0, 3L, 100L, "percent", 5, "<="),
                success("R2", "指标二", 8.0, 8L, 100L, "percent", 5, "<=")),
                START, END);

        assertThat(report).contains("| 指标一 | 3.00% | 3/100 | 5 | 达标 |");
        assertThat(report).contains("| 指标二 | 8.00% | 8/100 | 5 | 未达标 |");
    }

    @Test
    void rendersNoSampleRowInTable() {
        String report = aggregator.aggregate(List.of(noSample("R1", "指标一")), START, END);

        assertThat(report).contains("| 指标一 | 无样本 |");
        assertThat(report).doesNotContain("失败指标");
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

        assertThat(report).contains("| 指标一 | 12.35 | 12/100 | 10 | 达标 |");
    }

    @Test
    void emptyResultsProduceZeroSummaryWithoutTable() {
        String report = aggregator.aggregate(List.of(), START, END);

        assertThat(report).contains("共 0 项：0 项成功、0 项无样本、0 项失败");
        assertThat(report).doesNotContain("| 指标 |");
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
