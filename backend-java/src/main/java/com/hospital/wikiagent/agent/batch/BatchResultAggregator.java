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
        List<IndicatorExecutionResult> values = results == null ? List.of() : results;
        long succeeded = values.stream().filter(r -> r.status() == Status.SUCCESS).count();
        long noSample = values.stream().filter(r -> r.status() == Status.NO_SAMPLE).count();
        long failed = values.stream().filter(r -> r.status() == Status.FAILED).count();

        StringBuilder output = new StringBuilder();
        output.append("共 ").append(values.size()).append(" 项：")
                .append(succeeded).append(" 项成功、")
                .append(noSample).append(" 项无样本、")
                .append(failed).append(" 项失败");
        if (statStart != null && !statStart.isBlank() && statEnd != null && !statEnd.isBlank()) {
            output.append("；统计区间 ").append(statStart.strip())
                    .append(" 至 ").append(statEnd.strip()).append("（左闭右开）");
        }
        output.append("。\n");

        List<IndicatorExecutionResult> rows = values.stream()
                .filter(IndicatorExecutionResult::ok).toList();
        if (!rows.isEmpty()) {
            output.append("\n| 指标 | 结果值 | 分子/分母 | 目标值 | 达标 | 统计区间 |\n");
            output.append("| --- | --- | --- | --- | --- | --- |\n");
            for (IndicatorExecutionResult row : rows) {
                output.append("| ").append(row.ruleName())
                        .append(" | ").append(formatValue(row))
                        .append(" | ").append(formatFraction(row))
                        .append(" | ").append(formatTarget(row))
                        .append(" | ").append(compliance(row))
                        .append(" | ").append(formatPeriod(row, statStart, statEnd))
                        .append(" |\n");
            }
        }

        List<IndicatorExecutionResult> failures = values.stream()
                .filter(r -> r.status() == Status.FAILED).toList();
        if (!failures.isEmpty()) {
            output.append("\n**失败指标**\n\n");
            for (IndicatorExecutionResult failure : failures) {
                output.append("- ").append(failure.ruleName());
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

    private static String formatValue(IndicatorExecutionResult result) {
        if (result.status() == Status.NO_SAMPLE) {
            return "无样本";
        }
        if (result.resultValue() == null) {
            return DASH;
        }
        String formatted = String.format(Locale.ROOT, "%.2f", result.resultValue());
        return "percent".equalsIgnoreCase(result.unit()) ? formatted + "%" : formatted;
    }

    private static String formatFraction(IndicatorExecutionResult result) {
        if (result.numerator() == null || result.denominator() == null) {
            return DASH;
        }
        return result.numerator() + "/" + result.denominator();
    }

    private static String formatTarget(IndicatorExecutionResult result) {
        return result.targetValue() == null ? DASH : String.valueOf(result.targetValue());
    }

    private static String compliance(IndicatorExecutionResult result) {
        Double value = result.resultValue();
        Double target = toDouble(result.targetValue());
        if (value == null || target == null) {
            return DASH;
        }
        String direction = result.targetDirection() == null ? ">=" : result.targetDirection();
        boolean met = switch (direction) {
            case "<=" -> value <= target;
            case "<" -> value < target;
            case ">" -> value > target;
            default -> value >= target;
        };
        return met ? "达标" : "未达标";
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
