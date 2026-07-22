package com.hospital.wikiagent.auth;

import org.springframework.http.HttpStatus;

/**
 * 表示 {@code HospitalAuthException} 对应的业务失败，供上层统一处理错误语义。
 */
public class HospitalAuthException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public HospitalAuthException(String message, String code, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
