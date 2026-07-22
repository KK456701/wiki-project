package com.hospital.wikiagent.terminology;

import org.springframework.http.HttpStatus;

/**
 * 表示 {@code TerminologyGovernanceException} 对应的业务失败，供上层统一处理错误语义。
 *
 * <p>异常只携带稳定错误语义和可安全展示的信息，不附带密码、令牌、SQL 明文或患者数据。API 层负责将其映射为一致的状态码与响应结构。</p>
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
