package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;
import com.hospital.wikiagent.agent.ir.ExplanationFocus;
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
    public static final String VERSION = "final-answer-composer-v4";

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
        AnswerTemplate template = templates.resolve(
                input.intent(), input.requestedOutputs(), input.explanationFocuses());
        String userPrompt = buildUserPrompt(input, template);
        String raw;
        try {
            raw = models.complete(
                    modelId, prompts.finalAnswer(), userPrompt,
                    properties.getFinalAnswerTimeout()).content();
        } catch (RuntimeException exception) {
            return fallbackOrRethrow(input, template, modelId, false, exception);
        }
        String error = contractValidator.validate(raw, template, input.evidence());
        if (error == null) {
            return new FinalAnswerResult(
                    raw.strip(), modelId, false, false,
                    template.id(), template.version(), true);
        }
        String correction = prompts.finalAnswerCorrection()
                .replace("{{validation_error}}", error)
                .replace("{{raw_output}}", raw == null ? "" : raw);
        String repaired;
        try {
            repaired = models.complete(
                    modelId, prompts.finalAnswer(), userPrompt + "\n\n" + correction,
                    properties.getFinalAnswerTimeout()).content();
        } catch (RuntimeException exception) {
            return fallbackOrRethrow(input, template, modelId, true, exception);
        }
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

    /**
     * 已由确定性会话续接计划触发时，直接用 VerifiedEvidence 渲染，不先等待文案模型。
     */
    public FinalAnswerResult composeDeterministic(FinalAnswerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        AnswerTemplate template = templates.resolve(
                input.intent(), input.requestedOutputs(), input.explanationFocuses());
        String fallback = deterministicFallback(input.evidence(), template);
        if (fallback.isBlank()) {
            throw new AgentModelUnavailableException(
                    "FINAL_ANSWER_EVIDENCE_MISSING", "已验证证据不足，无法生成确定性回答。");
        }
        return new FinalAnswerResult(
                fallback, modelId, false, true,
                template.id(), template.version(), true);
    }

    private static FinalAnswerResult fallbackOrRethrow(
            FinalAnswerInput input,
            AnswerTemplate template,
            String modelId,
            boolean corrected,
            RuntimeException failure) {
        String fallback = deterministicFallback(input.evidence(), template);
        if (fallback.isBlank()) {
            throw failure;
        }
        return new FinalAnswerResult(
                fallback, modelId, corrected, true,
                template.id(), template.version(), true);
    }

    public AnswerTemplate selectTemplate(
            PlanIntent intent,
            List<RequestedOutput> requestedOutputs) {
        return templates.resolve(intent, requestedOutputs);
    }

    public AnswerTemplate selectTemplate(
            PlanIntent intent,
            List<RequestedOutput> requestedOutputs,
            List<ExplanationFocus> explanationFocuses) {
        return templates.resolve(intent, requestedOutputs, explanationFocuses);
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
                    + "本轮规则解释关注点：" + input.explanationFocuses().stream()
                            .map(ExplanationFocus::value).toList() + "\n"
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
        Map<String, Object> sql = latest(evidence, "sql_validation");
        if (!caliberTrial.isEmpty()) trial = caliberTrial;
        if (!trial.isEmpty()) {
            String name = firstText(trial.get("rule_name"), rule.get("rule_name"), "该指标");
            boolean noSample = isNoSample(trial);
            String resultDisplay = noSample ? "不适用" : percent(trial.get("result_value"));
            StringBuilder value = new StringBuilder("# ").append(name).append(" · 统计结果\n\n")
                    .append("> **结论速览**  \n> ")
                    .append(noSample ? "统计区间内无样本，指标率不适用" : "指标率为 **" + resultDisplay + "**")
                    .append("，统计区间为 ")
                    .append(period(trial)).append("。\n\n")
                    .append("## 结果速览\n\n")
                    .append("| 统计项 | 结果 |\n|---|---:|\n")
                    .append("| 统计区间 | ").append(period(trial)).append(" |\n")
                    .append("| 分子 | ").append(firstText(trial.get("numerator_count"), "—")).append(" |\n")
                    .append("| 分母 | ").append(firstText(trial.get("denominator_count"), "—")).append(" |\n")
                    .append("| 指标率 | **").append(resultDisplay).append("** |\n\n")
                    .append(dualComparisonSection(trial))
                    .append("## 计算口径\n\n");
            Map<String, Object> calculation = objectMap(rule.get("calculation_definition"));
            append(value, "计算公式", rule.get("formula"));
            append(value, "分子口径", firstText(
                    calculation.get("numerator_caliber"),
                    rule.get("numerator_rule")));
            append(value, "分母口径", firstText(
                    calculation.get("denominator_caliber"),
                    rule.get("denominator_rule")));
            append(value, "统计时间字段", timeDimensionLabel(
                    calculation.get("time_dimension")));
            append(value, "去重键", calculation.get("dedup_key"));
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
            List<Map<String, Object>> profileReports =
                    listOfMaps(diagnosis.get("profile_reports"));
            if (!profileReports.isEmpty()) {
                StringBuilder value = new StringBuilder("# 指标多口径异常诊断\n\n")
                        .append("已逐一诊断 ")
                        .append(profileReports.size())
                        .append(" 个已审批 Profile；每个 Profile 都独立经过数据准备、")
                        .append("双库概览和可用明细检查。\n");
                for (Map<String, Object> profile : profileReports) {
                    Map<String, Object> report = objectMap(profile.get("report"));
                    value.append("\n## ")
                            .append(firstText(
                                    profile.get("profile_name"),
                                    profile.get("profile_id")))
                            .append("\n\n");
                    append(value, "Profile 编号", profile.get("profile_id"));
                    append(value, "执行状态", profile.get("code"));
                    append(value, "结论", firstText(
                            report.get("user_summary"),
                            report.get("summary"),
                            profile.get("summary")));
                    append(value, "抽取状态", report.get("extraction_status"));
                    append(value, "数据新鲜度", report.get("data_freshness"));
                    append(value, "运行号", report.get("run_id"));
                    append(value, "诊断报告号", firstText(
                            report.get("diagnosis_report_id"),
                            report.get("report_id")));
                }
                return value.toString().strip();
            }
            StringBuilder value = new StringBuilder("# 指标异常诊断\n\n")
                    .append("## 当前计算和口径\n\n");
            append(value, "统计区间", diagnosis.get("stat_period"));
            append(value, "抽取状态", diagnosis.get("extraction_status"));
            append(value, "数据新鲜度", diagnosis.get("data_freshness"));
            value.append("\n## 已确认发现\n\n");
            append(value, "诊断状态", diagnosis.get("diagnose_status"));
            append(value, "已确认发现", diagnosis.get("confirmed_findings"));
            value.append("\n## 可能原因及置信度\n\n")
                    .append("当前确定性证据不足以给出额外原因推断。\n")
                    .append("\n## 尚不能确认的事项\n\n");
            append(value, "证据边界", diagnosis.get("evidence_limit"));
            value.append("\n## 建议的下一步核对方式\n\n")
                    .append("请根据上述证据缺口补充对应明细或外部预期结果。\n")
                    .append("\n## 证据与对象编号\n\n");
            append(value, "运行号", diagnosis.get("run_id"));
            append(value, "诊断报告号", firstText(
                    diagnosis.get("diagnosis_report_id"), diagnosis.get("report_id")));
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
        if (!sql.isEmpty()) {
            StringBuilder value = new StringBuilder("受控 SQL 已生成并通过只读安全校验。\n\n");
            append(value, "SQL 对象", sql.get("sql_id"));
            append(value, "统计区间", period(sql));
            return value.toString().strip();
        }
        if (!rule.isEmpty()) return renderRuleFallback(rule, template.explanationFocuses());
        return "";
    }

    private static String renderRuleFallback(
            Map<String, Object> rule,
            List<ExplanationFocus> requestedFocuses) {
        List<ExplanationFocus> focuses = requestedFocuses == null || requestedFocuses.isEmpty()
                ? List.of(ExplanationFocus.OVERVIEW)
                : requestedFocuses;
        String name = firstText(rule.get("rule_name"), "该指标");
        Map<String, Object> calculation = objectMap(rule.get("calculation_definition"));
        String definition = firstText(rule.get("definition"), "当前证据未提供");
        String formula = firstText(rule.get("formula"), "当前证据未提供");
        String numerator = firstText(
                calculation.get("numerator_caliber"),
                calculation.get("numerator"),
                rule.get("numerator_rule"),
                "当前证据未提供");
        String denominator = firstText(
                calculation.get("denominator_caliber"),
                calculation.get("denominator"),
                rule.get("denominator_rule"),
                "当前证据未提供");
        String timeDimension = firstText(
                timeDimensionLabel(calculation.get("time_dimension")),
                timeDimensionLabel(rule.get("period_time_field")),
                timeDimensionLabel(rule.get("period_time")),
                "当前证据未提供");
        String deduplication = firstText(
                calculation.get("dedup_key"),
                rule.get("distinct_key"),
                rule.get("deduplication"),
                "当前证据未提供");
        String exclusions = firstText(
                calculation.get("exclusions"),
                rule.get("exclude_rule"),
                rule.get("exclusion_rule"),
                "当前证据未提供");
        if (focuses.contains(ExplanationFocus.OVERVIEW)) {
            return new StringBuilder("# ").append(name).append("\n\n")
                    .append("> **口径速览**  \n> ")
                    .append(definition).append("\n\n")
                    .append("## 口径摘要\n\n")
                    .append("| 项目 | 内容 |\n|---|---|\n")
                    .append("| 指标定义 | ").append(definition).append(" |\n")
                    .append("| 规则编号 | ").append(firstText(
                            rule.get("rule_id"), "当前证据未提供")).append(" |\n")
                    .append("| 规则版本 | ").append(firstText(
                            rule.get("hospital_version"), rule.get("version"),
                            rule.get("national_version"), "当前证据未提供")).append(" |\n")
                    .append("| 生效层级 | ").append(effectiveLevelLabel(
                            rule.get("effective_level"))).append(" |\n\n")
                    .append("## 计算口径\n\n")
                    .append("- 计算公式：").append(formula).append("\n")
                    .append("- 分子口径：").append(numerator).append("\n")
                    .append("- 分母口径：").append(denominator).append("\n")
                    .append("- 统计时间：").append(timeDimension).append("\n")
                    .append("- 去重规则：").append(deduplication).append("\n")
                    .append("- 排除条件：").append(exclusions).append("\n")
                    .toString().strip();
        }
        StringBuilder answer = new StringBuilder("# ").append(name).append("\n\n");
        for (ExplanationFocus focus : focuses) {
            answer.append(AnswerTemplateRegistry.sectionTitle(focus)).append("\n\n");
            answer.append(switch (focus) {
                case DEFINITION -> definition;
                case FORMULA -> formula;
                case NUMERATOR -> numerator;
                case DENOMINATOR -> denominator;
                case TIME_DIMENSION -> timeDimension;
                case DEDUPLICATION -> deduplication;
                case EXCLUSIONS -> exclusions;
                case VERSION_SCOPE -> "规则编号："
                        + firstText(rule.get("rule_id"), "当前证据未提供")
                        + "；规则版本："
                        + firstText(rule.get("hospital_version"), rule.get("version"),
                                rule.get("national_version"), "当前证据未提供")
                        + "；生效层级："
                        + effectiveLevelLabel(rule.get("effective_level")) + "。";
                case OVERVIEW -> "";
            }).append("\n\n");
        }
        return answer.toString().strip();
    }

    private static String effectiveLevelLabel(Object raw) {
        String value = firstText(raw);
        if ("company".equalsIgnoreCase(value)) return "公司公版口径";
        if ("hospital".equalsIgnoreCase(value)) return "本院覆盖口径";
        if ("national".equalsIgnoreCase(value)) return "国家口径";
        return value.isBlank() ? "当前证据未提供" : value;
    }

    private static String dualComparisonSection(Map<String, Object> trial) {
        String status = firstText(trial.get("comparison_status"), "");
        if (status.isBlank()) return "";
        Map<String, Object> business = objectMap(trial.get("business_result"));
        Map<String, Object> real = objectMap(trial.get("real_result"));
        StringBuilder value = new StringBuilder("## 双库核对\n\n")
                .append("| 数据源 | 分子 | 分母 | 指标率 |\n")
                .append("|---|---:|---:|---:|\n")
                .append("| 业务库 | ").append(firstText(business.get("numerator_count"), "—"))
                .append(" | ").append(firstText(business.get("denominator_count"), "—"))
                .append(" | ").append(isNoSample(business)
                        ? "不适用" : percent(business.get("result_value"))).append(" |\n")
                .append("| 真实库 | ").append(firstText(real.get("numerator_count"), "—"))
                .append(" | ").append(firstText(real.get("denominator_count"), "—"))
                .append(" | ").append(isNoSample(real)
                        ? "不适用" : percent(real.get("result_value"))).append(" |\n\n");
        if ("matched".equals(status)) {
            value.append("> 双库分子、分母完全一致。\n\n");
        } else {
            value.append("> 双库概览的分子或分母不一致。");
            Map<String, Object> diagnosis = objectMap(trial.get("dual_difference_diagnosis"));
            if ("completed".equals(firstText(diagnosis.get("status"), ""))) {
                value.append("已完成受控的科室和患者明细核对。\n\n");
            } else {
                value.append("当前明细契约或查询未全部完成，暂不能判断具体原因。\n\n");
            }
        }
        return value.toString();
    }

    private static boolean isNoSample(Map<String, Object> value) {
        if (Boolean.TRUE.equals(value.get("no_sample"))) return true;
        Object denominator = value.get("denominator_count");
        if (denominator instanceof Number number) {
            return number.doubleValue() == 0.0;
        }
        try {
            return denominator != null
                    && new java.math.BigDecimal(String.valueOf(denominator))
                            .compareTo(java.math.BigDecimal.ZERO) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static Map<String, Object> latest(List<VerifiedEvidence> values, String factType) {
        for (int index = values.size() - 1; index >= 0; index--) {
            var evidence = values.get(index).evidence();
            if (factType.equals(evidence.factType())) return evidence.safePayload();
        }
        return Map.of();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(FinalAnswerComposer::objectMap)
                .filter(item -> !item.isEmpty())
                .toList();
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

    private static String timeDimensionLabel(Object value) {
        String raw = firstText(value);
        if ("admitted_to_ward_at".equalsIgnoreCase(raw)
                || "ward_entry_time".equalsIgnoreCase(raw)) {
            return "首次入区时间";
        }
        return raw;
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
            List<ExplanationFocus> explanationFocuses,
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
            this(userMessage, planGoal, PlanIntent.UNKNOWN, List.of(),
                    List.of(ExplanationFocus.OVERVIEW), modelId,
                    currentDate, recentHistory, evidence);
        }

        public FinalAnswerInput(
                String userMessage,
                String planGoal,
                PlanIntent intent,
                List<RequestedOutput> requestedOutputs,
                String modelId,
                LocalDate currentDate,
                String recentHistory,
                List<VerifiedEvidence> evidence) {
            this(userMessage, planGoal, intent, requestedOutputs,
                    List.of(ExplanationFocus.OVERVIEW),
                    modelId, currentDate, recentHistory, evidence);
        }

        public FinalAnswerInput {
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("用户问题不能为空");
            }
            intent = intent == null ? PlanIntent.UNKNOWN : intent;
            requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
            explanationFocuses = explanationFocuses == null || explanationFocuses.isEmpty()
                    ? List.of(ExplanationFocus.OVERVIEW)
                    : explanationFocuses.stream().filter(java.util.Objects::nonNull).distinct().toList();
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
