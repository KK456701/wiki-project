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

    private static MonitoringException notFound(String message) {
        return new MonitoringException("MONITOR_NOT_FOUND", message, 404);
    }
}
