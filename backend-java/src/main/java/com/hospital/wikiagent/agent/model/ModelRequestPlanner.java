package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.RequestPlan;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把自然语言请求转换为 RequestPlan；仅允许模型描述业务目标，
 * 实际工具、依赖顺序和 SQL 均由后续 Java 编译器决定。
 *
 * <p>输入只包含当前问题、受控轮数的会话摘要和当前日期；输出必须通过 Jackson 反序列化为
 * 版本匹配的 {@link RequestPlan}。模型不能输出工具名或执行顺序，格式修复最多一次，第二次失败
 * 立即返回稳定错误，避免陷入“让模型继续修模型”的循环。</p>
 */
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

    /**
     * 将本轮自然语言和安全会话上下文规划为业务目标。
     */
    public PlannerResult plan(PlannerInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        String userPrompt = "当前日期：" + input.currentDate() + "。\n"
                + "结构化会话状态：\n" + safe(input.structuredState()) + "\n"
                + "最近对话（最多 8 轮）：\n" + safe(input.recentHistory()) + "\n"
                + "本轮用户输入：\n" + input.userMessage();
        return generate(modelId, userPrompt);
    }

    /**
     * 在服务端策略允许时，根据原计划、失败原因和已确认事实生成唯一一次替代计划。
     */
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

    /**
     * 仅在确定性校验无法判断时审核“用户目标与计划是否一致”。
     *
     * <p>审核器不能生成新计划、工具名或 SQL；它只返回接受/拒绝和候选 profile
     * 编号。被拒绝的计划仍必须进入受限 Replanner，并重新通过服务端校验。</p>
     */
    public AlignmentReviewResult reviewAlignment(AlignmentReviewInput input) {
        String modelId = input.modelId() == null || input.modelId().isBlank()
                ? registry.defaultModelId() : input.modelId();
        String plan;
        try {
            plan = objectMapper.writeValueAsString(input.plan());
        } catch (Exception exception) {
            plan = String.valueOf(input.plan());
        }
        String userPrompt = "原始用户输入：\n" + input.userMessage() + "\n"
                + "结构化会话状态：\n" + safe(input.structuredState()) + "\n"
                + "Planner 计划：\n" + plan + "\n"
                + "允许的候选口径：\n" + safe(input.candidateSummary());
        String raw = models.complete(
                modelId,
                prompts.planAlignmentReview(),
                userPrompt,
                properties.getPlannerTimeout()).content();
        try {
            AlignmentReview value = objectMapper.readValue(
                    ModelJsonExtractor.firstObject(raw), AlignmentReview.class);
            return new AlignmentReviewResult(
                    value.aligned(),
                    safe(value.reason()),
                    safe(value.suggestedProfileId()),
                    raw,
                    modelId);
        } catch (Exception exception) {
            throw new PlannerOutputException(
                    "PLAN_ALIGNMENT_REVIEW_INVALID",
                    "模型未生成有效的计划一致性审核结果。",
                    exception);
        }
    }

    private PlannerResult generate(String modelId, String userPrompt) {
        String raw = models.complete(
                modelId, prompts.planner(), userPrompt, properties.getPlannerTimeout()).content();
        try {
            return new PlannerResult(parseAndValidate(raw), raw, modelId, false);
        } catch (RuntimeException firstFailure) {
            // 修复提示只纠正 JSON/Schema，不改变用户目标，也不暴露工具实现。
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

    public record AlignmentReviewInput(
            String userMessage,
            String modelId,
            RequestPlan plan,
            String structuredState,
            String candidateSummary) {
        public AlignmentReviewInput {
            if (userMessage == null || userMessage.isBlank() || plan == null) {
                throw new IllegalArgumentException("计划一致性审核缺少用户问题或业务计划");
            }
        }
    }

    public record AlignmentReviewResult(
            boolean aligned,
            String reason,
            String suggestedProfileId,
            String rawContent,
            String modelId) {
    }

    private record AlignmentReview(
            boolean aligned,
            String reason,
            String suggestedProfileId) {
    }
}
