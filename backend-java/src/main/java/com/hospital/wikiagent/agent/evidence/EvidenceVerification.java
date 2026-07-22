package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;

/**
 * 定义 {@code EvidenceVerification} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record EvidenceVerification(
        String schemaVersion,
        String verificationId,
        String evidenceId,
        String traceId,
        String subtaskId,
        String hospitalId,
        String verifierVersion,
        String status,
        String code,
        String message,
        Instant verifiedAt) {

    public static final String VERSION = "evidence-verification-v1";

    public EvidenceVerification {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? VERSION : schemaVersion;
        message = message == null ? "" : message;
    }
}
