package com.hospital.wikiagent.details;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;

import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.auth.HospitalAuthRepository;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.upload.UploadProperties;
import com.hospital.wikiagent.upload.UploadStorage.StoredUpload;
import com.hospital.wikiagent.upload.UploadStorage;
import com.hospital.wikiagent.upload.XlsxWorkbookReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

class IndicatorDetailServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @TempDir
    Path temp;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private IndicatorDetailRepository repository;
    private StubBusinessQuery businessQuery;
    private IndicatorDetailService service;
    private HospitalPrincipal principal;

    @BeforeEach
    void setUp() throws Exception {
        var database = new EmbeddedDatabaseBuilder()
                .setName("indicator_details_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        repository = new IndicatorDetailRepository(jdbc, objectMapper);
        repository.initialize();
        businessQuery = new StubBusinessQuery(List.of(
                Map.of(
                        "CONSULT_ID", "CONSULT-001",
                        "PATIENT_ID", "1234567890",
                        "REQUEST_TIME", "2026-01-02 08:00:00",
                        "ARRIVE_TIME", "2026-01-02 08:10:00",
                        "ARRIVE_MINUTES", 10,
                        "__MEETS_NUMERATOR", 1,
                        "__EVIDENCE_ROW_COUNT", 1),
                Map.of(
                        "CONSULT_ID", "CONSULT-002",
                        "PATIENT_ID", "9876543210",
                        "REQUEST_TIME", "2026-01-03 08:00:00",
                        "ARRIVE_TIME", "2026-01-03 08:30:00",
                        "ARRIVE_MINUTES", 30,
                        "__MEETS_NUMERATOR", 0,
                        "__EVIDENCE_ROW_COUNT", 1)));
        DetailProperties properties = new DetailProperties();
        properties.setExportRoot(temp.resolve("exports"));
        properties.setExpireHours(24);
        properties.setMaxRows(100);
        properties.setDefaultPageSize(50);
        service = new IndicatorDetailService(
                repository,
                new DetailQueryBuilder(),
                new SqlParameterBinder(),
                new ReadOnlySqlValidator(),
                businessQuery,
                new XlsxWorkbookWriter(),
                objectMapper,
                new HospitalAuthRepository(jdbc),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001",
                Set.of("indicator_detail_view", "indicator_detail_export"),
                false, "SESSION_test");
        seedSuccessfulRun("RUN_001", 1, 2);
    }

    @Test
    void createsReusesAndPagesCountVerifiedSnapshotWithMasking() {
        var created = service.ensureSnapshot(principal, "RUN_001");
        var reused = service.ensureSnapshot(principal, "RUN_001");
        var denominator = service.getPage(principal, "RUN_001", "denominator", 1, 20);
        var numerator = service.getPage(principal, "RUN_001", "numerator", 1, 20);

        assertThat(created.reused()).isFalse();
        assertThat(created.denominatorCount()).isEqualTo(2);
        assertThat(created.numeratorCount()).isEqualTo(1);
        assertThat(reused.reused()).isTrue();
        assertThat(businessQuery.calls()).isEqualTo(1);
        assertThat(denominator.total()).isEqualTo(2);
        assertThat(denominator.items().get(0).get("患者标识")).isEqualTo("12******90");
        assertThat(numerator.total()).isEqualTo(1);
        assertThat(numerator.items().get(0)).containsEntry("是否达到要求", "是");
    }

    @Test
    void exportsThreeSheetWorkbookOnlyAfterExplicitConfirmation() {
        assertThatThrownBy(() -> service.createExport(principal, "RUN_001", false))
                .isInstanceOfSatisfying(IndicatorDetailException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("DETAIL_EXPORT_CONFIRM_REQUIRED"));

        var exported = service.createExport(principal, "RUN_001", true);
        var download = service.resolveDownload(principal, exported.exportId());
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setMaxRowsPerSheet(5001);
        var workbook = new XlsxWorkbookReader(uploadProperties).read(
                new StoredUpload("test.xlsx", exported.fileName(),
                        download.path().toFile().length(), download.path()));

        assertThat(exported.rowCount()).isEqualTo(2);
        assertThat(workbook.sheets()).extracting("name")
                .containsExactly("统计范围_2", "达到要求_1", "未达到要求_1");
        assertThat(workbook.sheets()).extracting("rowCount")
                .containsExactly(2, 1, 1);
        assertThat(repository.export(exported.exportId()).orElseThrow().downloadCount())
                .isEqualTo(1);
    }

    @Test
    void exportsFourSheetRowComparisonFromOwnedDetailWorkbook() throws Exception {
        var sourceExport = service.createExport(principal, "RUN_001", true);
        var sourceDownload = service.resolveDownload(principal, sourceExport.exportId());
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setRoot(temp.resolve("uploads"));
        uploadProperties.setMaxRowsPerSheet(5001);
        UploadStorage uploads = new UploadStorage(uploadProperties);
        var uploaded = uploads.store(new MockMultipartFile(
                "file", sourceExport.fileName(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(sourceDownload.path())), principal);
        XlsxWorkbookReader reader = new XlsxWorkbookReader(uploadProperties);
        UploadComparisonExportService comparisons = new UploadComparisonExportService(
                service, repository, uploads, reader, new UploadDetailComparator(),
                new XlsxWorkbookWriter(), new HospitalAuthRepository(jdbc),
                detailProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                uploaded.fileKey().getBytes(StandardCharsets.UTF_8));

        var exported = comparisons.create(principal, "RUN_001", token, true);
        var download = service.resolveDownload(principal, exported.exportId());
        var workbook = reader.read(new StoredUpload(
                "comparison.xlsx", exported.fileName(), Files.size(download.path()), download.path()));

        assertThat(exported.rowCount()).isEqualTo(2);
        assertThat(workbook.sheets()).extracting("name").containsExactly(
                "对比摘要", "双方都有_2", "仅系统有_0", "仅上传文件有_0");
    }

    @Test
    void rejectsChangedBusinessCountsAndDoesNotPublishReadySnapshot() throws Exception {
        seedSuccessfulRun("RUN_002", 1, 3);

        assertThatThrownBy(() -> service.ensureSnapshot(principal, "RUN_002"))
                .isInstanceOfSatisfying(IndicatorDetailException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DETAIL_COUNT_MISMATCH"));
        assertThat(repository.snapshotByRun("RUN_002").orElseThrow().status()).isEqualTo("failed");
    }

    @Test
    void rejectsMissingPermissionAndCrossHospitalWithoutLeakingRun() {
        HospitalPrincipal withoutPermission = new HospitalPrincipal(
                "user_002", "viewer", "hospital_001", Set.of(), false, "SESSION_2");
        HospitalPrincipal otherHospital = new HospitalPrincipal(
                "user_003", "doctor", "hospital_002", Set.of("indicator_detail_view"),
                false, "SESSION_3");

        assertThatThrownBy(() -> service.ensureSnapshot(withoutPermission, "RUN_001"))
                .isInstanceOfSatisfying(IndicatorDetailException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTH_PERMISSION_DENIED"));
        assertThatThrownBy(() -> service.ensureSnapshot(otherHospital, "RUN_001"))
                .isInstanceOfSatisfying(IndicatorDetailException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DETAIL_RUN_NOT_FOUND"));
    }

    private void seedSuccessfulRun(String runId, int numerator, int denominator) throws Exception {
        Map<String, Object> runContext = Map.of(
                "effective_rule", Map.of(
                        "rule_id", "MQSI2025_005",
                        "rule_name", "急会诊及时到位率",
                        "effective_level", "hospital",
                        "national_version", "2025",
                        "hospital_version", 1,
                        "calculation_definition", Map.of(
                                "detail_fields", List.of(
                                        field("consult_id", "会诊编号", "none"),
                                        field("patient_id", "患者标识", "patient_id"),
                                        field("request_time", "请求时间", "none"),
                                        field("arrive_time", "到位时间", "none"),
                                        field("arrive_minutes", "到位耗时", "none")))),
                "field_mapping", Map.of(
                        "hospital_id", "hospital_001",
                        "db_name", "win60_qa_991827",
                        "schema", "WINDBA",
                        "main_table", "INPATIENT_CONSULT_APPLY",
                        "dialect", "sqlserver",
                        "query_profile", "urgent_consult_sqlserver"),
                "execution_context", Map.of(),
                "params", Map.of(
                        "hospital_soid", 991827,
                        "urgent_level_code", 977578,
                        "arrive_minutes_threshold", 20),
                "stat_start", "2026-01-01 00:00:00",
                "stat_end", "2026-04-01 00:00:00",
                "db_source_id", "win60_qa_991827");
        jdbc.update("""
                INSERT INTO med_sql_run_log (
                  run_id,sql_id,hospital_id,rule_id,stat_start_time,stat_end_time,
                  run_status,result_value,error_message,duration_ms,run_by,
                  numerator_count,denominator_count,run_context_json,run_time
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                runId, "SQL_" + runId, "hospital_001", "MQSI2025_005",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00", "success",
                denominator == 0 ? 0 : numerator * 100.0 / denominator, "", 12, "user_001",
                numerator, denominator, objectMapper.writeValueAsString(runContext),
                java.sql.Timestamp.from(NOW));
    }

    private DetailProperties detailProperties() {
        DetailProperties properties = new DetailProperties();
        properties.setExportRoot(temp.resolve("exports"));
        properties.setExpireHours(24);
        properties.setMaxRows(100);
        properties.setDefaultPageSize(50);
        return properties;
    }

    private static Map<String, Object> field(String field, String label, String sensitivity) {
        return Map.of("field", field, "label", label, "sensitivity", sensitivity);
    }

    private static final class StubBusinessQuery implements IndicatorBusinessQueryClient {
        private final List<Map<String, Object>> rows;
        private final AtomicInteger calls = new AtomicInteger();

        private StubBusinessQuery(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public List<Map<String, Object>> execute(String sql) {
            assertThat(sql)
                    .contains("SELECT TOP 101", "'2026-01-01 00:00:00'", "'2026-04-01 00:00:00'")
                    .doesNotContain(":start_time", ":end_time");
            calls.incrementAndGet();
            return rows;
        }

        @Override
        public String sourceId() {
            return "win60_qa_991827";
        }

        int calls() {
            return calls.get();
        }
    }
}
