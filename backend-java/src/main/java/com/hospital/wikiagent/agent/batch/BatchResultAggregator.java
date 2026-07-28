package com.hospital.wikiagent.agent.batch;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;

/**
 * 将批量计算的单指标结果聚合为确定性的 Markdown 报告。
 *
 * <p>输出由纯代码渲染、不经过任何模型：顶部为可机读的成功/无样本/失败汇总与统计区间，
 * 中部为结果表格（含目标值与达标列），底部单独列出失败指标及原因。目标值缺失时达标列
 * 显示“—”，用户后续往知识库补充 {@code target_value} 即自动生效，无需改动代码。</p>
 */
@Component
public class BatchResultAggregator {
    private static final String DASH = "—";

    /**
     * 按枚举顺序渲染全部指标结果。
     *
     * @param results 单指标结构化结果（保持枚举顺序）
     * @param statStart 批量请求解析出的统计开始时间（左闭）
     * @param statEnd 批量请求解析出的统计结束时间（右开）
     */
    public String aggregate(
            List<IndicatorExecutionResult> results, String statStart, String statEnd) {
        int indicatorCount = results == null
                ? 0
                : (int) results.stream()
                        .map(IndicatorExecutionResult::ruleId)
                        .distinct()
                        .count();
        return aggregate(results, indicatorCount, statStart, statEnd);
    }

    public String aggregate(
            List<IndicatorExecutionResult> results,
            int indicatorCount,
            String statStart,
            String statEnd) {
        List<IndicatorExecutionResult> values = results == null ? List.of() : results;
        long succeeded = values.stream().filter(r -> r.status() == Status.SUCCESS).count();
        long noSample = values.stream().filter(r -> r.status() == Status.NO_SAMPLE).count();
        long failed = values.stream().filter(r -> r.status() == Status.FAILED).count();
        boolean profileAware = values.stream().anyMatch(
                value -> value.profileId() != null);

        StringBuilder output = new StringBuilder();
        if (profileAware) {
            output.append("共 ").append(Math.max(0, indicatorCount))
                    .append(" 项指标、").append(values.size()).append(" 个已审批口径：")
                    .append(succeeded).append(" 个口径成功、")
                    .append(noSample).append(" 个口径无样本、")
                    .append(failed).append(" 个口径失败");
        } else {
            output.append("共 ").append(values.size()).append(" 项：")
                    .append(succeeded).append(" 项成功、")
                    .append(noSample).append(" 项无样本、")
                    .append(failed).append(" 项失败");
        }
        if (statStart != null && !statStart.isBlank() && statEnd != null && !statEnd.isBlank()) {
            output.append("；统计区间 ").append(statStart.strip())
                    .append(" 至 ").append(statEnd.strip()).append("（左闭右开）");
        }
        output.append("。\n");

        List<IndicatorExecutionResult> rows = values.stream()
                .filter(IndicatorExecutionResult::ok).toList();
        if (!rows.isEmpty()) {
            output.append(profileAware
                    ? "\n| 指标 | Profile 口径 | 结果值 | 计算构成 | 目标值 | 达标 | 统计区间 |\n"
                    : "\n| 指标 | 结果值 | 计算构成 | 目标值 | 达标 | 统计区间 |\n");
            output.append(profileAware
                    ? "| --- | --- | --- | --- | --- | --- | --- |\n"
                    : "| --- | --- | --- | --- | --- | --- |\n");
            for (IndicatorExecutionResult row : rows) {
                output.append("| ").append(row.ruleName())
                        .append(profileAware
                                ? " | " + formatProfile(row)
                                : "")
                        .append(" | ").append(formatValue(row))
                        .append(" | ").append(formatFraction(row))
                        .append(" | ").append(formatTarget(row))
                        .append(" | ").append(compliance(row))
                        .append(" | ").append(formatPeriod(row, statStart, statEnd))
                        .append(" |\n");
            }
            output.append("\n> 列说明：结果值是最终指标值；计算构成展示分子/分母、"
                    + "率比两侧发生率或标量统计结构；目标值来自本院运行配置或审批口径；"
                    + "达标按指标方向比较，无结果、无目标或目标冲突时不判定。\n");
            if (values.stream().anyMatch(value ->
                    "existing_snapshot_not_refreshed".equals(value.dataFreshness()))) {
                output.append("\n> 数据新鲜度：本轮未刷新真实库数据，"
                        + "结果与比较结论基于当前已有快照。\n");
            }
            if (values.stream().anyMatch(value ->
                    "extraction_failed_stale".equals(value.dataFreshness()))) {
                output.append("\n> ⚠️ 数据抽取失败：本轮未能从源库刷新数据，"
                        + "以上结果基于中间表旧数据，仅供参考，请排查抽取链路后重新计算。\n");
            }
        }

        List<IndicatorExecutionResult> failures = values.stream()
                .filter(r -> r.status() == Status.FAILED).toList();
        if (!failures.isEmpty()) {
            output.append("\n**失败指标**\n\n");
            for (IndicatorExecutionResult failure : failures) {
                output.append("- ").append(failure.ruleName());
                if (profileAware) {
                    output.append(" / ").append(formatProfile(failure));
                }
                if (failure.errorCode() != null) {
                    output.append("（").append(failure.errorCode()).append("）");
                }
                if (failure.errorMessage() != null) {
                    output.append("：").append(failure.errorMessage());
                }
                output.append("\n");
            }
        }

        return output.toString().stripTrailing();
    }

    private static String formatProfile(IndicatorExecutionResult result) {
        if (result.profileId() == null) {
            return "默认口径";
        }
        String label = result.profileLabel() == null
                ? result.profileId() : result.profileLabel();
        return label + "（" + result.profileId() + "）";
    }

    private static String formatValue(IndicatorExecutionResult result) {
        if (result.status() == Status.NO_SAMPLE) {
            return "无样本";
        }
        if (result.resultValue() == null) {
            return DASH;
        }
        String formatted = String.format(Locale.ROOT, "%.2f", result.resultValue());
        return switch (normalizeUnit(result.unit())) {
            case "percent" -> formatted + "%";
            case "ratio" -> formatted + " 倍";
            case "minutes" -> formatted + " 分钟";
            default -> formatted;
        };
    }

    private static String formatFraction(IndicatorExecutionResult result) {
        if (result.calculationDisplay() != null) {
            return result.calculationDisplay();
        }
        if (result.numerator() == null || result.denominator() == null) {
            return "不适用";
        }
        return result.numerator() + "/" + result.denominator();
    }

    private static String formatTarget(IndicatorExecutionResult result) {
        if (result.targetValue() == null) {
            return "未配置";
        }
        Double numeric = toDouble(result.targetValue());
        if (numeric == null) {
            return String.valueOf(result.targetValue());
        }
        String formatted = String.format(Locale.ROOT, "%.2f", numeric);
        return switch (normalizeUnit(result.unit())) {
            case "percent" -> formatted + "%";
            case "ratio" -> formatted + " 倍";
            case "minutes" -> formatted + " 分钟";
            default -> formatted;
        };
    }

    private static String compliance(IndicatorExecutionResult result) {
        if (result.status() == Status.NO_SAMPLE) {
            return "不判定";
        }
        Double value = result.resultValue();
        Double target = toDouble(result.targetValue());
        if (value == null || target == null) {
            return "不判定";
        }
        String direction = normalizeDirection(result.targetDirection());
        boolean met = switch (direction) {
            case "<=" -> value <= target;
            case "<" -> value < target;
            case ">" -> value > target;
            default -> value >= target;
        };
        return met ? "达标" : "未达标";
    }

    private static String normalizeUnit(String value) {
        if (value == null) return "";
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "percentage", "percent", "%" -> "percent";
            case "ratio", "倍" -> "ratio";
            case "minute", "minutes", "分钟" -> "minutes";
            default -> value.strip().toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeDirection(String value) {
        if (value == null || value.isBlank()) {
            return ">=";
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "lower_is_better", "lower", "<=" -> "<=";
            case "higher_is_better", "higher", ">=" -> ">=";
            default -> value.strip();
        };
    }

    private static String formatPeriod(
            IndicatorExecutionResult result, String statStart, String statEnd) {
        String start = result.statStart() != null ? result.statStart() : statStart;
        String end = result.statEnd() != null ? result.statEnd() : statEnd;
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return DASH;
        }
        return start.strip() + " ~ " + end.strip();
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
