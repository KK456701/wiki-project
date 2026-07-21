package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;

import tools.jackson.databind.ObjectMapper;

@Component
public class FinalAnswerComposer {
    public static final String VERSION = "final-answer-composer-v1";

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
            throw new AgentModelUnavailableException(
                    "FINAL_ANSWER_INVALID", "模型未生成有效业务回答。");
        }
        return new FinalAnswerResult(repaired.strip(), modelId, true);
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

    public record FinalAnswerResult(String content, String modelId, boolean corrected) {
    }
}
