package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.DualDatabaseIndicatorExecutionWorkflow;
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
    private RuleReadRepository rules;
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
        rules = mock(RuleReadRepository.class);
        when(rules.effectiveRule(anyString(), anyString())).thenReturn(executableRule());
        when(rules.effectiveRule(anyString(), anyString(), isNull()))
                .thenReturn(executableRule());
        when(rules.effectiveRule(anyString(), anyString(), anyString()))
                .thenReturn(executableRule());
        when(rules.fieldMapping(anyString(), anyString())).thenReturn(confirmedMapping());
        when(rules.fieldMapping(anyString(), anyString(), isNull()))
                .thenReturn(confirmedMapping());
        when(rules.fieldMapping(anyString(), anyString(), anyString()))
                .thenReturn(confirmedMapping());
        business = new StubBusinessQuery();
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                rules, new SqlObjectRepository(jdbc, objectMapper), new SqlTemplateRenderer(),
                new ReadOnlySqlValidator(), new SqlParameterBinder(), business, objectMapper);
        DualDatabaseIndicatorExecutionWorkflow controlledWorkflow =
                mock(DualDatabaseIndicatorExecutionWorkflow.class);
        when(controlledWorkflow.enabled()).thenReturn(true);
        when(controlledWorkflow.execute(any(), any(), anyString(), any(), any()))
                .thenReturn(ToolResult.success(
                        "TRIAL_RUN_COMPLETED",
                        "双库诊断执行完成",
                        Map.of(
                                "run_id", "RUN_DIAG_TEST",
                                "diagnosis_report_id", "DDR_TEST",
                                "extraction_status", "SKIPPED_DISABLED",
                                "data_freshness", "existing_snapshot_not_refreshed",
                                "comparison_status", "matched",
                                "business_result", Map.of("result_value", 25.0),
                                "real_result", Map.of("result_value", 25.0),
                                "dual_difference_diagnosis",
                                Map.of("status", "completed"))));
        sqlTools.setDualDatabaseWorkflow(controlledWorkflow);
        diagnosis = new IndicatorDiagnosisTools(
                rules, sqlTools, business, new DiagnosisReportRepository(jdbc, objectMapper));
        state = new AgentRunState();
        state.currentRuleId("HXZD-003-001");
        state.lastIntent("indicator_diagnosis");
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
                        "HXZD-003-001", "排查这个指标为什么异常",
                        "2026-01-01T00:00~2026-02-01T00:00", "company-default"),
                context);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("INDICATOR_DIAGNOSED");
        assertThat(result.data()).containsEntry("diagnose_status", "warning");
        assertThat(result.data()).containsKeys("confirmed_findings", "evidence_limit");
        assertThat(result.data().get("layers")).asList().hasSize(4);
        assertThat(business.sql).isNotEmpty().allMatch(sql -> sql.startsWith("SELECT"));
        assertThat(business.sql).noneMatch(sql -> sql.contains("patient_id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_index_diagnose_report", Integer.class)).isEqualTo(1);
    }

    @Test
    void stopsAtStructureLayerWhenLatestMetadataColumnIsMissing() {
        Map<String, Object> mapping = new LinkedHashMap<>(confirmedMapping());
        List<Map<String, Object>> metadata = new ArrayList<>(
                (List<Map<String, Object>>) mapping.get("metadata_items"));
        metadata.replaceAll(item -> "arrive_time".equals(item.get("business_field"))
                ? Map.of(
                        "business_field", "arrive_time",
                        "table_name", "consult_record",
                        "column_name", "arrive_time",
                        "data_type", "datetime",
                        "mapping_data_type", "datetime",
                        "metadata_data_type", "",
                        "status", "confirmed")
                : item);
        mapping.put("metadata_items", metadata);
        when(rules.fieldMapping(anyString(), anyString(), anyString())).thenReturn(mapping);

        ToolResult result = diagnosis.diagnose(
                new IndicatorDiagnosisTools.Input(
                        "HXZD-003-001", "排查字段问题", null, "company-default"),
                context);

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
                new IndicatorDiagnosisTools.Input(
                        "HXZD-003-001", "排查业务库访问失败",
                        "2026-01-01T00:00~2026-02-01T00:00", "company-default"),
                context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("diagnose_status", "failed");
        assertThat(result.data().get("summary").toString())
                .contains("无法通过 DBHub 访问业务主表")
                .doesNotContain("password", "internal");
    }

    @Test
    void blocksDatabaseDiagnosisWhenPublishedMainTableMappingIsMissing() {
        Map<String, Object> rule = new LinkedHashMap<>(executableRule());
        rule.put("field_contract", Map.of("business_fields", Map.of()));
        when(rules.effectiveRule(anyString(), anyString(), anyString())).thenReturn(rule);
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("status", "missing");
        mapping.put("dialect", "sqlserver");
        mapping.put("schema", "");
        mapping.put("main_table", "");
        mapping.put("fields", Map.of());
        mapping.put("items", List.of());
        mapping.put("metadata_items", List.of());
        mapping.put("relations", List.of());
        when(rules.fieldMapping(anyString(), anyString(), anyString())).thenReturn(mapping);

        ToolResult result = diagnosis.diagnose(
                new IndicatorDiagnosisTools.Input(
                        "HXZD-003-001", "直接排查这个指标",
                        "2026-01-01T00:00~2026-02-01T00:00", "company-default"),
                context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("diagnose_status", "failed");
        assertThat(result.data().get("summary").toString())
                .contains("字段映射或元数据预检查未通过")
                .doesNotContain("主表标识无效");
    }

    private static Map<String, Object> executableRule() {
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value
                FROM consult_record
                WHERE hospital_id=:hospital_id AND request_time>=:start_time AND request_time<:end_time
                """;
        return Map.ofEntries(
                Map.entry("rule_id", "HXZD-003-001"),
                Map.entry("rule_name", "急会诊及时到位率"),
                Map.entry("profile_id", "company-default"),
                Map.entry("definition", "急会诊及时到位次数占总次数比例。"),
                Map.entry("formula", "分子 ÷ 分母 × 100%"),
                Map.entry("execution_status", "executable"),
                Map.entry("standard_sql", sql),
                Map.entry("field_contract", Map.of("business_fields", Map.of(
                        "hospital_id", Map.of("required", true, "type", "code"),
                        "request_time", Map.of("required", true, "type", "datetime"),
                        "arrive_time", Map.of("required", true, "type", "datetime"),
                        "consult_type", Map.of("required", true, "type", "code")))),
                Map.entry("effective_params", Map.of("consult_type_value", "急会诊")),
                Map.entry("overridden_fields", List.of()));
    }

    private static Map<String, Object> confirmedMapping() {
        List<Map<String, Object>> items = List.of(
                mappingItem("hospital_id", "varchar"),
                mappingItem("request_time", "datetime"),
                mappingItem("arrive_time", "datetime"),
                mappingItem("consult_type", "varchar"));
        return Map.of(
                "status", "confirmed",
                "dialect", "sqlserver",
                "schema", "dbo",
                "main_table", "consult_record",
                "fields", Map.of(
                        "hospital_id", "consult_record.hospital_id",
                        "request_time", "consult_record.request_time",
                        "arrive_time", "consult_record.arrive_time",
                        "consult_type", "consult_record.consult_type"),
                "items", items,
                "metadata_items", items,
                "relations", List.of());
    }

    private static Map<String, Object> mappingItem(String field, String type) {
        return Map.of(
                "business_field", field,
                "table_name", "consult_record",
                "column_name", field,
                "data_type", type,
                "mapping_data_type", type,
                "metadata_data_type", type,
                "status", "confirmed");
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
