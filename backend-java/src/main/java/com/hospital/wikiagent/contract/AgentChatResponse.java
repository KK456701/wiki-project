package com.hospital.wikiagent.contract;

/**
 * 定义 {@code AgentChatResponse} 的不可变数据载体。
 */
public record AgentChatResponse(
        String answer,
        String stopReason,
        String traceId,
        String sessionId,
        int stepCount) {
}
