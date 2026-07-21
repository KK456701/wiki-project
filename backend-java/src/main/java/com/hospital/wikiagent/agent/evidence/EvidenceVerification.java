package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;

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
