package com.hospital.wikiagent.agent.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 规则解释回答中允许展示的业务关注点。
 *
 * <p>关注点不是新的任务意图，也不会改变工具调用。它只约束同一份生效规则证据最终
 * 应展示哪些部分，避免用户只问分子或分母时仍收到完整口径报告。</p>
 */
public enum ExplanationFocus {
    OVERVIEW("overview"),
    DEFINITION("definition"),
    FORMULA("formula"),
    NUMERATOR("numerator"),
    DENOMINATOR("denominator"),
    TIME_DIMENSION("time_dimension"),
    DEDUPLICATION("deduplication"),
    EXCLUSIONS("exclusions"),
    VERSION_SCOPE("version_scope");

    private final String value;

    ExplanationFocus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExplanationFocus fromValue(String value) {
        for (ExplanationFocus candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("未知规则解释关注点: " + value);
    }
}
