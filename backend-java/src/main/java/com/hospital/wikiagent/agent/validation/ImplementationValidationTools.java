package com.hospital.wikiagent.agent.validation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;

@Component
public class ImplementationValidationTools {
    private final ImplementationValidationWorkflow workflow;

    public ImplementationValidationTools(ImplementationValidationWorkflow workflow) {
        this.workflow = workflow;
    }

    public ToolResult validate(Input input, ToolExecutionContext context) {
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(input.statStartTime());
            end = LocalDateTime.parse(input.statEndTime());
        } catch (RuntimeException exception) {
            return ToolResult.failure("validation_failed", "STAT_PERIOD_INVALID", "统计时间格式无效。", false);
        }
        ImplementationValidationReport report;
        try {
            report = workflow.run(new ImplementationValidationWorkflow.Input(
                    input.ruleId(), start, end, input.fileKey()), context);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure("validation_failed", "IMPLEMENTATION_VALIDATION_INPUT_INVALID",
                    exception.getMessage(), false);
        }
        Map<String, List<String>> statusGroups = new LinkedHashMap<>();
        for (ValidationStageStatus status : ValidationStageStatus.values()) {
            statusGroups.put(status.value(), report.stages().stream()
                    .filter(stage -> stage.status() == status)
                    .map(ValidationStageResult::stageId).toList());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report_id", report.reportId());
        data.put("report_schema_version", report.schemaVersion());
        data.put("overall_status", report.overallStatus().value());
        data.put("rule_id", report.ruleId());
        data.put("rule_name", report.ruleName());
        data.put("hospital_id", report.hospitalId());
        data.put("stat_start", report.statStart());
        data.put("stat_end", report.statEnd());
        data.put("stages", report.stages().stream().map(ValidationStageResult::asMap).toList());
        data.put("passed_stages", statusGroups.get("passed"));
        data.put("warning_stages", statusGroups.get("warning"));
        data.put("failed_stages", statusGroups.get("failed"));
        data.put("skipped_stages", statusGroups.get("skipped"));
        put(data, "sql_id", report.sqlId());
        put(data, "run_id", report.runId());
        put(data, "result_value", report.resultValue());
        put(data, "numerator_count", report.numeratorCount());
        put(data, "denominator_count", report.denominatorCount());
        put(data, "file_key", report.fileKey());
        data.put("created_at", report.createdAt().toString());
        String summary = switch (report.overallStatus()) {
            case PASSED -> "指标全面实施验收已完成，结论为通过。";
            case WARNING -> "指标全面实施验收已完成，存在警告。";
            case FAILED -> "指标全面实施验收已完成，存在未通过项。";
            case SKIPPED -> "指标全面实施验收已完成。";
        };
        return ToolResult.success("IMPLEMENTATION_VALIDATION_COMPLETED", summary, data);
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    public record Input(String ruleId, String statStartTime, String statEndTime, String fileKey) {
        public Input {
            ruleId = ruleId == null ? "" : ruleId.strip();
            statStartTime = statStartTime == null ? "" : statStartTime.strip();
            statEndTime = statEndTime == null ? "" : statEndTime.strip();
            fileKey = fileKey == null || fileKey.isBlank() ? null : fileKey.strip();
            if (ruleId.isEmpty() || statStartTime.isEmpty() || statEndTime.isEmpty()) {
                throw new IllegalArgumentException("实施验收参数不完整");
            }
        }
    }
}
