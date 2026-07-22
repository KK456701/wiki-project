package com.hospital.wikiagent.agent.ir;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 枚举 {@code FailureClass} 允许的有限业务状态。
 *
 * <p>有限状态用于编译期约束 Planner、Controller 和 Verifier 的分支。未知文本必须被拒绝或进入明确兜底，不能静默映射为成功状态。</p>
 */
public enum FailureClass {
    SEMANTIC_PLAN_ERROR("semantic_plan_error"),
    TASK_TYPE_ERROR("task_type_error"),
    USER_GOAL_CHANGED("user_goal_changed"),
    ALTERNATIVE_DIRECTION_AVAILABLE("alternative_direction_available"),
    USER_CLARIFICATION_REQUIRED("user_clarification_required"),
    DATABASE_ERROR("database_error"),
    PERMISSION_ERROR("permission_error"),
    OBJECT_EXPIRED("object_expired"),
    EVIDENCE_CONFLICT("evidence_conflict"),
    TOOL_ERROR("tool_error"),
    UNKNOWN("unknown");

    private static final Map<String, FailureClass> CODES = Map.ofEntries(
            Map.entry("PLAN_INTENT_MISMATCH", SEMANTIC_PLAN_ERROR),
            Map.entry("TASK_TYPE_MISMATCH", TASK_TYPE_ERROR),
            Map.entry("USER_GOAL_CHANGED", USER_GOAL_CHANGED),
            Map.entry("ASSUMPTION_INVALID_ALTERNATIVE_AVAILABLE", ALTERNATIVE_DIRECTION_AVAILABLE),
            Map.entry("INDICATOR_AMBIGUOUS", USER_CLARIFICATION_REQUIRED),
            Map.entry("TARGET_INDICATOR_AMBIGUOUS", USER_CLARIFICATION_REQUIRED),
            Map.entry("TIME_RANGE_AMBIGUOUS", USER_CLARIFICATION_REQUIRED),
            Map.entry("STAT_PERIOD_MISSING", USER_CLARIFICATION_REQUIRED),
            Map.entry("DATABASE_ACCESS_CONFLICT", DATABASE_ERROR),
            Map.entry("DATABASE_UNAVAILABLE", DATABASE_ERROR),
            Map.entry("TRIAL_RUN_FAILED", DATABASE_ERROR),
            Map.entry("DIAGNOSIS_FAILED", TOOL_ERROR),
            Map.entry("PERMISSION_DENIED", PERMISSION_ERROR),
            Map.entry("PATIENT_DETAIL_FORBIDDEN", PERMISSION_ERROR),
            Map.entry("SQL_OBJECT_EXPIRED", OBJECT_EXPIRED),
            Map.entry("SQL_CHAIN_INCONSISTENT", EVIDENCE_CONFLICT),
            Map.entry("SQL_PERIOD_INCONSISTENT", EVIDENCE_CONFLICT),
            Map.entry("NUMERIC_RESULT_INCONSISTENT", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_HOSPITAL_MISMATCH", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_SUBTASK_MISMATCH", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_RULE_MISMATCH", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_PERIOD_MISMATCH", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_SQL_MISMATCH", EVIDENCE_CONFLICT),
            Map.entry("EVIDENCE_EXPIRED", OBJECT_EXPIRED),
            Map.entry("TOOL_TIMEOUT", TOOL_ERROR),
            Map.entry("TOOL_EXECUTION_FAILED", TOOL_ERROR));

    private final String value;

    FailureClass(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static FailureClass classify(String code) {
        return CODES.getOrDefault(code == null ? "" : code, UNKNOWN);
    }
}
