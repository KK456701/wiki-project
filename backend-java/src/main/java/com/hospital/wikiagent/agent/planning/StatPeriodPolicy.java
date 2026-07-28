package com.hospital.wikiagent.agent.planning;

import java.time.LocalDateTime;

/**
 * 统一约束指标数据库查询的统计区间。
 *
 * <p>抽取和指标查询都可能扫描较大的医院业务表，因此所有入口必须复用本策略，
 * 在任何外部 I/O 之前阻止超过一年的请求。区间采用左闭右开语义：
 * {@code [start, end)}，允许的最晚结束时间为 {@code start.plusYears(1)}。</p>
 */
public final class StatPeriodPolicy {
    public static final String EXCEEDED_CODE = "STAT_PERIOD_EXCEEDS_ONE_YEAR";

    private StatPeriodPolicy() {
    }

    public static Validation validate(LocalDateTime start, LocalDateTime end) {
        return validate(start, end, true);
    }

    /**
     * 仅供不访问数据库的 SQL 展示校验关闭年份上限；所有计算和诊断入口必须使用
     * {@link #validate(LocalDateTime, LocalDateTime)}。
     */
    public static Validation validate(
            LocalDateTime start, LocalDateTime end, boolean enforceLimit) {
        if (start == null || end == null || !start.isBefore(end)) {
            return new Validation(false, "STAT_PERIOD_INVALID",
                    "统计开始时间必须早于结束时间。", null);
        }
        LocalDateTime latestEnd = start.plusYears(1);
        if (enforceLimit && end.isAfter(latestEnd)) {
            return new Validation(false, EXCEEDED_CODE,
                    "单次指标查询最多允许一年，当前开始时间允许的最晚结束时间为 "
                            + latestEnd + "。",
                    latestEnd);
        }
        return new Validation(true, "STAT_PERIOD_VALID", "", latestEnd);
    }

    public record Validation(
            boolean ok,
            String code,
            String message,
            LocalDateTime latestEnd) {
    }
}
