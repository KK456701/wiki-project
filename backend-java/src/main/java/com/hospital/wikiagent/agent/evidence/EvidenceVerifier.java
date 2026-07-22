package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

/** 校验 Evidence 的医院、子任务、规则、周期和 SQL 链一致性后生成 VerifiedEvidence。 */
@Component
public class EvidenceVerifier {
    public static final String VERSION = "plan-verifier-v1";

    private final EvidenceStore store;
    private final EvidenceLedger ledger;

    public EvidenceVerifier(EvidenceStore store, EvidenceLedger ledger) {
        this.store = store;
        this.ledger = ledger;
    }

    public List<VerifiedEvidence> verifyMany(
            List<String> evidenceIds,
            AgentRuntimeContext context,
            VerificationExpectations expected) {
        List<VerifiedEvidence> values = new ArrayList<>();
        for (String evidenceId : evidenceIds) {
            EvidenceEnvelope envelope = store.loadEvidence(evidenceId)
                    .orElseThrow(() -> new EvidenceAccessException(
                            "EVIDENCE_NOT_FOUND", "证据对象不存在。"));
            try {
                validate(envelope, context, expected);
            } catch (EvidenceAccessException exception) {
                store.saveVerification(verification(
                        envelope, context, "rejected", exception.code(), exception.getMessage()));
                throw exception;
            }
            EvidenceVerification verification = store.loadVerified(evidenceId)
                    .orElseGet(() -> {
                        EvidenceVerification created = verification(
                                envelope, context, "verified", "PLAN_VERIFIED", "");
                        store.saveVerification(created);
                        return created;
                    });
            values.add(new VerifiedEvidence(envelope, verification));
        }
        return List.copyOf(values);
    }

    private void validate(
            EvidenceEnvelope value,
            AgentRuntimeContext context,
            VerificationExpectations expected) {
        if (!value.hospitalId().equals(context.hospitalId())) {
            throw new EvidenceAccessException(
                    "EVIDENCE_HOSPITAL_MISMATCH", "证据不属于当前医院。");
        }
        if (!value.subtaskId().equals(expected.subtaskId())) {
            throw new EvidenceAccessException(
                    "EVIDENCE_SUBTASK_MISMATCH", "证据不属于当前子任务。");
        }
        if (value.expiresAt() != null && !value.expiresAt().isAfter(Instant.now())) {
            throw new EvidenceAccessException("EVIDENCE_EXPIRED", "证据对象已过期。");
        }
        requireMatch(expected.ruleId(), value.ruleId(),
                "EVIDENCE_RULE_MISMATCH", "证据规则与当前指标不一致。");
        requireMatch(expected.statStart(), value.statStart(),
                "EVIDENCE_PERIOD_MISMATCH", "证据统计开始时间与当前请求不一致。");
        requireMatch(expected.statEnd(), value.statEnd(),
                "EVIDENCE_PERIOD_MISMATCH", "证据统计结束时间与当前请求不一致。");
        String evidenceSqlId = text(value.safePayload().get("sql_id"));
        requireMatch(expected.sqlId(), evidenceSqlId,
                "EVIDENCE_SQL_MISMATCH", "证据 SQL 对象与当前已校验 SQL 不一致。");
        ToolResult currentResult = expected.currentToolResults().get(value.evidenceId());
        if (currentResult != null
                && !ledger.fingerprint(currentResult.withEvidenceIds(List.of()))
                .equals(value.resultFingerprint())) {
            throw new EvidenceAccessException(
                    "EVIDENCE_PAYLOAD_MISMATCH", "Evidence 与本轮工具结果不一致。");
        }
    }

    private EvidenceVerification verification(
            EvidenceEnvelope envelope,
            AgentRuntimeContext context,
            String status,
            String code,
            String message) {
        return new EvidenceVerification(
                EvidenceVerification.VERSION,
                "EVV_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                envelope.evidenceId(),
                envelope.traceId(),
                envelope.subtaskId(),
                context.hospitalId(),
                VERSION,
                status,
                code,
                message,
                Instant.now());
    }

    private static void requireMatch(String expected, String actual, String code, String message) {
        if (expected != null && !expected.isBlank()
                && actual != null && !actual.isBlank()
                && !expected.equals(actual)) {
            throw new EvidenceAccessException(code, message);
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    public record VerificationExpectations(
            String subtaskId,
            String ruleId,
            String statStart,
            String statEnd,
            String sqlId,
            Map<String, ToolResult> currentToolResults) {
        public VerificationExpectations {
            if (subtaskId == null || subtaskId.isBlank()) {
                throw new IllegalArgumentException("Evidence 校验必须指定子任务");
            }
            currentToolResults = currentToolResults == null ? Map.of() : Map.copyOf(currentToolResults);
        }
    }
}
