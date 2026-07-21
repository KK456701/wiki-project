package com.hospital.wikiagent.agent.model;

public class AgentModelUnavailableException extends RuntimeException {
    private final String code;

    public AgentModelUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
