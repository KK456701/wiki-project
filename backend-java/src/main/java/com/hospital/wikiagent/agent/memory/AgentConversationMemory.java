package com.hospital.wikiagent.agent.memory;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 保存最近多轮对话以及当前指标、统计区间和运行对象引用。
 * 存储键包含医院和用户，防止相同 session_id 在租户之间串用。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class AgentConversationMemory {
    private static final int MAX_MESSAGES = 16;
    private static final int MAX_HISTORY_CHARS = 12_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentConversationMemory.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Message>> fallback = new ConcurrentHashMap<>();
    private final Map<String, ContextValues> fallbackContext = new ConcurrentHashMap<>();

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
            // 生产运行库使用 SQLite，单元测试使用 H2；两者的自增主键语法不同。
            // 这里只做明确的方言分支，不引入 ORM 或新的迁移中间件。
            String identity = identityColumn();
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_java_message (
                      id %s,
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
                    """.formatted(identity));
        } catch (Exception exception) {
            // 运行库不可用时仍允许服务启动；具体消息会进入租户隔离的内存兜底。
            LOGGER.warn("Unable to initialize Agent conversation memory table; fallback remains enabled: {}",
                    exception.getMessage());
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
        // 消息和结构化上下文采用两份进程内索引。即使某条助手消息因存储降级只恢复了文本，
        // 当前指标和统计区间仍可从 context 索引恢复，避免跨轮 SQL 追问重新询问时间。
        ContextValues cached = fallbackContext.get(key);
        String ruleId = first(latestContext == null ? null : latestContext.ruleId(),
                cached == null ? null : cached.ruleId());
        String ruleName = first(latestContext == null ? null : latestContext.ruleName(),
                cached == null ? null : cached.ruleName());
        String statStart = first(latestContext == null ? null : latestContext.statStart(),
                cached == null ? null : cached.statStart());
        String statEnd = first(latestContext == null ? null : latestContext.statEnd(),
                cached == null ? null : cached.statEnd());
        String runId = first(latestContext == null ? null : latestContext.runId(),
                cached == null ? null : cached.runId());
        String uploadFileKey = first(latestContext == null ? null : latestContext.uploadFileKey(),
                cached == null ? null : cached.uploadFileKey());
        Map<String, Object> structured = new LinkedHashMap<>();
        put(structured, "active_rule_id", ruleId);
        put(structured, "active_rule_name", ruleName);
        put(structured, "stat_start", statStart);
        put(structured, "stat_end", statEnd);
        put(structured, "last_run_id", runId);
        put(structured, "current_upload_file_key", uploadFileKey);
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
                ruleId, ruleName, statStart, statEnd, runId, uploadFileKey);
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
        fallbackContext.put(conversation.storageKey(), values);
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
                return merge(rows, fallback.getOrDefault(key, List.of()));
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to load Agent conversation memory; using fallback for session key hash={}: {}",
                        Integer.toHexString(key.hashCode()), exception.getMessage());
            }
        }
        List<Message> values = fallback.getOrDefault(key, List.of());
        int start = Math.max(0, values.size() - MAX_MESSAGES);
        return List.copyOf(values.subList(start, values.size()));
    }

    private void append(Message message) {
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
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to persist Agent conversation memory; using fallback for session key hash={}: {}",
                        Integer.toHexString(message.sessionKey().hashCode()), exception.getMessage());
            }
        }
        // 无论 JDBC 是否成功，都保留当前进程缓存。这样数据库写入失败但读取返回空列表时，
        // 结构化规则、统计区间和运行对象引用仍不会丢失；数据库用于跨进程恢复。
        cache(message);
    }

    private String identityColumn() throws java.sql.SQLException {
        if (jdbc == null || jdbc.getDataSource() == null) {
            return "BIGINT AUTO_INCREMENT PRIMARY KEY";
        }
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("sqlite")
                    ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                    : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        }
    }

    private static ContextValues contextValues(
            AgentRunState state,
            ConversationSnapshot previous) {
        String ruleId = first(state.currentRuleId(), previous.ruleId());
        String ruleName = previous.ruleName();
        String statStart = first(state.statStart(), previous.statStart());
        String statEnd = first(state.statEnd(), previous.statEnd());
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

    private void cache(Message message) {
        fallback.compute(message.sessionKey(), (key, existing) -> {
            List<Message> values = new ArrayList<>(existing == null ? List.of() : existing);
            values.add(message);
            if (values.size() > MAX_MESSAGES * 4) {
                values = new ArrayList<>(values.subList(values.size() - MAX_MESSAGES * 2, values.size()));
            }
            return List.copyOf(values);
        });
    }

    /**
     * 合并持久化消息和当前进程缓存，并按写入时间去重。
     *
     * <p>缓存中也包含成功持久化的消息，因此必须去重；使用消息的完整安全字段构造键，
     * 避免同一毫秒内连续写入用户和助手消息时互相覆盖。</p>
     */
    private static List<Message> merge(List<Message> persisted, List<Message> cached) {
        Map<String, Message> merged = new LinkedHashMap<>();
        for (Message message : persisted) {
            merged.put(messageKey(message), message);
        }
        for (Message message : cached) {
            merged.put(messageKey(message), message);
        }
        List<Message> values = new ArrayList<>(merged.values());
        values.sort(java.util.Comparator.comparing(Message::createdAt));
        int start = Math.max(0, values.size() - MAX_MESSAGES);
        return List.copyOf(values.subList(start, values.size()));
    }

    private static String messageKey(Message message) {
        return String.join("\u001f",
                safeKey(message.createdAt()), safeKey(message.role()), safeKey(message.content()),
                safeKey(message.ruleId()), safeKey(message.statStart()), safeKey(message.statEnd()),
                safeKey(message.runId()), safeKey(message.uploadFileKey()));
    }

    private static String safeKey(String value) {
        return value == null ? "" : value;
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
