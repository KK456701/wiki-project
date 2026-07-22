package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
        seedRuleAndMapping();
        RuleReadRepository rules = new RuleReadRepository(jdbc, objectMapper);
        tools = new IndicatorSqlTools(
                rules,
                new SqlObjectRepository(jdbc, objectMapper),
                new SqlTemplateRenderer(),
                new ReadOnlySqlValidator(),
                new SqlParameterBinder(),
                new StubBusinessQuery(),
                objectMapper);
        state = new AgentRunState();
        state.currentRuleId("MQSI2025_005");
        runtimeContext = new AgentRuntimeContext(
                new HospitalPrincipal("u1", "doctor", "h1", Set.of(), false, "login-session"),
                "request-1", "trace-1", "business_test");
    }

    @Test
    void inspectsPreparesAndRunsOnlyValidatedServerSqlObject() {
        ToolExecutionContext context = executionContext(runtimeContext, state);
        ToolResult inspected = tools.inspect(new IndicatorSqlTools.InspectInput("MQSI2025_005"), context);
        assertThat(inspected.ok()).isTrue();
        assertThat(inspected.data()).containsEntry("mapping_status", "confirmed");

        ToolResult prepared = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "MQSI2025_005", "2026-01-01T00:00:00", "2026-04-01T00:00:00"), context);
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
    void rejectsMissingFieldMappingBeforeSqlGeneration() {
        jdbc.update("DELETE FROM med_field_mapping WHERE business_field='arrive_time'");
        ToolResult result = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "MQSI2025_005", "2026-01-01T00:00:00", "2026-04-01T00:00:00"),
                executionContext(runtimeContext, state));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("FIELD_PRECHECK_FAILED");
        assertThat(result.data().get("missing_mappings")).asList().contains("arrive_time");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_generated_sql", Integer.class)).isZero();
    }

    @Test
    void sqlObjectCannotCrossHospitalBoundary() {
        ToolResult prepared = tools.prepare(new IndicatorSqlTools.PrepareInput(
                "MQSI2025_005", "2026-01-01T00:00:00", "2026-04-01T00:00:00"),
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

    private ToolExecutionContext executionContext(AgentRuntimeContext runtime, AgentRunState currentState) {
        return new ToolExecutionContext(
                runtime, "subtask-1", currentState,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOW", "", "test-policy"));
    }

    private void seedRuleAndMapping() {
        LocalDateTime now = LocalDateTime.now();
        String fieldContract = """
                {"business_fields":{"hospital_id":{"required":true},"request_time":{"required":true},
                "arrive_time":{"required":true},"consult_type":{"required":true}}}
                """;
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value,
                       1 AS numerator_count, 4 AS denominator_count, 4 AS sample_count
                FROM consult_record
                WHERE hospital_id=:hospital_id
                  AND consult_type=:consult_type_value
                  AND request_time>=:start_time AND request_time<:end_time
                """;
        jdbc.update(
                "INSERT INTO med_index_standard "
                        + "(index_code,index_name,index_type,index_desc,stat_cycle,numerator_rule,denominator_rule,"
                        + "filter_rule,exclude_rule,rely_table_field,calculation_definition,standard_sql,rule_params,"
                        + "source_path,version,status,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "MQSI2025_005", "急会诊及时到位率", "会诊制度", "及时到位次数占总次数比例。", "month",
                "及时到位次数", "急会诊总次数", "", "", fieldContract, "{}", sql,
                "{\"consult_type_value\":\"急会诊\"}", "rules/source.yml", "2025", 1, now, now);
        for (String field : List.of("hospital_id", "request_time", "arrive_time", "consult_type")) {
            jdbc.update(
                    "INSERT INTO med_field_mapping "
                            + "(hospital_id,rule_id,business_field,db_name,table_name,column_name,data_type,status) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    "h1", "MQSI2025_005", field, "business_test", "consult_record", field, "varchar", "confirmed");
            jdbc.update(
                    "INSERT INTO med_metadata_column "
                            + "(hospital_id,db_name,table_name,column_name,data_type,sync_batch_id,sync_time) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    "h1", "business_test", "consult_record", field,
                    field.endsWith("time") ? "datetime" : "varchar", "batch-1", now);
        }
    }

    private static class StubBusinessQuery implements IndicatorBusinessQueryClient {
        @Override
        public List<Map<String, Object>> execute(String sql) {
            assertThat(sql).contains("'h1'", "'2026-01-01 00:00:00'", "'2026-04-01 00:00:00'");
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
