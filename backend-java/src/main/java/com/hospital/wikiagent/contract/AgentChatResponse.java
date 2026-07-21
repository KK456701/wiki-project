package com.hospital.wikiagent.contract;

public record AgentChatResponse(
        String answer,
        String stopReason,
        String traceId,
        String sessionId,
        int stepCount) {
}
