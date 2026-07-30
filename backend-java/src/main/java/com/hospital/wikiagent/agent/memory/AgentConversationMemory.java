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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.EvidenceEnvelope;
import com.hospital.wikiagent.agent.evidence.EvidenceStore;
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
    private final EvidenceStore evidenceStore;
    private final Map<String, List<Message>> fallback = new ConcurrentHashMap<>();
    private final Map<String, ContextValues> fallbackContext = new ConcurrentHashMap<>();
    // 上一轮复合澄清确认的整批指标名，按存储键记住。历史 ## 小节被长 SQL 挤掉时，
    // 供拆分器在纯时间补充/指代追问下重新展开为复合，避免退化成单指标。
    private final Map<String, List<String>> compoundTargets = new ConcurrentHashMap<>();
    private final Map<String, QueryScopeState> queryScopes = new ConcurrentHashMap<>();

    @Autowired
    public AgentConversationMemory(JdbcTemplate jdbc, ObjectMapper objectMapper,
            @Autowired(required = false) EvidenceStore evidenceStore) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evidenceStore = evidenceStore;
    }

    /**
     * 兼容测试和内部构造的两参数入口，不注入 EvidenceStore。
     */
    public AgentConversationMemory(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, null);
    }

    private AgentConversationMemory() {
        this.jdbc = null;
        this.objectMapper = null;
        this.evidenceStore = null;
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
                      caliber_profile_id VARCHAR(128),
                      caliber_label VARCHAR(255),
                      stat_start VARCHAR(40),
                      stat_end VARCHAR(40),
                      run_id VARCHAR(80),
                      upload_file_key VARCHAR(255),
                      created_at VARCHAR(40) NOT NULL
                    )
                    """.formatted(identity));
            ensureColumn("caliber_profile_id", "VARCHAR(128)");
            ensureColumn("caliber_label", "VARCHAR(255)");
            ensureColumn("digest", "VARCHAR(512)");
            // 批量指标卡片载荷（JSON 数组）随助手消息持久化，切换会话后前端可恢复卡片。
            ensureColumn("batch_results", "TEXT");
            // 复合指标确认目标需要随会话持久化，服务重启后仍可恢复，
            // 供后续纯时间补充/指代追问重新展开为复合，与消息持久化能力保持一致。
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_compound_target (
                      session_key VARCHAR(512) NOT NULL,
                      position INT NOT NULL,
                      target_name VARCHAR(255) NOT NULL,
                      created_at VARCHAR(40) NOT NULL,
                      PRIMARY KEY (session_key, position)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_query_scope (
                      session_key VARCHAR(512) PRIMARY KEY,
                      scope_json TEXT NOT NULL,
                      updated_at VARCHAR(40) NOT NULL
                    )
                    """);
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
            if (candidate.ruleId() != null || candidate.caliberProfileId() != null
                    || candidate.statStart() != null
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
        String caliberProfileId = first(
                latestContext == null ? null : latestContext.caliberProfileId(),
                cached == null ? null : cached.caliberProfileId());
        String caliberLabel = first(
                latestContext == null ? null : latestContext.caliberLabel(),
                cached == null ? null : cached.caliberLabel());
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
        put(structured, "active_caliber_profile_id", caliberProfileId);
        put(structured, "active_caliber_label", caliberLabel);
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
                ruleId, ruleName, caliberProfileId, caliberLabel,
                statStart, statEnd, runId, uploadFileKey,
                loadCompoundTargets(key),
                loadQueryScope(key),
                buildEvidenceContext(principal.hospitalId(), ruleId));
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
                conversation.caliberProfileId(), conversation.caliberLabel(),
                conversation.statStart(), conversation.statEnd(), conversation.lastRunId(),
                first(uploadFileKey, conversation.uploadFileKey()), Instant.now().toString(),
                null, null));
    }

    public void appendAssistant(
            ConversationSnapshot conversation,
            HospitalPrincipal principal,
            String content,
            AgentRunState state) {
        appendAssistant(conversation, principal, content, state, null);
    }

    /**
     * 保存助手回答，可附带批量指标卡片载荷（与 SSE batch_indicator_result 同形态）。
     * 卡片载荷序列化为 JSON 存入 batch_results 列，仅供会话恢复时重建卡片，
     * 不进入 Planner 历史上下文；序列化失败仅告警，不影响文本消息落库。
     */
    public void appendAssistant(
            ConversationSnapshot conversation,
            HospitalPrincipal principal,
            String content,
            AgentRunState state,
            List<Map<String, Object>> batchResults) {
        ContextValues values = contextValues(state, conversation);
        fallbackContext.put(conversation.storageKey(), values);
        String digest = generateDigest(state, content);
        String batchJson = null;
        if (batchResults != null && !batchResults.isEmpty() && objectMapper != null) {
            try {
                batchJson = objectMapper.writeValueAsString(batchResults);
            } catch (Exception exception) {
                LOGGER.warn("Unable to serialize batch results for session key hash={}: {}",
                        Integer.toHexString(conversation.storageKey().hashCode()),
                        exception.getMessage());
            }
        }
        append(new Message(
                conversation.storageKey(), principal.hospitalId(), principal.userId(),
                "assistant", limited(content, 12_000), values.ruleId(), values.ruleName(),
                values.caliberProfileId(), values.caliberLabel(),
                values.statStart(), values.statEnd(), values.runId(), values.uploadFileKey(),
                Instant.now().toString(), digest, batchJson));
    }

    /**
     * 记住本轮复合澄清确认的整批指标名，供后续纯时间补充/指代追问重新展开为复合。
     *
     * <p>只保存指标名文本（安全字段），不保存 SQL 或患者数据；少于 2 个指标不记录，
     * 单指标会话不会被误判为复合。最多保留 35 个，与当前医院活跃指标上限一致。</p>
     */
    public void rememberCompoundTargets(ConversationSnapshot conversation, List<String> targets) {
        if (conversation == null || targets == null) {
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (String target : targets) {
            if (target != null && !target.isBlank()) {
                cleaned.add(target.strip());
            }
            if (cleaned.size() >= 35) {
                break;
            }
        }
        if (cleaned.size() >= 2) {
            compoundTargets.put(conversation.storageKey(), List.copyOf(cleaned));
            persistCompoundTargets(conversation.storageKey(), cleaned);
        }
    }

    /**
     * 保存最近一次确定执行的操作、指标范围和统计区间。批量续问只消费这份结构态，
     * 不从自然语言历史猜测“全部指标”或若干指标。
     */
    public void rememberQueryScope(
            ConversationSnapshot conversation, QueryScopeState scope) {
        if (conversation == null || scope == null || !scope.valid()) {
            return;
        }
        QueryScopeState normalized = scope.normalized();
        queryScopes.put(conversation.storageKey(), normalized);
        if (jdbc == null || objectMapper == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(normalized);
            jdbc.update("DELETE FROM med_agent_query_scope WHERE session_key = ?",
                    conversation.storageKey());
            jdbc.update("""
                    INSERT INTO med_agent_query_scope (session_key, scope_json, updated_at)
                    VALUES (?, ?, ?)
                    """, conversation.storageKey(), json, Instant.now().toString());
        } catch (Exception exception) {
            LOGGER.warn("Unable to persist query scope for session key hash={}: {}",
                    Integer.toHexString(conversation.storageKey().hashCode()),
                    exception.getMessage());
        }
    }

    private QueryScopeState loadQueryScope(String key) {
        if (jdbc != null && objectMapper != null) {
            try {
                List<String> rows = jdbc.query(
                        "SELECT scope_json FROM med_agent_query_scope WHERE session_key = ?",
                        (result, row) -> result.getString("scope_json"), key);
                if (!rows.isEmpty()) {
                    QueryScopeState value =
                            objectMapper.readValue(rows.get(0), QueryScopeState.class);
                    if (value != null && value.valid()) {
                        QueryScopeState normalized = value.normalized();
                        queryScopes.put(key, normalized);
                        return normalized;
                    }
                }
            } catch (Exception exception) {
                LOGGER.warn("Unable to load query scope for session key hash={}: {}",
                        Integer.toHexString(key.hashCode()), exception.getMessage());
            }
        }
        return queryScopes.get(key);
    }

    /**
     * 读取会话的复合指标确认目标：优先持久化数据库记录，数据库不可用时
     * 回退到进程内缓存，保证服务重启后仍能恢复复合目标。
     */
    private List<String> loadCompoundTargets(String key) {
        if (jdbc != null) {
            try {
                List<String> rows = jdbc.query("""
                        SELECT target_name FROM med_agent_compound_target
                        WHERE session_key = ?
                        ORDER BY position ASC
                        """, (result, row) -> result.getString("target_name"), key);
                if (!rows.isEmpty()) {
                    return List.copyOf(rows);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to load compound targets for session key hash={}: {}",
                        Integer.toHexString(key.hashCode()), exception.getMessage());
            }
        }
        return compoundTargets.getOrDefault(key, List.of());
    }

    /**
     * 把复合指标确认目标持久化到数据库（先删后插以保持顺序）。
     * 写入失败仅告警；进程内缓存已更新，同进程追问不受影响。
     */
    private void persistCompoundTargets(String key, List<String> targets) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("DELETE FROM med_agent_compound_target WHERE session_key = ?", key);
            int position = 0;
            for (String target : targets) {
                jdbc.update("""
                        INSERT INTO med_agent_compound_target (
                          session_key, position, target_name, created_at
                        ) VALUES (?, ?, ?, ?)
                        """, key, position++, target, Instant.now().toString());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to persist compound targets for session key hash={}: {}",
                    Integer.toHexString(key.hashCode()), exception.getMessage());
        }
    }

    private List<Message> load(String key) {
        if (jdbc != null) {
            try {
                List<Message> rows = jdbc.query("""
                        SELECT session_key, hospital_id, user_id, role, content,
                               rule_id, rule_name, caliber_profile_id, caliber_label,
                               stat_start, stat_end, run_id,
                               upload_file_key, created_at, digest, batch_results
                        FROM med_agent_java_message
                        WHERE session_key = ?
                        ORDER BY id DESC
                        LIMIT ?
                        """, (result, row) -> new Message(
                        result.getString("session_key"), result.getString("hospital_id"),
                        result.getString("user_id"), result.getString("role"),
                        result.getString("content"), result.getString("rule_id"),
                        result.getString("rule_name"),
                        result.getString("caliber_profile_id"),
                        result.getString("caliber_label"),
                        result.getString("stat_start"),
                        result.getString("stat_end"), result.getString("run_id"),
                        result.getString("upload_file_key"), result.getString("created_at"),
                        result.getString("digest"), result.getString("batch_results")),
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
                          caliber_profile_id, caliber_label, stat_start, stat_end, run_id,
                          upload_file_key, created_at, digest, batch_results
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        message.sessionKey(), message.hospitalId(), message.userId(), message.role(),
                        message.content(), message.ruleId(), message.ruleName(),
                        message.caliberProfileId(), message.caliberLabel(),
                        message.statStart(), message.statEnd(), message.runId(),
                        message.uploadFileKey(), message.createdAt(), message.digest(),
                        message.batchResults());
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

    /**
     * 为已存在的轻量运行库补充 v2 会话列。
     *
     * <p>旧实现使用 {@code DatabaseMetaData#getColumns(null, null, null, null)}
     * 枚举整个 SQLite 库。当库内表较多时，SQLite JDBC 会生成超长复合查询并报
     * “too many terms in compound SELECT”，导致迁移被整体跳过。这里改为对目标表
     * 执行零行查询，只读取其 ResultSetMetaData，不扫描其他业务表。</p>
     */
    private void ensureColumn(String name, String type) {
        if (jdbc == null || hasColumn(name)) return;
        jdbc.execute("ALTER TABLE med_agent_java_message ADD COLUMN " + name + " " + type);
    }

    private boolean hasColumn(String name) {
        Boolean found = jdbc.query(
                "SELECT * FROM med_agent_java_message WHERE 1 = 0",
                result -> {
                    java.sql.ResultSetMetaData metadata = result.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        if (name.equalsIgnoreCase(metadata.getColumnName(index))) {
                            return true;
                        }
                    }
                    return false;
                });
        return Boolean.TRUE.equals(found);
    }

    private static ContextValues contextValues(
            AgentRunState state,
            ConversationSnapshot previous) {
        String ruleId = first(state.currentRuleId(), previous.ruleId());
        String ruleName = previous.ruleName();
        String caliberProfileId = first(
                state.currentCaliberProfileId(), previous.caliberProfileId());
        String caliberLabel = first(
                state.currentCaliberLabel(), previous.caliberLabel());
        String statStart = first(state.statStart(), previous.statStart());
        String statEnd = first(state.statEnd(), previous.statEnd());
        String runId = first(state.lastRunId(), previous.lastRunId());
        for (ToolResult result : state.lastToolResults()) {
            if (!result.ok()) {
                continue;
            }
            Map<String, Object> data = result.data();
            if (text(data.get("ruleId")) != null
                    && (ruleId == null || ruleId.equals(text(data.get("ruleId"))))) {
                ruleId = text(data.get("ruleId"));
                ruleName = first(text(data.get("ruleName")), ruleName);
            }
            caliberProfileId = first(
                    text(data.get("caliberProfileId")), caliberProfileId);
            caliberLabel = first(text(data.get("caliberLabel")), caliberLabel);
            statStart = first(text(data.get("statStart")), text(data.get("statStartTime")), statStart);
            statEnd = first(text(data.get("statEnd")), text(data.get("statEndTime")), statEnd);
            runId = first(text(data.get("runId")), runId);
        }
        return new ContextValues(
                ruleId, ruleName, caliberProfileId, caliberLabel,
                statStart, statEnd, runId,
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
                safeKey(message.ruleId()), safeKey(message.caliberProfileId()),
                safeKey(message.statStart()), safeKey(message.statEnd()),
                safeKey(message.runId()), safeKey(message.uploadFileKey()));
    }

    private static String safeKey(String value) {
        return value == null ? "" : value;
    }

    private static String history(List<Message> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        // 用户最近 3 轮保留较完整表达；助手消息始终只注入结构化 digest。
        // 完整助手回答仍保存在消息表供页面回看，但批量表格和 SQL 不再回灌 Planner。
        int recentCount = Math.min(6, messages.size());
        int summaryEnd = messages.size() - recentCount;
        StringBuilder value = new StringBuilder();
        // 摘要区
        for (int i = 0; i < summaryEnd; i++) {
            Message message = messages.get(i);
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            String body;
            if ("assistant".equals(message.role())) {
                body = message.digest() != null && !message.digest().isBlank()
                        ? "[摘要] " + message.digest()
                        : limited(message.content(), 100) + "…";
            } else {
                body = limited(message.content(), 150)
                        + (message.content() != null && message.content().length() > 150 ? "…" : "");
            }
            value.append(role).append("：").append(body).append("\n");
        }
        // 最近用户表达区；助手仍使用摘要
        for (int i = summaryEnd; i < messages.size(); i++) {
            Message message = messages.get(i);
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            String body = "assistant".equals(message.role())
                    ? "[摘要] " + (message.digest() != null && !message.digest().isBlank()
                            ? message.digest()
                            : firstSentence(message.content(), 100))
                    : limited(message.content(), 800);
            value.append(role).append("：").append(body).append("\n");
        }
        // 总量上限
        if (value.length() > MAX_HISTORY_CHARS) {
            int overflow = value.length() - MAX_HISTORY_CHARS;
            value.delete(0, Math.min(overflow, value.length()));
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

    // ─── Evidence 上下文和回答摘要 ─────────────────────────────────────────────────

    /**
     * 按当前指标检索最近已验证 Evidence，生成紧凑文本摘要注入 Planner 上下文。
     * 纯代码拼接，不调用 LLM。
     *
     * <p>每条证据除结果摘要外还携带证据号、来源运行对象和记录时间，
     * 让 Planner 能区分不同统计区间/口径/来源的多份结果，避免混淆哪条对应当前请求。</p>
     */
    private String buildEvidenceContext(String hospitalId, String ruleId) {
        if (evidenceStore == null || hospitalId == null || ruleId == null) {
            return "";
        }
        try {
            List<EvidenceEnvelope> envelopes = evidenceStore.recentByRule(hospitalId, ruleId, 3);
            if (envelopes.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (EvidenceEnvelope envelope : envelopes) {
                sb.append("[EV] ").append(envelope.factType()).append(": ");
                sb.append(formatPayload(envelope.safePayload()));
                // 证据溯源元数据：证据号 + 来源对象（RUN_/SQL_ 等）+ 记录时间。
                sb.append(" ｜ 证据号=").append(envelope.evidenceId());
                if (envelope.sourceObjectId() != null && !envelope.sourceObjectId().isBlank()) {
                    sb.append(", 来源=").append(envelope.sourceObjectId());
                }
                if (envelope.createdAt() != null) {
                    sb.append(", 记录于=").append(envelope.createdAt());
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String formatPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "无摘要";
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, payload, "numeratorCount", "分子");
        appendIfPresent(sb, payload, "denominatorCount", "分母");
        appendIfPresent(sb, payload, "resultValue", "结果");
        appendIfPresent(sb, payload, "sampleCount", "样本");
        appendIfPresent(sb, payload, "sqlId", "sqlId");
        appendIfPresent(sb, payload, "sqlStatus", "状态");
        appendIfPresent(sb, payload, "statStart", "开始");
        appendIfPresent(sb, payload, "statEnd", "结束");
        appendIfPresent(sb, payload, "statStartTime", "开始");
        appendIfPresent(sb, payload, "statEndTime", "结束");
        appendIfPresent(sb, payload, "caliberLabel", "口径");
        appendIfPresent(sb, payload, "ruleName", "指标");
        appendIfPresent(sb, payload, "diagnoseStatus", "诊断状态");
        appendIfPresent(sb, payload, "userSummary", "摘要");
        if (sb.isEmpty()) {
            // 没有命中已知字段时，取前 3 个键值对
            int count = 0;
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (count++ >= 3) break;
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        return sb.toString();
    }

    private static void appendIfPresent(
            StringBuilder sb, Map<String, Object> payload, String key, String label) {
        Object value = payload.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(label).append("=").append(value);
        }
    }

    /**
     * 根据本轮意图和指标名生成代码摘要（digest），不调用 LLM。
     * 用于更早轮次的历史压缩，让 Planner 用极少 token 了解之前做了什么。
     */
    private static String generateDigest(AgentRunState state, String content) {
        if (state == null) {
            return firstSentence(content, 80);
        }
        String intent = state.lastIntent();
        String name = state.lastRuleName() != null ? state.lastRuleName() : "该指标";
        if (intent == null || intent.isBlank()) {
            return firstSentence(content, 80);
        }
        return switch (intent) {
            case "rule_explanation" -> "已解释" + name + "的定义、公式和本院口径";
            case "indicator_sql_prepare" -> "已为" + name + "生成并校验 SQL";
            case "indicator_trial_run" -> "已计算" + name + "的结果"
                    + (state.statStart() != null
                            ? "（" + state.statStart() + " 至 " + state.statEnd() + "）" : "");
            case "indicator_diagnosis" -> "已诊断" + name + "的异常原因";
            case "indicator_caliber_query" -> "已查询" + name + "的可用口径列表";
            case "indicator_caliber_simulation" -> "已模拟" + name + "的候选口径计算";
            case "indicator_difference_diagnosis" -> "已完成" + name + "的差异对比诊断";
            case "upload_analysis" -> "已分析上传文件";
            case "compound" -> "已处理" + name;
            default -> firstSentence(content, 80);
        };
    }

    private static String firstSentence(String content, int limit) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String stripped = content.strip();
        // 取第一个换行或句号之前的内容
        int end = stripped.length();
        for (int i = 0; i < stripped.length() && i < limit; i++) {
            char c = stripped.charAt(i);
            if (c == '\n' || c == '。' || c == '！') {
                end = i + 1;
                break;
            }
        }
        end = Math.min(end, limit);
        return stripped.substring(0, end) + (stripped.length() > end ? "…" : "");
    }

    // ─── 会话管理查询 ───────────────────────────────────────────────────────────

    /**
     * 查询当前用户的所有会话摘要，按最后消息时间倒序。
     * 标题取该会话时间上第一条 user 消息的前 60 个字符（而非字典序最小）。
     */
    public List<SessionSummary> listSessions(HospitalPrincipal principal) {
        if (jdbc == null) {
            return List.of();
        }
        String prefix = "agent:" + principal.hospitalId() + ":" + principal.userId() + ":";
        try {
            return jdbc.query("""
                    SELECT m1.session_key,
                           (SELECT m2.content FROM med_agent_java_message m2
                            WHERE m2.session_key = m1.session_key AND m2.role = 'user'
                            ORDER BY m2.id ASC LIMIT 1) AS title,
                           MAX(m1.created_at) AS last_at,
                           COUNT(*) AS message_count
                    FROM med_agent_java_message m1
                    WHERE m1.session_key LIKE ? ESCAPE '\\'
                    GROUP BY m1.session_key
                    ORDER BY MAX(m1.created_at) DESC
                    LIMIT 100
                    """, (result, row) -> {
                String key = result.getString("session_key");
                String sessionId = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
                String title = result.getString("title");
                if (title != null && title.length() > 60) {
                    title = title.substring(0, 60) + "…";
                }
                return new SessionSummary(
                        sessionId,
                        title == null || title.isBlank() ? "新对话" : title.strip(),
                        result.getString("last_at"),
                        result.getInt("message_count"));
            }, prefix.replace("%", "\\%").replace("_", "\\_") + "%");
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to list sessions: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 查询指定会话的消息，按时间正序返回。
     * 会话超过 200 条时只保留最近 200 条（而非最早 200 条），避免恢复会话时
     * 丢失最新对话、只看到最早的历史。
     */
    public List<SessionMessage> getSessionMessages(HospitalPrincipal principal, String sessionId) {
        if (jdbc == null) {
            return List.of();
        }
        String key = storageKey(principal, sessionId);
        try {
            return jdbc.query("""
                    SELECT role, content, rule_id, rule_name, stat_start, stat_end, run_id,
                           created_at, batch_results
                    FROM (
                      SELECT id, role, content, rule_id, rule_name, stat_start, stat_end, run_id,
                             created_at, batch_results
                      FROM med_agent_java_message
                      WHERE session_key = ?
                      ORDER BY id DESC
                      LIMIT 200
                    ) recent_messages
                    ORDER BY id ASC
                    """, (result, row) -> new SessionMessage(
                    result.getString("role"),
                    result.getString("content"),
                    result.getString("rule_id"),
                    result.getString("rule_name"),
                    result.getString("stat_start"),
                    result.getString("stat_end"),
                    result.getString("run_id"),
                    result.getString("created_at"),
                    parseBatchResults(result.getString("batch_results"))), key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to load session messages: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 把持久化的批量卡片 JSON 还原为结构化列表；为空或解析失败时返回 null，
     * 按无卡片处理，不影响文本消息恢复。
     */
    private List<Map<String, Object>> parseBatchResults(String json) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception exception) {
            LOGGER.warn("Unable to parse persisted batch results: {}", exception.getMessage());
            return null;
        }
    }

    /**
     * 删除指定会话的全部消息。
     */
    public void deleteSession(HospitalPrincipal principal, String sessionId) {
        if (jdbc == null) {
            return;
        }
        String key = storageKey(principal, sessionId);
        try {
            jdbc.update("DELETE FROM med_agent_java_message WHERE session_key = ?", key);
            jdbc.update("DELETE FROM med_agent_compound_target WHERE session_key = ?", key);
            jdbc.update("DELETE FROM med_agent_query_scope WHERE session_key = ?", key);
            fallback.remove(key);
            fallbackContext.remove(key);
            compoundTargets.remove(key);
            queryScopes.remove(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to delete session: {}", exception.getMessage());
        }
    }

    /** 生成一个新的会话 ID。 */
    public static String newSessionId() {
        return "session_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    // ─── 会话管理数据载体 ─────────────────────────────────────────────────────────

    public record SessionSummary(
            String sessionId,
            String title,
            String lastMessageAt,
            int messageCount) {
    }

    public record SessionMessage(
            String role,
            String content,
            String ruleId,
            String ruleName,
            String statStart,
            String statEnd,
            String runId,
            String createdAt,
            List<Map<String, Object>> batchResults) {
    }

    public record ConversationSnapshot(
            String storageKey,
            String sessionId,
            String recentHistory,
            String structuredSummary,
            String ruleId,
            String ruleName,
            String caliberProfileId,
            String caliberLabel,
            String statStart,
            String statEnd,
            String lastRunId,
            String uploadFileKey,
            List<String> compoundTargets,
            QueryScopeState queryScope,
            String evidenceContext) {
        public ConversationSnapshot {
            compoundTargets = compoundTargets == null ? List.of() : List.copyOf(compoundTargets);
            evidenceContext = evidenceContext == null ? "" : evidenceContext;
        }

        public ConversationSnapshot(
                String storageKey,
                String sessionId,
                String recentHistory,
                String structuredSummary,
                String ruleId,
                String ruleName,
                String caliberProfileId,
                String caliberLabel,
                String statStart,
                String statEnd,
                String lastRunId,
                String uploadFileKey,
                List<String> compoundTargets,
                String evidenceContext) {
            this(storageKey, sessionId, recentHistory, structuredSummary,
                    ruleId, ruleName, caliberProfileId, caliberLabel,
                    statStart, statEnd, lastRunId, uploadFileKey,
                    compoundTargets, null, evidenceContext);
        }
    }

    public record QueryScopeState(
            String operation,
            String targetMode,
            List<QueryTarget> targets,
            String statStart,
            String statEnd) {
        public QueryScopeState {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        public boolean valid() {
            if (!List.of(
                    "rule_explanation",
                    "indicator_sql_prepare",
                    "indicator_trial_run",
                    "indicator_diagnosis").contains(operation)) {
                return false;
            }
            return "ALL".equals(targetMode)
                    || (List.of("SINGLE", "SUBSET").contains(targetMode) && !targets.isEmpty());
        }

        QueryScopeState normalized() {
            List<QueryTarget> cleaned = targets.stream()
                    .filter(value -> value != null
                            && value.ruleId() != null && !value.ruleId().isBlank()
                            && value.ruleName() != null && !value.ruleName().isBlank())
                    .map(value -> new QueryTarget(
                            value.ruleId().strip(), value.ruleName().strip()))
                    .distinct()
                    .limit(35)
                    .toList();
            return new QueryScopeState(
                    operation == null ? "" : operation.strip(),
                    targetMode == null ? "" : targetMode.strip().toUpperCase(),
                    cleaned,
                    statStart == null || statStart.isBlank() ? null : statStart.strip(),
                    statEnd == null || statEnd.isBlank() ? null : statEnd.strip());
        }
    }

    public record QueryTarget(String ruleId, String ruleName) {
    }

    private record ContextValues(
            String ruleId,
            String ruleName,
            String caliberProfileId,
            String caliberLabel,
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
            String caliberProfileId,
            String caliberLabel,
            String statStart,
            String statEnd,
            String runId,
            String uploadFileKey,
            String createdAt,
            String digest,
            String batchResults) {
    }
}
