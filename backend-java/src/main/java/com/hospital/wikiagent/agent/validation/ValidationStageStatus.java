package com.hospital.wikiagent.agent.validation;

/**
 * 枚举 {@code ValidationStageStatus} 允许的有限业务状态。
 */
public enum ValidationStageStatus {
    PASSED("passed"),
    WARNING("warning"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String value;

    ValidationStageStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
