package com.hospital.wikiagent.agent.evidence;

public class EvidenceAccessException extends RuntimeException {
    private final String code;

    public EvidenceAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
