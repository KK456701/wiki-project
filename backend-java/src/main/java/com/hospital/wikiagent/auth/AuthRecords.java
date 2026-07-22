package com.hospital.wikiagent.auth;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 实现 {@code AuthRecords} 对应的领域职责。
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
