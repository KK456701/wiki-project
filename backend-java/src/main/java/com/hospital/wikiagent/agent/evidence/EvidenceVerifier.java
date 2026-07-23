package com.hospital.wikiagent.agent.evidence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

/**
 * 校验 Evidence 的医院、子任务、规则、周期和 SQL 链一致性后生成 VerifiedEvidence。
 *
 * <p>验证由确定性代码完成，至少检查医院、子任务、过期时间、规则、统计周期、SQL 对象和
 * 本轮结果指纹。任一维度不一致都会保存独立的 rejected 验证记录并终止回答，模型无权覆盖
 * 验证结论或把未验证 Evidence 当作事实。</p>
 */
@Component
public class EvidenceVerifier {
    public static final String VERSION = "plan-verifier-v1";

    private final EvidenceStore store;
    private final EvidenceLedger ledger;

    public EvidenceVerifier(EvidenceStore store, EvidenceLedger ledger) {
        this.store = store;
        this.ledger = ledger;
    }

    /**
     * 按输入顺序验证本轮全部 Evidence，并返回不可变的已验证列表。
     */
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
        requireTimeMatch(expected.statStart(), value.statStart(),
                "EVIDENCE_PERIOD_MISMATCH", "证据统计开始时间与当前请求不一致。");
        requireTimeMatch(expected.statEnd(), value.statEnd(),
                "EVIDENCE_PERIOD_MISMATCH", "证据统计结束时间与当前请求不一致。");
        String evidenceSqlId = text(value.safePayload().get("sql_id"));
        requireMatch(expected.sqlId(), evidenceSqlId,
                "EVIDENCE_SQL_MISMATCH", "证据 SQL 对象与当前已校验 SQL 不一致。");
        String evidenceCaliberProfileId = text(
                value.safePayload().get("caliber_profile_id"));
        requireMatch(expected.caliberProfileId(), evidenceCaliberProfileId,
                "EVIDENCE_CALIBER_MISMATCH", "证据候选口径与当前计划不一致。");
        String evidenceRuleVersion = text(
                value.safePayload().get("current_rule_version"));
        requireMatch(expected.currentRuleVersion(), evidenceRuleVersion,
                "EVIDENCE_RULE_MISMATCH", "候选口径证据引用的当前规则版本已变化。");
        String caliberSqlId = text(value.safePayload().get("caliber_sql_id"));
        requireMatch(expected.sqlId(), caliberSqlId,
                "EVIDENCE_SQL_MISMATCH", "候选口径试运行未使用当前已校验 SQL。");
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

    /**
     * Evidence 的时间可能来自 SQL 对象（空格分隔、秒精度）或 Java 业务对象
     *（ISO {@code T} 分隔、可能带纳秒）。两者表示同一时刻时必须视为一致，避免仅因
     * 序列化格式不同拒绝一份已经通过业务校验的证据。
     */
    private static void requireTimeMatch(
            String expected,
            String actual,
            String code,
            String message) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return;
        }
        String expectedTime = canonicalTime(expected);
        String actualTime = canonicalTime(actual);
        if (!expectedTime.equals(actualTime)) {
            throw new EvidenceAccessException(code, message);
        }
    }

    private static String canonicalTime(String value) {
        String normalized = value.strip().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized).withNano(0).toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized).atStartOfDay().toString();
            } catch (DateTimeParseException ignoredDate) {
                // 未知格式仍按原始文本严格比较，不能放宽 Evidence 的安全边界。
                return value.strip();
            }
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
            String caliberProfileId,
            String currentRuleVersion,
            Map<String, ToolResult> currentToolResults) {
        public VerificationExpectations(
                String subtaskId,
                String ruleId,
                String statStart,
                String statEnd,
                String sqlId,
                Map<String, ToolResult> currentToolResults) {
            this(subtaskId, ruleId, statStart, statEnd, sqlId, null, null, currentToolResults);
        }

        public VerificationExpectations {
            if (subtaskId == null || subtaskId.isBlank()) {
                throw new IllegalArgumentException("Evidence 校验必须指定子任务");
            }
            currentToolResults = currentToolResults == null ? Map.of() : Map.copyOf(currentToolResults);
        }
    }
}
