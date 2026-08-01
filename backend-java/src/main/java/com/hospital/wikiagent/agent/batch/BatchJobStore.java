package com.hospital.wikiagent.agent.batch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            ensureTaskColumn("extraction_id", "VARCHAR(80)");
            ensureTaskColumn("snapshot_status", "VARCHAR(64)");
            ensureTaskColumn("stat_start", "VARCHAR(40)");
            ensureTaskColumn("stat_end", "VARCHAR(40)");
            ensureTaskColumn("unit", "VARCHAR(40)");
            ensureTaskColumn("target_direction", "VARCHAR(16)");
            ensureTaskColumn("quality_status", "VARCHAR(64)");
            ensureTaskColumn("data_freshness", "VARCHAR(64)");
            ensureTaskColumn("overview_sql_hash", "VARCHAR(64)");
            ensureTaskColumn("knowledge_release_id", "VARCHAR(128)");
            ensureTaskColumn("detail_kind", "VARCHAR(64)");
            ensureTaskColumn("detail_contract_version", "VARCHAR(64)");
            ensureTaskColumn("detail_snapshot_id", "VARCHAR(64)");
            ensureTaskColumn("calculation_display", "VARCHAR(512)");
            ensureTaskColumn("sample_count", "BIGINT");
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
        recordTask(jobId, position, result,
                result.status() == IndicatorExecutionResult.Status.SUCCESS
                        ? "NORMAL" : "ABNORMAL");
    }

    public void recordTask(
            String jobId, int position, IndicatorExecutionResult result, String qualityStatus) {
        jdbc.update("""
                INSERT INTO med_agent_batch_task
                  (job_id, position, rule_id, rule_name, status, result_value,
                   numerator_count, denominator_count, target_value, run_id,
                   error_code, error_message, created_at, profile_id, profile_name,
                   event_no, extraction_id, snapshot_status, stat_start, stat_end,
                   unit, target_direction, quality_status, overview_sql_hash,
                   detail_kind, detail_contract_version, calculation_display,
                   sample_count, data_freshness)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId, position, result.ruleId(), result.ruleName(), result.status().name(),
                result.resultValue(), result.numerator(), result.denominator(),
                result.targetValue() == null ? null : String.valueOf(result.targetValue()),
                result.runId(), result.errorCode(), result.errorMessage(),
                Instant.now().toString(),
                result.profileId(), result.profileLabel(), result.eventNo(),
                result.extractionId(), result.extractionStatus(),
                result.statStart(), result.statEnd(), result.unit(), result.targetDirection(),
                qualityStatus, result.overviewSqlHash(), result.detailKind(),
                result.detailContractVersion(), result.calculationDisplay(),
                result.sampleCount(), result.dataFreshness());
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

    public Optional<BatchJobSnapshot> loadJob(
            String jobId, String hospitalId, String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM med_agent_batch_job
                WHERE job_id=? AND hospital_id=? AND user_id=?
                """, jobId, hospitalId, userId);
        return rows.stream().findFirst().map(BatchJobStore::job);
    }

    public List<BatchTaskSnapshot> loadTasks(
            String jobId, String hospitalId, String userId) {
        return jdbc.queryForList("""
                SELECT task.*
                FROM med_agent_batch_task task
                JOIN med_agent_batch_job job ON job.job_id=task.job_id
                WHERE task.job_id=? AND job.hospital_id=? AND job.user_id=?
                ORDER BY task.position
                """, jobId, hospitalId, userId).stream()
                .map(BatchJobStore::task)
                .toList();
    }

    public Optional<BatchTaskSnapshot> loadTask(
            String jobId,
            String hospitalId,
            String userId,
            String ruleId,
            String profileId) {
        String normalizedProfile = blankToNull(profileId);
        return jdbc.queryForList("""
                SELECT task.*
                FROM med_agent_batch_task task
                JOIN med_agent_batch_job job ON job.job_id=task.job_id
                WHERE task.job_id=? AND job.hospital_id=? AND job.user_id=?
                  AND task.rule_id=?
                  AND ((task.profile_id IS NULL AND ? IS NULL) OR task.profile_id=?)
                ORDER BY task.position
                LIMIT 1
                """, jobId, hospitalId, userId, ruleId, normalizedProfile, normalizedProfile)
                .stream().findFirst().map(BatchJobStore::task);
    }

    public void bindDetailSnapshot(
            String jobId, int position, String snapshotId) {
        jdbc.update("""
                UPDATE med_agent_batch_task
                SET detail_snapshot_id=?
                WHERE job_id=? AND position=?
                """, snapshotId, jobId, position);
    }

    private static BatchJobSnapshot job(Map<String, Object> row) {
        return new BatchJobSnapshot(
                text(row.get("job_id")), text(row.get("hospital_id")),
                text(row.get("user_id")), text(row.get("status")),
                integer(row.get("total")), integer(row.get("succeeded")),
                integer(row.get("no_sample")), integer(row.get("failed")),
                text(row.get("stat_start")), text(row.get("stat_end")),
                text(row.get("trace_id")), text(row.get("created_at")),
                text(row.get("finished_at")));
    }

    private static BatchTaskSnapshot task(Map<String, Object> row) {
        return new BatchTaskSnapshot(
                text(row.get("job_id")), integer(row.get("position")),
                text(row.get("rule_id")), text(row.get("rule_name")),
                nullableText(row.get("profile_id")), nullableText(row.get("profile_name")),
                text(row.get("status")), decimal(row.get("result_value")),
                longValue(row.get("numerator_count")), longValue(row.get("denominator_count")),
                longValue(row.get("sample_count")),
                nullableText(row.get("unit")), nullableText(row.get("target_value")),
                nullableText(row.get("target_direction")), nullableText(row.get("quality_status")),
                nullableText(row.get("data_freshness")),
                nullableText(row.get("stat_start")), nullableText(row.get("stat_end")),
                nullableText(row.get("overview_sql_hash")), nullableText(row.get("detail_kind")),
                nullableText(row.get("detail_contract_version")),
                nullableText(row.get("detail_snapshot_id")),
                nullableText(row.get("calculation_display")),
                nullableText(row.get("error_code")), nullableText(row.get("error_message")));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String nullableText(Object value) {
        String valueText = text(value);
        return valueText.isBlank() ? null : valueText;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public record BatchJobSnapshot(
            String batchRunId,
            String hospitalId,
            String userId,
            String status,
            int total,
            int succeeded,
            int noSample,
            int failed,
            String statStart,
            String statEnd,
            String traceId,
            String createdAt,
            String finishedAt) {
    }

    public record BatchTaskSnapshot(
            String batchRunId,
            int position,
            String ruleId,
            String ruleName,
            String profileId,
            String profileName,
            String status,
            Double resultValue,
            Long numeratorCount,
            Long denominatorCount,
            Long sampleCount,
            String unit,
            String targetValue,
            String targetDirection,
            String qualityStatus,
            String dataFreshness,
            String statStart,
            String statEnd,
            String overviewSqlHash,
            String detailKind,
            String detailContractVersion,
            String detailSnapshotId,
            String calculationDisplay,
            String errorCode,
            String errorMessage) {
        /** 兼容尚未持久化 data_freshness 的既有测试与调用。 */
        public BatchTaskSnapshot(
                String batchRunId, int position, String ruleId, String ruleName,
                String profileId, String profileName, String status, Double resultValue,
                Long numeratorCount, Long denominatorCount, Long sampleCount, String unit,
                String targetValue, String targetDirection, String qualityStatus,
                String statStart, String statEnd, String overviewSqlHash, String detailKind,
                String detailContractVersion, String detailSnapshotId,
                String calculationDisplay, String errorCode, String errorMessage) {
            this(batchRunId, position, ruleId, ruleName, profileId, profileName, status,
                    resultValue, numeratorCount, denominatorCount, sampleCount, unit,
                    targetValue, targetDirection, qualityStatus, null, statStart, statEnd,
                    overviewSqlHash, detailKind, detailContractVersion, detailSnapshotId,
                    calculationDisplay, errorCode, errorMessage);
        }
    }

    private void ensureJobColumn(String name, String type) {
        Boolean found = jdbc.query(
                "SELECT * FROM med_agent_batch_job WHERE 1 = 0",
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
            jdbc.execute("ALTER TABLE med_agent_batch_job ADD COLUMN " + name + " " + type);
        }
    }

    private void ensureTaskColumn(String name, String type) {
        Boolean found = jdbc.query(
                "SELECT * FROM med_agent_batch_task WHERE 1 = 0",
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
            jdbc.execute("ALTER TABLE med_agent_batch_task ADD COLUMN " + name + " " + type);
        }
    }
}
