package com.hospital.wikiagent.agent.evidence;

import java.util.Optional;

public interface EvidenceStore {
    void saveEvidence(EvidenceEnvelope value);
    void saveVerification(EvidenceVerification value);
    Optional<EvidenceEnvelope> loadEvidence(String evidenceId);
    Optional<EvidenceVerification> loadVerified(String evidenceId);
}
