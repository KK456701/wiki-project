package com.hospital.wikiagent.agent.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PlanIntent {
    GENERAL_CHAT("general_chat"),
    RULE_EXPLANATION("rule_explanation"),
    INDICATOR_SQL_PREPARE("indicator_sql_prepare"),
    INDICATOR_TRIAL_RUN("indicator_trial_run"),
    INDICATOR_DIAGNOSIS("indicator_diagnosis"),
    RULE_CHANGE_PREVIEW("rule_change_preview"),
    UPLOAD_ANALYSIS("upload_analysis"),
    IMPLEMENTATION_VALIDATION("implementation_validation"),
    UNKNOWN("unknown");

    private final String value;

    PlanIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static PlanIntent fromValue(String value) {
        for (PlanIntent candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("未知计划意图: " + value);
    }
}
