package com.hospital.wikiagent.agent.runtime;

import com.hospital.wikiagent.auth.HospitalPrincipal;

/**
 * 定义 {@code AgentRunRequest} 的不可变数据载体。
 */
public record AgentRunRequest(
        String query,
        String sessionId,
        String modelId,
        String fileKey,
        String requestId,
        String traceId,
        String dbSourceId,
        String structuredState,
        String recentHistory,
        HospitalPrincipal principal) {

    public AgentRunRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        if (principal == null) {
            throw new IllegalArgumentException("Agent 请求缺少登录主体");
        }
    }
}
