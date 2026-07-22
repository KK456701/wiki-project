package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import tools.jackson.databind.ObjectMapper;

@Component
public class ModelRequestPlanner {
    public static final String VERSION = "model-request-planner-v2";

    private final AgentModelInvoker models;
    private final AgentModelRegistry registry;
    private final AgentModelProperties properties;
    private final PromptCatalog prompts;
    private final ObjectMapper objectMapper;

    public ModelRequestPlanner(
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

    public PlannerResult plan(PlannerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        String userPrompt = "当前日期：" + input.currentDate() + "。\n"
                + "结构化会话状态：\n" + safe(input.structuredState()) + "\n"
                + "最近对话（最多 8 轮）：\n" + safe(input.recentHistory()) + "\n"
                + "本轮用户输入：\n" + input.userMessage();
        return generate(modelId, userPrompt);
    }

    public PlannerResult replan(ReplannerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        String original;
        try {
            original = objectMapper.writeValueAsString(input.originalPlan());
        } catch (Exception exception) {
            original = String.valueOf(input.originalPlan());
        }
        String userPrompt = "当前日期：" + input.currentDate() + "。\n"
                + "原始用户输入：\n" + input.userMessage() + "\n"
                + "原业务计划：\n" + original + "\n"
                + "失败代码：" + safe(input.failureCode()) + "\n"
                + "失败原因：" + safe(input.failureReason()) + "\n"
                + "已确认事实：\n" + safe(input.knownFacts()) + "\n"
                + "失败计划编号：" + safe(input.failedPlanId()) + "\n\n"
                + prompts.replanner();
        return generate(modelId, userPrompt);
    }

    private PlannerResult generate(String modelId, String userPrompt) {
        String raw = models.complete(
                modelId, prompts.planner(), userPrompt, properties.getPlannerTimeout()).content();
        try {
            return new PlannerResult(parseAndValidate(raw), raw, modelId, false);
        } catch (RuntimeException firstFailure) {
            String repair = prompts.plannerRepair()
                    .replace("{{validation_error}}", safe(firstFailure.getMessage()))
                    .replace("{{raw_output}}", raw == null ? "" : raw);
            String repaired = models.complete(
                    modelId, prompts.planner(), userPrompt + "\n\n" + repair,
                    properties.getPlannerTimeout()).content();
            try {
                return new PlannerResult(parseAndValidate(repaired), repaired, modelId, true);
            } catch (RuntimeException secondFailure) {
                throw new PlannerOutputException(
                        "PLANNER_OUTPUT_INVALID", "模型未生成有效业务计划。", secondFailure);
            }
        }
    }

    private RequestPlan parseAndValidate(String raw) {
        try {
            RequestPlan value = objectMapper.readValue(ModelJsonExtractor.firstObject(raw), RequestPlan.class);
            if (!RequestPlan.VERSION.equals(value.schemaVersion())) {
                throw new IllegalArgumentException("RequestPlan 版本不匹配");
            }
            if (value.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                    && (!value.requestedOutputs().contains(RequestedOutput.PREPARED_SQL_HANDLE)
                    || value.requestedOutputs().contains(RequestedOutput.TRIAL_RESULT))) {
                throw new IllegalArgumentException("SQL 准备意图的输出目标不合法");
            }
            if (value.intent() == PlanIntent.INDICATOR_TRIAL_RUN
                    && !value.requestedOutputs().contains(RequestedOutput.TRIAL_RESULT)) {
                throw new IllegalArgumentException("指标试运行缺少 trial_result 输出目标");
            }
            return value;
        } catch (PlannerOutputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record PlannerInput(
            String userMessage,
            String modelId,
            LocalDate currentDate,
            String structuredState,
            String recentHistory) {
        public PlannerInput {
            if (userMessage == null || userMessage.isBlank()) {
                throw new IllegalArgumentException("用户输入不能为空");
            }
            currentDate = currentDate == null ? LocalDate.now() : currentDate;
        }
    }

    public record PlannerResult(
            RequestPlan plan,
            String rawContent,
            String modelId,
            boolean repaired) {
    }

    public record ReplannerInput(
            String userMessage,
            String modelId,
            LocalDate currentDate,
            RequestPlan originalPlan,
            String failureCode,
            String failureReason,
            String knownFacts,
            String failedPlanId) {
        public ReplannerInput {
            if (userMessage == null || userMessage.isBlank() || originalPlan == null) {
                throw new IllegalArgumentException("重规划必须包含原始问题和原计划");
            }
            currentDate = currentDate == null ? LocalDate.now() : currentDate;
        }
    }
}
