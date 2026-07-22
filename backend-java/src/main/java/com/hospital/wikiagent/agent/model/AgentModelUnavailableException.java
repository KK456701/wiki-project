package com.hospital.wikiagent.agent.model;

/**
 * 表示 {@code AgentModelUnavailableException} 对应的业务失败，供上层统一处理错误语义。
 */
public class AgentModelUnavailableException extends RuntimeException {
    private final String code;

    public AgentModelUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
