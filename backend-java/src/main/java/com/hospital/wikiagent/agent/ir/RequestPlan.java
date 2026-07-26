package com.hospital.wikiagent.agent.ir;

import java.util.List;

/**
 * 定义 {@code RequestPlan} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record RequestPlan(
        String schemaVersion,
        PlanIntent intent,
        String goal,
        TargetIndicator targetIndicator,
        TargetCaliber targetCaliber,
        TimeExpression timeExpression,
        List<RequestedOutput> requestedOutputs,
        List<ExplanationFocus> explanationFocuses,
        List<String> constraints,
        List<SemanticAmbiguity> semanticAmbiguities,
        Double confidence) {

    public static final String VERSION = "request-plan-v3";
    public static final String LEGACY_VERSION = "request-plan-v2";

    /**
     * 兼容项目内仍使用 v1 形状构造计划的调用方。序列化协议已经升级为 v2，
     * 旧构造方式只表示“本轮没有候选口径目标”。
     */
    public RequestPlan(
            String schemaVersion,
            PlanIntent intent,
            String goal,
            TargetIndicator targetIndicator,
            TimeExpression timeExpression,
            List<RequestedOutput> requestedOutputs,
            List<String> constraints,
            List<SemanticAmbiguity> semanticAmbiguities) {
        this(schemaVersion, intent, goal, targetIndicator, null, timeExpression,
                requestedOutputs, List.of(ExplanationFocus.OVERVIEW),
                constraints, semanticAmbiguities, null);
    }

    /**
     * 兼容不使用 confidence 字段的调用方。
     */
    public RequestPlan(
            String schemaVersion,
            PlanIntent intent,
            String goal,
            TargetIndicator targetIndicator,
            TargetCaliber targetCaliber,
            TimeExpression timeExpression,
            List<RequestedOutput> requestedOutputs,
            List<String> constraints,
            List<SemanticAmbiguity> semanticAmbiguities) {
        this(schemaVersion, intent, goal, targetIndicator, targetCaliber, timeExpression,
                requestedOutputs, List.of(ExplanationFocus.OVERVIEW),
                constraints, semanticAmbiguities, null);
    }

    /**
     * 兼容 v2 形状且显式携带 confidence 的既有调用方。
     */
    public RequestPlan(
            String schemaVersion,
            PlanIntent intent,
            String goal,
            TargetIndicator targetIndicator,
            TargetCaliber targetCaliber,
            TimeExpression timeExpression,
            List<RequestedOutput> requestedOutputs,
            List<String> constraints,
            List<SemanticAmbiguity> semanticAmbiguities,
            Double confidence) {
        this(schemaVersion, intent, goal, targetIndicator, targetCaliber, timeExpression,
                requestedOutputs, List.of(ExplanationFocus.OVERVIEW),
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan {
        schemaVersion = blankTo(schemaVersion, VERSION);
        intent = intent == null ? PlanIntent.UNKNOWN : intent;
        goal = goal == null ? "" : goal.strip();
        if (goal.isEmpty() || goal.length() > 500) {
            throw new IllegalArgumentException("业务目标必须为 1 至 500 个字符");
        }
        targetIndicator = targetIndicator == null ? new TargetIndicator("", null) : targetIndicator;
        targetCaliber = targetCaliber == null ? new TargetCaliber("", null) : targetCaliber;
        timeExpression = timeExpression == null ? new TimeExpression("", null, null) : timeExpression;
        requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
        explanationFocuses = normalizeFocuses(explanationFocuses);
        constraints = constraints == null ? List.of() : constraints.stream().map(String::strip).toList();
        semanticAmbiguities = semanticAmbiguities == null ? List.of() : List.copyOf(semanticAmbiguities);
        confidence = confidence == null ? 1.0 : confidence;
    }

    /**
     * 返回仅替换意图、其余字段（含 confidence）保持不变的新计划。
     * 转换型富化必须使用这类 with 方法，避免重建时丢失 Planner 给出的置信度。
     */
    public RequestPlan withIntent(PlanIntent newIntent) {
        return new RequestPlan(schemaVersion, newIntent, goal, targetIndicator, targetCaliber,
                timeExpression, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withTargetIndicator(TargetIndicator newTarget) {
        return new RequestPlan(schemaVersion, intent, goal, newTarget, targetCaliber,
                timeExpression, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withTargetCaliber(TargetCaliber newCaliber) {
        return new RequestPlan(schemaVersion, intent, goal, targetIndicator, newCaliber,
                timeExpression, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withTimeExpression(TimeExpression newTime) {
        return new RequestPlan(schemaVersion, intent, goal, targetIndicator, targetCaliber,
                newTime, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withRequestedOutputs(List<RequestedOutput> newOutputs) {
        return new RequestPlan(schemaVersion, intent, goal, targetIndicator, targetCaliber,
                timeExpression, newOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withExplanationFocuses(List<ExplanationFocus> newFocuses) {
        return new RequestPlan(schemaVersion, intent, goal, targetIndicator, targetCaliber,
                timeExpression, requestedOutputs, newFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    public RequestPlan withSchemaVersion(String newSchemaVersion) {
        return new RequestPlan(newSchemaVersion, intent, goal, targetIndicator, targetCaliber,
                timeExpression, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, confidence);
    }

    /**
     * 返回仅替换置信度、其余字段保持不变的新计划。
     * 用于模型输出缺少 confidence 字段时的确定性降级，避免静默按 1.0 处理。
     */
    public RequestPlan withConfidence(Double newConfidence) {
        return new RequestPlan(schemaVersion, intent, goal, targetIndicator, targetCaliber,
                timeExpression, requestedOutputs, explanationFocuses,
                constraints, semanticAmbiguities, newConfidence);
    }

    public record TargetIndicator(String rawName, String ruleId) {
        public TargetIndicator {
            rawName = rawName == null ? "" : rawName.strip();
            ruleId = normalizeNullable(ruleId);
        }
    }

    /**
     * 描述用户希望模拟的候选口径。profileId 只能由服务端 Wiki 候选解析器确认，
     * Planner 提供的编号仍需重新校验，不能直接决定字段覆盖或 SQL。
     */
    public record TargetCaliber(String rawText, String profileId) {
        public TargetCaliber {
            rawText = rawText == null ? "" : rawText.strip();
            profileId = normalizeNullable(profileId);
        }
    }

    public record TimeExpression(String rawText, String startTime, String endTime) {
        public TimeExpression {
            rawText = rawText == null ? "" : rawText.strip();
            startTime = normalizeNullable(startTime);
            endTime = normalizeNullable(endTime);
        }
    }

    public record SemanticAmbiguity(String field, String description) {
        public SemanticAmbiguity {
            field = field == null ? "" : field.strip();
            description = description == null ? "" : description.strip();
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<ExplanationFocus> normalizeFocuses(List<ExplanationFocus> values) {
        if (values == null || values.isEmpty()) {
            return List.of(ExplanationFocus.OVERVIEW);
        }
        List<ExplanationFocus> normalized = values.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of(ExplanationFocus.OVERVIEW) : normalized;
    }
}
