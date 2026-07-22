package com.hospital.wikiagent.agent.planning;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.RequestPlan.TimeExpression;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;

/**
 * 将“今年一月到现在”等中文表达解析成左闭右开的确定时间区间。
 * 用户原文优先于模型补写日期，避免小模型把当前年份或边界计算错误。
 *
 * <p>解析过程优先使用确定性规则并保留原始输入，无法唯一确定时返回歧义而不是猜测。模型结果只能作为候选，仍需经过类型和业务约束校验。</p>
 */
@Component
public class TimeRangeResolver {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SPACE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, Integer> CHINESE_MONTHS = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("三", 3),
            Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6),
            Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9),
            Map.entry("十", 10), Map.entry("十一", 11), Map.entry("十二", 12));
    private static final Pattern CHINESE_MONTH = Pattern.compile("(十二|十一|十|[一二三四五六七八九])月份?");
    private static final Pattern MONTH_TO_NOW = Pattern.compile(
            "(?:从)?(?:(\\d{2}|\\d{4})年|今年)?(1[0-2]|[1-9])月(?:到现在|至今|开始)");
    private static final Pattern MONTH_RANGE = Pattern.compile(
            "(?:从)?(?:(\\d{2}|\\d{4})年|今年)?(1[0-2]|[1-9])月(?:到|至)"
                    + "(?:(\\d{2}|\\d{4})年)?(1[0-2]|[1-9])月");
    private static final Pattern DATE_TO_NOW = Pattern.compile(
            "从(\\d{2}|\\d{4})年(1[0-2]|[1-9])月(3[01]|[12]\\d|[1-9])(?:日|号)(?:到现在|至今|开始)");
    private static final Pattern CHINESE_DATE_RANGE = Pattern.compile(
            "(\\d{2}|\\d{4})年(1[0-2]|[1-9])月(3[01]|[12]\\d|[1-9])(?:日|号)?(?:到|至)"
                    + "(\\d{2}|\\d{4})年(1[0-2]|[1-9])月(3[01]|[12]\\d|[1-9])(?:日|号)?");
    private static final Pattern ISO_DATE_RANGE = Pattern.compile(
            "(\\d{4}-\\d{1,2}-\\d{1,2})(?:到|至|-)(\\d{4}-\\d{1,2}-\\d{1,2})");

    private final Clock clock;

    @Autowired
    public TimeRangeResolver() {
        this(Clock.system(ZONE));
    }

    TimeRangeResolver(Clock clock) {
        this.clock = clock;
    }

    public ResolvedTimeRange resolve(TimeExpression expression) {
        if (expression == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String raw = normalize(expression.rawText());
        if (SetValues.CURRENT_MONTH.contains(raw)) {
            return valid(monthStart(now.getYear(), now.getMonthValue()), now, expression.rawText());
        }
        if (SetValues.PREVIOUS_MONTH.contains(raw)) {
            LocalDateTime current = monthStart(now.getYear(), now.getMonthValue());
            LocalDate previous = current.toLocalDate().minusDays(1);
            return valid(monthStart(previous.getYear(), previous.getMonthValue()), current, expression.rawText());
        }
        if (SetValues.CURRENT_YEAR.contains(raw)) {
            return valid(LocalDate.of(now.getYear(), 1, 1).atStartOfDay(), now, expression.rawText());
        }

        Matcher toNow = MONTH_TO_NOW.matcher(raw);
        if (toNow.find()) {
            int year = year(toNow.group(1), now.getYear());
            LocalDateTime start = monthStart(year, Integer.parseInt(toNow.group(2)));
            return valid(start, now, expression.rawText());
        }

        Matcher monthRange = MONTH_RANGE.matcher(raw);
        if (monthRange.find()) {
            int startYear = year(monthRange.group(1), now.getYear());
            int startMonth = Integer.parseInt(monthRange.group(2));
            int endYear = monthRange.group(3) == null ? startYear : year(monthRange.group(3), startYear);
            int endMonth = Integer.parseInt(monthRange.group(4));
            if (monthRange.group(3) == null && endMonth < startMonth) {
                endYear++;
            }
            return valid(
                    monthStart(startYear, startMonth),
                    monthStart(endYear, endMonth).plusMonths(1),
                    expression.rawText());
        }

        Matcher dateToNow = DATE_TO_NOW.matcher(raw);
        if (dateToNow.find()) {
            LocalDateTime start = date(
                    year(dateToNow.group(1), now.getYear()),
                    Integer.parseInt(dateToNow.group(2)),
                    Integer.parseInt(dateToNow.group(3)));
            return valid(start, now, expression.rawText());
        }

        Matcher chineseRange = CHINESE_DATE_RANGE.matcher(raw);
        if (chineseRange.find()) {
            LocalDateTime start = date(
                    year(chineseRange.group(1), now.getYear()),
                    Integer.parseInt(chineseRange.group(2)),
                    Integer.parseInt(chineseRange.group(3)));
            LocalDateTime end = date(
                    year(chineseRange.group(4), now.getYear()),
                    Integer.parseInt(chineseRange.group(5)),
                    Integer.parseInt(chineseRange.group(6)));
            return valid(start, end == null ? null : end.plusDays(1), expression.rawText());
        }

        Matcher isoRange = ISO_DATE_RANGE.matcher(raw);
        if (isoRange.find()) {
            try {
                LocalDateTime start = LocalDate.parse(isoRange.group(1)).atStartOfDay();
                LocalDateTime end = LocalDate.parse(isoRange.group(2)).plusDays(1).atStartOfDay();
                return valid(start, end, expression.rawText());
            } catch (DateTimeParseException exception) {
                return null;
            }
        }

        for (String relative : SetValues.ALL) {
            if (raw.contains(relative)) {
                return resolve(new TimeExpression(relative, null, null));
            }
        }
        // 用户原始时间表达是事实来源。模型擅自补出的绝对日期不能覆盖
        // “从一月份到现在”等可由服务端确定性解析的相对时间。
        if (expression.startTime() != null && expression.endTime() != null) {
            LocalDateTime start = parse(expression.startTime());
            LocalDateTime end = parse(expression.endTime());
            return valid(start, end, expression.rawText());
        }
        return null;
    }

    private static String normalize(String value) {
        String raw = value == null ? "" : value.replaceAll("\\s+", "");
        raw = raw.replaceFirst("(?:的)?(?:结果|数据|指标值)$", "");
        if (raw.endsWith("的")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        Matcher matcher = CHINESE_MONTH.matcher(raw);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, CHINESE_MONTHS.get(matcher.group(1)) + "月");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static LocalDateTime parse(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, SPACE_TIME);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDate.parse(value).atStartOfDay();
                } catch (DateTimeParseException invalid) {
                    return null;
                }
            }
        }
    }

    private static int year(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int result = Integer.parseInt(value);
        return result < 100 ? result + 2000 : result;
    }

    private static LocalDateTime monthStart(int year, int month) {
        try {
            return LocalDate.of(year, month, 1).atStartOfDay();
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static LocalDateTime date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day).atStartOfDay();
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static ResolvedTimeRange valid(LocalDateTime start, LocalDateTime end, String raw) {
        return start != null && end != null && start.isBefore(end)
                ? new ResolvedTimeRange(start, end, raw)
                : null;
    }

    private static final class SetValues {
        private static final java.util.Set<String> CURRENT_MONTH = java.util.Set.of("本月", "这个月", "当月");
        private static final java.util.Set<String> PREVIOUS_MONTH = java.util.Set.of("上月", "上个月");
        private static final java.util.Set<String> CURRENT_YEAR = java.util.Set.of("今年", "今年至今", "本年至今");
        private static final java.util.List<String> ALL = java.util.List.of(
                "今年至今", "本年至今", "这个月", "上个月", "本月", "当月", "上月", "今年");

        private SetValues() {
        }
    }
}
