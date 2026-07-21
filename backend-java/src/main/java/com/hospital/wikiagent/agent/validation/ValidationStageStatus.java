package com.hospital.wikiagent.agent.validation;

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
