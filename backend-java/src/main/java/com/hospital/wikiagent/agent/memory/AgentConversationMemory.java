package com.hospital.wikiagent.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;

@Component
public class AgentConversationMemory {
    private static final int MAX_MESSAGES = 16;
    private static final int MAX_HISTORY_CHARS = 12_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Message>> fallback = new ConcurrentHashMap<>();

    public AgentConversationMemory(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private AgentConversationMemory() {
        this.jdbc = null;
        this.objectMapper = null;
    }

    public static AgentConversationMemory noop() {
        return new AgentConversationMemory();
    }

    @PostConstruct
    void initialize() {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_java_message (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      session_key VARCHAR(512) NOT NULL,
                      hospital_id VARCHAR(128) NOT NULL,
                      user_id VARCHAR(128) NOT NULL,
                      role VARCHAR(16) NOT NULL,
                      content TEXT NOT NULL,
                      rule_id VARCHAR(128),
                      rule_name VARCHAR(255),
                      stat_start VARCHAR(40),
                      stat_end VARCHAR(40),
                      run_id VARCHAR(80),
                      upload_file_key VARCHAR(255),
                      created_at VARCHAR(40) NOT NULL
                    )
                    """);
        } catch (RuntimeException ignored) {
            // MySQL 不可用时仅在当前进程使用租户隔离的内存兜底。
        }
    }

    public ConversationSnapshot open(HospitalPrincipal principal, String requestedSessionId) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? principal.sessionId() : requestedSessionId.strip();
        String key = storageKey(principal, sessionId);
        List<Message> messages = load(key);
        Message latestContext = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message candidate = messages.get(index);
            if (candidate.ruleId() != null || candidate.statStart() != null
                    || candidate.runId() != null || candidate.uploadFileKey() != null) {
                latestContext = candidate;
                break;
            }
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        if (latestContext != null) {
            put(structured, "active_rule_id", latestContext.ruleId());
            put(structured, "active_rule_name", latestContext.ruleName());
            put(structured, "stat_start", latestContext.statStart());
            put(structured, "stat_end", latestContext.statEnd());
            put(structured, "last_run_id", latestContext.runId());
            put(structured, "current_upload_file_key", latestContext.uploadFileKey());
        }
        String structuredSummary;
        try {
            structuredSummary = objectMapper == null || structured.isEmpty()
                    ? "{}" : objectMapper.writeValueAsString(structured);
        } catch (Exception exception) {
            structuredSummary = "{}";
        }
        return new ConversationSnapshot(
                key,
                sessionId,
                history(messages),
                structuredSummary,
                latestContext == null ? null : latestContext.ruleId(),
                latestContext == null ? null : latestContext.ruleName(),
                latestContext == null ? null : latestContext.statStart(),
                latestContext == null ? null : latestContext.statEnd(),
                latestContext == null ? null : latestContext.runId(),
                latestContext == null ? null : latestContext.uploadFileKey());
    }

    public void appendUser(
            ConversationSnapshot conversation,
            HospitalPrincipal principal,
            String content,
            String uploadFileKey) {
        append(new Message(
                conversation.storageKey(), principal.hospitalId(), principal.userId(),
                "user", limited(content, 5_000),
                conversation.ruleId(), conversation.ruleName(),
                conversation.statStart(), conversation.statEnd(), conversation.lastRunId(),
                first(uploadFileKey, conversation.uploadFileKey()), Instant.now().toString()));
    }

    public void appendAssistant(
            ConversationSnapshot conversation,
            HospitalPrincipal principal,
            String content,
            AgentRunState state) {
        ContextValues values = contextValues(state, conversation);
        append(new Message(
                conversation.storageKey(), principal.hospitalId(), principal.userId(),
                "assistant", limited(content, 12_000), values.ruleId(), values.ruleName(),
                values.statStart(), values.statEnd(), values.runId(), values.uploadFileKey(),
                Instant.now().toString()));
    }

    private List<Message> load(String key) {
        if (jdbc != null) {
            try {
                List<Message> rows = jdbc.query("""
                        SELECT session_key, hospital_id, user_id, role, content,
                               rule_id, rule_name, stat_start, stat_end, run_id,
                               upload_file_key, created_at
                        FROM med_agent_java_message
                        WHERE session_key = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """, (result, row) -> new Message(
                        result.getString("session_key"), result.getString("hospital_id"),
                        result.getString("user_id"), result.getString("role"),
                        result.getString("content"), result.getString("rule_id"),
                        result.getString("rule_name"), result.getString("stat_start"),
                        result.getString("stat_end"), result.getString("run_id"),
                        result.getString("upload_file_key"), result.getString("created_at")),
                        key, MAX_MESSAGES);
                Collections.reverse(rows);
                return List.copyOf(rows);
            } catch (RuntimeException ignored) {
            }
        }
        List<Message> values = fallback.getOrDefault(key, List.of());
        int start = Math.max(0, values.size() - MAX_MESSAGES);
        return List.copyOf(values.subList(start, values.size()));
    }

    private void append(Message message) {
        boolean persisted = false;
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO med_agent_java_message (
                          session_key, hospital_id, user_id, role, content, rule_id, rule_name,
                          stat_start, stat_end, run_id, upload_file_key, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        message.sessionKey(), message.hospitalId(), message.userId(), message.role(),
                        message.content(), message.ruleId(), message.ruleName(), message.statStart(),
                        message.statEnd(), message.runId(), message.uploadFileKey(), message.createdAt());
                persisted = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!persisted) {
            fallback.compute(message.sessionKey(), (key, existing) -> {
                List<Message> values = new ArrayList<>(existing == null ? List.of() : existing);
                values.add(message);
                if (values.size() > MAX_MESSAGES * 4) {
                    values = new ArrayList<>(values.subList(values.size() - MAX_MESSAGES * 2, values.size()));
                }
                return List.copyOf(values);
            });
        }
    }

    private static ContextValues contextValues(
            AgentRunState state,
            ConversationSnapshot previous) {
        String ruleId = first(state.currentRuleId(), previous.ruleId());
        String ruleName = previous.ruleName();
        String statStart = previous.statStart();
        String statEnd = previous.statEnd();
        String runId = first(state.lastRunId(), previous.lastRunId());
        for (ToolResult result : state.lastToolResults()) {
            if (!result.ok()) {
                continue;
            }
            Map<String, Object> data = result.data();
            if (text(data.get("rule_id")) != null
                    && (ruleId == null || ruleId.equals(text(data.get("rule_id"))))) {
                ruleId = text(data.get("rule_id"));
                ruleName = first(text(data.get("rule_name")), ruleName);
            }
            statStart = first(text(data.get("stat_start")), text(data.get("stat_start_time")), statStart);
            statEnd = first(text(data.get("stat_end")), text(data.get("stat_end_time")), statEnd);
            runId = first(text(data.get("run_id")), runId);
        }
        return new ContextValues(
                ruleId, ruleName, statStart, statEnd, runId,
                first(state.currentUploadFileKey(), previous.uploadFileKey()));
    }

    private static String history(List<Message> messages) {
        StringBuilder value = new StringBuilder();
        for (Message message : messages) {
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            String line = role + "：" + limited(message.content(), 2_000) + "\n";
            if (value.length() + line.length() > MAX_HISTORY_CHARS) {
                int overflow = value.length() + line.length() - MAX_HISTORY_CHARS;
                value.delete(0, Math.min(overflow, value.length()));
            }
            value.append(line);
        }
        return value.toString().strip();
    }

    private static String storageKey(HospitalPrincipal principal, String sessionId) {
        return "agent:" + principal.hospitalId() + ":" + principal.userId() + ":" + sessionId;
    }

    private static String limited(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            values.put(key, value);
        }
    }

    public record ConversationSnapshot(
            String storageKey,
            String sessionId,
            String recentHistory,
            String structuredSummary,
            String ruleId,
            String ruleName,
            String statStart,
            String statEnd,
            String lastRunId,
            String uploadFileKey) {
    }

    private record ContextValues(
            String ruleId,
            String ruleName,
            String statStart,
            String statEnd,
            String runId,
            String uploadFileKey) {
    }

    private record Message(
            String sessionKey,
            String hospitalId,
            String userId,
            String role,
            String content,
            String ruleId,
            String ruleName,
            String statStart,
            String statEnd,
            String runId,
            String uploadFileKey,
            String createdAt) {
    }
}
