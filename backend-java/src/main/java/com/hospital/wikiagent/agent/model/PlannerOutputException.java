package com.hospital.wikiagent.agent.model;

public class PlannerOutputException extends RuntimeException {
    private final String code;

    public PlannerOutputException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
