package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataCatalogClient;
import com.hospital.wikiagent.rules.RuleReadRepository;

class IndicatorDifferenceDiagnosisWorkflowTest {
    private RuleReadRepository rules;
    private IndicatorSqlTools sql;
    private UploadedIndicatorTools uploads;
    private MetadataCatalogClient metadata;
    private DiagnosisReportRepository reports;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        rules = mock(RuleReadRepository.class);
        sql = mock(IndicatorSqlTools.class);
        uploads = mock(UploadedIndicatorTools.class);
        metadata = mock(MetadataCatalogClient.class);
        reports = mock(DiagnosisReportRepository.class);
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_001");
        state.subtaskId("subtask-1");
        AgentRuntimeContext runtime = new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user-1", "doctor", "hospital_001",
                        Set.of("indicator_detail_view", "indicator_detail_export"),
                        false, "login-1"),
                "request-1", "trace-1", "dbhub-test");
        context = new ToolExecutionContext(
                runtime, "subtask-1", state,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOW", "", "test"));
        when(rules.effectiveRule("MQSI2025_001", "hospital_001")).thenReturn(rule());
        when(rules.fieldMapping("MQSI2025_001", "hospital_001")).thenReturn(mapping());
        when(rules.dataQualityRules("MQSI2025_001")).thenReturn(List.of());
        when(metadata.listTables("TEST_DB", "dbo")).thenReturn(List.of(
                Map.of("TABLE_NAME", "encounter")));
        when(metadata.listColumns("TEST_DB", "dbo", "encounter")).thenReturn(List.of(
                column("hospital_id", "varchar"),
                column("admission_id", "varchar"),
                column("admit_time", "datetime")));
    }

    @Test
    void stopsAtRealtimeStructureWhenRequiredTableIsMissing() {
        when(metadata.listTables("TEST_DB", "dbo")).thenReturn(List.of());
        IndicatorDifferenceDiagnosisWorkflow workflow = workflow();

        ToolResult result = workflow.diagnose(input("我们有100人，系统只有98人"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("DIFFERENCE_DIAGNOSIS_COMPLETED");
        assertThat(result.data()).containsEntry("conclusion_code", "STRUCTURE_BLOCKING");
        assertThat(result.data().get("layers")).asList().hasSize(2);
        verify(sql, never()).prepare(any(), any());
        verify(reports).saveDifference(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), any());
    }

    @Test
    void doesNotConfirmCaliberFromCoincidentalNumberMatchAlone() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001")).thenReturn(List.of(Map.of(
                "profile_id", "candidate-100",
                "label", "候选口径",
                "source_level", "company",
                "status", "approved",
                "effective_from", "2025-01-01",
                "parameter_overrides", Map.of("threshold", 10),
                "evidence_keywords", List.of("特殊阈值"))));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sql_id", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sql_id", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 98L, 200L, 49.0))
                .thenReturn(trial("RUN_CANDIDATE", 100L, 200L, 50.0));

        ToolResult result = workflow().diagnose(input("用户100，系统98，分析为什么不同"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("conclusion_code", "SYSTEM_RESULT_VERIFIED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.data().get("layers");
        Map<String, Object> caliber = layers.stream()
                .filter(layer -> Integer.valueOf(4).equals(layer.get("layer")))
                .findFirst().orElseThrow();
        assertThat(caliber).containsEntry("cause_confirmed", false);
        assertThat(caliber.get("candidates").toString()).contains("evidence_limit");
    }

    @Test
    void stopsAtPreflightWhenNeitherValuesNorFileAreProvided() {
        ToolResult result = workflow().diagnose(input("为什么双方结果不一样"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusion_code", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers")).asList().hasSize(1);
        verify(metadata, never()).listTables(anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void rejectsUploadedPeriodConflictBeforeReadingDatabaseMetadata() {
        when(uploads.analyze(any(), any())).thenReturn(ToolResult.success(
                "UPLOAD_ANALYZED", "analyzed", Map.of(
                        "file_name", "external.xlsx",
                        "row_count", 100,
                        "columns", List.of("admission_id"),
                        "file_evidence_type", "detail",
                        "uploaded_rule_id", "MQSI2025_001",
                        "uploaded_stat_period",
                        "2026-04-01 00:00:00 至 2026-07-01 00:00:00",
                        "comparison_level", "row")));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-04-01T00:00:00",
                        "hospital_001_test.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusion_code", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers").toString()).contains("FILE_PERIOD_CONFLICT");
        verify(metadata, never()).listTables(anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void ignoresExplanatoryDateWhenCheckingUploadedPeriodEndpoints() {
        when(uploads.analyze(any(), any())).thenReturn(ToolResult.success(
                "UPLOAD_ANALYZED", "analyzed", Map.of(
                        "file_name", "ward-entry.xlsx",
                        "row_count", 234,
                        "columns", List.of("入院流水号", "首次入区时间", "是否达到要求"),
                        "file_evidence_type", "detail",
                        "uploaded_rule_id", "MQSI2025_001",
                        "uploaded_stat_period",
                        "2026-01-01 00:00:00 至 2026-07-24 00:00:00"
                                + "（左闭右开，覆盖至2026-07-23自然日结束）",
                        "comparison_level", "row")));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-07-23T14:28:34",
                        "hospital_001_ward_entry.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusion_code", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers").toString()).contains("FILE_PERIOD_CONFLICT");
        verify(metadata, never()).listTables(anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void confirmsWardEntryCaliberFromUploadedSchemaAndExactAggregate() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001")).thenReturn(List.of(Map.of(
                "profile_id", "hospital_001_ward_entry_anchor",
                "label", "首次入区时间统计及48小时口径",
                "source_level", "hospital_history",
                "status", "approved",
                "effective_from", "2026-01-01",
                "parameter_overrides", Map.of("threshold", 48),
                "field_role_overrides", Map.of(
                        "period_time", "ward_entry_time",
                        "admit_time", "ward_entry_time"),
                "evidence_keywords", List.of("首次入区", "入区时间"))));
        Map<String, Object> uploaded = Map.ofEntries(
                Map.entry("file_name", "ward-entry.xlsx"),
                Map.entry("row_count", 234),
                Map.entry("columns", List.of("入院流水号", "首次入区时间", "是否达到要求")),
                Map.entry("file_evidence_type", "detail"),
                Map.entry("contains_detail_records", true),
                Map.entry("uploaded_rule_id", "MQSI2025_001"),
                Map.entry("uploaded_stat_period",
                        "2026-01-01 00:00:00 至 2026-07-24 00:00:00"),
                Map.entry("uploaded_count", 234),
                Map.entry("uploaded_numerator_count", 12),
                Map.entry("comparison_level", "row"),
                Map.entry("row_level_comparison_available", false));
        when(uploads.analyze(any(), any())).thenReturn(
                ToolResult.success("UPLOAD_ANALYZED", "analyzed", uploaded));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sql_id", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(
                ToolResult.success(
                        "SQL_OBJECT_PREPARED", "prepared", Map.of("sql_id", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 11L, 394L, 2.79))
                .thenReturn(trial("RUN_CANDIDATE", 12L, 234L, 5.13));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-07-24T00:00:00",
                        "hospital_001_ward_entry.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusion_code", "CALIBER_CAUSE_CONFIRMED");
        assertThat(result.data().get("layers").toString())
                .contains("hospital_001_ward_entry_anchor", "file_schema_evidence=true");
        verify(sql).prepareDiagnostic(
                any(), anyString(), any(), any(), any());
    }

    private IndicatorDifferenceDiagnosisWorkflow workflow() {
        DbHubProperties properties = new DbHubProperties();
        properties.setDatabaseName("TEST_DB");
        properties.setSchemaName("dbo");
        IndicatorBusinessQueryClient business = mock(IndicatorBusinessQueryClient.class);
        return new IndicatorDifferenceDiagnosisWorkflow(
                rules, sql, uploads, metadata, properties,
                business, reports);
    }

    private static IndicatorDifferenceDiagnosisWorkflow.Input input(String issue) {
        return new IndicatorDifferenceDiagnosisWorkflow.Input(
                "MQSI2025_001", issue,
                "2026-01-01T00:00:00", "2026-04-01T00:00:00", null);
    }

    private static ToolResult trial(
            String runId,
            long numerator,
            long denominator,
            double rate) {
        return ToolResult.success("TRIAL_RUN_COMPLETED", "trial", Map.of(
                "run_id", runId,
                "sql_id", "SQL_" + runId,
                "rule_id", "MQSI2025_001",
                "stat_start", "2026-01-01 00:00:00",
                "stat_end", "2026-04-01 00:00:00",
                "numerator_count", numerator,
                "denominator_count", denominator,
                "result_value", rate));
    }

    private static Map<String, Object> rule() {
        return Map.of(
                "rule_id", "MQSI2025_001",
                "effective_params", Map.of("hospital_id", "hospital_001", "threshold", 48),
                "field_contract", Map.of("business_fields", Map.of(
                        "hospital_id", Map.of("type", "code"),
                        "admission_id", Map.of("type", "code"),
                        "admit_time", Map.of("type", "datetime"))),
                "calculation_definition", Map.of(
                        "scope", Map.of("conditions", List.of(Map.of(
                                "field", "admit_time", "operator", "half_open_range"))),
                        "denominator", Map.of("aggregate", Map.of("field", "admission_id"))));
    }

    private static Map<String, Object> mapping() {
        return Map.of(
                "status", "confirmed",
                "dialect", "sqlserver",
                "db_name", "TEST_DB",
                "schema", "dbo",
                "main_table", "encounter",
                "fields", Map.of(
                        "hospital_id", "encounter.hospital_id",
                        "admission_id", "encounter.admission_id",
                        "admit_time", "encounter.admit_time"),
                "relations", List.of());
    }

    private static Map<String, Object> column(String name, String type) {
        return Map.of("COLUMN_NAME", name, "DATA_TYPE", type);
    }
}
