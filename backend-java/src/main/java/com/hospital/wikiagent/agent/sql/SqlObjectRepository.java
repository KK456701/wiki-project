package com.hospital.wikiagent.agent.sql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SqlObjectRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqlObjectRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(PreparedSqlObject value) {
        jdbc.update(
                "INSERT INTO med_generated_sql "
                        + "(sql_id,hospital_id,rule_id,dialect,sql_text,sql_status,validation_message,generated_by,generated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                value.sqlId(), value.hospitalId(), value.ruleId(), value.dialect(), value.sqlText(),
                value.validationStatus(), value.validationMessage(), value.userId(), Timestamp.from(value.createdAt()));
        jdbc.update(
                "INSERT INTO med_agent_sql_object "
                        + "(sql_id,hospital_id,user_id,session_id,rule_id,dialect,sql_text,params_json,stat_start,stat_end,"
                        + "context_snapshot_json,context_digest,validation_status,validation_message,created_at,expires_at,db_source_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.sqlId(), value.hospitalId(), value.userId(), value.sessionId(), value.ruleId(),
                value.dialect(), value.sqlText(), json(value.params()), value.statStart(), value.statEnd(),
                json(value.contextSnapshot()), value.contextDigest(), value.validationStatus(),
                value.validationMessage(), value.createdAt().toString(), value.expiresAt().toString(), value.dbSourceId());
    }

    public void saveGeneratedDraft(
            String sqlId, String hospitalId, String ruleId, String dialect, String sqlText,
            String validationMessage, String generatedBy) {
        jdbc.update(
                "INSERT INTO med_generated_sql "
                        + "(sql_id,hospital_id,rule_id,dialect,sql_text,sql_status,validation_message,generated_by,generated_at) "
                        + "VALUES (?,?,?,?,?,'validated',?,?,?)",
                sqlId, hospitalId, ruleId, dialect, sqlText, validationMessage, generatedBy,
                Timestamp.from(Instant.now()));
    }

    public void saveDraftRun(
            String runId, String sqlId, String hospitalId, String ruleId, String statStart,
            String statEnd, String status, Number resultValue, Long numerator, Long denominator,
            String errorMessage, long durationMs, String runBy, Map<String, Object> runContext) {
        jdbc.update(
                "INSERT INTO med_sql_run_log "
                        + "(run_id,sql_id,hospital_id,rule_id,stat_start_time,stat_end_time,run_status,result_value,"
                        + "error_message,duration_ms,run_by,numerator_count,denominator_count,run_context_json,run_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, sqlId, hospitalId, ruleId, statStart, statEnd, status, resultValue,
                errorMessage == null ? "" : errorMessage, durationMs, runBy, numerator, denominator,
                json(runContext), Timestamp.from(Instant.now()));
    }

    public PreparedSqlObject loadForExecution(String sqlId, AgentRuntimeContext context, Instant now) {
        List<PreparedSqlObject> values = jdbc.query(
                "SELECT * FROM med_agent_sql_object WHERE sql_id=?",
                (rs, rowNum) -> new PreparedSqlObject(
                        rs.getString("sql_id"), rs.getString("hospital_id"), rs.getString("user_id"),
                        rs.getString("session_id"), rs.getString("rule_id"), rs.getString("dialect"),
                        rs.getString("sql_text"), map(rs.getString("params_json")), rs.getString("stat_start"),
                        rs.getString("stat_end"), map(rs.getString("context_snapshot_json")),
                        rs.getString("context_digest"), rs.getString("validation_status"),
                        rs.getString("validation_message"), Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("expires_at")), rs.getString("db_source_id")),
                sqlId);
        if (values.isEmpty()) {
            throw new SqlObjectAccessException("SQL_OBJECT_NOT_FOUND", "SQL 对象不存在，请重新准备。");
        }
        PreparedSqlObject value = values.get(0);
        if (!value.hospitalId().equals(context.hospitalId())) {
            throw new SqlObjectAccessException("SQL_OBJECT_TENANT_MISMATCH", "不能访问其他医院的 SQL 对象。");
        }
        if (!value.userId().equals(context.userId())) {
            throw new SqlObjectAccessException("SQL_OBJECT_OWNER_MISMATCH", "不能访问其他用户的 SQL 对象。");
        }
        if (!value.sessionId().equals(context.sessionId())) {
            throw new SqlObjectAccessException("SQL_OBJECT_SESSION_MISMATCH", "SQL 对象不属于当前登录会话。");
        }
        if (value.expiresAt().isBefore(now)) {
            throw new SqlObjectAccessException("SQL_OBJECT_EXPIRED", "SQL 对象已过期，请重新准备。");
        }
        if (!"validated".equals(value.validationStatus())) {
            throw new SqlObjectAccessException("SQL_OBJECT_NOT_VALIDATED", "SQL 对象未通过安全校验。");
        }
        if (context.dbSourceId() != null && !context.dbSourceId().isBlank()
                && value.dbSourceId() != null && !context.dbSourceId().equals(value.dbSourceId())) {
            throw new SqlObjectAccessException("SQL_OBJECT_SOURCE_MISMATCH", "SQL 对象不属于当前业务数据源。");
        }
        return value;
    }

    public void saveRun(
            String runId,
            PreparedSqlObject sql,
            String status,
            Number resultValue,
            Long numerator,
            Long denominator,
            String errorMessage,
            long durationMs,
            String runBy,
            Map<String, Object> runContext) {
        jdbc.update(
                "INSERT INTO med_sql_run_log "
                        + "(run_id,sql_id,hospital_id,rule_id,stat_start_time,stat_end_time,run_status,result_value,"
                        + "error_message,duration_ms,run_by,numerator_count,denominator_count,run_context_json,run_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, sql.sqlId(), sql.hospitalId(), sql.ruleId(), sql.statStart(), sql.statEnd(), status,
                resultValue, errorMessage == null ? "" : errorMessage, durationMs, runBy, numerator, denominator,
                json(runContext), Timestamp.from(Instant.now()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SQL 对象序列化失败", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            return new LinkedHashMap<>(objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception exception) {
            throw new SqlObjectAccessException("SQL_OBJECT_CORRUPTED", "SQL 对象内容损坏，请重新准备。");
        }
    }
}
