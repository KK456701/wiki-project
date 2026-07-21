package com.hospital.wikiagent.monitoring;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoringRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Map<String, Object>> mapper = this::mapRow;

    public MonitoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listPlans(String hospitalId) {
        return jdbc.query("SELECT * FROM med_indicator_run_plan WHERE hospital_id=? ORDER BY id", mapper, hospitalId);
    }

    public Optional<Map<String, Object>> plan(String planId, String hospitalId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM med_indicator_run_plan WHERE plan_id=? AND hospital_id=?", mapper, planId, hospitalId);
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> plan(String planId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM med_indicator_run_plan WHERE plan_id=?", mapper, planId);
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> duePlans(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM med_indicator_run_plan
                WHERE status='enabled' AND next_run_at IS NOT NULL AND next_run_at<=?
                ORDER BY id
                """, mapper, now);
    }

    public boolean acquireLease(String planId, String workerId, LocalDateTime now, LocalDateTime lockedUntil) {
        return jdbc.update("""
                UPDATE med_indicator_run_plan SET locked_until=?,locked_by=?,updated_at=?
                WHERE plan_id=? AND status='enabled' AND (locked_until IS NULL OR locked_until<=?)
                """, lockedUntil, workerId, now, planId, now) == 1;
    }

    public void releaseLease(String planId, String workerId, LocalDateTime now, LocalDateTime nextRunAt) {
        jdbc.update("""
                UPDATE med_indicator_run_plan SET locked_until=NULL,locked_by='',last_run_at=?,next_run_at=?,updated_at=?
                WHERE plan_id=? AND locked_by=?
                """, now, nextRunAt, now, planId, workerId);
    }

    public Map<String, Object> createPlan(MonitoringService.PlanValues value) {
        try {
            jdbc.update("""
                    INSERT INTO med_indicator_run_plan
                    (plan_id,hospital_id,rule_id,plan_name,frequency,run_time,day_of_month,timezone,
                     mom_enabled,mom_threshold_pct,yoy_enabled,yoy_threshold_pct,status,next_run_at,
                     locked_by,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.planId(), value.hospitalId(), value.ruleId(), value.planName(), value.frequency(),
                    value.runTime(), value.dayOfMonth(), value.timezone(), value.momEnabled(), value.momThresholdPct(),
                    value.yoyEnabled(), value.yoyThresholdPct(), value.status(), value.nextRunAt(), "",
                    value.createdBy(), value.now(), value.now());
        } catch (DuplicateKeyException exception) {
            throw new MonitoringException("MONITOR_PLAN_DUPLICATE", "同一医院、指标和名称的监控计划已存在。", 409);
        }
        return requirePlan(value.planId(), value.hospitalId());
    }

    public Map<String, Object> updatePlan(MonitoringService.PlanValues value) {
        int changed = jdbc.update("""
                UPDATE med_indicator_run_plan SET plan_name=?,frequency=?,run_time=?,day_of_month=?,timezone=?,
                mom_enabled=?,mom_threshold_pct=?,yoy_enabled=?,yoy_threshold_pct=?,next_run_at=?,updated_at=?
                WHERE plan_id=? AND hospital_id=?
                """, value.planName(), value.frequency(), value.runTime(), value.dayOfMonth(), value.timezone(),
                value.momEnabled(), value.momThresholdPct(), value.yoyEnabled(), value.yoyThresholdPct(),
                value.nextRunAt(), value.now(), value.planId(), value.hospitalId());
        if (changed != 1) throw notFound("运行计划不存在。");
        return requirePlan(value.planId(), value.hospitalId());
    }

    public Map<String, Object> setPlanStatus(String planId, String hospitalId, String status, LocalDateTime now) {
        int changed = jdbc.update("UPDATE med_indicator_run_plan SET status=?,updated_at=? WHERE plan_id=? AND hospital_id=?",
                status, now, planId, hospitalId);
        if (changed != 1) throw notFound("运行计划不存在。");
        return requirePlan(planId, hospitalId);
    }

    public List<Map<String, Object>> listResults(String hospitalId, String ruleId, int limit) {
        if (ruleId == null || ruleId.isBlank()) {
            return jdbc.query("SELECT * FROM med_index_run_result WHERE hospital_id=? ORDER BY id DESC LIMIT ?",
                    mapper, hospitalId, limit);
        }
        return jdbc.query("SELECT * FROM med_index_run_result WHERE hospital_id=? AND rule_id=? ORDER BY id DESC LIMIT ?",
                mapper, hospitalId, ruleId.strip(), limit);
    }

    public Optional<Map<String, Object>> resultByRunKey(String runKey) {
        return jdbc.query("SELECT * FROM med_index_run_result WHERE run_key=?", mapper, runKey)
                .stream().findFirst();
    }

    public Optional<Map<String, Object>> successfulResult(
            String hospitalId, String ruleId, LocalDateTime start, LocalDateTime end) {
        return jdbc.query("""
                SELECT * FROM med_index_run_result
                WHERE hospital_id=? AND rule_id=? AND stat_start_time=? AND stat_end_time=?
                  AND run_status='success' ORDER BY id DESC LIMIT 1
                """, mapper, hospitalId, ruleId, start, end).stream().findFirst();
    }

    public Map<String, Object> createRunResult(Map<String, Object> value) {
        String runKey = String.valueOf(value.get("run_key"));
        try {
            jdbc.update("""
                    INSERT INTO med_index_run_result
                    (hospital_id,rule_id,stat_period,result_value,previous_value,change_rate,is_abnormal,
                     run_id,created_at,plan_id,run_key,retry_of_result_id,trigger_type,stat_start_time,
                     stat_end_time,run_status,no_sample,effective_level,national_version,hospital_version,
                     data_source,duration_ms,error_code,error_message,wave_status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.get("hospital_id"), value.get("rule_id"), value.get("stat_period"),
                    value.get("result_value"), value.get("previous_value"), value.get("change_rate"),
                    boolValue(value.get("is_abnormal")), value.get("run_id"), value.get("created_at"),
                    value.get("plan_id"), runKey, value.get("retry_of_result_id"), value.get("trigger_type"),
                    value.get("stat_start_time"), value.get("stat_end_time"), value.get("run_status"),
                    boolValue(value.get("no_sample")), value.get("effective_level"), value.get("national_version"),
                    value.get("hospital_version"), value.get("data_source"), value.get("duration_ms"),
                    value.get("error_code"), value.get("error_message"),
                    value.getOrDefault("wave_status", "baseline_insufficient"));
        } catch (DuplicateKeyException exception) {
            return resultByRunKey(runKey).orElseThrow();
        }
        return resultByRunKey(runKey).orElseThrow();
    }

    public Map<String, Object> updateWave(long resultId, Map<String, Object> wave) {
        jdbc.update("""
                UPDATE med_index_run_result SET previous_value=?,change_rate=?,mom_baseline_result_id=?,
                  mom_change_rate=?,yoy_baseline_result_id=?,yoy_change_rate=?,wave_status=?,is_abnormal=?
                WHERE id=?
                """, wave.get("previous_value"), wave.get("mom_change_rate"),
                wave.get("mom_baseline_result_id"), wave.get("mom_change_rate"),
                wave.get("yoy_baseline_result_id"), wave.get("yoy_change_rate"),
                wave.get("wave_status"), boolValue(wave.get("is_abnormal")), resultId);
        return jdbc.query("SELECT * FROM med_index_run_result WHERE id=?", mapper, resultId)
                .stream().findFirst().orElseThrow();
    }

    public Map<String, Object> createAlert(Map<String, Object> value) {
        List<Map<String, Object>> existing = jdbc.query("""
                SELECT * FROM med_indicator_alert
                WHERE result_id=? AND alert_type=? AND conclusion_code=?
                """, mapper, value.get("result_id"), value.get("alert_type"), value.get("conclusion_code"));
        if (!existing.isEmpty()) return existing.get(0);
        String alertId = "ALERT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        try {
            jdbc.update("""
                    INSERT INTO med_indicator_alert
                    (alert_id,hospital_id,rule_id,plan_id,result_id,alert_type,alert_level,conclusion_code,
                     current_value,mom_value,mom_change_rate,yoy_value,yoy_change_rate,diagnose_status,
                     diagnose_report_id,status,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, alertId, value.get("hospital_id"), value.get("rule_id"), value.get("plan_id"),
                    value.get("result_id"), value.get("alert_type"), value.get("alert_level"),
                    value.get("conclusion_code"), value.get("current_value"), value.get("mom_value"),
                    value.get("mom_change_rate"), value.get("yoy_value"), value.get("yoy_change_rate"),
                    value.getOrDefault("diagnose_status", "pending"), value.get("diagnose_report_id"),
                    value.getOrDefault("status", "open"), now, now);
        } catch (DuplicateKeyException exception) {
            return jdbc.query("""
                    SELECT * FROM med_indicator_alert
                    WHERE result_id=? AND alert_type=? AND conclusion_code=?
                    """, mapper, value.get("result_id"), value.get("alert_type"), value.get("conclusion_code"))
                    .stream().findFirst().orElseThrow();
        }
        return jdbc.query("SELECT * FROM med_indicator_alert WHERE alert_id=?", mapper, alertId)
                .stream().findFirst().orElseThrow();
    }

    public Optional<Map<String, Object>> alert(String alertId, String hospitalId) {
        return jdbc.query("SELECT * FROM med_indicator_alert WHERE alert_id=? AND hospital_id=?",
                mapper, alertId, hospitalId).stream().findFirst();
    }

    public Map<String, Object> updateAlertDiagnosis(
            String alertId, String hospitalId, String status, String reportId, LocalDateTime now) {
        int changed = jdbc.update("""
                UPDATE med_indicator_alert SET diagnose_status=?,diagnose_report_id=?,updated_at=?
                WHERE alert_id=? AND hospital_id=?
                """, status, reportId, now, alertId, hospitalId);
        if (changed != 1) throw notFound("指标预警不存在。");
        return alert(alertId, hospitalId).orElseThrow();
    }

    public Map<String, Object> result(long resultId, String hospitalId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM med_index_run_result WHERE id=? AND hospital_id=?", mapper, resultId, hospitalId);
        return rows.stream().findFirst().orElseThrow(() -> notFound("运行结果不存在。"));
    }

    public List<Map<String, Object>> listAlerts(String hospitalId, String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM med_indicator_alert WHERE hospital_id=? ORDER BY id DESC LIMIT ?",
                    mapper, hospitalId, limit);
        }
        return jdbc.query("SELECT * FROM med_indicator_alert WHERE hospital_id=? AND status=? ORDER BY id DESC LIMIT ?",
                mapper, hospitalId, status.strip(), limit);
    }

    public Map<String, Object> updateAlert(
            String alertId, String hospitalId, String status, String actorId, LocalDateTime now) {
        int changed;
        if ("acknowledged".equals(status)) {
            changed = jdbc.update("""
                    UPDATE med_indicator_alert SET status='acknowledged',acknowledged_by=?,acknowledged_at=?,updated_at=?
                    WHERE alert_id=? AND hospital_id=?
                    """, actorId, now, now, alertId, hospitalId);
        } else {
            changed = jdbc.update("""
                    UPDATE med_indicator_alert SET status='closed',closed_at=?,updated_at=?
                    WHERE alert_id=? AND hospital_id=?
                    """, now, now, alertId, hospitalId);
        }
        if (changed != 1) throw notFound("指标预警不存在。");
        return jdbc.query("SELECT * FROM med_indicator_alert WHERE alert_id=? AND hospital_id=?",
                mapper, alertId, hospitalId).stream().findFirst().orElseThrow();
    }

    private Map<String, Object> requirePlan(String planId, String hospitalId) {
        return plan(planId, hospitalId).orElseThrow(() -> notFound("运行计划不存在。"));
    }

    private Map<String, Object> mapRow(ResultSet result, int ignored) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String key = metadata.getColumnLabel(index).toLowerCase(Locale.ROOT);
            Object value = result.getObject(index);
            if (("mom_enabled".equals(key) || "yoy_enabled".equals(key)
                    || "is_abnormal".equals(key) || "no_sample".equals(key)) && value instanceof Number number) {
                value = number.intValue() != 0;
            }
            row.put(key, value);
        }
        return row;
    }

    private static int boolValue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0) ? 1 : 0;
    }

    private static MonitoringException notFound(String message) {
        return new MonitoringException("MONITOR_NOT_FOUND", message, 404);
    }
}
