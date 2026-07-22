package com.hospital.wikiagent.agent.evidence;

import java.util.Optional;

/**
 * 定义 {@code EvidenceStore} 的稳定协作契约，便于替换实现和隔离测试。
 */
public interface EvidenceStore {
    void saveEvidence(EvidenceEnvelope value);
    void saveVerification(EvidenceVerification value);
    Optional<EvidenceEnvelope> loadEvidence(String evidenceId);
    Optional<EvidenceVerification> loadVerified(String evidenceId);
}
