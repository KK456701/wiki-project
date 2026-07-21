package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
