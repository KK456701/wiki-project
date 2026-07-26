package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

class IndicatorSqlToolsTest {
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private IndicatorSqlTools tools;
    private RuleReadRepository rules;
    private AgentRunState state;
    private AgentRuntimeContext runtimeContext;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("sql_tools_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        rules = mock(RuleReadRepository.class);
        when(rules.effectiveRule(anyString(), anyString())).thenReturn(executableRule());
        when(rules.effectiveRule(anyString(), anyString(), isNull())).thenReturn(executableRule());
        when(rules.effectiveRule(anyString(), anyString(), anyString())).thenReturn(executableRule());
        when(rules.fieldMapping(anyString(), anyString())).thenReturn(confirmedMapping());
        when(rules.fieldMapping(anyString(), anyString(), isNull())).thenReturn(confirmedMapping());
        when(rules.fieldMapping(anyString(), anyString(), anyString())).thenReturn(confirmedMapping());
        tools = new IndicatorSqlTools(
                rules,
                new SqlObjectRepository(jdbc, objectMapper),
                new SqlTemplateRenderer(),
                new ReadOnlySqlValidator(),
                new SqlParameterBinder(),
                new StubBusinessQuery(),
                objectMapper);
        state = new AgentRunState();
        state.currentRuleId("HXZD-003-001");
        runtimeContext = new AgentRuntimeContext(
                new HospitalPrincipal("u1", "doctor", "h1", Set.of(), false, "login-session"),
                "request-1", "trace-1", "business_test");
    }

    @Test
    void inspectsPreparesAndRunsOnlyValidatedServerSqlObject() {
        ToolExecutionContext context = executionContext(runtimeContext, state);
        ToolResult inspected = tools.inspect(new IndicatorSqlTools.InspectInput("HXZD-003-001"), context);
        assertThat(inspected.ok()).isTrue();
        assertThat(inspected.data()).containsEntry("mapping_status", "confirmed");

        ToolResult prepared = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "HXZD-003-001", "2026-01-01T00:00:00", "2026-02-01T00:00:00"), context);
        assertThat(prepared.ok()).isTrue();
        assertThat(prepared.code()).isEqualTo("SQL_OBJECT_PREPARED");
        assertThat(prepared.data()).containsKeys(
                "sql_id", "context_digest", "stat_start", "stat_end", "sql_preview", "parameters");
        assertThat(prepared.data().get("sql_preview").toString()).startsWith("SELECT");
        assertThat(prepared.data()).doesNotContainKey("sql_text");

        String sqlId = prepared.data().get("sql_id").toString();
        ToolResult trial = tools.trial(new IndicatorSqlTools.TrialInput(sqlId), context);
        assertThat(trial.ok()).isTrue();
        assertThat(trial.code()).isEqualTo("TRIAL_RUN_COMPLETED");
        assertThat(trial.data())
                .containsEntry("numerator_count", 1L)
                .containsEntry("denominator_count", 4L)
                .containsEntry("result_value", 25.0)
                .containsEntry("source", "business_test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_generated_sql", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_agent_sql_object", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_sql_run_log", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsPeriodLongerThanOneCalendarMonthBeforeCreatingSqlObject() {
        ToolResult result = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "HXZD-003-001",
                "2026-01-01T00:00:00",
                "2026-02-01T00:00:01"), executionContext(runtimeContext, state));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("STAT_PERIOD_EXCEEDS_ONE_MONTH");
        assertThat(result.summary()).contains("2026-02-01T00:00");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_agent_sql_object", Integer.class)).isZero();
    }

    @Test
    void rejectsMissingFieldMappingBeforeSqlGeneration() {
        Map<String, Object> missing = new LinkedHashMap<>(confirmedMapping());
        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) missing.get("fields"));
        fields.remove("arrive_time");
        missing.put("fields", fields);
        when(rules.fieldMapping(anyString(), anyString(), isNull())).thenReturn(missing);
        ToolResult result = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "HXZD-003-001", "2026-01-01T00:00:00", "2026-02-01T00:00:00"),
                executionContext(runtimeContext, state));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("FIELD_PRECHECK_FAILED");
        assertThat(result.data().get("missing_mappings")).asList().contains("arrive_time");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_generated_sql", Integer.class)).isZero();
    }

    @Test
    void rejectsDocumentationOnlyProfileBeforeDatabaseAccess() {
        Map<String, Object> documentationOnly = new LinkedHashMap<>(executableRule());
        documentationOnly.put("execution_status", "documentation_only");
        documentationOnly.put("standard_sql", "");
        documentationOnly.put("execution_blockers", List.of("缺少经确认的医院字段契约"));
        when(rules.effectiveRule(anyString(), anyString(), isNull())).thenReturn(documentationOnly);

        ToolResult result = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "HXZD-003-001", "2026-01-01T00:00:00", "2026-02-01T00:00:00"),
                executionContext(runtimeContext, state));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("PROFILE_NOT_EXECUTABLE");
        assertThat(result.data()).containsEntry("execution_status", "documentation_only");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_generated_sql", Integer.class)).isZero();
    }

    @Test
    void sqlObjectCannotCrossHospitalBoundary() {
        ToolResult prepared = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "HXZD-003-001", "2026-01-01T00:00:00", "2026-02-01T00:00:00"),
                executionContext(runtimeContext, state));
        AgentRuntimeContext otherHospital = new AgentRuntimeContext(
                new HospitalPrincipal("u1", "doctor", "h2", Set.of(), false, "login-session"),
                "request-2", "trace-2", "business_test");

        ToolResult result = tools.trial(
                new IndicatorSqlTools.TrialInput(prepared.data().get("sql_id").toString()),
                executionContext(otherHospital, state));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("SQL_OBJECT_TENANT_MISMATCH");
        assertThat(result.status()).isEqualTo("forbidden");
    }

    @Test
    void rendersSupportedSqlServerTemplateExpressionsDeterministically() {
        String rendered = new SqlTemplateRenderer().render(
                """
                WITH base AS (
                  SELECT e.{{ fields.admit_time.split('.')[-1] }} AS admit_time
                  FROM WINDBA.{{ main_table }} e
                  WHERE e.{{ fields.get('period_time', fields.admit_time).split('.')[-1] }} >= :start_time
                    AND e.{{ fields.get('period_time', fields.admit_time).split('.')[-1] }} < :end_time
                )
                {% if fields.get('ward_entry_time') %}SELECT {{ fields.ward_entry_time }} FROM base{% endif %}
                """,
                Map.of("admit_time", "INPATIENT_ENCOUNTER.ADMITTED_AT"),
                "INPATIENT_ENCOUNTER");

        assertThat(rendered).contains("e.ADMITTED_AT", "WINDBA.INPATIENT_ENCOUNTER");
        assertThat(rendered).doesNotContain("{{", "{%", "ward_entry_time");
    }

    @Test
    void rejectsWriteOrMultiStatementSql() {
        ReadOnlySqlValidator validator = new ReadOnlySqlValidator();
        assertThat(validator.validate(
                "SELECT * FROM consult_record WHERE request_time>=:start_time AND request_time<:end_time; DELETE FROM x",
                "consult_record").ok()).isFalse();
    }

    @Test
    void allowsBusinessOrAndIgnoresKeywordsInsideCommentsAndStrings() {
        ReadOnlySqlValidator validator = new ReadOnlySqlValidator();
        String sql = """
                SELECT COUNT(*) AS sample_count
                FROM consult_record
                WHERE request_time >= :start_time AND request_time < :end_time
                  AND (status = 'DELETE' OR status = 'UPDATE')
                  -- 注释中的 DROP 不代表真实语句
                """;

        assertThat(validator.validate(sql, "consult_record").ok()).isTrue();
    }

    @Test
    void rejectsUnresolvedExcelAndTemplateArtifacts() {
        ReadOnlySqlValidator validator = new ReadOnlySqlValidator();

        assertThat(validator.validate(
                "SELECT #NAME? FROM consult_record WHERE request_time>=:start_time AND request_time<:end_time",
                "consult_record").ok()).isFalse();
        assertThat(validator.validate(
                "SELECT * FROM consult_record #{NOLOCK} WHERE request_time>=:start_time AND request_time<:end_time",
                "consult_record").ok()).isFalse();
    }

    private ToolExecutionContext executionContext(AgentRuntimeContext runtime, AgentRunState currentState) {
        return new ToolExecutionContext(
                runtime, "subtask-1", currentState,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOW", "", "test-policy"));
    }

    private static Map<String, Object> executableRule() {
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value,
                       1 AS numerator_count, 4 AS denominator_count, 4 AS sample_count
                FROM consult_record
                WHERE hospital_id=:hospital_id
                  AND consult_type=:consult_type_value
                  AND request_time>=:start_time AND request_time<:end_time
                """;
        return Map.ofEntries(
                Map.entry("rule_id", "HXZD-003-001"),
                Map.entry("rule_name", "急会诊及时到位率"),
                Map.entry("profile_id", "company-default"),
                Map.entry("execution_status", "executable"),
                Map.entry("standard_sql", sql),
                Map.entry("field_contract", Map.of("business_fields", Map.of(
                        "hospital_id", Map.of("type", "string"),
                        "request_time", Map.of("type", "datetime"),
                        "arrive_time", Map.of("type", "datetime"),
                        "consult_type", Map.of("type", "string")))),
                Map.entry("effective_params", Map.of("consult_type_value", "急会诊")),
                Map.entry("hospital_version", 1),
                Map.entry("national_version", "2025"));
    }

    private static Map<String, Object> confirmedMapping() {
        List<Map<String, Object>> items = List.of(
                mappingItem("hospital_id", "varchar"),
                mappingItem("request_time", "datetime"),
                mappingItem("arrive_time", "datetime"),
                mappingItem("consult_type", "varchar"));
        return Map.ofEntries(
                Map.entry("rule_id", "HXZD-003-001"),
                Map.entry("profile_id", "company-default"),
                Map.entry("dialect", "sqlserver"),
                Map.entry("db_name", "business_test"),
                Map.entry("schema", "dbo"),
                Map.entry("main_table", "consult_record"),
                Map.entry("status", "confirmed"),
                Map.entry("fields", Map.of(
                        "hospital_id", "consult_record.hospital_id",
                        "request_time", "consult_record.request_time",
                        "arrive_time", "consult_record.arrive_time",
                        "consult_type", "consult_record.consult_type")),
                Map.entry("items", items),
                Map.entry("metadata_items", items),
                Map.entry("relations", List.of()));
    }

    private static Map<String, Object> mappingItem(String field, String dataType) {
        return Map.of(
                "business_field", field,
                "table_name", "consult_record",
                "column_name", field,
                "data_type", dataType,
                "mapping_data_type", dataType,
                "metadata_data_type", dataType,
                "status", "confirmed");
    }

    private static class StubBusinessQuery implements IndicatorBusinessQueryClient {
        @Override
        public List<Map<String, Object>> execute(String sql) {
            assertThat(sql).contains("'h1'", "'2026-01-01 00:00:00'", "'2026-02-01 00:00:00'");
            assertThat(sql).doesNotContain(":hospital_id", ":start_time", ":end_time");
            return List.of(Map.of(
                    "index_value", 25.0,
                    "numerator_count", 1,
                    "denominator_count", 4));
        }

        @Override
        public String sourceId() {
            return "business_test";
        }
    }
}
