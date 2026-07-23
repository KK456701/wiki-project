package com.hospital.wikiagent.agent.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 枚举 {@code PlanCapability} 允许的有限业务状态。
 *
 * <p>有限状态用于编译期约束 Planner、Controller 和 Verifier 的分支。未知文本必须被拒绝或进入明确兜底，不能静默映射为成功状态。</p>
 */
public enum PlanCapability {
    RESOLVE_INDICATOR("resolve_indicator"),
    RESOLVE_EFFECTIVE_RULE("resolve_effective_rule"),
    RESOLVE_TIME_RANGE("resolve_time_range"),
    INSPECT_IMPLEMENTATION("inspect_implementation"),
    PREPARE_VERIFIED_SQL("prepare_verified_sql"),
    EXECUTE_TRIAL_RUN("execute_trial_run"),
    DIAGNOSE_INDICATOR("diagnose_indicator"),
    DIAGNOSE_INDICATOR_DIFFERENCE("diagnose_indicator_difference"),
    PREVIEW_RULE_CHANGE("preview_rule_change"),
    ANALYZE_UPLOADED_FILE("analyze_uploaded_file"),
    VALIDATE_IMPLEMENTATION("validate_implementation"),
    COMPOSE_ANSWER("compose_answer");

    private final String value;

    PlanCapability(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PlanCapability fromValue(String value) {
        for (PlanCapability candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("未知业务能力: " + value);
    }
}
