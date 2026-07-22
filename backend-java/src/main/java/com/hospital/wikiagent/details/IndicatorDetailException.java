package com.hospital.wikiagent.details;

import org.springframework.http.HttpStatus;

/**
 * 表示 {@code IndicatorDetailException} 对应的业务失败，供上层统一处理错误语义。
 */
public class IndicatorDetailException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public IndicatorDetailException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public IndicatorDetailException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
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
