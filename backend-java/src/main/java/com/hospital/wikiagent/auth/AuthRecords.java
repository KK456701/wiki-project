package com.hospital.wikiagent.auth;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 实现 {@code AuthRecords} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
final class AuthRecords {
    private AuthRecords() {
    }

    record User(
            String userId,
            String accountId,
            String hospitalId,
            String passwordHash,
            String passwordSalt,
            int passwordIterations,
            boolean mustChangePassword,
            String status,
            int failedAttempts,
            LocalDateTime lockedUntil) {
    }

    record Session(
            String sessionId,
            String userId,
            String accountId,
            String hospitalId,
            boolean mustChangePassword,
            String status,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt) {
    }
}
