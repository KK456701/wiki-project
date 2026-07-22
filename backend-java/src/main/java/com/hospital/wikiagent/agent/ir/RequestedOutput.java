package com.hospital.wikiagent.agent.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 枚举 {@code RequestedOutput} 允许的有限业务状态。
 */
public enum RequestedOutput {
    DEFINITION("definition"),
    FORMULA("formula"),
    IMPLEMENTATION_STATUS("implementation_status"),
    PREPARED_SQL_HANDLE("prepared_sql_handle"),
    TRIAL_RESULT("trial_result"),
    DIAGNOSIS("diagnosis"),
    CHANGE_PREVIEW("change_preview"),
    FILE_ANALYSIS("file_analysis"),
    IMPLEMENTATION_VALIDATION_REPORT("implementation_validation_report"),
    EXPLANATION("explanation");

    private final String value;

    RequestedOutput(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RequestedOutput fromValue(String value) {
        for (RequestedOutput candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("未知输出目标: " + value);
    }
}
