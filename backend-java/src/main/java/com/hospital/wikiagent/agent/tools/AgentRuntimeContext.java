package com.hospital.wikiagent.agent.tools;

import com.hospital.wikiagent.auth.HospitalPrincipal;

/**
 * 定义 {@code AgentRuntimeContext} 的不可变数据载体。
 */
public record AgentRuntimeContext(
        HospitalPrincipal principal,
        String requestId,
        String traceId,
        String dbSourceId) {

    public AgentRuntimeContext {
        if (principal == null) {
            throw new IllegalArgumentException("工具运行上下文缺少登录主体");
        }
        requestId = requestId == null ? "" : requestId;
        traceId = traceId == null ? "" : traceId;
    }

    public String hospitalId() {
        return principal.hospitalId();
    }

    public String userId() {
        return principal.userId();
    }

    public String sessionId() {
        return principal.sessionId();
    }
}
