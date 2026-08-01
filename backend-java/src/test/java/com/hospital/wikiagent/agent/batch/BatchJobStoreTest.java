package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;

/**
 * 使用 H2 内存库验证批量作业持久化：建表、写入、汇总与读取。
 */
class BatchJobStoreTest {
    private JdbcTemplate jdbc;
    private BatchJobStore store;

    @BeforeEach
    void setUp() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("batch_job_" + System.nanoTime())
                .build();
        jdbc = new JdbcTemplate(source);
        store = new BatchJobStore(jdbc);
        store.initialize();
    }

    @Test
    void createsJobAndReturnsId() {
        String jobId = store.createJob(
                "session_1", "hospital_001", "user_1", "计算所有指标的结果",
                3, "2026-01-01 00:00:00", "2026-04-01 00:00:00");

        assertThat(jobId).startsWith("BJOB_");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM med_agent_batch_job WHERE job_id = ?", jobId);
        assertThat(row.get("session_key")).isEqualTo("session_1");
        assertThat(row.get("hospital_id")).isEqualTo("hospital_001");
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(((Number) row.get("total")).intValue()).isEqualTo(3);
    }

    @Test
    void recordsTasksPerIndicator() {
        String jobId = store.createJob(
                "session_1", "hospital_001", "user_1", "q", 2, null, null);
        store.recordTask(jobId, 0, new IndicatorExecutionResult(
                "R1", "指标一", Status.SUCCESS, 92.5, 185L, 200L, "percent",
                95, ">=", "2026-01-01 00:00:00", "2026-04-01 00:00:00", "RUN_1",
                null, null, 10));
        store.recordTask(jobId, 1, IndicatorExecutionResult.failed(
                "R2", "指标二", "TRIAL_RUN_FAILED", "数据库超时。"));

        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT * FROM med_agent_batch_task WHERE job_id = ? ORDER BY position", jobId);
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).get("rule_id")).isEqualTo("R1");
        assertThat(tasks.get(0).get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) tasks.get(0).get("result_value")).doubleValue()).isEqualTo(92.5);
        assertThat(tasks.get(0).get("target_value")).isEqualTo("95");
        assertThat(tasks.get(1).get("status")).isEqualTo("FAILED");
        assertThat(tasks.get(1).get("error_code")).isEqualTo("TRIAL_RUN_FAILED");
    }

    @Test
    void persistsMedianSampleCountForTypedCards() {
        String jobId = store.createJob(
                "session_1", "hospital_001", "user_1", "q", 1, null, null);
        store.recordTask(jobId, 0, new IndicatorExecutionResult(
                "M1", "危急值报告时间", Status.SUCCESS,
                12.5, null, null, "median_duration", "minute",
                "中位数，n=5", 5L, 30, "<=",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00", "RUN_M1",
                null, null, 10, "normal", null, null,
                null, null, null, null, "MEDIAN_SAMPLE", "v1"));

        BatchJobStore.BatchTaskSnapshot task = store.loadTasks(
                jobId, "hospital_001", "user_1").get(0);

        assertThat(task.sampleCount()).isEqualTo(5L);
        assertThat(task.detailKind()).isEqualTo("MEDIAN_SAMPLE");
    }

    @Test
    void finishJobUpdatesSummaryAndTimestamp() {
        String jobId = store.createJob(
                "session_1", "hospital_001", "user_1", "q", 3, null, null);
        store.finishJob(jobId, "PARTIAL_SUCCESS", 2, 0, 1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM med_agent_batch_job WHERE job_id = ?", jobId);
        assertThat(row.get("status")).isEqualTo("PARTIAL_SUCCESS");
        assertThat(((Number) row.get("succeeded")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("failed")).intValue()).isEqualTo(1);
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void initializeIsIdempotent() {
        // 重复建表不应抛异常（CREATE TABLE IF NOT EXISTS）。
        store.initialize();
        store.initialize();

        String jobId = store.createJob("s", "h", "u", "q", 1, null, null);
        assertThat(jobId).isNotBlank();
    }
}
