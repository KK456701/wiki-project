package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义 {@code EvidenceEnvelope} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record EvidenceEnvelope(
        String schemaVersion,
        String evidenceId,
        String traceId,
        String subtaskId,
        String factType,
        String hospitalId,
        String ruleId,
        String ruleVersion,
        String statStart,
        String statEnd,
        String sourceTool,
        String sourceObjectId,
        String inputFingerprint,
        String resultFingerprint,
        String confidentiality,
        Instant createdAt,
        Instant expiresAt,
        String payloadRef,
        Map<String, Object> safePayload) {

    public static final String VERSION = "evidence-envelope-v1";

    public EvidenceEnvelope {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? VERSION : schemaVersion;
        safePayload = safePayload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(safePayload));
    }
}
