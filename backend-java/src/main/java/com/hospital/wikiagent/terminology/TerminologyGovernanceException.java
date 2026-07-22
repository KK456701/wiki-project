package com.hospital.wikiagent.terminology;

import org.springframework.http.HttpStatus;

/**
 * 表示 {@code TerminologyGovernanceException} 对应的业务失败，供上层统一处理错误语义。
 */
public class TerminologyGovernanceException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public TerminologyGovernanceException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
