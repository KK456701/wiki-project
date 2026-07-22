package com.hospital.wikiagent.agent.tools;

/**
 * 定义 {@code PolicyDecision} 的不可变数据载体。
 */
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
