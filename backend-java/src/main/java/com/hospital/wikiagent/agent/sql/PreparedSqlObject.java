package com.hospital.wikiagent.agent.sql;

import java.time.Instant;
import java.util.Map;

/**
 * 定义 {@code PreparedSqlObject} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
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
