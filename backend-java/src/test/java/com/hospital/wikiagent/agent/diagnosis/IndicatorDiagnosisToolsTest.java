package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlObjectRepository;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.agent.sql.SqlTemplateRenderer;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

class IndicatorDiagnosisToolsTest {
    private JdbcTemplate jdbc;
    private StubBusinessQuery business;
    private IndicatorDiagnosisTools diagnosis;
    private AgentRunState state;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("diagnosis_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        seed();
        RuleReadRepository rules = new RuleReadRepository(jdbc, objectMapper);
        business = new StubBusinessQuery();
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                rules, new SqlObjectRepository(jdbc, objectMapper), new SqlTemplateRenderer(),
                new ReadOnlySqlValidator(), new SqlParameterBinder(), business, objectMapper);
        diagnosis = new IndicatorDiagnosisTools(
                rules, sqlTools, business, new DiagnosisReportRepository(jdbc, objectMapper));
        state = new AgentRunState();
        state.currentRuleId("MQSI2025_005");
        AgentRuntimeContext runtime = new AgentRuntimeContext(
                new HospitalPrincipal("u1", "doctor", "h1", Set.of(), false, "login-session"),
                "request-1", "trace-1", "business_test");
        context = new ToolExecutionContext(
                runtime, "subtask-1", state,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOW", "", "test-policy"));
    }

    @Test
    void persistsThreeLayerHealthyDiagnosisWithAggregateOnlyDbHubChecks() {
        ToolResult result = diagnosis.diagnose(
                new IndicatorDiagnosisTools.Input(
                        "MQSI2025_005", "排查这个指标为什么异常", "2026-01-01T00:00~2026-04-01T00:00"),
                context);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("INDICATOR_DIAGNOSED");
        assertThat(result.data()).containsEntry("diagnose_status", "healthy");
        assertThat(result.data().get("layers")).asList().hasSize(3);
        assertThat(business.sql).isNotEmpty().allMatch(sql -> sql.startsWith("SELECT"));
        assertThat(business.sql).noneMatch(sql -> sql.contains("patient_id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_index_diagnose_report", Integer.class)).isEqualTo(1);
    }

    @Test
    void stopsAtStructureLayerWhenLatestMetadataColumnIsMissing() {
        jdbc.update("DELETE FROM med_metadata_column WHERE column_name='arrive_time'");

        ToolResult result = diagnosis.diagnose(
                new IndicatorDiagnosisTools.Input("MQSI2025_005", "排查字段问题", null), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("diagnose_status", "failed");
        assertThat(result.data().get("layers")).asList().hasSize(1);
        assertThat(result.data().get("summary").toString()).contains("最新元数据中缺少字段");
        assertThat(business.sql).isEmpty();
    }

    @Test
    void reportsSafeLayerThreeFailureWhenDbHubIsUnavailable() {
        business.fail = true;

        ToolResult result = diagnosis.diagnose(
                new IndicatorDiagnosisTools.Input("MQSI2025_005", "排查业务库访问失败", null), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("diagnose_status", "failed");
        assertThat(result.data().get("summary").toString())
                .contains("无法通过 DBHub 访问业务主表")
                .doesNotContain("password", "internal");
    }

    private void seed() {
        LocalDateTime now = LocalDateTime.now();
        String fieldContract = """
                {"business_fields":{"hospital_id":{"required":true,"type":"code"},
                "request_time":{"required":true,"type":"datetime"},
                "arrive_time":{"required":true,"type":"datetime"},
                "consult_type":{"required":true,"type":"code"}}}
                """;
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value
                FROM consult_record
                WHERE hospital_id=:hospital_id AND request_time>=:start_time AND request_time<:end_time
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
                    "h1", "MQSI2025_005", field, "business_test", "consult_record", field,
                    field.endsWith("time") ? "datetime" : "varchar", "confirmed");
            jdbc.update(
                    "INSERT INTO med_metadata_column "
                            + "(hospital_id,db_name,table_name,column_name,data_type,sync_batch_id,sync_time) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    "h1", "business_test", "consult_record", field,
                    field.endsWith("time") ? "datetime" : "varchar", "batch-1", now);
        }
    }

    private static class StubBusinessQuery implements IndicatorBusinessQueryClient {
        private final List<String> sql = new ArrayList<>();
        private boolean fail;

        @Override
        public List<Map<String, Object>> execute(String statement) {
            sql.add(statement);
            if (fail) throw new IllegalStateException("password=secret internal-host");
            if (statement.contains(" AS nulls")) {
                return List.of(Map.of("total", 20, "nulls", 0));
            }
            return List.of(Map.of("total", 20));
        }

        @Override
        public String sourceId() {
            return "business_test";
        }
    }
}
