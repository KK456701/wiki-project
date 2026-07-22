package com.hospital.wikiagent.agent.model;

/**
 * 表示 {@code PlannerOutputException} 对应的业务失败，供上层统一处理错误语义。
 */
public class PlannerOutputException extends RuntimeException {
    private final String code;

    public PlannerOutputException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
