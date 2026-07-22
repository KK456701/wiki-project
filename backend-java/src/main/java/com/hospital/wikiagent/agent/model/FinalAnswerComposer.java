package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;

import tools.jackson.databind.ObjectMapper;

/** 使用已验证 Evidence 组织中文回答，并在协议泄漏时执行受控修复或确定性降级。 */
@Component
public class FinalAnswerComposer {
    public static final String VERSION = "final-answer-composer-v2";

    private final AgentModelInvoker models;
    private final AgentModelRegistry registry;
    private final AgentModelProperties properties;
    private final PromptCatalog prompts;
    private final ObjectMapper objectMapper;

    public FinalAnswerComposer(
            AgentModelInvoker models,
            AgentModelRegistry registry,
            AgentModelProperties properties,
            PromptCatalog prompts,
            ObjectMapper objectMapper) {
        this.models = models;
        this.registry = registry;
        this.properties = properties;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public FinalAnswerResult compose(FinalAnswerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        String userPrompt = buildUserPrompt(input);
        String raw = models.complete(
                modelId, prompts.finalAnswer(), userPrompt, properties.getFinalAnswerTimeout()).content();
        String error = validate(raw);
        if (error == null) {
            return new FinalAnswerResult(raw.strip(), modelId, false);
        }
        String correction = prompts.finalAnswerCorrection()
                .replace("{{validation_error}}", error)
                .replace("{{raw_output}}", raw == null ? "" : raw);
        String repaired = models.complete(
                modelId, prompts.finalAnswer(), userPrompt + "\n\n" + correction,
                properties.getFinalAnswerTimeout()).content();
        String repairedError = validate(repaired);
        if (repairedError != null) {
            String fallback = deterministicFallback(input.evidence());
            if (!fallback.isBlank()) {
                return new FinalAnswerResult(fallback, modelId, true, true);
            }
            throw new AgentModelUnavailableException(
                    "FINAL_ANSWER_INVALID", "模型未生成有效业务回答。");
        }
        return new FinalAnswerResult(repaired.strip(), modelId, true, false);
    }

    private String buildUserPrompt(FinalAnswerInput input) {
        List<Map<String, Object>> evidence = input.evidence().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("evidence_id", item.evidence().evidenceId());
            value.put("fact_type", item.evidence().factType());
            value.put("rule_id", item.evidence().ruleId());
            value.put("rule_version", item.evidence().ruleVersion());
            value.put("stat_start", item.evidence().statStart());
            value.put("stat_end", item.evidence().statEnd());
            value.put("source_tool", item.evidence().sourceTool());
            value.put("source_object_id", item.evidence().sourceObjectId());
            value.put("safe_payload", item.evidence().safePayload());
            value.put("verification_code", item.verification().code());
            return value;
        }).toList();
        try {
            return "当前日期：" + input.currentDate() + "\n"
                    + "用户问题：" + input.userMessage() + "\n"
                    + "计划目标：" + input.planGoal() + "\n"
                    + "最近对话（仅用于指代，不作为数值证据）：\n" + safe(input.recentHistory()) + "\n"
                    + "VerifiedEvidence：\n" + objectMapper.writeValueAsString(evidence);
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建最终回答证据上下文", exception);
        }
    }

    private static String validate(String content) {
        if (content == null || content.isBlank()) {
            return "回答为空";
        }
        String lower = content.toLowerCase();
        for (String forbidden : List.of("tool_calls", "function call", "<｜｜dsml｜｜", "invoke name=")) {
            if (lower.contains(forbidden)) {
                return "回答包含工具协议标记";
            }
        }
        return null;
    }

    private static String deterministicFallback(List<VerifiedEvidence> evidence) {
        Map<String, Object> rule = latest(evidence, "effective_rule");
        Map<String, Object> trial = latest(evidence, "trial_run");
        Map<String, Object> upload = latest(evidence, "file_analysis");
        Map<String, Object> diagnosis = latest(evidence, "diagnosis");
        Map<String, Object> preview = latest(evidence, "rule_change_preview");
        Map<String, Object> validation = latest(evidence, "implementation_validation_report");
        Map<String, Object> sql = latest(evidence, "sql_validation");
        if (!trial.isEmpty()) {
            String name = firstText(trial.get("rule_name"), rule.get("rule_name"), "该指标");
            StringBuilder value = new StringBuilder(name).append("统计结果如下：\n\n");
            append(value, "统计区间", period(trial));
            append(value, "分子", trial.get("numerator_count"));
            append(value, "分母", trial.get("denominator_count"));
            append(value, "指标值", percent(trial.get("result_value")));
            append(value, "计算公式", rule.get("formula"));
            return value.toString().strip();
        }
        if (!upload.isEmpty()) {
            StringBuilder value = new StringBuilder("上传文件分析结果如下：\n\n");
            append(value, "文件", firstText(upload.get("file_name"), upload.get("file_key")));
            append(value, "工作表数量", upload.get("sheet_count"));
            append(value, "数据行数", upload.get("row_count"));
            append(value, "分析摘要", upload.get("summary"));
            append(value, "对比状态", upload.get("comparison_status"));
            append(value, "已确认差异", upload.get("confirmed_findings"));
            return value.toString().strip();
        }
        if (!diagnosis.isEmpty()) {
            StringBuilder value = new StringBuilder("指标诊断结果如下：\n\n");
            append(value, "诊断状态", diagnosis.get("diagnose_status"));
            append(value, "结论", diagnosis.get("user_summary"));
            append(value, "已确认发现", diagnosis.get("confirmed_findings"));
            return value.toString().strip();
        }
        if (!preview.isEmpty()) {
            StringBuilder value = new StringBuilder("规则变更预览如下（尚未写入或发布）：\n\n");
            append(value, "当前生效口径", preview.get("current_effective"));
            append(value, "拟变更内容", preview.get("requested"));
            append(value, "字段变化", preview.get("field_changes"));
            append(value, "影响", preview.get("impact"));
            return value.toString().strip();
        }
        if (!validation.isEmpty()) {
            StringBuilder value = new StringBuilder("实施验收结果如下：\n\n");
            append(value, "总体状态", validation.get("overall_status"));
            append(value, "通过阶段", validation.get("passed_stages"));
            append(value, "警告阶段", validation.get("warning_stages"));
            append(value, "失败阶段", validation.get("failed_stages"));
            return value.toString().strip();
        }
        if (!sql.isEmpty()) {
            StringBuilder value = new StringBuilder("受控 SQL 已生成并通过只读安全校验。\n\n");
            append(value, "SQL 对象", sql.get("sql_id"));
            append(value, "统计区间", period(sql));
            return value.toString().strip();
        }
        if (!rule.isEmpty()) {
            String name = firstText(rule.get("rule_name"), "该指标");
            StringBuilder value = new StringBuilder(name).append("的本院生效口径如下：\n\n");
            append(value, "定义", rule.get("definition"));
            append(value, "计算公式", rule.get("formula"));
            append(value, "生效层级", rule.get("effective_level"));
            append(value, "本院版本", rule.get("hospital_version"));
            return value.toString().strip();
        }
        return "";
    }

    private static Map<String, Object> latest(List<VerifiedEvidence> values, String factType) {
        for (int index = values.size() - 1; index >= 0; index--) {
            var evidence = values.get(index).evidence();
            if (factType.equals(evidence.factType())) return evidence.safePayload();
        }
        return Map.of();
    }

    private static String period(Map<String, Object> value) {
        String start = firstText(value.get("stat_start"), value.get("stat_start_time"));
        String end = firstText(value.get("stat_end"), value.get("stat_end_time"));
        return start.isBlank() && end.isBlank() ? "" : start + " 至 " + end + "（左闭右开）";
    }

    private static String percent(Object value) {
        String text = firstText(value);
        return text.isBlank() || text.endsWith("%") ? text : text + "%";
    }

    private static void append(StringBuilder target, String label, Object raw) {
        String value = firstText(raw);
        if (!value.isBlank()) target.append("- ").append(label).append("：").append(value).append("\n");
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public record FinalAnswerInput(
            String userMessage,
            String planGoal,
            String modelId,
            LocalDate currentDate,
            String recentHistory,
            List<VerifiedEvidence> evidence) {
        public FinalAnswerInput {
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("用户问题不能为空");
            }
            currentDate = currentDate == null ? LocalDate.now() : currentDate;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record FinalAnswerResult(
            String content,
            String modelId,
            boolean corrected,
            boolean deterministicFallback) {
        public FinalAnswerResult(String content, String modelId, boolean corrected) {
            this(content, modelId, corrected, false);
        }
    }
}
