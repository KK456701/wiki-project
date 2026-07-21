package com.hospital.wikiagent.agent.tools;

public record PolicyDecision(
        Decision decision,
        String reasonCode,
        String displayMessage,
        String policyVersion) {

    public enum Decision {
        ALLOW,
        DENY
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
