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
import com.hospital.wikiagent.agent.sql.DatabaseRole;
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
        when(metadata.listTables(DatabaseRole.BUSINESS, "TEST_DB", "dbo")).thenReturn(List.of(
                Map.of("TABLE_NAME", "encounter")));
        when(metadata.listColumns(
                DatabaseRole.BUSINESS, "TEST_DB", "dbo", "encounter")).thenReturn(List.of(
                column("hospital_id", "varchar"),
                column("admission_id", "varchar"),
                column("admit_time", "datetime")));
    }

    @Test
    void stopsAtRealtimeStructureWhenRequiredTableIsMissing() {
        when(metadata.listTables(DatabaseRole.BUSINESS, "TEST_DB", "dbo"))
                .thenReturn(List.of());
        IndicatorDifferenceDiagnosisWorkflow workflow = workflow();

        ToolResult result = workflow.diagnose(input("我们有100人，系统只有98人"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("DIFFERENCE_DIAGNOSIS_COMPLETED");
        assertThat(result.data()).containsEntry("conclusionCode", "STRUCTURE_BLOCKING");
        assertThat(result.data().get("layers")).asList().hasSize(2);
        verify(sql, never()).prepare(any(), any());
        verify(reports).saveDifference(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), any());
    }

    @Test
    void treatsSingleAmbiguousNumberMatchAsPossibleRatherThanConfirmed() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001")).thenReturn(List.of(Map.of(
                "profileId", "candidate-100",
                "label", "候选口径",
                "sourceLevel", "company",
                "status", "approved",
                "effectiveFrom", "2025-01-01",
                "parameterOverrides", Map.of("threshold", 10),
                "evidenceKeywords", List.of("特殊阈值"))));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 98L, 200L, 49.0))
                .thenReturn(trial("RUN_CANDIDATE", 100L, 200L, 50.0));

        ToolResult result = workflow().diagnose(input("用户100，系统98，分析为什么不同"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("conclusionCode", "SYSTEM_RESULT_VERIFIED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.data().get("layers");
        Map<String, Object> caliber = layers.stream()
                .filter(layer -> Integer.valueOf(4).equals(layer.get("layer")))
                .findFirst().orElseThrow();
        assertThat(caliber).containsEntry("causeConfirmed", false);
        assertThat(caliber.get("candidates").toString())
                .contains("matchLevel=partial", "causeLikelihood=possible");
    }

    @Test
    void reportsPartialCaliberMatchAsLikelyAndKeepsUnexplainedDifference() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        Map<String, Object> uploaded = Map.ofEntries(
                Map.entry("fileName", "ward-entry-summary.xlsx"),
                Map.entry("rowCount", 1),
                Map.entry("columns", List.of("首次入区时间", "分子", "分母", "指标率")),
                Map.entry("fileEvidenceType", "summary"),
                Map.entry("uploadedRuleId", "MQSI2025_001"),
                Map.entry("uploadedStatPeriod",
                        "2026-01-01 00:00:00 至 2026-02-01 00:00:00"),
                Map.entry("uploadedNumerator", 12),
                Map.entry("uploadedDenominator", 234),
                Map.entry("uploadedRate", 5.13),
                Map.entry("comparisonLevel", "summary"));
        when(uploads.analyze(any(), any())).thenReturn(
                ToolResult.success("UPLOAD_ANALYZED", "analyzed", uploaded));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(
                ToolResult.success(
                        "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 11L, 394L, 2.79))
                .thenReturn(trial("RUN_CANDIDATE", 12L, 235L, 5.11));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-02-01T00:00:00",
                        "hospital_001_ward_entry_summary.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusionCode", "CALIBER_CAUSE_LIKELY");
        assertThat(result.data().get("caliberCandidates").toString())
                .contains(
                        "matchLevel=partial",
                        "causeLikelihood=likely",
                        "matchingDimensions=[numerator]",
                        "mismatchedDimensions=[denominator, rate]");
    }

    @Test
    void reportsCandidateAsNoMatchWhenEveryAggregateDimensionDiffers() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        Map<String, Object> uploaded = Map.ofEntries(
                Map.entry("fileName", "ward-entry-summary.xlsx"),
                Map.entry("rowCount", 1),
                Map.entry("columns", List.of("首次入区时间", "分子", "分母", "指标率")),
                Map.entry("fileEvidenceType", "summary"),
                Map.entry("uploadedRuleId", "MQSI2025_001"),
                Map.entry("uploadedStatPeriod",
                        "2026-01-01 00:00:00 至 2026-02-01 00:00:00"),
                Map.entry("uploadedNumerator", 20),
                Map.entry("uploadedDenominator", 400),
                Map.entry("uploadedRate", 5.0),
                Map.entry("comparisonLevel", "summary"));
        when(uploads.analyze(any(), any())).thenReturn(
                ToolResult.success("UPLOAD_ANALYZED", "analyzed", uploaded));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(
                ToolResult.success(
                        "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 11L, 394L, 2.79))
                .thenReturn(trial("RUN_CANDIDATE", 12L, 235L, 5.11));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-02-01T00:00:00",
                        "hospital_001_ward_entry_summary.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("conclusionCode", "SYSTEM_RESULT_VERIFIED");
        assertThat(result.data().get("caliberCandidates").toString())
                .contains(
                        "matchLevel=none",
                        "causeLikelihood=none",
                        "mismatchedDimensions=[numerator, denominator, rate]");
    }

    @Test
    void stopsAtPreflightWhenNeitherValuesNorFileAreProvided() {
        ToolResult result = workflow().diagnose(input("为什么双方结果不一样"), context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusionCode", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers")).asList().hasSize(1);
        verify(metadata, never()).listTables(any(DatabaseRole.class), anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void rejectsUploadedPeriodConflictBeforeReadingDatabaseMetadata() {
        when(uploads.analyze(any(), any())).thenReturn(ToolResult.success(
                "UPLOAD_ANALYZED", "analyzed", Map.of(
                        "fileName", "external.xlsx",
                        "rowCount", 100,
                        "columns", List.of("admission_id"),
                        "fileEvidenceType", "detail",
                        "uploadedRuleId", "MQSI2025_001",
                        "uploadedStatPeriod",
                        "2026-03-01 00:00:00 至 2026-04-01 00:00:00",
                        "comparisonLevel", "row")));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-02-01T00:00:00",
                        "hospital_001_test.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusionCode", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers").toString()).contains("FILE_PERIOD_CONFLICT");
        verify(metadata, never()).listTables(any(DatabaseRole.class), anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void ignoresExplanatoryDateWhenCheckingUploadedPeriodEndpoints() {
        when(uploads.analyze(any(), any())).thenReturn(ToolResult.success(
                "UPLOAD_ANALYZED", "analyzed", Map.of(
                        "fileName", "ward-entry.xlsx",
                        "rowCount", 234,
                        "columns", List.of("入院流水号", "首次入区时间", "是否达到要求"),
                        "fileEvidenceType", "detail",
                        "uploadedRuleId", "MQSI2025_001",
                        "uploadedStatPeriod",
                        "2026-01-01 00:00:00 至 2026-02-01 00:00:00"
                                + "（左闭右开，覆盖至2026-01-31自然日结束）",
                        "comparisonLevel", "row")));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-01-31T14:28:34",
                        "hospital_001_ward_entry.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusionCode", "INSUFFICIENT_EXTERNAL_EVIDENCE");
        assertThat(result.data().get("layers").toString()).contains("FILE_PERIOD_CONFLICT");
        verify(metadata, never()).listTables(any(DatabaseRole.class), anyString(), anyString());
        verify(sql, never()).prepare(any(), any());
    }

    @Test
    void confirmsWardEntryCaliberFromUploadedSchemaAndExactAggregate() {
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        Map<String, Object> uploaded = Map.ofEntries(
                Map.entry("fileName", "ward-entry.xlsx"),
                Map.entry("rowCount", 234),
                Map.entry("columns", List.of("入院流水号", "首次入区时间", "是否达到要求")),
                Map.entry("fileEvidenceType", "detail"),
                Map.entry("containsDetailRecords", true),
                Map.entry("uploadedRuleId", "MQSI2025_001"),
                Map.entry("uploadedStatPeriod",
                        "2026-01-01 00:00:00 至 2026-02-01 00:00:00"),
                Map.entry("uploadedCount", 234),
                Map.entry("uploadedNumeratorCount", 12),
                Map.entry("comparisonLevel", "row"),
                Map.entry("rowLevelComparisonAvailable", false));
        when(uploads.analyze(any(), any())).thenReturn(
                ToolResult.success("UPLOAD_ANALYZED", "analyzed", uploaded));
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_BASE")));
        when(sql.prepareDiagnostic(any(), anyString(), any(), any(), any())).thenReturn(
                ToolResult.success(
                        "SQL_OBJECT_PREPARED", "prepared", Map.of("sqlId", "SQL_CANDIDATE")));
        when(sql.trial(any(), any()))
                .thenReturn(trial("RUN_BASE", 11L, 394L, 2.79))
                .thenReturn(trial("RUN_CANDIDATE", 12L, 234L, 5.13));
        IndicatorDifferenceDiagnosisWorkflow.Input input =
                new IndicatorDifferenceDiagnosisWorkflow.Input(
                        "MQSI2025_001", "为什么文件与系统不一致",
                        "2026-01-01T00:00:00", "2026-02-01T00:00:00",
                        "hospital_001_ward_entry.xlsx");

        ToolResult result = workflow().diagnose(input, context);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry(
                "conclusionCode", "CALIBER_CAUSE_CONFIRMED");
        assertThat(result.data().get("caliberCandidates").toString())
                .contains("matchLevel=exact", "causeLikelihood=confirmed");
        assertThat(result.data().get("layers").toString())
                .contains("hospital_001_ward_entry_anchor", "fileSchemaEvidence=true");
        verify(sql).prepareDiagnostic(
                any(), anyString(), any(), any(), any());
    }

    private IndicatorDifferenceDiagnosisWorkflow workflow() {
        DbHubProperties properties = new DbHubProperties();
        properties.businessSource().setDatabaseName("TEST_DB");
        properties.businessSource().setSchemaName("dbo");
        IndicatorBusinessQueryClient business = mock(IndicatorBusinessQueryClient.class);
        return new IndicatorDifferenceDiagnosisWorkflow(
                rules, sql, uploads, metadata, properties,
                business, reports);
    }

    private static IndicatorDifferenceDiagnosisWorkflow.Input input(String issue) {
        return new IndicatorDifferenceDiagnosisWorkflow.Input(
                "MQSI2025_001", issue,
                "2026-01-01T00:00:00", "2026-02-01T00:00:00", null);
    }

    private static ToolResult trial(
            String runId,
            long numerator,
            long denominator,
            double rate) {
        return ToolResult.success("TRIAL_RUN_COMPLETED", "trial", Map.of(
                "runId", runId,
                "sqlId", "SQL_" + runId,
                "ruleId", "MQSI2025_001",
                "statStart", "2026-01-01 00:00:00",
                "statEnd", "2026-02-01 00:00:00",
                "numeratorCount", numerator,
                "denominatorCount", denominator,
                "resultValue", rate));
    }

    private static Map<String, Object> rule() {
        return Map.of(
                "ruleId", "MQSI2025_001",
                "effectiveParams", Map.of("hospital_id", "hospital_001", "threshold", 48),
                // field_contract/calculation_definition 内层是知识 Profile 透传键，保持 snake
                "fieldContract", Map.of("business_fields", Map.of(
                        "hospital_id", Map.of("type", "code"),
                        "admission_id", Map.of("type", "code"),
                        "admit_time", Map.of("type", "datetime"))),
                "calculationDefinition", Map.of(
                        "scope", Map.of("conditions", List.of(Map.of(
                                "field", "admit_time", "operator", "half_open_range"))),
                        "denominator", Map.of("aggregate", Map.of("field", "admission_id"))));
    }

    private static Map<String, Object> mapping() {
        return Map.of(
                "status", "confirmed",
                "dialect", "sqlserver",
                "dbName", "TEST_DB",
                "schema", "dbo",
                "mainTable", "encounter",
                "fields", Map.of(
                        "hospital_id", "encounter.hospital_id",
                        "admission_id", "encounter.admission_id",
                        "admit_time", "encounter.admit_time"),
                "relations", List.of());
    }

    private static Map<String, Object> column(String name, String type) {
        return Map.of("COLUMN_NAME", name, "DATA_TYPE", type);
    }

    private static Map<String, Object> wardEntryProfile() {
        return Map.of(
                "profileId", "hospital_001_ward_entry_anchor",
                "label", "首次入区时间统计及48小时口径",
                "sourceLevel", "hospital_history",
                "status", "approved",
                "effectiveFrom", "2026-01-01",
                "parameterOverrides", Map.of("threshold", 48),
                // field_role_overrides 内层是知识 Profile 透传键，保持 snake
                "fieldRoleOverrides", Map.of(
                        "period_time", "ward_entry_time",
                        "admit_time", "ward_entry_time"),
                "evidenceKeywords", List.of("首次入区", "入区时间"));
    }
}
