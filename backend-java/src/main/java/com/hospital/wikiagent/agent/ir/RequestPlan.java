package com.hospital.wikiagent.agent.ir;

import java.util.List;

/**
 * 定义 {@code RequestPlan} 的不可变数据载体。
 */
public record RequestPlan(
        String schemaVersion,
        PlanIntent intent,
        String goal,
        TargetIndicator targetIndicator,
        TimeExpression timeExpression,
        List<RequestedOutput> requestedOutputs,
        List<String> constraints,
        List<SemanticAmbiguity> semanticAmbiguities) {

    public static final String VERSION = "request-plan-v1";

    public RequestPlan {
        schemaVersion = blankTo(schemaVersion, VERSION);
        intent = intent == null ? PlanIntent.UNKNOWN : intent;
        goal = goal == null ? "" : goal.strip();
        if (goal.isEmpty() || goal.length() > 500) {
            throw new IllegalArgumentException("业务目标必须为 1 至 500 个字符");
        }
        targetIndicator = targetIndicator == null ? new TargetIndicator("", null) : targetIndicator;
        timeExpression = timeExpression == null ? new TimeExpression("", null, null) : timeExpression;
        requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
        constraints = constraints == null ? List.of() : constraints.stream().map(String::strip).toList();
        semanticAmbiguities = semanticAmbiguities == null ? List.of() : List.copyOf(semanticAmbiguities);
    }

    public record TargetIndicator(String rawName, String ruleId) {
        public TargetIndicator {
            rawName = rawName == null ? "" : rawName.strip();
            ruleId = normalizeNullable(ruleId);
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
}
