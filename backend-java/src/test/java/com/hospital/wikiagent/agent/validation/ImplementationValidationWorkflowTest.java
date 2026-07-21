package com.hospital.wikiagent.agent.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.auth.HospitalPrincipal;

class ImplementationValidationWorkflowTest {

    @Test
    void runsFixedL1L4L5AndSkipsL6WithoutUpload() {
        IndicatorSqlTools sql = mock(IndicatorSqlTools.class);
        UploadedIndicatorTools upload = mock(UploadedIndicatorTools.class);
        ToolExecutionContext context = context(readyState());
        when(sql.prepare(any(), any())).thenReturn(ToolResult.success(
                "SQL_OBJECT_PREPARED", "prepared", Map.of(
                        "sql_id", "SQL_test", "validation_status", "validated")));
        when(sql.trial(any(), any())).thenReturn(ToolResult.success(
                "TRIAL_RUN_COMPLETED", "trial", Map.of(
                        "sql_id", "SQL_test", "run_id", "RUN_test", "status", "success",
                        "result_value", 25.0, "numerator_count", 1L, "denominator_count", 4L)));

        ImplementationValidationReport report = new ImplementationValidationWorkflow(sql, upload).run(
                new ImplementationValidationWorkflow.Input(
                        "MQSI2025_005", LocalDateTime.parse("2026-01-01T00:00:00"),
                        LocalDateTime.parse("2026-04-01T00:00:00"), null),
                context);

        assertThat(report.overallStatus()).isEqualTo(ValidationStageStatus.PASSED);
        assertThat(report.stages()).extracting(ValidationStageResult::stageId)
                .containsExactly("L1", "L4", "L5", "L6");
        assertThat(report.stages()).extracting(ValidationStageResult::status)
                .containsExactly(
                        ValidationStageStatus.PASSED,
                        ValidationStageStatus.PASSED,
                        ValidationStageStatus.PASSED,
                        ValidationStageStatus.SKIPPED);
        assertThat(report.runId()).isEqualTo("RUN_test");
        assertThat(report.resultValue()).isEqualTo(25.0);
        InOrder ordered = inOrder(sql);
        ordered.verify(sql).prepare(any(), any());
        ordered.verify(sql).trial(any(), any());
        verifyNoInteractions(upload);
    }

    @Test
    void returnsStructuredFailedReportInsteadOfAbortingWorkflow() {
        IndicatorSqlTools sql = mock(IndicatorSqlTools.class);
        UploadedIndicatorTools upload = mock(UploadedIndicatorTools.class);
        AgentRunState state = readyState();
        state.lastToolResults().removeIf(result -> "IMPLEMENTATION_INSPECTED".equals(result.code()));
        ToolExecutionContext context = context(state);
        when(sql.prepare(any(), any())).thenReturn(ToolResult.failure(
                "validation_failed", "FIELD_PRECHECK_FAILED", "字段映射未通过。", false));

        ImplementationValidationReport report = new ImplementationValidationWorkflow(sql, upload).run(
                new ImplementationValidationWorkflow.Input(
                        "MQSI2025_005", LocalDateTime.parse("2026-01-01T00:00:00"),
                        LocalDateTime.parse("2026-04-01T00:00:00"), null),
                context);

        assertThat(report.overallStatus()).isEqualTo(ValidationStageStatus.FAILED);
        assertThat(report.stages()).filteredOn(stage -> stage.status() == ValidationStageStatus.FAILED)
                .extracting(ValidationStageResult::stageId)
                .containsExactly("L1", "L5");
    }

    @Test
    void topLevelToolAlwaysReturnsAuditableCompletedReport() {
        ImplementationValidationWorkflow workflow = mock(ImplementationValidationWorkflow.class);
        ImplementationValidationReport report = new ImplementationValidationReport(
                ImplementationValidationReport.VERSION, "IVR_test", "hospital_001",
                "MQSI2025_005", "急会诊及时到位率",
                "2026-01-01 00:00:00", "2026-04-01 00:00:00",
                ValidationStageStatus.WARNING,
                List.of(new ValidationStageResult(
                        "L6", "报表数据核对", ValidationStageStatus.WARNING,
                        "存在差异。", List.of("ROW_COMPARISON_DIFFERENCES"), Map.of(), 2)),
                "SQL_test", "RUN_test", 25.0, 1, 4, "upload.xlsx", null);
        when(workflow.run(any(), any())).thenReturn(report);

        ToolResult result = new ImplementationValidationTools(workflow).validate(
                new ImplementationValidationTools.Input(
                        "MQSI2025_005", "2026-01-01T00:00:00",
                        "2026-04-01T00:00:00", "upload.xlsx"),
                context(readyState()));

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("IMPLEMENTATION_VALIDATION_COMPLETED");
        assertThat(result.data()).containsEntry("overall_status", "warning")
                .containsEntry("warning_stages", List.of("L6"))
                .containsEntry("report_id", "IVR_test");
    }

    private static AgentRunState readyState() {
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_005");
        state.lastToolResults().add(ToolResult.success(
                "EFFECTIVE_RULE_FOUND", "effective", Map.of(
                        "rule_id", "MQSI2025_005", "rule_name", "急会诊及时到位率",
                        "definition", "定义", "formula", "公式", "hospital_version", 1)));
        state.lastToolResults().add(ToolResult.success(
                "IMPLEMENTATION_INSPECTED", "inspected", Map.of(
                        "rule_id", "MQSI2025_005", "status", "confirmed",
                        "main_table", "consult_record", "dialect", "sqlserver",
                        "required_business_fields", List.of("request_time"),
                        "mapped_fields", List.of("request_time"),
                        "missing_mappings", List.of(), "unconfirmed_mappings", List.of())));
        return state;
    }

    private static ToolExecutionContext context(AgentRunState state) {
        AgentRuntimeContext runtime = new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "session"),
                "request", "trace", "business_test");
        return new ToolExecutionContext(runtime, "subtask", state,
                new PolicyDecision(Decision.ALLOW, "ALLOWED", "", "test"));
    }
}
