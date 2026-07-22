package com.hospital.wikiagent.agent.validation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;

/**
 * L1/L4/L5/可选 L6 固定顺序实施验收，模型不参与阶段选择。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class ImplementationValidationWorkflow {
    public static final String VERSION = "implementation-validation-mvp-v1";
    private static final DateTimeFormatter OUTPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IndicatorSqlTools sqlTools;
    private final UploadedIndicatorTools uploadTools;

    public ImplementationValidationWorkflow(
            IndicatorSqlTools sqlTools,
            UploadedIndicatorTools uploadTools) {
        this.sqlTools = sqlTools;
        this.uploadTools = uploadTools;
    }

    public ImplementationValidationReport run(Input input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        ValidationStageResult l1 = stageL1(input.ruleId(), state);
        RuleStage l4 = stageL4(input.ruleId(), state);
        TrialStage l5 = stageL5(input, context);
        ValidationStageResult l6 = stageL6(input.fileKey(), context);
        List<ValidationStageResult> stages = List.of(l1, l4.stage(), l5.stage(), l6);
        return new ImplementationValidationReport(
                ImplementationValidationReport.VERSION,
                "IVR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                context.agentContext().hospitalId(),
                input.ruleId(),
                l4.ruleName(),
                input.statStartTime().format(OUTPUT_TIME),
                input.statEndTime().format(OUTPUT_TIME),
                overall(stages),
                stages,
                text(l5.result().get("sql_id")),
                text(l5.result().get("run_id")),
                number(l5.result().get("result_value")),
                number(l5.result().get("numerator_count")),
                number(l5.result().get("denominator_count")),
                input.fileKey(),
                Instant.now());
    }

    private ValidationStageResult stageL1(String ruleId, AgentRunState state) {
        long started = System.nanoTime();
        ToolResult inspected = latest(state, "IMPLEMENTATION_INSPECTED");
        if (inspected == null) {
            return stage(started, "L1", "字段映射与来源检查", ValidationStageStatus.FAILED,
                    "未取得当前指标的实施映射证据。",
                    List.of("IMPLEMENTATION_EVIDENCE_MISSING"), Map.of());
        }
        Map<String, Object> data = inspected.data();
        List<String> missing = strings(data.get("missing_mappings"));
        List<String> unconfirmed = strings(data.get("unconfirmed_mappings"));
        boolean confirmed = ruleId.equals(text(data.get("rule_id")))
                && "confirmed".equals(text(data.get("status")))
                && missing.isEmpty() && unconfirmed.isEmpty()
                && text(data.get("main_table")) != null;
        List<String> findings = new ArrayList<>();
        if (!missing.isEmpty()) findings.add("FIELD_MAPPING_MISSING");
        if (!unconfirmed.isEmpty()) findings.add("FIELD_MAPPING_UNCONFIRMED");
        if (text(data.get("main_table")) == null) findings.add("MAIN_TABLE_MISSING");
        if (!ruleId.equals(text(data.get("rule_id")))) findings.add("RULE_ID_MISMATCH");
        if (!confirmed && findings.isEmpty()) findings.add("FIELD_MAPPING_NOT_CONFIRMED");
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "mapping_status", data.get("status"));
        put(details, "main_table", data.get("main_table"));
        put(details, "dialect", data.get("dialect"));
        details.put("required_field_count", size(data.get("required_business_fields")));
        details.put("mapped_field_count", size(data.get("mapped_fields")));
        details.put("missing_mappings", missing);
        details.put("unconfirmed_mappings", unconfirmed);
        return stage(started, "L1", "字段映射与来源检查",
                confirmed ? ValidationStageStatus.PASSED : ValidationStageStatus.FAILED,
                confirmed ? "必需字段、主表和本院字段映射均已确认。"
                        : "字段来源或本院映射未满足实施验收要求。",
                findings, details);
    }

    private RuleStage stageL4(String ruleId, AgentRunState state) {
        long started = System.nanoTime();
        ToolResult effective = latest(state, "EFFECTIVE_RULE_FOUND");
        if (effective == null) {
            return new RuleStage(stage(started, "L4", "规则口径对齐", ValidationStageStatus.FAILED,
                    "未取得当前医院的生效规则证据。",
                    List.of("EFFECTIVE_RULE_EVIDENCE_MISSING"), Map.of()), "");
        }
        Map<String, Object> data = effective.data();
        List<String> findings = new ArrayList<>();
        if (!ruleId.equals(text(data.get("rule_id")))) findings.add("RULE_ID_MISMATCH");
        if (text(data.get("definition")) == null) findings.add("RULE_DEFINITION_MISSING");
        if (text(data.get("formula")) == null) findings.add("RULE_FORMULA_MISSING");
        if (data.get("hospital_version") == null && data.get("national_version") == null
                && data.get("version") == null) {
            findings.add("RULE_VERSION_MISSING");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "rule_name", data.get("rule_name"));
        put(details, "effective_level", data.get("effective_level"));
        put(details, "national_version", data.get("national_version"));
        put(details, "hospital_version", data.get("hospital_version"));
        details.put("overridden_field_count", size(data.get("overridden_fields")));
        boolean passed = findings.isEmpty();
        return new RuleStage(stage(started, "L4", "规则口径对齐",
                passed ? ValidationStageStatus.PASSED : ValidationStageStatus.FAILED,
                passed ? "指标身份、定义、公式和生效版本均已确认。"
                        : "生效规则证据不完整或与当前指标不一致。",
                findings, details), blankTo(text(data.get("rule_name")), ""));
    }

    private TrialStage stageL5(Input input, ToolExecutionContext context) {
        long started = System.nanoTime();
        ToolResult prepared = sqlTools.prepare(new IndicatorSqlTools.PrepareInput(
                input.ruleId(), input.statStartTime().toString(), input.statEndTime().toString()), context);
        if (!prepared.ok()) {
            return new TrialStage(stage(started, "L5", "受控 SQL 与试运行", ValidationStageStatus.FAILED,
                    prepared.summary(), List.of(prepared.code()), Map.of(
                            "sql_prepare_status", prepared.status(),
                            "sql_prepare_code", prepared.code())), Map.of());
        }
        context.runState().lastToolResults().add(prepared);
        String sqlId = text(prepared.data().get("sql_id"));
        ToolResult trial = sqlTools.trial(new IndicatorSqlTools.TrialInput(sqlId), context);
        if (!trial.ok()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sql_id", sqlId);
            put(details, "sql_validation_status", prepared.data().get("validation_status"));
            details.put("trial_status", trial.status());
            details.put("trial_code", trial.code());
            return new TrialStage(stage(started, "L5", "受控 SQL 与试运行",
                    ValidationStageStatus.FAILED, trial.summary(), List.of(trial.code()), details),
                    Map.of("sql_id", sqlId));
        }
        context.runState().lastToolResults().add(trial);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sql_id", sqlId);
        put(details, "run_id", trial.data().get("run_id"));
        put(details, "sql_validation_status", prepared.data().get("validation_status"));
        put(details, "trial_status", trial.data().get("status"));
        put(details, "numerator_count", trial.data().get("numerator_count"));
        put(details, "denominator_count", trial.data().get("denominator_count"));
        put(details, "result_value", trial.data().get("result_value"));
        return new TrialStage(stage(started, "L5", "受控 SQL 与试运行",
                ValidationStageStatus.PASSED,
                "SQL 已通过安全校验并完成医院业务库只读试运行。", List.of(), details),
                trial.data());
    }

    @SuppressWarnings("unchecked")
    private ValidationStageResult stageL6(String fileKey, ToolExecutionContext context) {
        long started = System.nanoTime();
        if (fileKey == null || fileKey.isBlank()) {
            return stage(started, "L6", "报表数据核对", ValidationStageStatus.SKIPPED,
                    "本轮未指定上传文件，已跳过报表数据核对。", List.of(), Map.of());
        }
        ToolResult analyzed = uploadTools.analyze(new UploadedIndicatorTools.Input(fileKey), context);
        if (!analyzed.ok()) {
            return stage(started, "L6", "报表数据核对", ValidationStageStatus.FAILED,
                    analyzed.summary(), List.of(analyzed.code()), Map.of());
        }
        context.runState().lastToolResults().add(analyzed);
        Map<String, Object> data = analyzed.data();
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "file_name", data.get("file_name"));
        put(details, "row_count", data.get("row_count"));
        if (data.get("row_comparison") instanceof Map<?, ?> rawRow) {
            Map<String, Object> row = (Map<String, Object>) rawRow;
            String comparisonStatus = blankTo(text(row.get("comparison_status")), "");
            details.put("comparison_status", comparisonStatus);
            for (String key : List.of("both_count", "system_only_count", "uploaded_only_count",
                    "field_difference_count")) {
                put(details, key, row.get(key));
            }
            if ("indicator_mismatch".equals(comparisonStatus)) {
                return stage(started, "L6", "报表数据核对", ValidationStageStatus.FAILED,
                        blankTo(text(row.get("message")), "上传文件指标与当前指标不一致。"),
                        List.of("UPLOADED_INDICATOR_MISMATCH"), details);
            }
            if (Boolean.TRUE.equals(row.get("row_level_comparison_available"))) {
                int different = integer(row.get("system_only_count"))
                        + integer(row.get("uploaded_only_count"))
                        + integer(row.get("field_difference_count"));
                return stage(started, "L6", "报表数据核对",
                        different == 0 ? ValidationStageStatus.PASSED : ValidationStageStatus.WARNING,
                        different == 0 ? "上传明细与系统明细逐条一致。" : "上传明细与系统明细存在逐条差异。",
                        different == 0 ? List.of() : List.of("ROW_COMPARISON_DIFFERENCES"), details);
            }
        }
        List<Map<String, Object>> metrics = data.get("comparison_metrics") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance)
                        .map(value -> (Map<String, Object>) value).toList()
                : List.of();
        long mismatchCount = metrics.stream().filter(value -> !Boolean.TRUE.equals(value.get("match"))).count();
        details.put("comparison_status", blankTo(text(data.get("comparison_status")), "aggregate_compared"));
        details.put("metric_count", metrics.size());
        details.put("mismatch_count", mismatchCount);
        boolean passed = !metrics.isEmpty() && mismatchCount == 0;
        return stage(started, "L6", "报表数据核对",
                passed ? ValidationStageStatus.PASSED : ValidationStageStatus.WARNING,
                passed ? "上传汇总值与系统聚合结果一致。" : "上传汇总值与系统聚合结果存在差异或证据不足。",
                passed ? List.of() : List.of(metrics.isEmpty()
                        ? "REPORT_COMPARISON_EVIDENCE_INSUFFICIENT"
                        : "AGGREGATE_COMPARISON_DIFFERENCES"), details);
    }

    private static ValidationStageResult stage(
            long started,
            String id,
            String name,
            ValidationStageStatus status,
            String summary,
            List<String> findings,
            Map<String, Object> details) {
        return new ValidationStageResult(id, name, status, summary, findings, details,
                Math.max(1, (System.nanoTime() - started) / 1_000_000));
    }

    private static ToolResult latest(AgentRunState state, String code) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            if (result.ok() && code.equals(result.code())) return result;
        }
        return null;
    }

    private static ValidationStageStatus overall(List<ValidationStageResult> stages) {
        if (stages.stream().anyMatch(value -> value.status() == ValidationStageStatus.FAILED)) {
            return ValidationStageStatus.FAILED;
        }
        if (stages.stream().anyMatch(value -> value.status() == ValidationStageStatus.WARNING)) {
            return ValidationStageStatus.WARNING;
        }
        return ValidationStageStatus.PASSED;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    private static int size(Object value) {
        return value instanceof List<?> values ? values.size()
                : value instanceof Map<?, ?> values ? values.size() : 0;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    public record Input(
            String ruleId,
            LocalDateTime statStartTime,
            LocalDateTime statEndTime,
            String fileKey) {
        public Input {
            ruleId = ruleId == null ? "" : ruleId.strip();
            fileKey = fileKey == null || fileKey.isBlank() ? null : fileKey.strip();
            if (ruleId.isEmpty() || statStartTime == null || statEndTime == null
                    || !statStartTime.isBefore(statEndTime)) {
                throw new IllegalArgumentException("实施验收参数不完整或统计区间无效");
            }
        }
    }

    private record RuleStage(ValidationStageResult stage, String ruleName) {}
    private record TrialStage(ValidationStageResult stage, Map<String, Object> result) {}
}
