package com.hospital.wikiagent.agent.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.ExplanationFocus;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /**
     * 模型输出缺少 confidence 字段时使用的降级值。
     * 低于默认阈值0.9，会触发确定性澄清而不是静默按1.0直接执行。
     */
    private static final double MISSING_CONFIDENCE = 0.0;

    /**
     * 只识别“整条消息就是问候”的轻量快速通道。锚定整句可确保
     * “你好，计算去年指标”之类带业务目标的输入仍交给 Planner。
     */
    private static final Pattern PURE_GREETING = Pattern.compile(
            "^(?:(?:你|您)?好(?:呀|啊|哇)?|(?:嗨|哈[喽啰]|hi|hello|hey))[!！。,.，\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        if (isPureGreeting(input.userMessage())) {
            RequestPlan greetingPlan = new RequestPlan(
                    RequestPlan.VERSION,
                    PlanIntent.GENERAL_CHAT,
                    input.userMessage().strip(),
                    null,
                    null,
                    null,
                    List.of(RequestedOutput.EXPLANATION),
                    List.of(ExplanationFocus.OVERVIEW),
                    List.of(),
                    List.of(),
                    1.0);
            return new PlannerResult(
                    greetingPlan,
                    toJson(greetingPlan),
                    modelId,
                    false,
                    null,
                    List.of());
        }
        String userPrompt = "当前日期：" + input.currentDate() + "。\n"
                + "结构化会话状态：\n" + safe(input.structuredState()) + "\n"
                + "最近对话（最多 8 轮）：\n" + safe(input.recentHistory()) + "\n"
                + "本轮用户输入：\n" + input.userMessage();
        return generate(modelId, userPrompt, input.currentDate());
    }

    private static boolean isPureGreeting(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.strip();
        return normalized.length() <= 24 && PURE_GREETING.matcher(normalized).matches();
    }

    private String toJson(RequestPlan value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"intent\":\"general_chat\"}";
        }
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
        return generate(modelId, userPrompt, input.currentDate());
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
            AlignmentReview value = objectMapper.treeToValue(
                    ModelJsonFieldNames.toCamelCase(
                            objectMapper.readTree(ModelJsonExtractor.firstObject(raw))),
                    AlignmentReview.class);
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

    private PlannerResult generate(
            String modelId,
            String userPrompt,
            LocalDate currentDate) {
        String systemPrompt = prompts.planner();
        PlannerRequestAudit initialAudit = audit(
                modelId, currentDate, systemPrompt, userPrompt, false);
        String raw = models.complete(
                modelId, systemPrompt, userPrompt, properties.getPlannerTimeout()).content();
        try {
            return new PlannerResult(
                    parseAndValidate(raw), raw, modelId, false, initialAudit,
                    List.of(attempt("initial", raw, "", initialAudit, true)));
        } catch (RuntimeException firstFailure) {
            PlannerAttemptAudit initialAttempt = attempt(
                    "initial", raw, firstFailure.getMessage(), initialAudit, false);
            // 修复提示只纠正 JSON/Schema，不改变用户目标，也不暴露工具实现。
            String repair = prompts.plannerRepair()
                    .replace("{{validation_error}}", safe(firstFailure.getMessage()))
                    .replace("{{raw_output}}", raw == null ? "" : raw);
            String repairUserPrompt = userPrompt + "\n\n" + repair;
            PlannerRequestAudit repairAudit = audit(
                    modelId, currentDate, systemPrompt, repairUserPrompt, true);
            String repaired = models.complete(
                    modelId, systemPrompt, repairUserPrompt,
                    properties.getPlannerTimeout()).content();
            try {
                return new PlannerResult(
                        parseAndValidate(repaired),
                        repaired,
                        modelId,
                        true,
                        repairAudit,
                        List.of(
                                initialAttempt,
                                attempt("repair", repaired, "", repairAudit, true)));
            } catch (RuntimeException secondFailure) {
                throw new PlannerOutputException(
                        "PLANNER_OUTPUT_INVALID",
                        "模型业务计划格式错误，自动修复后仍未通过校验。",
                        secondFailure,
                        List.of(
                                initialAttempt,
                                attempt("repair", repaired, secondFailure.getMessage(),
                                        repairAudit, false)));
            }
        }
    }

    private static PlannerAttemptAudit attempt(
            String phase,
            String rawContent,
            String validationError,
            PlannerRequestAudit requestAudit,
            boolean valid) {
        return new PlannerAttemptAudit(
                phase, safe(rawContent), safe(validationError), requestAudit, valid);
    }

    private PlannerRequestAudit audit(
            String modelId,
            LocalDate currentDate,
            String systemPrompt,
            String userPrompt,
            boolean repairAttempt) {
        return new PlannerRequestAudit(
                currentDate,
                systemPrompt,
                userPrompt,
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                modelId,
                properties.getPlannerTimeout().toMillis(),
                PromptCatalog.VERSION,
                VERSION,
                repairAttempt);
    }

    private RequestPlan parseAndValidate(String raw) {
        try {
            // 提示词声明的字段是 snake_case，IR record 是 camelCase，先统一键的书写再反序列化。
            JsonNode node = ModelJsonFieldNames.toCamelCase(
                    objectMapper.readTree(ModelJsonExtractor.firstObject(raw)));
            normalizeEmptyCollections(node);
            normalizeRequestedOutputs(node);
            RequestPlan value = objectMapper.treeToValue(node, RequestPlan.class);
            if (!RequestPlan.VERSION.equals(value.schemaVersion())
                    && !RequestPlan.LEGACY_VERSION.equals(value.schemaVersion())) {
                throw new IllegalArgumentException("RequestPlan 版本不匹配");
            }
            if (RequestPlan.LEGACY_VERSION.equals(value.schemaVersion())) {
                value = value.withSchemaVersion(RequestPlan.VERSION);
            }
            // 模型输出缺少 confidence 时不能静默按 1.0 处理：小模型漏输置信度
            // 并不代表意图一定正确。降级为低置信度以触发确定性澄清，把判断权
            // 交还用户；确定性构造路径仍可通过兼容构造器显式传 1.0。
            if (!node.has("confidence") || node.get("confidence").isNull()) {
                value = value.withConfidence(MISSING_CONFIDENCE);
            }
            return value;
        } catch (PlannerOutputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    /**
     * 小模型偶尔会把“没有任何项”的数组写成空字符串。这里仅把明确为空的复数字段
     * 归一化为 {@code []}；非空字符串仍交给严格 Schema 校验拒绝，避免掩盖语义错误。
     */
    private void normalizeEmptyCollections(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        for (String field : List.of(
                "requestedOutputs",
                "explanationFocuses",
                "constraints",
                "semanticAmbiguities")) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual() && value.textValue().isBlank()) {
                object.putArray(field);
            }
        }
    }

    /**
     * 小模型偶尔把 requested_outputs 写成单值对象或布尔映射。只在对象能被无歧义地
     * 还原为已知枚举时转换为数组；未知字段仍交给严格校验和一次修复重试。
     */
    private void normalizeRequestedOutputs(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        JsonNode value = object.get("requestedOutputs");
        if (!(value instanceof ObjectNode requested)) {
            return;
        }
        List<String> values = requestedOutputValues(requested);
        if (values.isEmpty()) {
            return;
        }
        ArrayNode array = object.putArray("requestedOutputs");
        values.forEach(array::add);
    }

    private static List<String> requestedOutputValues(ObjectNode requested) {
        if (requested.size() == 1) {
            Map.Entry<String, JsonNode> entry = requested.fields().next();
            if (Set.of("value", "type", "name", "output", "requestedOutput")
                    .contains(entry.getKey()) && entry.getValue().isTextual()) {
                String candidate = entry.getValue().textValue();
                return knownRequestedOutput(candidate) ? List.of(candidate) : List.of();
            }
        }
        List<String> values = new java.util.ArrayList<>();
        var fields = requested.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isBoolean() || !entry.getValue().booleanValue()
                    || !knownRequestedOutput(entry.getKey())) {
                return List.of();
            }
            values.add(entry.getKey());
        }
        return List.copyOf(values);
    }

    private static boolean knownRequestedOutput(String value) {
        try {
            RequestedOutput.fromValue(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
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
            boolean repaired,
            PlannerRequestAudit requestAudit,
            List<PlannerAttemptAudit> attempts) {

        public PlannerResult {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        public PlannerResult(
                RequestPlan plan,
                String rawContent,
                String modelId,
                boolean repaired,
                PlannerRequestAudit requestAudit) {
            this(plan, rawContent, modelId, repaired, requestAudit, List.of());
        }

        /** 兼容服务端确定性计划和既有测试；这类计划没有实际 LLM 请求。 */
        public PlannerResult(
                RequestPlan plan,
                String rawContent,
                String modelId,
                boolean repaired) {
            this(plan, rawContent, modelId, repaired, null);
        }
    }

    /** 单次 Planner 模型输出及校验结果；仅进入授权 Trace，不通过 SSE 广播。 */
    public record PlannerAttemptAudit(
            String phase,
            String rawContent,
            String validationError,
            PlannerRequestAudit requestAudit,
            boolean valid) {
    }

    /** Planner 实际模型请求的完整审计信息，仅进入授权 Trace。 */
    public record PlannerRequestAudit(
            LocalDate currentDate,
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> messages,
            String modelId,
            long timeoutMs,
            String promptVersion,
            String plannerVersion,
            boolean repairAttempt) {
        public PlannerRequestAudit {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
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
