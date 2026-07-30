package com.hospital.wikiagent.agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

class EvidenceLedgerTest {
    @Test
    void persistsOnlySafePayloadAndVerifierRejectsCrossHospitalAccess() {
        MemoryStore store = new MemoryStore();
        AgentModelProperties properties = new AgentModelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, new ObjectMapper(), properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        AgentRunState state = new AgentRunState();
        state.subtaskId("subtask_001");
        state.currentRuleId("MQSI2025_005");
        AgentRuntimeContext context = context("hospital_001");
        ToolResult raw = ToolResult.success("SQL_PREPARED", "已生成 SQL", Map.of(
                "ruleId", "MQSI2025_005",
                "sqlId", "SQL_001",
                "statStart", "2026-01-01T00:00",
                "statEnd", "2026-04-01T00:00:00.123456789",
                "sql", "SELECT patient_id FROM secret"));

        ToolResult recorded = ledger.recordToolResult(
                "prepare_indicator_sql", Map.of("ruleId", "MQSI2025_005"),
                raw, context, state);
        EvidenceEnvelope envelope = store.loadEvidence(recorded.evidenceIds().get(0)).orElseThrow();

        assertThat(envelope.factType()).isEqualTo("sql_validation");
        assertThat(envelope.safePayload()).containsEntry("sqlId", "SQL_001");
        assertThat(envelope.safePayload()).doesNotContainKey("sql");
        assertThat(envelope.confidentiality()).isEqualTo("sensitive_reference");

        var expected = new EvidenceVerifier.VerificationExpectations(
                "subtask_001", "MQSI2025_005", "2026-01-01 00:00:00",
                "2026-04-01 00:00:00", "SQL_001",
                Map.of(envelope.evidenceId(), recorded));
        assertThat(verifier.verifyMany(recorded.evidenceIds(), context, expected)).hasSize(1);
        assertThatThrownBy(() -> verifier.verifyMany(
                recorded.evidenceIds(), context("hospital_002"), expected))
                .isInstanceOf(EvidenceAccessException.class)
                .hasMessageContaining("不属于当前医院");
    }

    @Test
    void dualRunCreatesIndependentFactTypesWithoutPatientRows() {
        MemoryStore store = new MemoryStore();
        EvidenceLedger ledger = new EvidenceLedger(
                store, new ObjectMapper(), new AgentModelProperties());
        AgentRunState state = new AgentRunState();
        state.subtaskId("subtask_dual");
        state.currentRuleId("HXZD-001-001");
        ToolResult raw = ToolResult.success(
                "TRIAL_RUN_COMPLETED",
                "双库完成",
                Map.of(
                        "ruleId", "HXZD-001-001",
                        "runId", "RUN_COMPOSITE_1",
                        "comparisonStatus", "mismatched",
                        "businessResult", Map.of(
                                "runId", "RUN_BUSINESS_1",
                                "numeratorCount", 10,
                                "denominatorCount", 100),
                        "realResult", Map.of(
                                "runId", "RUN_REAL_1",
                                "numeratorCount", 9,
                                "denominatorCount", 100),
                        "patientRows", List.of(Map.of("patient_id", "secret"))));

        ToolResult recorded = ledger.recordToolResult(
                "trial_run_indicator_sql", Map.of(), raw,
                context("hospital_001"), state);

        assertThat(recorded.evidenceIds()).hasSize(5);
        assertThat(recorded.evidenceIds().stream()
                .map(store::loadEvidence)
                .map(Optional::orElseThrow)
                .map(EvidenceEnvelope::factType))
                .containsExactly(
                        "trial_run",
                        "source_extraction_completed",
                        "business_overview_result",
                        "real_overview_result",
                        "dual_result_comparison");
        assertThat(store.loadEvidence(recorded.evidenceIds().get(0)).orElseThrow()
                .safePayload()).doesNotContainKey("patientRows");
    }

    private static AgentRuntimeContext context(String hospitalId) {
        return new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user_001", "doctor", hospitalId, Set.of(), false, "session_001"),
                "request_001", "trace_001", "db_source_001");
    }

    private static class MemoryStore implements EvidenceStore {
        private final Map<String, EvidenceEnvelope> evidence = new LinkedHashMap<>();
        private final Map<String, EvidenceVerification> verifications = new LinkedHashMap<>();

        @Override
        public void saveEvidence(EvidenceEnvelope value) {
            evidence.put(value.evidenceId(), value);
        }

        @Override
        public void saveVerification(EvidenceVerification value) {
            verifications.put(value.evidenceId(), value);
        }

        @Override
        public Optional<EvidenceEnvelope> loadEvidence(String evidenceId) {
            return Optional.ofNullable(evidence.get(evidenceId));
        }

        @Override
        public Optional<EvidenceVerification> loadVerified(String evidenceId) {
            return Optional.ofNullable(verifications.get(evidenceId))
                    .filter(value -> "verified".equals(value.status()));
        }
    }
}
