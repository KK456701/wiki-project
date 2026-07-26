package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hospital.wikiagent.agent.extraction.ExtractionProperties;
import com.hospital.wikiagent.agent.extraction.ExtractionRequest;
import com.hospital.wikiagent.agent.extraction.ExtractionResult;
import com.hospital.wikiagent.agent.extraction.HospitalExecutionLock;
import com.hospital.wikiagent.agent.extraction.SourceExtractionGateway;
import com.hospital.wikiagent.agent.diagnosis.DiagnosisReportRepository;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;

class DualDatabaseIndicatorExecutionWorkflowTest {
    private JdbcTemplate jdbc;
    private ExtractionProperties extractionProperties;
    private CountingExtractionGateway extraction;
    private StubDualQueryClient database;
    private DualDatabaseIndicatorExecutionWorkflow workflow;

    @BeforeEach
    void setUp() {
        var embedded = new EmbeddedDatabaseBuilder()
                .setName("dual_database_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(embedded);
        ObjectMapper mapper = JsonMapper.builder().build();
        extractionProperties = new ExtractionProperties();
        extractionProperties.setMode(ExtractionProperties.Mode.REQUIRED);
        extraction = new CountingExtractionGateway();
        database = new StubDualQueryClient();
        workflow = new DualDatabaseIndicatorExecutionWorkflow(
                extractionProperties,
                extraction,
                new HospitalExecutionLock(),
                database,
                new SqlParameterBinder(),
                new ReadOnlySqlValidator(),
                new SqlObjectRepository(jdbc, mapper),
                new DiagnosisReportRepository(jdbc, mapper));
    }

    @Test
    void matchingOverviewStopsBeforeDetailQueriesAndExtractsOnce() {
        database.businessOverview = overview(11, 394);
        database.realOverview = overview(11, 394);

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data()).containsEntry("comparison_status", "matched");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).containsExactly("business:overview", "real:overview");
        assertThat(jdbc.queryForObject(
                "SELECT comparison_status FROM med_dual_indicator_run", String.class))
                .isEqualTo("matched");
    }

    @Test
    void disabledExtractionStillRunsBothDatabasesWithoutCallingGateway() {
        extractionProperties.setMode(ExtractionProperties.Mode.DISABLED);
        database.businessOverview = overview(11, 394);
        database.realOverview = overview(11, 394);

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("comparison_status", "matched")
                .containsEntry("extraction_id", "");
        assertThat(extraction.calls).isZero();
        assertThat(database.calls).containsExactly("business:overview", "real:overview");
    }

    @Test
    void equalRateWithDifferentCountsStillRunsBothDetailLevels() {
        database.businessOverview = overview(1, 2);
        database.realOverview = overview(2, 4);
        database.department.put(DatabaseRole.BUSINESS, List.of(
                Map.of("dept_id", "A", "numerator_count", 1),
                Map.of("dept_id", "B", "numerator_count", 0)));
        database.department.put(DatabaseRole.REAL, List.of(
                Map.of("dept_id", "A", "numerator_count", 2),
                Map.of("dept_id", "C", "numerator_count", 0)));
        database.patient.put(DatabaseRole.BUSINESS, List.of(
                Map.of("encounter_id", "E1", "is_numerator", 1),
                Map.of("encounter_id", "E2", "is_numerator", 0)));
        database.patient.put(DatabaseRole.REAL, List.of(
                Map.of("encounter_id", "E1", "is_numerator", 0),
                Map.of("encounter_id", "E3", "is_numerator", 1)));

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data()).containsEntry("comparison_status", "mismatched");
        assertThat(String.valueOf(result.data().get("diagnosis_report_id")))
                .startsWith("DDR_");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).containsExactly(
                "business:overview", "real:overview",
                "business:department", "real:department",
                "business:patient", "real:patient");
        Map<String, Object> diagnosis = map(result.data().get("dual_difference_diagnosis"));
        assertThat(diagnosis).containsEntry("status", "completed");
        assertThat(map(diagnosis.get("patient_comparison")))
                .containsEntry("business_only_count", 1L)
                .containsEntry("real_only_count", 1L)
                .containsEntry("different_count", 1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_index_diagnose_report", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void unverifiedDualSchemaStopsBeforeExtractionAndDatabase() {
        ToolResult result = workflow.execute(
                preparedSql(), rule(false), executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("DUAL_DATABASE_SCHEMA_INCOMPATIBLE");
        assertThat(extraction.calls).isZero();
        assertThat(database.calls).isEmpty();
    }

    @Test
    void reusesSuccessfulExtractionWithinSameIndicatorSubtask() {
        ToolExecutionContext context = context();

        ToolResult first = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context);
        ToolResult second = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context);

        assertThat(first.ok()).withFailMessage(first.toString()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).hasSize(4);
    }

    private static PreparedSqlObject preparedSql() {
        return new PreparedSqlObject(
                "SQL_test", "h1", "u1", "session1", "HXZD-001-001", "sqlserver",
                sql("overview"), Map.of(), "2026-01-01T00:00:00", "2026-02-01T00:00:00",
                Map.of(), "digest", "validated", "ok",
                Instant.now(), Instant.now().plusSeconds(60), "legacy");
    }

    private static Map<String, Object> rule(boolean compatible) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schema_compatible", compatible);
        contract.put("verified_source_roles",
                compatible ? List.of("business", "real") : List.of());
        contract.put("source_verification", Map.of(
                "business", Map.of(
                        "metadata_status", compatible ? "validated" : "unverified",
                        "compile_status", compatible ? "validated" : "unverified"),
                "real", Map.of(
                        "metadata_status", compatible ? "validated" : "unverified",
                        "compile_status", compatible ? "validated" : "unverified")));
        contract.put("overview_result_mapping", Map.of(
                "numerator_count", "numerator_count",
                "denominator_count", "denominator_count"));
        contract.put("department_comparison_key", "dept_id");
        contract.put("patient_comparison_key", "encounter_id");
        contract.put("numerator_classification_field", "is_numerator");
        contract.put("department_compare_fields", List.of("numerator_count"));
        contract.put("patient_compare_fields", List.of("is_numerator"));
        contract.put("allowed_compare_fields", List.of(
                "numerator_count", "is_numerator"));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("profile_id", "PROFILE-1");
        rule.put("knowledge_release_id", "KB-test");
        rule.put("source_extract_sql", sql("source"));
        rule.put("department_detail_sql", sql("department"));
        rule.put("patient_detail_sql", sql("patient"));
        rule.put("dual_database_contract", contract);
        return rule;
    }

    private static String sql(String marker) {
        return "SELECT '" + marker + "' AS query_kind "
                + "FROM sample WHERE happened_at >= :start_time AND happened_at < :end_time";
    }

    private static String executable(String marker) {
        return sql(marker)
                .replace(":start_time", "'2026-01-01 00:00:00'")
                .replace(":end_time", "'2026-02-01 00:00:00'");
    }

    private static Map<String, Object> parameters() {
        return Map.of(
                "start_time", "2026-01-01 00:00:00",
                "end_time", "2026-02-01 00:00:00",
                "hospital_id", "h1");
    }

    private static ToolExecutionContext context() {
        AgentRuntimeContext runtime = new AgentRuntimeContext(
                new HospitalPrincipal(
                        "u1", "doctor", "h1", Set.of(), false, "session1"),
                "request1", "trace1", "legacy");
        AgentRunState state = new AgentRunState();
        state.subtaskId("subtask1");
        return new ToolExecutionContext(
                runtime,
                "subtask1",
                state,
                new PolicyDecision(Decision.ALLOW, "allowed", "", "test"));
    }

    private static List<Map<String, Object>> overview(long numerator, long denominator) {
        return List.of(Map.of(
                "numerator_count", numerator,
                "denominator_count", denominator));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class CountingExtractionGateway implements SourceExtractionGateway {
        private int calls;

        @Override
        public ExtractionResult extract(ExtractionRequest request) {
            calls++;
            return new ExtractionResult(
                    "EXT-1",
                    ExtractionResult.Status.SUCCESS,
                    10, 10, 0, 0,
                    Instant.now(),
                    "business-snapshot",
                    "real-snapshot",
                    "",
                    "ok");
        }
    }

    private static final class StubDualQueryClient implements IndicatorDatabaseQueryClient {
        private List<Map<String, Object>> businessOverview = overview(1, 1);
        private List<Map<String, Object>> realOverview = overview(1, 1);
        private final Map<DatabaseRole, List<Map<String, Object>>> department =
                new LinkedHashMap<>();
        private final Map<DatabaseRole, List<Map<String, Object>>> patient =
                new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        @Override
        public List<Map<String, Object>> execute(DatabaseRole role, String sql) {
            String kind = sql.contains("'department'") ? "department"
                    : sql.contains("'patient'") ? "patient" : "overview";
            calls.add(role.value() + ":" + kind);
            return switch (kind) {
                case "department" -> department.getOrDefault(role, List.of());
                case "patient" -> patient.getOrDefault(role, List.of());
                default -> role == DatabaseRole.BUSINESS
                        ? businessOverview : realOverview;
            };
        }

        @Override
        public String sourceId(DatabaseRole role) {
            return role == DatabaseRole.BUSINESS ? "winex_all_dev" : "winex_aima";
        }
    }
}
