package com.hospital.wikiagent.details;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult;
import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;
import com.hospital.wikiagent.agent.mras.MrasDetailContractRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.BatchDetailSnapshotService.MaterializedDetail;

class BatchDetailSnapshotServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T08:00:00Z");

    @TempDir
    Path temp;

    private BatchJobStore batchJobs;
    private BatchDetailSnapshotService service;
    private HospitalPrincipal principal;
    private String batchRunId;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("batch_detail_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        ObjectMapper objectMapper = new ObjectMapper();
        IndicatorDetailRepository repository =
                new IndicatorDetailRepository(jdbc, objectMapper);
        repository.initialize();
        batchJobs = new BatchJobStore(jdbc);
        ReflectionTestUtils.invokeMethod(batchJobs, "initialize");
        DetailProperties properties = new DetailProperties();
        properties.setExportRoot(temp.resolve("exports"));
        properties.setExpireHours(24);
        properties.setMaxRows(100);
        service = new BatchDetailSnapshotService(
                repository,
                batchJobs,
                objectMapper,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "SESSION_test");
        batchRunId = batchJobs.createJob(
                "session", principal.hospitalId(), principal.userId(), "全量",
                1, "2025-01-01", "2025-12-31");
        batchJobs.recordTask(batchRunId, 0, new IndicatorExecutionResult(
                "HXZD-001-001",
                "患者入院48小时内转科的比例",
                Status.SUCCESS,
                52.0,
                13L,
                25L,
                "ratio",
                "percentage",
                "13/25",
                25L,
                5,
                "<=",
                "2025-01-01",
                "2025-12-31",
                null,
                null,
                null,
                1,
                "fresh",
                null,
                null,
                null,
                "success",
                null,
                "hash-001",
                MrasDetailKind.COUNT_RATIO.name(),
                MrasDetailContractRegistry.CONTRACT_VERSION));
    }

    @Test
    void createsOnceThenPagesBothGroupsFromPersistedSnapshotRows() {
        var task = batchJobs.loadTask(
                batchRunId,
                principal.hospitalId(),
                principal.userId(),
                "HXZD-001-001",
                null).orElseThrow();
        AtomicInteger materializations = new AtomicInteger();
        List<Map<String, Object>> rows = rows();

        var denominatorPageTwo = service.loadOrCreate(
                principal,
                task,
                "denominator",
                2,
                20,
                () -> {
                    materializations.incrementAndGet();
                    return new MaterializedDetail(rows, 13, 25, 100, 200);
                });
        var refreshedTask = batchJobs.loadTask(
                batchRunId,
                principal.hospitalId(),
                principal.userId(),
                "HXZD-001-001",
                null).orElseThrow();
        var numeratorPage = service.loadOrCreate(
                principal,
                refreshedTask,
                "numerator",
                1,
                20,
                () -> {
                    materializations.incrementAndGet();
                    throw new AssertionError("快照命中后不应重新查询源库");
                });

        assertThat(materializations).hasValue(1);
        assertThat(denominatorPageTwo.total()).isEqualTo(25);
        assertThat(denominatorPageTwo.rows()).hasSize(5);
        assertThat(denominatorPageTwo.snapshotReused()).isFalse();
        assertThat(numeratorPage.total()).isEqualTo(13);
        assertThat(numeratorPage.rows()).hasSize(13);
        assertThat(numeratorPage.snapshotReused()).isTrue();
        assertThat(refreshedTask.detailSnapshotId()).isEqualTo(numeratorPage.snapshotId());
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 1; index <= 25; index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ENCOUNTER_ID", "E-" + index);
            row.put(MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN, index <= 13 ? 1 : 0);
            rows.add(row);
        }
        return rows;
    }
}
