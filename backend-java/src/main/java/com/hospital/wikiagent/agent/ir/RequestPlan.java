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
        List<String> constraints,
        List<SemanticAmbiguity> semanticAmbiguities) {

    public static final String VERSION = "request-plan-v2";

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
                requestedOutputs, constraints, semanticAmbiguities);
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
        constraints = constraints == null ? List.of() : constraints.stream().map(String::strip).toList();
        semanticAmbiguities = semanticAmbiguities == null ? List.of() : List.copyOf(semanticAmbiguities);
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
}
