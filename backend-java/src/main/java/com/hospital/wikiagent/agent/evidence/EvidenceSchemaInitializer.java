package com.hospital.wikiagent.agent.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class EvidenceSchemaInitializer {
    private final JdbcTemplate jdbc;

    public EvidenceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_evidence (
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
                    CREATE TABLE IF NOT EXISTS med_agent_evidence_verification (
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
        } catch (RuntimeException ignored) {
            // 运行库不可用时由 JdbcJsonlEvidenceStore 使用 JSONL 兜底，不能阻止服务启动。
        }
    }
}
