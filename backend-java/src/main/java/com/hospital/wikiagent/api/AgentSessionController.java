package com.hospital.wikiagent.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.SessionMessage;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.SessionSummary;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;

/**
 * 会话管理接口：创建、列表、恢复和删除对话会话。
 *
 * <p>前端通过该控制器实现历史对话列表和会话恢复。所有查询按当前登录医院和用户隔离，
 * 不能跨租户访问其他医院或其他用户的会话。</p>
 */
@RestController
@RequestMapping("/api/agent/sessions")
public class AgentSessionController {

    private final HospitalAuthService auth;
    private final AgentConversationMemory memory;

    public AgentSessionController(HospitalAuthService auth, AgentConversationMemory memory) {
        this.auth = auth;
        this.memory = memory;
    }

    /**
     * 创建新会话，后端生成唯一 session_id 返回给前端。
     * 前端拿到后应持久化到 localStorage，后续对话携带此 ID。
     */
    @PostMapping
    public Map<String, String> createSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        auth.authenticate(BearerTokens.require(authorization));
        String sessionId = AgentConversationMemory.newSessionId();
        return Map.of("sessionId", sessionId);
    }

    /**
     * 查询当前用户的历史会话列表，按最后消息时间倒序。
     * 返回每条会话的 ID、标题（第一条问题）、最后活跃时间和消息数。
     */
    @GetMapping
    public List<SessionSummary> listSessions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        return memory.listSessions(principal);
    }

    /**
     * 恢复指定会话的完整消息记录，按时间正序。
     * 前端用于点击历史会话后恢复聊天界面。
     */
    @GetMapping("/{sessionId}/messages")
    public List<SessionMessage> getSessionMessages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        return memory.getSessionMessages(principal, sessionId);
    }

    /**
     * 删除指定会话及其全部消息。
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, String> deleteSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String sessionId) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        memory.deleteSession(principal, sessionId);
        return Map.of("message", "会话已删除");
    }
}
