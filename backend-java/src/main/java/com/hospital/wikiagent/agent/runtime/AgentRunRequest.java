package com.hospital.wikiagent.agent.runtime;

import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.contract.AgentChatRequest.ClarificationResponse;

/**
 * 定义 {@code AgentRunRequest} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
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
        HospitalPrincipal principal,
        ClarificationResponse clarificationResponse) {

    public AgentRunRequest(
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
        this(query, sessionId, modelId, fileKey, requestId, traceId, dbSourceId,
                structuredState, recentHistory, principal, null);
    }

    public AgentRunRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        if (principal == null) {
            throw new IllegalArgumentException("Agent 请求缺少登录主体");
        }
    }
}
