package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.model.AnswerTemplateRegistry.AnswerTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 使用已验证 Evidence 组织中文回答，并在协议泄漏时执行受控修复或确定性降级。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class FinalAnswerComposer {
    public static final String VERSION = "final-answer-composer-v3";

    private final AgentModelInvoker models;
    private final AgentModelRegistry registry;
    private final AgentModelProperties properties;
    private final PromptCatalog prompts;
    private final ObjectMapper objectMapper;
    private final AnswerTemplateRegistry templates;
    private final AnswerContractValidator contractValidator;

    @Autowired
    public FinalAnswerComposer(
            AgentModelInvoker models,
            AgentModelRegistry registry,
            AgentModelProperties properties,
            PromptCatalog prompts,
            ObjectMapper objectMapper,
            AnswerTemplateRegistry templates,
            AnswerContractValidator contractValidator) {
        this.models = models;
        this.registry = registry;
        this.properties = properties;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
        this.templates = templates;
        this.contractValidator = contractValidator;
    }

    /**
     * 保留测试和少量包内构造代码的兼容入口；生产环境使用上方显式注入构造器。
     */
    public FinalAnswerComposer(
            AgentModelInvoker models,
            AgentModelRegistry registry,
            AgentModelProperties properties,
            PromptCatalog prompts,
            ObjectMapper objectMapper) {
        this(models, registry, properties, prompts, objectMapper,
                new AnswerTemplateRegistry(), new AnswerContractValidator());
    }

    public FinalAnswerResult compose(FinalAnswerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        AnswerTemplate template = templates.resolve(input.intent(), input.requestedOutputs());
        String userPrompt = buildUserPrompt(input, template);
        String raw = models.complete(
                modelId, prompts.finalAnswer(), userPrompt, properties.getFinalAnswerTimeout()).content();
        String error = contractValidator.validate(raw, template, input.evidence());
        if (error == null) {
            return new FinalAnswerResult(
                    raw.strip(), modelId, false, false,
                    template.id(), template.version(), true);
        }
        String correction = prompts.finalAnswerCorrection()
                .replace("{{validation_error}}", error)
                .replace("{{raw_output}}", raw == null ? "" : raw);
        String repaired = models.complete(
                modelId, prompts.finalAnswer(), userPrompt + "\n\n" + correction,
                properties.getFinalAnswerTimeout()).content();
        String repairedError = contractValidator.validate(repaired, template, input.evidence());
        if (repairedError != null) {
            String fallback = deterministicFallback(input.evidence(), template);
            if (!fallback.isBlank()) {
                return new FinalAnswerResult(
                        fallback, modelId, true, true,
                        template.id(), template.version(), true);
            }
            throw new AgentModelUnavailableException(
                    "FINAL_ANSWER_INVALID", "模型未生成有效业务回答。");
        }
        return new FinalAnswerResult(
                repaired.strip(), modelId, true, false,
                template.id(), template.version(), true);
    }

    public AnswerTemplate selectTemplate(
            PlanIntent intent,
            List<RequestedOutput> requestedOutputs) {
        return templates.resolve(intent, requestedOutputs);
    }

    private String buildUserPrompt(FinalAnswerInput input, AnswerTemplate template) {
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
                    + "计划意图：" + input.intent().value() + "\n"
                    + "输出目标：" + input.requestedOutputs().stream()
                            .map(RequestedOutput::value).toList() + "\n"
                    + "最近对话（仅用于指代，不作为数值证据）：\n" + safe(input.recentHistory()) + "\n"
                    + "VerifiedEvidence：\n" + objectMapper.writeValueAsString(evidence) + "\n\n"
                    + "本轮回答模板：" + template.id() + "@" + template.version()
                    + "（" + template.kind() + "）\n"
                    + "请严格保留模板规定的标题和信息层级，用 VerifiedEvidence 替换占位符；"
                    + "不要输出占位符、模板说明或没有证据的事实。\n"
                    + "<answer_template>\n" + template.body() + "\n</answer_template>";
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建最终回答证据上下文", exception);
        }
    }

    private static String deterministicFallback(
            List<VerifiedEvidence> evidence,
            AnswerTemplate template) {
        Map<String, Object> rule = latest(evidence, "effective_rule");
        Map<String, Object> trial = latest(evidence, "trial_run");
        Map<String, Object> caliberTrial = latest(evidence, "caliber_trial_result");
        Map<String, Object> upload = latest(evidence, "file_analysis");
        Map<String, Object> diagnosis = latest(evidence, "diagnosis");
        Map<String, Object> difference = latest(evidence, "difference_diagnosis_report");
        Map<String, Object> preview = latest(evidence, "rule_change_preview");
        Map<String, Object> validation = latest(evidence, "implementation_validation_report");
        Map<String, Object> sql = latest(evidence, "sql_validation");
        if (!caliberTrial.isEmpty()) trial = caliberTrial;
        if (!trial.isEmpty()) {
            String name = firstText(trial.get("rule_name"), rule.get("rule_name"), "该指标");
            StringBuilder value = new StringBuilder("# ").append(name).append(" · 统计结果\n\n")
                    .append("> **结论速览**  \n> 指标率为 **")
                    .append(percent(trial.get("result_value"))).append("**，统计区间为 ")
                    .append(period(trial)).append("。\n\n")
                    .append("## 结果速览\n\n")
                    .append("| 统计项 | 结果 |\n|---|---:|\n")
                    .append("| 统计区间 | ").append(period(trial)).append(" |\n")
                    .append("| 分子 | ").append(firstText(trial.get("numerator_count"), "—")).append(" |\n")
                    .append("| 分母 | ").append(firstText(trial.get("denominator_count"), "—")).append(" |\n")
                    .append("| 指标率 | **").append(percent(trial.get("result_value"))).append("** |\n\n")
                    .append("## 计算口径\n\n");
            append(value, "计算公式", rule.get("formula"));
            append(value, "分子口径", rule.get("numerator_rule"));
            append(value, "分母口径", rule.get("denominator_rule"));
            value.append("\n## 数据依据\n\n");
            append(value, "规则版本", firstText(trial.get("hospital_version"), rule.get("hospital_version")));
            append(value, "试运行对象", trial.get("run_id"));
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
            StringBuilder value = new StringBuilder("# 指标异常诊断\n\n")
                    .append("## 诊断结论\n\n");
            append(value, "诊断状态", diagnosis.get("diagnose_status"));
            append(value, "结论", diagnosis.get("user_summary"));
            value.append("\n## 已确认事实\n\n");
            append(value, "已确认发现", diagnosis.get("confirmed_findings"));
            value.append("\n## 建议处理\n\n请根据上述已确认事实继续核对。\n");
            return value.toString().strip();
        }
        if (!difference.isEmpty()) {
            StringBuilder value = new StringBuilder("# 指标结果差异诊断\n\n")
                    .append("## 双方结果\n\n");
            append(value, "当前口径结果", difference.get("baseline_result"));
            append(value, "用户或文件结果", difference.get("external_evidence"));
            value.append("\n## 候选口径试算\n\n");
            append(value, "候选结果", difference.get("caliber_candidates"));
            value.append("\n## 诊断结论\n\n");
            append(value, "结论代码", difference.get("conclusion_code"));
            append(value, "停止层级", difference.get("stopped_layer"));
            append(value, "结论", difference.get("user_summary"));
            value.append("\n## 证据限制\n\n")
                    .append(firstText(difference.get("evidence_limit"),
                            "当前证据只能支持上述结论。"));
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
            StringBuilder value = new StringBuilder("# ").append(name).append("\n\n")
                    .append("> **口径速览**  \n")
                    .append("> 以下内容来自当前已验证的本院生效口径；未提供的内容不会推测补充。\n\n")
                    .append("## 口径摘要\n\n")
                    .append("| 项目 | 内容 |\n|---|---|\n")
                    .append("| 指标定义 | ").append(firstText(
                            rule.get("definition"), "当前证据未提供")).append(" |\n")
                    .append("| 规则编号 | ").append(firstText(
                            rule.get("rule_id"), "当前证据未提供")).append(" |\n")
                    .append("| 规则版本 | ").append(firstText(
                            rule.get("hospital_version"), rule.get("version"),
                            "当前证据未提供")).append(" |\n")
                    .append("| 生效层级 | ").append(firstText(
                            rule.get("effective_level"), "当前证据未提供")).append(" |\n\n")
                    .append("## 计算口径\n\n");
            append(value, "计算公式", rule.get("formula"));
            append(value, "分子口径", rule.get("numerator_rule"));
            append(value, "分母口径", rule.get("denominator_rule"));
            append(value, "统计时间字段", firstText(
                    rule.get("period_time_field"), rule.get("period_time")));
            append(value, "去重方式", firstText(
                    rule.get("distinct_key"), rule.get("deduplication")));
            append(value, "排除条件", firstText(
                    rule.get("exclude_rule"), rule.get("exclusion_rule")));
            value.append("\n## 实施信息\n\n");
            append(value, "生效状态", firstText(
                    rule.get("implementation_status"), rule.get("status")));
            append(value, "字段映射", rule.get("mapping_status"));
            append(value, "SQL 状态", rule.get("sql_status"));
            value.append("\n> **使用说明**  \n")
                    .append("> 当前证据未提供国标对比结论，不能据此判断本院口径与国标是否一致。\n");
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
            PlanIntent intent,
            List<RequestedOutput> requestedOutputs,
            String modelId,
            LocalDate currentDate,
            String recentHistory,
            List<VerifiedEvidence> evidence) {
        public FinalAnswerInput(
                String userMessage,
                String planGoal,
                String modelId,
                LocalDate currentDate,
                String recentHistory,
                List<VerifiedEvidence> evidence) {
            this(userMessage, planGoal, PlanIntent.UNKNOWN, List.of(), modelId,
                    currentDate, recentHistory, evidence);
        }

        public FinalAnswerInput {
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("用户问题不能为空");
            }
            intent = intent == null ? PlanIntent.UNKNOWN : intent;
            requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
            currentDate = currentDate == null ? LocalDate.now() : currentDate;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record FinalAnswerResult(
            String content,
            String modelId,
            boolean corrected,
            boolean deterministicFallback,
            String templateId,
            String templateVersion,
            boolean contractValidated) {
        public FinalAnswerResult(String content, String modelId, boolean corrected) {
            this(content, modelId, corrected, false, "", "", true);
        }

        public FinalAnswerResult(
                String content,
                String modelId,
                boolean corrected,
                boolean deterministicFallback) {
            this(content, modelId, corrected, deterministicFallback, "", "", true);
        }
    }
}
