package com.hospital.wikiagent.agent.sql;

import java.time.Instant;
import java.util.Map;

/**
 * 定义 {@code PreparedSqlObject} 的不可变数据载体。
 */
public record PreparedSqlObject(
        String sqlId,
        String hospitalId,
        String userId,
        String sessionId,
        String ruleId,
        String dialect,
        String sqlText,
        Map<String, Object> params,
        String statStart,
        String statEnd,
        Map<String, Object> contextSnapshot,
        String contextDigest,
        String validationStatus,
        String validationMessage,
        Instant createdAt,
        Instant expiresAt,
        String dbSourceId) {

    public PreparedSqlObject {
        params = params == null ? Map.of() : Map.copyOf(params);
        contextSnapshot = contextSnapshot == null ? Map.of() : Map.copyOf(contextSnapshot);
    }
}
