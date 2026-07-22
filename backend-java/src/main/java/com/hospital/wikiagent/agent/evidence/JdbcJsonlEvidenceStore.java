package com.hospital.wikiagent.agent.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.model.AgentModelProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实现 {@code JdbcJsonlEvidenceStore} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class JdbcJsonlEvidenceStore implements EvidenceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path jsonlPath;
    private final Object fileLock = new Object();

    public JdbcJsonlEvidenceStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AgentModelProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jsonlPath = Path.of(properties.getEvidenceJsonlPath()).toAbsolutePath().normalize();
    }

    @Override
    public void saveEvidence(EvidenceEnvelope value) {
        try {
            jdbc.update("""
                    INSERT INTO med_agent_evidence (
                      evidence_id, schema_version, trace_id, subtask_id, fact_type, hospital_id,
                      rule_id, rule_version, stat_start, stat_end, source_tool, source_object_id,
                      input_fingerprint, result_fingerprint, confidentiality, created_at, expires_at,
                      payload_ref, safe_payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.evidenceId(), value.schemaVersion(), value.traceId(), value.subtaskId(),
                    value.factType(), value.hospitalId(), value.ruleId(), value.ruleVersion(),
                    value.statStart(), value.statEnd(), value.sourceTool(), value.sourceObjectId(),
                    value.inputFingerprint(), value.resultFingerprint(), value.confidentiality(),
                    iso(value.createdAt()), iso(value.expiresAt()), value.payloadRef(),
                    objectMapper.writeValueAsString(value.safePayload()));
        } catch (Exception exception) {
            append("evidence", value);
        }
    }

    @Override
    public void saveVerification(EvidenceVerification value) {
        try {
            jdbc.update("""
                    INSERT INTO med_agent_evidence_verification (
                      verification_id, schema_version, evidence_id, trace_id, subtask_id, hospital_id,
                      verifier_version, status, code, message, verified_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.verificationId(), value.schemaVersion(), value.evidenceId(), value.traceId(),
                    value.subtaskId(), value.hospitalId(), value.verifierVersion(), value.status(),
                    value.code(), value.message(), iso(value.verifiedAt()));
        } catch (RuntimeException exception) {
            append("verification", value);
        }
    }

    @Override
    public Optional<EvidenceEnvelope> loadEvidence(String evidenceId) {
        try {
            List<EvidenceEnvelope> rows = jdbc.query("""
                    SELECT * FROM med_agent_evidence WHERE evidence_id = ?
                    """, (result, row) -> new EvidenceEnvelope(
                    result.getString("schema_version"), result.getString("evidence_id"),
                    result.getString("trace_id"), result.getString("subtask_id"),
                    result.getString("fact_type"), result.getString("hospital_id"),
                    result.getString("rule_id"), result.getString("rule_version"),
                    result.getString("stat_start"), result.getString("stat_end"),
                    result.getString("source_tool"), result.getString("source_object_id"),
                    result.getString("input_fingerprint"), result.getString("result_fingerprint"),
                    result.getString("confidentiality"), parseInstant(result.getString("created_at")),
                    parseInstant(result.getString("expires_at")), result.getString("payload_ref"),
                    readMap(result.getString("safe_payload_json"))), evidenceId);
            if (!rows.isEmpty()) {
                return Optional.of(rows.get(0));
            }
        } catch (RuntimeException ignored) {
        }
        return loadJsonl("evidence", "evidence_id", evidenceId, EvidenceEnvelope.class);
    }

    @Override
    public Optional<EvidenceVerification> loadVerified(String evidenceId) {
        try {
            List<EvidenceVerification> rows = jdbc.query("""
                    SELECT * FROM med_agent_evidence_verification
                    WHERE evidence_id = ? AND status = 'verified'
                    ORDER BY verified_at DESC
                    """, (result, row) -> new EvidenceVerification(
                    result.getString("schema_version"), result.getString("verification_id"),
                    result.getString("evidence_id"), result.getString("trace_id"),
                    result.getString("subtask_id"), result.getString("hospital_id"),
                    result.getString("verifier_version"), result.getString("status"),
                    result.getString("code"), result.getString("message"),
                    parseInstant(result.getString("verified_at"))), evidenceId);
            if (!rows.isEmpty()) {
                return Optional.of(rows.get(0));
            }
        } catch (RuntimeException ignored) {
        }
        return loadJsonl("verification", "evidence_id", evidenceId, EvidenceVerification.class)
                .filter(value -> "verified".equals(value.status()));
    }

    private void append(String event, Object payload) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", event);
        record.put("payload", payload);
        synchronized (fileLock) {
            try {
                Path parent = jsonlPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        jsonlPath,
                        objectMapper.writeValueAsString(record) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new IllegalStateException("Evidence 数据库与 JSONL 兜底均不可写", exception);
            }
        }
    }

    private <T> Optional<T> loadJsonl(String event, String key, String value, Class<T> type) {
        if (!Files.exists(jsonlPath)) {
            return Optional.empty();
        }
        synchronized (fileLock) {
            try {
                List<String> lines = Files.readAllLines(jsonlPath, StandardCharsets.UTF_8);
                for (int index = lines.size() - 1; index >= 0; index--) {
                    JsonNode item = objectMapper.readTree(lines.get(index));
                    JsonNode payload = item.get("payload");
                    if (event.equals(item.path("event").asText())
                            && payload != null
                            && value.equals(payload.path(key).asText())) {
                        return Optional.of(objectMapper.treeToValue(payload, type));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, Map.class);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static String iso(Instant value) { return value == null ? null : value.toString(); }
    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
