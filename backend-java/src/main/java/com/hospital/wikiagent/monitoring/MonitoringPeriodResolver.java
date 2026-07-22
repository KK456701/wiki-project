package com.hospital.wikiagent.monitoring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

@Component
/**
 * 解析并规范化 {@code MonitoringPeriodResolver} 对应的业务输入。
 */
public class MonitoringPeriodResolver {
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final Clock clock;

    public MonitoringPeriodResolver() {
        this(Clock.systemUTC());
    }

    MonitoringPeriodResolver(Clock clock) {
        this.clock = clock;
    }

    public Period resolve(String frequency, String raw, String timezone) {
        checkFrequency(frequency);
        ZoneId zone = zone(timezone);
        LocalDateTime start;
        LocalDateTime end;
        if (raw != null && !raw.isBlank()) {
            String[] parts = raw.split("~", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("统计周期必须使用 开始时间~结束时间 格式。");
            }
            start = endpoint(parts[0], false);
            end = endpoint(parts[1], true);
        } else {
            LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDate();
            if ("daily".equals(frequency)) {
                end = today.atStartOfDay();
                start = end.minusDays(1);
            } else {
                end = today.withDayOfMonth(1).atStartOfDay();
                start = end.minusMonths(1);
            }
        }
        if (!start.isBefore(end)) throw new IllegalArgumentException("统计周期结束时间必须晚于开始时间。");
        return new Period(frequency, start, end, zone.getId());
    }

    public Period compare(Period period, String comparison) {
        if ("yoy".equals(comparison)) {
            return new Period(period.frequency(), period.start().minusYears(1), period.end().minusYears(1),
                    period.timezone());
        }
        if (!"mom".equals(comparison)) throw new IllegalArgumentException("不支持的比较类型。");
        if ("monthly".equals(period.frequency()) && period.start().getDayOfMonth() == 1
                && period.end().getDayOfMonth() == 1) {
            return new Period(period.frequency(), period.start().minusMonths(1), period.start(), period.timezone());
        }
        java.time.Duration duration = java.time.Duration.between(period.start(), period.end());
        return new Period(period.frequency(), period.start().minus(duration), period.start(), period.timezone());
    }

    public LocalDateTime nextRun(String frequency, String runTime, int dayOfMonth, String timezone) {
        checkFrequency(frequency);
        ZoneId zone = zone(timezone);
        LocalTime time;
        try { time = LocalTime.parse(runTime, RUN_TIME); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("run_time 必须是 HH:mm。"); }
        LocalDateTime current = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDateTime();
        LocalDateTime candidate = "daily".equals(frequency)
                ? LocalDateTime.of(current.toLocalDate(), time)
                : LocalDateTime.of(current.toLocalDate().withDayOfMonth(dayOfMonth), time);
        return candidate.isAfter(current) ? candidate
                : "daily".equals(frequency) ? candidate.plusDays(1) : candidate.plusMonths(1);
    }

    private static LocalDateTime endpoint(String raw, boolean end) {
        String value = raw.strip();
        try {
            if (value.length() == 10) {
                LocalDateTime parsed = LocalDate.parse(value).atStartOfDay();
                return end ? parsed.plusDays(1) : parsed;
            }
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("统计周期格式无效：" + raw);
        }
    }

    private static ZoneId zone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value); }
        catch (Exception exception) { throw new IllegalArgumentException("无效时区：" + value); }
    }

    private static void checkFrequency(String frequency) {
        if (!"daily".equals(frequency) && !"monthly".equals(frequency)) {
            throw new IllegalArgumentException("不支持的运行频率：" + frequency);
        }
    }

    public record Period(String frequency, LocalDateTime start, LocalDateTime end, String timezone) {
        public String startText() { return start.format(TEXT); }
        public String endText() { return end.format(TEXT); }
        public String label() { return startText() + "~" + endText(); }
    }
}
