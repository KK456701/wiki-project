package com.hospital.wikiagent.agent.evidence;

/**
 * 表示 {@code EvidenceAccessException} 对应的业务失败，供上层统一处理错误语义。
 */
public class EvidenceAccessException extends RuntimeException {
    private final String code;

    public EvidenceAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
