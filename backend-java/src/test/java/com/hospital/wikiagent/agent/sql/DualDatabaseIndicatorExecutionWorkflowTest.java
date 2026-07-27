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
import com.hospital.wikiagent.dbhub.DbHubMcpException;

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
    void ordinaryCalculationExtractsThenQueriesOnlyRealOverview() {
        database.businessOverview = overview(99, 100);
        database.realOverview = overview(11, 394);

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("source_role", "real")
                .containsEntry("source_id", "winex_aima")
                .containsEntry("workflow_version", "profile-extract-real-overview-v1")
                .containsEntry("numerator_count", 11L)
                .containsEntry("denominator_count", 394L)
                .doesNotContainKeys(
                        "business_result",
                        "comparison_status",
                        "dual_difference_diagnosis");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).containsExactly("real:overview");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_dual_indicator_run", Integer.class))
                .isZero();
    }

    @Test
    void disabledExtractionStopsBeforeAnyDatabaseQuery() {
        extractionProperties.setMode(ExtractionProperties.Mode.DISABLED);
        database.businessOverview = overview(11, 394);
        database.realOverview = overview(11, 394);

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("SOURCE_EXTRACTION_DISABLED");
        assertThat(extraction.calls).isZero();
        assertThat(database.calls).isEmpty();
    }

    @Test
    void requiredExtractionWithMissingContractStopsBeforeGatewayAndDatabases() {
        Map<String, Object> missingSource = rule(true);
        missingSource.put("extraction_contract", Map.of());

        ToolResult result = workflow.execute(
                preparedSql(), missingSource, executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("EXTRACTION_CONTRACT_INVALID");
        assertThat(extraction.calls).isZero();
        assertThat(database.calls).isEmpty();
    }

    @Test
    void requiredExtractionFailureDoesNotFallBackToExistingSnapshot() {
        extraction.result = new ExtractionResult(
                "",
                ExtractionResult.Status.FAILED,
                0, 0, 0, 0,
                Instant.now(),
                "", "",
                "SOURCE_EXTRACTION_FAILED",
                "failed");

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("SOURCE_EXTRACTION_FAILED");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).isEmpty();
    }

    @Test
    void aggregateNullsAreTreatedAsEmptyStatisticsWhenColumnsExist() {
        database.realOverview = emptyOverview();

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("numerator_count", 0L)
                .containsEntry("denominator_count", 0L)
                .containsEntry("no_sample", true);
        assertThat(database.calls).containsExactly("real:overview");
    }

    @Test
    void retriesRealReadOnlyOverviewOnceAfterDbHubFailure() {
        database.realOverviewFailures = 1;

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(database.calls).containsExactly(
                "real:overview", "real:overview");
    }

    @Test
    void stopsAfterSecondRealOverviewFailure() {
        database.realOverviewFailures = 2;

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("REAL_DATABASE_OVERVIEW_FAILED");
        assertThat(database.calls).containsExactly(
                "real:overview", "real:overview");
    }

    @Test
    void reportsPublishedOverviewMappingErrorsWithoutRetryingDatabase() {
        Map<String, Object> invalidRule = rule(true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(invalidRule.get("dual_database_contract")));
        contract.put("overview_result_mapping", Map.of(
                "numerator_count", "missing_numerator",
                "denominator_count", "missing_denominator"));
        invalidRule.put("dual_database_contract", contract);

        ToolResult result = workflow.execute(
                preparedSql(), invalidRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("OVERVIEW_RESULT_CONTRACT_INVALID");
        assertThat(database.calls).containsExactly("real:overview");
    }

    @Test
    void ordinaryCalculationNeverComparesBusinessOrRunsDetails() {
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
        assertThat(result.data())
                .containsEntry("numerator_count", 2L)
                .containsEntry("denominator_count", 4L)
                .doesNotContainKeys(
                        "comparison_status",
                        "diagnosis_report_id",
                        "dual_difference_diagnosis");
        assertThat(result.data().get("result_value").toString()).isEqualTo("50.00");
        assertThat(extraction.calls).isEqualTo(1);
        assertThat(database.calls).containsExactly("real:overview");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_index_diagnose_report", Integer.class))
                .isZero();
    }

    @Test
    void unverifiedRealOverviewSchemaStopsBeforeExtractionAndDatabase() {
        ToolResult result = workflow.execute(
                preparedSql(), rule(false), executable("overview"), parameters(), context());

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("REAL_OVERVIEW_RUNTIME_UNAVAILABLE");
        assertThat(extraction.calls).isZero();
        assertThat(database.calls).isEmpty();
    }

    @Test
    void ordinaryCalculationDoesNotRequireBusinessOverviewVerification() {
        Map<String, Object> realOnlyRule = rule(true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(realOnlyRule.get("dual_database_contract")));
        contract.put("schema_compatible", false);
        contract.put("verified_source_roles", List.of("real"));
        contract.put("source_verification", Map.of(
                "business", Map.of(
                        "metadata_status", "unverified",
                        "compile_status", "unverified"),
                "real", Map.of(
                        "metadata_status", "validated",
                        "compile_status", "validated")));
        realOnlyRule.put("dual_database_contract", contract);
        database.businessOverview = overview(99, 100);
        database.realOverview = overview(3, 8);

        ToolResult result = workflow.execute(
                preparedSql(), realOnlyRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("numerator_count", 3L)
                .containsEntry("denominator_count", 8L)
                .containsEntry("source_role", "real");
        assertThat(database.calls).containsExactly("real:overview");
    }

    @Test
    void staticValidatedOverviewUsesRealDatabaseExecution() {
        Map<String, Object> staticRule = rule(false);
        staticRule.put("overview_runtime_eligible", true);
        database.realOverview = overview(3, 68);

        ToolResult result = workflow.execute(
                preparedSql(), staticRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("numerator_count", 3L)
                .containsEntry("denominator_count", 68L)
                .containsEntry("source_role", "real");
        assertThat(database.calls).containsExactly("real:overview");
    }

    @Test
    void scalarMetricCanBeCalculatedWithoutInventingNumeratorAndDenominator() {
        Map<String, Object> scalarRule = rule(false);
        scalarRule.put("overview_runtime_eligible", true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(scalarRule.get("dual_database_contract")));
        contract.put("overview_result_mapping", Map.of(
                "index_value", "median_minutes"));
        scalarRule.put("dual_database_contract", contract);
        database.realOverview = List.of(Map.of("median_minutes", 18.50));

        ToolResult result = workflow.execute(
                preparedSql(), scalarRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("result_value", 18.50)
                .containsEntry("source_role", "real")
                .doesNotContainKeys("numerator_count", "denominator_count");
    }

    @Test
    void scalarMetricWithNullValuesIsReportedAsNoSample() {
        Map<String, Object> scalarRule = rule(false);
        scalarRule.put("overview_runtime_eligible", true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(scalarRule.get("dual_database_contract")));
        contract.put("overview_result_mapping", Map.of(
                "index_value", "median_minutes"));
        scalarRule.put("dual_database_contract", contract);
        Map<String, Object> emptyScalar = new LinkedHashMap<>();
        emptyScalar.put("median_minutes", null);
        database.realOverview = List.of(emptyScalar);

        ToolResult result = workflow.execute(
                preparedSql(), scalarRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("no_sample", true)
                .containsEntry("result_value", null)
                .doesNotContainKeys("numerator_count", "denominator_count");
    }

    @Test
    void scalarResultContractCarriesComponentsSampleAndTarget() {
        Map<String, Object> scalarRule = rule(false);
        scalarRule.put("overview_runtime_eligible", true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(scalarRule.get("dual_database_contract")));
        contract.put("overview_result_mapping", Map.of(
                "index_value", "index_value",
                "component_left", "left_rate",
                "component_right", "right_rate",
                "sample_count", "sample_count",
                "target_value", "target_value"));
        scalarRule.put("dual_database_contract", contract);
        Map<String, Object> row = Map.of(
                "index_value", 2.0,
                "left_rate", "2.00%",
                "right_rate", "1.00%",
                "sample_count", 12,
                "target_value", 5);
        database.realOverview = List.of(row);

        ToolResult result = workflow.execute(
                preparedSql(), scalarRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("result_value", 2.0)
                .containsEntry("component_left", "2.00%")
                .containsEntry("component_right", "1.00%")
                .containsEntry("sample_count", 12L)
                .containsEntry("target_value", 5);
    }

    @Test
    void ordinaryCalculationUsesRealTargetWithoutComparingBusinessTarget() {
        Map<String, Object> scalarRule = rule(true);
        Map<String, Object> contract =
                new LinkedHashMap<>(map(scalarRule.get("dual_database_contract")));
        contract.put("overview_result_mapping", Map.of(
                "numerator_count", "numerator_count",
                "denominator_count", "denominator_count",
                "target_value", "target_value"));
        scalarRule.put("dual_database_contract", contract);
        database.businessOverview = List.of(Map.of(
                "numerator_count", 95, "denominator_count", 100, "target_value", 90));
        database.realOverview = List.of(Map.of(
                "numerator_count", 95, "denominator_count", 100, "target_value", 92));

        ToolResult result = workflow.execute(
                preparedSql(), scalarRule, executable("overview"), parameters(), context());

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.data())
                .containsEntry("target_value", 92)
                .containsEntry("target_source", "real")
                .doesNotContainKeys(
                        "comparison_status",
                        "target_comparison_status",
                        "target_conflict");
        assertThat(database.calls).containsExactly("real:overview");
    }

    @Test
    void refreshesSnapshotForEveryProfileExecution() {
        ToolExecutionContext context = context();

        ToolResult first = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context);
        ToolResult second = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context);

        assertThat(first.ok()).withFailMessage(first.toString()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(extraction.calls).isEqualTo(2);
        assertThat(database.calls).containsExactly("real:overview", "real:overview");
    }

    @Test
    void explicitDiagnosisRunsExtractionStageAndDetailsEvenWhenOverviewMatches() {
        extractionProperties.setMode(ExtractionProperties.Mode.DISABLED);
        ToolExecutionContext context = context();
        context.runState().lastIntent("indicator_diagnosis");

        ToolResult result = workflow.execute(
                preparedSql(), rule(true), executable("overview"), parameters(), context);

        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        assertThat(result.summary()).contains("已按用户要求继续完成明细诊断");
        assertThat(result.data())
                .containsEntry("comparison_status", "matched")
                .containsEntry("extraction_status", "SKIPPED_DISABLED")
                .containsEntry("data_freshness", "existing_snapshot_not_refreshed")
                .containsKey("diagnosis_report_id");
        assertThat(database.calls).containsExactly(
                "business:overview", "real:overview",
                "business:department", "real:department",
                "business:patient", "real:patient");
    }

    @Test
    void explicitDiagnosisRejectsPeriodLongerThanOneMonth() {
        extractionProperties.setMode(ExtractionProperties.Mode.DISABLED);
        ToolExecutionContext context = context();
        context.runState().lastIntent("indicator_diagnosis");
        PreparedSqlObject longPeriod = preparedSql(
                "2025-02-01T00:00:00", "2026-07-27T00:00:00");

        ToolResult result = workflow.execute(
                longPeriod, rule(true), executable("overview"), parameters(), context);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("STAT_PERIOD_EXCEEDS_ONE_MONTH");
        assertThat(database.calls).isEmpty();
    }

    private static PreparedSqlObject preparedSql() {
        return preparedSql("2026-01-01T00:00:00", "2026-02-01T00:00:00");
    }

    private static PreparedSqlObject preparedSql(String start, String end) {
        return new PreparedSqlObject(
                "SQL_test", "h1", "u1", "session1", "HXZD-001-001", "sqlserver",
                sql("overview"), Map.of(), start, end,
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
        rule.put("extraction_contract", Map.of(
                "database_name", "winex_aima",
                "schema_name", "dbo",
                "route", "EVENT",
                "event_no", "CORE_FDR",
                "event_table", "MRAS_BUSINESS_FIRSTVISIT",
                "dependency_tables", List.of("MRAS_TARGET_DEFINITION")));
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

    private static List<Map<String, Object>> emptyOverview() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("numerator_count", null);
        row.put("denominator_count", null);
        return List.of(row);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class CountingExtractionGateway implements SourceExtractionGateway {
        private int calls;
        private ExtractionResult result = new ExtractionResult(
                "EXT-1",
                ExtractionResult.Status.SUCCESS,
                10, 10, 0, 0,
                Instant.now(),
                "business-snapshot",
                "real-snapshot",
                "",
                "ok");

        @Override
        public ExtractionResult extract(ExtractionRequest request) {
            calls++;
            return result;
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
        private int businessOverviewFailures;
        private int realOverviewFailures;

        @Override
        public List<Map<String, Object>> execute(DatabaseRole role, String sql) {
            String kind = sql.contains("'department'") ? "department"
                    : sql.contains("'patient'") ? "patient" : "overview";
            calls.add(role.value() + ":" + kind);
            if ("overview".equals(kind)
                    && role == DatabaseRole.BUSINESS
                    && businessOverviewFailures > 0) {
                businessOverviewFailures--;
                throw new DbHubMcpException("temporary business DBHub failure");
            }
            if ("overview".equals(kind)
                    && role == DatabaseRole.REAL
                    && realOverviewFailures > 0) {
                realOverviewFailures--;
                throw new DbHubMcpException("temporary real DBHub failure");
            }
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
