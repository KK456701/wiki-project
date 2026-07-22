package com.hospital.wikiagent.agent.planning;

/**
 * 表示 {@code CapabilityDispatchException} 对应的业务失败，供上层统一处理错误语义。
 */
public class CapabilityDispatchException extends RuntimeException {
    private final String code;
    private final boolean needsClarification;

    public CapabilityDispatchException(String code, String message) {
        this(code, message, false);
    }

    public CapabilityDispatchException(String code, String message, boolean needsClarification) {
        super(message);
        this.code = code;
        this.needsClarification = needsClarification;
    }

    public String code() {
        return code;
    }

    public boolean needsClarification() {
        return needsClarification;
    }
}
