package com.hospital.wikiagent.agent.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

/**
 * 集中管理 Final Answer 的按需回答模板。
 *
 * <p>模板不属于基础 Prompt。服务端先根据已经校验的业务意图和输出目标选择唯一模板，
 * 再把这一份模板连同 VerifiedEvidence 发送给 Final Answer LLM。模型不会看到其他意图
 * 的模板，也不能通过模板改变工具、SQL 或业务状态。</p>
 *
 * <p>注册关系使用 Java 枚举固定声明，避免把意图路由变成可动态执行的配置。模板正文
 * 独立保存在 {@code resources/answer-templates}，便于产品和业务人员集中查看版式。</p>
 */
@Component
public class AnswerTemplateRegistry {
    public static final String VERSION = "answer-template-registry-v1";

    private final Map<PlanIntent, TemplateDescriptor> byIntent;
    private final Map<RequestedOutput, TemplateDescriptor> byOutput;
    private final Map<String, AnswerTemplate> loadedTemplates;

    public AnswerTemplateRegistry() {
        Map<PlanIntent, TemplateDescriptor> intents = new EnumMap<>(PlanIntent.class);
        intents.put(PlanIntent.GENERAL_CHAT, descriptor(
                "general-chat", "answer", "general-chat.md", List.of(), false));
        intents.put(PlanIntent.RULE_EXPLANATION, descriptor(
                "rule-explanation", "answer", "rule-explanation.md",
                List.of("## 口径摘要", "## 计算口径", "## 实施信息"), false));
        intents.put(PlanIntent.INDICATOR_SQL_PREPARE, descriptor(
                "indicator-sql-report", "report", "indicator-sql-report.md",
                List.of("## SQL 口径", "## 已校验 SQL", "## 统计参数", "## 安全状态"), false));
        intents.put(PlanIntent.INDICATOR_TRIAL_RUN, descriptor(
                "indicator-trial-result", "report", "indicator-trial-result.md",
                List.of("## 结果速览", "## 计算口径", "## 数据依据"), true));
        intents.put(PlanIntent.INDICATOR_CALIBER_SIMULATION, descriptor(
                "caliber-simulation-report", "report", "caliber-simulation-report.md",
                List.of("## 模拟结果", "## 候选口径", "## 与当前口径的区别", "## 使用限制"), true));
        intents.put(PlanIntent.INDICATOR_DIAGNOSIS, descriptor(
                "indicator-diagnosis-report", "report", "indicator-diagnosis-report.md",
                List.of("## 诊断结论", "## 已确认事实", "## 建议处理"), false));
        intents.put(PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS, descriptor(
                "difference-diagnosis-report", "report", "difference-diagnosis-report.md",
                List.of("## 双方结果", "## 候选口径试算", "## 诊断结论", "## 证据限制"), false));
        intents.put(PlanIntent.RULE_CHANGE_PREVIEW, descriptor(
                "rule-change-preview", "report", "rule-change-preview.md",
                List.of("## 变更摘要", "## 影响范围", "## 后续操作"), false));
        intents.put(PlanIntent.UPLOAD_ANALYSIS, descriptor(
                "upload-analysis-report", "report", "upload-analysis-report.md",
                List.of("## 文件概览", "## 分析结果", "## 数据限制"), false));
        intents.put(PlanIntent.IMPLEMENTATION_VALIDATION, descriptor(
                "implementation-validation-report", "report", "implementation-validation-report.md",
                List.of("## 验收结论", "## 阶段结果", "## 整改建议"), false));
        intents.put(PlanIntent.UNKNOWN, descriptor(
                "clarification", "answer", "clarification.md",
                List.of("# 需要补充的信息"), false));

        Map<RequestedOutput, TemplateDescriptor> outputs = new EnumMap<>(RequestedOutput.class);
        outputs.put(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT,
                intents.get(PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS));
        outputs.put(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT,
                intents.get(PlanIntent.IMPLEMENTATION_VALIDATION));
        outputs.put(RequestedOutput.PREPARED_SQL_HANDLE,
                intents.get(PlanIntent.INDICATOR_SQL_PREPARE));
        outputs.put(RequestedOutput.CALIBER_PREPARED_SQL_HANDLE,
                intents.get(PlanIntent.INDICATOR_SQL_PREPARE));
        outputs.put(RequestedOutput.CALIBER_TRIAL_RESULT,
                intents.get(PlanIntent.INDICATOR_CALIBER_SIMULATION));
        outputs.put(RequestedOutput.TRIAL_RESULT,
                intents.get(PlanIntent.INDICATOR_TRIAL_RUN));
        outputs.put(RequestedOutput.DIAGNOSIS,
                intents.get(PlanIntent.INDICATOR_DIAGNOSIS));
        outputs.put(RequestedOutput.FILE_ANALYSIS,
                intents.get(PlanIntent.UPLOAD_ANALYSIS));
        outputs.put(RequestedOutput.CHANGE_PREVIEW,
                intents.get(PlanIntent.RULE_CHANGE_PREVIEW));

        byIntent = Map.copyOf(intents);
        byOutput = Map.copyOf(outputs);
        Map<String, AnswerTemplate> loaded = new LinkedHashMap<>();
        for (TemplateDescriptor value : intents.values()) {
            loaded.computeIfAbsent(value.id(), ignored -> load(value));
        }
        loadedTemplates = Map.copyOf(loaded);
    }

    /**
     * 输出目标比意图更具体，因此优先匹配报告型输出；没有专用输出时再使用意图模板。
     */
    public AnswerTemplate resolve(PlanIntent intent, List<RequestedOutput> requestedOutputs) {
        for (RequestedOutput output : requestedOutputs == null ? List.<RequestedOutput>of() : requestedOutputs) {
            TemplateDescriptor descriptor = byOutput.get(output);
            if (descriptor != null) return loadedTemplates.get(descriptor.id());
        }
        TemplateDescriptor descriptor = byIntent.getOrDefault(
                intent == null ? PlanIntent.UNKNOWN : intent,
                byIntent.get(PlanIntent.UNKNOWN));
        return loadedTemplates.get(descriptor.id());
    }

    public List<AnswerTemplate> all() {
        return loadedTemplates.values().stream()
                .sorted(java.util.Comparator.comparing(AnswerTemplate::id))
                .toList();
    }

    private static TemplateDescriptor descriptor(
            String id,
            String kind,
            String fileName,
            List<String> requiredSections,
            boolean preserveTrialNumbers) {
        return new TemplateDescriptor(
                id, "v1", kind, "answer-templates/" + fileName,
                List.copyOf(requiredSections), preserveTrialNumbers);
    }

    private static AnswerTemplate load(TemplateDescriptor descriptor) {
        try (var input = new ClassPathResource(descriptor.resourcePath()).getInputStream()) {
            String body = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (body.isBlank()) {
                throw new IllegalStateException("回答模板为空: " + descriptor.resourcePath());
            }
            return new AnswerTemplate(
                    descriptor.id(), descriptor.version(), descriptor.kind(),
                    body, descriptor.requiredSections(), descriptor.preserveTrialNumbers());
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取回答模板: " + descriptor.resourcePath(), exception);
        }
    }

    private record TemplateDescriptor(
            String id,
            String version,
            String kind,
            String resourcePath,
            List<String> requiredSections,
            boolean preserveTrialNumbers) {}

    /**
     * Final Answer 单次调用所需的完整模板契约。
     */
    public record AnswerTemplate(
            String id,
            String version,
            String kind,
            String body,
            List<String> requiredSections,
            boolean preserveTrialNumbers) {
        public AnswerTemplate {
            requiredSections = requiredSections == null ? List.of() : List.copyOf(requiredSections);
        }
    }
}
