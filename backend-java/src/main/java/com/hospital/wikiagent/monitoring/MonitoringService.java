package com.hospital.wikiagent.monitoring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class MonitoringService {
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final MonitoringRepository repository;
    private final Clock clock;

    @Autowired
    public MonitoringService(MonitoringRepository repository) {
        this(repository, Clock.systemUTC());
    }

    MonitoringService(MonitoringRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Map<String, Object> create(PlanCommand command, String actor) {
        String id = blank(command.planId()) ? "PLAN_" + shortId() : command.planId().strip();
        return repository.createPlan(values(id, command, actor, "enabled"));
    }

    public Map<String, Object> update(String planId, String hospitalId, PlanCommand patch) {
        Map<String, Object> current = repository.plan(planId, hospitalId)
                .orElseThrow(() -> new MonitoringException("MONITOR_NOT_FOUND", "运行计划不存在。", 404));
        PlanCommand merged = new PlanCommand(planId, hospitalId, text(patch.ruleId(), current.get("rule_id")),
                text(patch.planName(), current.get("plan_name")), text(patch.frequency(), current.get("frequency")),
                text(patch.runTime(), current.get("run_time")), number(patch.dayOfMonth(), current.get("day_of_month")),
                text(patch.timezone(), current.get("timezone")), bool(patch.momEnabled(), current.get("mom_enabled")),
                decimal(patch.momThresholdPct(), current.get("mom_threshold_pct")),
                bool(patch.yoyEnabled(), current.get("yoy_enabled")),
                decimal(patch.yoyThresholdPct(), current.get("yoy_threshold_pct")));
        return repository.updatePlan(values(planId, merged, text(null, current.get("created_by")),
                text(null, current.get("status"))));
    }

    public Map<String, Object> status(String planId, String hospitalId, String status) {
        if (!"enabled".equals(status) && !"disabled".equals(status)) {
            throw new IllegalArgumentException("计划状态必须是 enabled 或 disabled。");
        }
        return repository.setPlanStatus(planId, hospitalId, status, now());
    }

    private PlanValues values(String planId, PlanCommand command, String actor, String status) {
        String hospital = required(command.hospitalId(), "hospital_id");
        String rule = required(command.ruleId(), "rule_id");
        String name = required(command.planName(), "plan_name");
        String frequency = required(command.frequency(), "frequency");
        if (!"daily".equals(frequency) && !"monthly".equals(frequency)) {
            throw new IllegalArgumentException("frequency 必须是 daily 或 monthly。");
        }
        String runTime = blank(command.runTime()) ? "02:00" : command.runTime().strip();
        LocalTime parsed;
        try { parsed = LocalTime.parse(runTime, RUN_TIME); }
        catch (DateTimeParseException exception) { throw new IllegalArgumentException("run_time 必须是 HH:mm。"); }
        int day = command.dayOfMonth() == null ? 1 : command.dayOfMonth();
        if (day < 1 || day > 28) throw new IllegalArgumentException("day_of_month 必须在 1 到 28 之间。");
        String timezone = blank(command.timezone()) ? "Asia/Shanghai" : command.timezone().strip();
        ZoneId zone;
        try { zone = ZoneId.of(timezone); }
        catch (Exception exception) { throw new IllegalArgumentException("timezone 不是有效时区。"); }
        double mom = command.momThresholdPct() == null ? 20.0 : command.momThresholdPct();
        double yoy = command.yoyThresholdPct() == null ? 30.0 : command.yoyThresholdPct();
        if (mom <= 0 || yoy <= 0 || mom > 10000 || yoy > 10000) {
            throw new IllegalArgumentException("波动阈值必须大于 0 且不超过 10000。");
        }
        LocalDateTime current = now();
        LocalDateTime next = nextRun(frequency, parsed, day, zone);
        return new PlanValues(planId, hospital, rule, name, frequency, runTime, day, timezone,
                command.momEnabled() == null || command.momEnabled(), mom,
                command.yoyEnabled() == null || command.yoyEnabled(), yoy,
                status, next, blank(actor) ? "admin" : actor.strip(), current);
    }

    private LocalDateTime nextRun(String frequency, LocalTime runTime, int day, ZoneId zone) {
        ZonedDateTime current = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalDate date = current.toLocalDate();
        if ("daily".equals(frequency)) {
            LocalDateTime candidate = LocalDateTime.of(date, runTime);
            return candidate.isAfter(current.toLocalDateTime()) ? candidate : candidate.plusDays(1);
        }
        LocalDate candidateDate = date.withDayOfMonth(day);
        LocalDateTime candidate = LocalDateTime.of(candidateDate, runTime);
        return candidate.isAfter(current.toLocalDateTime()) ? candidate : candidate.plusMonths(1);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " 不能为空。");
        return value.strip();
    }
    private static String text(String patch, Object existing) { return blank(patch) ? String.valueOf(existing) : patch.strip(); }
    private static Integer number(Integer patch, Object existing) { return patch != null ? patch : ((Number) existing).intValue(); }
    private static Double decimal(Double patch, Object existing) { return patch != null ? patch : ((Number) existing).doubleValue(); }
    private static Boolean bool(Boolean patch, Object existing) { return patch != null ? patch : Boolean.parseBoolean(String.valueOf(existing)); }

    public record PlanCommand(String planId, String hospitalId, String ruleId, String planName,
            String frequency, String runTime, Integer dayOfMonth, String timezone,
            Boolean momEnabled, Double momThresholdPct, Boolean yoyEnabled, Double yoyThresholdPct) { }

    public record PlanValues(String planId, String hospitalId, String ruleId, String planName,
            String frequency, String runTime, int dayOfMonth, String timezone,
            boolean momEnabled, double momThresholdPct, boolean yoyEnabled, double yoyThresholdPct,
            String status, LocalDateTime nextRunAt, String createdBy, LocalDateTime now) { }
}
