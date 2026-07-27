package com.hospital.wikiagent.agent.batch;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 批量指标计算的轻量持久化：记录整批作业与逐指标任务，供审计与后续扩展。
 *
 * <p>完全复刻 {@code AgentConversationMemory} 的建表模式：JdbcTemplate + @PostConstruct，
 * 建表失败仅告警、不阻断启动。持久化是旁路能力，任何写入异常都不应影响最终回答，调用方
 * 需自行包裹 try/catch。</p>
 */
@Component
public class BatchJobStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchJobStore.class);

    private final JdbcTemplate jdbc;

    public BatchJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_batch_job (
                      job_id VARCHAR(64) PRIMARY KEY,
                      session_key VARCHAR(512) NOT NULL,
                      hospital_id VARCHAR(128) NOT NULL,
                      user_id VARCHAR(128),
                      query TEXT,
                      status VARCHAR(32) NOT NULL,
                      total INT NOT NULL,
                      succeeded INT NOT NULL,
                      no_sample INT NOT NULL,
                      failed INT NOT NULL,
                      stat_start VARCHAR(40),
                      stat_end VARCHAR(40),
                      trace_id VARCHAR(80),
                      created_at VARCHAR(40) NOT NULL,
                      finished_at VARCHAR(40)
                    )
                    """);
            ensureJobColumn("trace_id", "VARCHAR(80)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_batch_task (
                      job_id VARCHAR(64) NOT NULL,
                      position INT NOT NULL,
                      rule_id VARCHAR(128),
                      rule_name VARCHAR(255),
                      profile_id VARCHAR(128),
                      profile_name VARCHAR(255),
                      event_no VARCHAR(128),
                      extraction_id VARCHAR(128),
                      snapshot_status VARCHAR(64),
                      status VARCHAR(32) NOT NULL,
                      result_value DOUBLE,
                      numerator_count BIGINT,
                      denominator_count BIGINT,
                      target_value VARCHAR(128),
                      run_id VARCHAR(80),
                      error_code VARCHAR(64),
                      error_message VARCHAR(512),
                      created_at VARCHAR(40) NOT NULL,
                      PRIMARY KEY (job_id, position)
                    )
                    """);
            ensureTaskColumn("profile_id", "VARCHAR(128)");
            ensureTaskColumn("profile_name", "VARCHAR(255)");
            ensureTaskColumn("event_no", "VARCHAR(128)");
            ensureTaskColumn("extraction_id", "VARCHAR(128)");
            ensureTaskColumn("snapshot_status", "VARCHAR(64)");
        } catch (Exception exception) {
            LOGGER.warn("Unable to initialize batch job tables; batch persistence disabled: {}",
                    exception.getMessage());
        }
    }

    /**
     * 创建一条批量作业记录并返回作业 ID。
     */
    public String createJob(
            String sessionKey,
            String hospitalId,
            String userId,
            String query,
            int total,
            String statStart,
            String statEnd) {
        return createJob(
                sessionKey, hospitalId, userId, query, total, statStart, statEnd, null);
    }

    public String createJob(
            String sessionKey,
            String hospitalId,
            String userId,
            String query,
            int total,
            String statStart,
            String statEnd,
            String traceId) {
        String jobId = "BJOB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("""
                INSERT INTO med_agent_batch_job
                  (job_id, session_key, hospital_id, user_id, query, status,
                   total, succeeded, no_sample, failed, stat_start, stat_end,
                   trace_id, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId, sessionKey, hospitalId, userId, query, "RUNNING",
                total, 0, 0, 0, statStart, statEnd,
                traceId, Instant.now().toString(), null);
        return jobId;
    }

    /**
     * 记录单个指标任务的执行结果。
     */
    public void recordTask(String jobId, int position, IndicatorExecutionResult result) {
        jdbc.update("""
                INSERT INTO med_agent_batch_task
                  (job_id, position, rule_id, rule_name, profile_id, profile_name,
                   event_no, extraction_id, snapshot_status, status, result_value,
                   numerator_count, denominator_count, target_value, run_id,
                   error_code, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId, position, result.ruleId(), result.ruleName(),
                result.profileId(), result.profileName(), result.eventNo(),
                result.extractionId(), result.extractionStatus(), result.status().name(),
                result.resultValue(), result.numerator(), result.denominator(),
                result.targetValue() == null ? null : String.valueOf(result.targetValue()),
                result.runId(), result.errorCode(), result.errorMessage(),
                Instant.now().toString());
    }

    /**
     * 汇总并收尾批量作业。
     */
    public void finishJob(
            String jobId, String status, int succeeded, int noSample, int failed) {
        jdbc.update("""
                UPDATE med_agent_batch_job
                SET status = ?, succeeded = ?, no_sample = ?, failed = ?, finished_at = ?
                WHERE job_id = ?
                """,
                status, succeeded, noSample, failed, Instant.now().toString(), jobId);
    }

    private void ensureJobColumn(String name, String type) {
        ensureColumn("med_agent_batch_job", name, type);
    }

    private void ensureTaskColumn(String name, String type) {
        ensureColumn("med_agent_batch_task", name, type);
    }

    private void ensureColumn(String table, String name, String type) {
        Boolean found = jdbc.query(
                "SELECT * FROM " + table + " WHERE 1 = 0",
                result -> {
                    java.sql.ResultSetMetaData metadata = result.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        if (name.equalsIgnoreCase(metadata.getColumnName(index))) {
                            return true;
                        }
                    }
                    return false;
                });
        if (!Boolean.TRUE.equals(found)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + type);
        }
    }
}
