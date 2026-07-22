package com.hospital.wikiagent.agent.evidence;

import java.util.Optional;

/**
 * 定义 {@code EvidenceStore} 的稳定协作契约，便于替换实现和隔离测试。
 *
 * <p>实现方必须遵守相同的医院隔离、超时和错误语义，替换实现不能扩大权限。接口保持无框架业务语义，便于单元测试和受控适配外部系统。</p>
 */
public interface EvidenceStore {
    void saveEvidence(EvidenceEnvelope value);
    void saveVerification(EvidenceVerification value);
    Optional<EvidenceEnvelope> loadEvidence(String evidenceId);
    Optional<EvidenceVerification> loadVerified(String evidenceId);
}
