package com.hospital.wikiagent.monitoring;

/**
 * 表示 {@code MonitoringException} 对应的业务失败，供上层统一处理错误语义。
 */
public class MonitoringException extends RuntimeException {
    private final String code;
    private final int status;

    public MonitoringException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
