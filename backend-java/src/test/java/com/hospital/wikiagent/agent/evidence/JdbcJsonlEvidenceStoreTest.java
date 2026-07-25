package com.hospital.wikiagent.agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.model.AgentModelProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 验证 {@link JdbcJsonlEvidenceStore#recentByRule} 只返回已验证且未过期的 Evidence。
 *
 * <p>该检索结果会被注入下一轮 Planner 上下文，因此必须排除被拒绝、未验证和已过期的
 * 记录，避免把不可信的工具结果当作既成事实喂给模型。</p>
 */
class JdbcJsonlEvidenceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void recentByRuleReturnsOnlyVerifiedAndUnexpiredEvidence() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("evidence_store_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        createSchema(jdbc);
        AgentModelProperties properties = new AgentModelProperties();
        properties.setEvidenceJsonlPath(tempDir.resolve("evidence.jsonl").toString());
        JdbcJsonlEvidenceStore store = new JdbcJsonlEvidenceStore(
                jdbc, new ObjectMapper(), properties);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // 已验证 + 未过期 → 应返回（最新）
        store.saveEvidence(envelope("EVD_VERIFIED", "hospital_001", "MQSI2025_005",
                now.minus(1, ChronoUnit.HOURS), now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_VERIFIED", "hospital_001", "verified"));
        // 已验证 + 未过期 → 应返回（较旧，验证按创建时间倒序）
        store.saveEvidence(envelope("EVD_VERIFIED_OLD", "hospital_001", "MQSI2025_005",
                now.minus(5, ChronoUnit.HOURS), now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_VERIFIED_OLD", "hospital_001", "verified"));
        // 被拒绝 → 不应返回
        store.saveEvidence(envelope("EVD_REJECTED", "hospital_001", "MQSI2025_005",
                now.minus(2, ChronoUnit.HOURS), now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_REJECTED", "hospital_001", "rejected"));
        // 从未验证（写入台账后尚未经过 EvidenceVerifier）→ 不应返回
        store.saveEvidence(envelope("EVD_UNVERIFIED", "hospital_001", "MQSI2025_005",
                now.minus(3, ChronoUnit.HOURS), now.plus(30, ChronoUnit.DAYS)));
        // 已验证但已过期 → 不应返回
        store.saveEvidence(envelope("EVD_EXPIRED", "hospital_001", "MQSI2025_005",
                now.minus(4, ChronoUnit.HOURS), now.minus(1, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_EXPIRED", "hospital_001", "verified"));

        List<EvidenceEnvelope> recent = store.recentByRule("hospital_001", "MQSI2025_005", 5);

        assertThat(recent).extracting(EvidenceEnvelope::evidenceId)
                .containsExactly("EVD_VERIFIED", "EVD_VERIFIED_OLD");
    }

    @Test
    void recentByRuleIsolatesByHospitalAndRule() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("evidence_store_iso_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        createSchema(jdbc);
        AgentModelProperties properties = new AgentModelProperties();
        properties.setEvidenceJsonlPath(tempDir.resolve("evidence_iso.jsonl").toString());
        JdbcJsonlEvidenceStore store = new JdbcJsonlEvidenceStore(
                jdbc, new ObjectMapper(), properties);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.saveEvidence(envelope("EVD_TARGET", "hospital_001", "MQSI2025_005",
                now, now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_TARGET", "hospital_001", "verified"));
        // 其他医院 → 不应返回
        store.saveEvidence(envelope("EVD_OTHER_HOSPITAL", "hospital_002", "MQSI2025_005",
                now, now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_OTHER_HOSPITAL", "hospital_002", "verified"));
        // 其他指标 → 不应返回
        store.saveEvidence(envelope("EVD_OTHER_RULE", "hospital_001", "MQSI2025_006",
                now, now.plus(30, ChronoUnit.DAYS)));
        store.saveVerification(verification("EVD_OTHER_RULE", "hospital_001", "verified"));

        List<EvidenceEnvelope> recent = store.recentByRule("hospital_001", "MQSI2025_005", 5);

        assertThat(recent).extracting(EvidenceEnvelope::evidenceId)
                .containsExactly("EVD_TARGET");
    }

    private static EvidenceEnvelope envelope(
            String evidenceId, String hospitalId, String ruleId,
            Instant createdAt, Instant expiresAt) {
        return new EvidenceEnvelope(
                EvidenceEnvelope.VERSION, evidenceId, "TRACE_1", "SUB_1", "trial_run",
                hospitalId, ruleId, "2025", "2026-01-01", "2026-07-01",
                "trial_run_indicator_sql", "RUN_001", "fp_in", "fp_out",
                "internal", createdAt, expiresAt, null,
                Map.of("result_value", "87.5%"));
    }

    private static EvidenceVerification verification(
            String evidenceId, String hospitalId, String status) {
        return new EvidenceVerification(
                EvidenceVerification.VERSION, "EVV_" + evidenceId, evidenceId,
                "TRACE_1", "SUB_1", hospitalId, EvidenceVerifier.VERSION,
                status, "PLAN_VERIFIED", "", Instant.now());
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE med_agent_evidence (
                  evidence_id VARCHAR(80) PRIMARY KEY,
                  schema_version VARCHAR(40) NOT NULL,
                  trace_id VARCHAR(128) NOT NULL,
                  subtask_id VARCHAR(128) NOT NULL,
                  fact_type VARCHAR(80) NOT NULL,
                  hospital_id VARCHAR(128) NOT NULL,
                  rule_id VARCHAR(128),
                  rule_version VARCHAR(80),
                  stat_start VARCHAR(40),
                  stat_end VARCHAR(40),
                  source_tool VARCHAR(80) NOT NULL,
                  source_object_id VARCHAR(128),
                  input_fingerprint VARCHAR(64) NOT NULL,
                  result_fingerprint VARCHAR(64) NOT NULL,
                  confidentiality VARCHAR(32) NOT NULL,
                  created_at VARCHAR(40) NOT NULL,
                  expires_at VARCHAR(40),
                  payload_ref VARCHAR(255),
                  safe_payload_json TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE med_agent_evidence_verification (
                  verification_id VARCHAR(80) PRIMARY KEY,
                  schema_version VARCHAR(40) NOT NULL,
                  evidence_id VARCHAR(80) NOT NULL,
                  trace_id VARCHAR(128) NOT NULL,
                  subtask_id VARCHAR(128) NOT NULL,
                  hospital_id VARCHAR(128) NOT NULL,
                  verifier_version VARCHAR(80) NOT NULL,
                  status VARCHAR(20) NOT NULL,
                  code VARCHAR(80) NOT NULL,
                  message TEXT NOT NULL,
                  verified_at VARCHAR(40) NOT NULL
                )
                """);
    }
}
