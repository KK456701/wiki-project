package com.hospital.wikiagent.agent.evidence;

public record VerifiedEvidence(
        EvidenceEnvelope evidence,
        EvidenceVerification verification) {

    public VerifiedEvidence {
        if (evidence == null || verification == null
                || !"verified".equals(verification.status())
                || !evidence.evidenceId().equals(verification.evidenceId())) {
            throw new IllegalArgumentException("最终回答只允许使用已验证 Evidence");
        }
    }
}
