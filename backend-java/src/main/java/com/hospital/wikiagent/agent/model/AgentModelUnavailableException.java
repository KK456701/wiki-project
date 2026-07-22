package com.hospital.wikiagent.agent.model;

/**
 * 表示 {@code AgentModelUnavailableException} 对应的业务失败，供上层统一处理错误语义。
 *
 * <p>异常只携带稳定错误语义和可安全展示的信息，不附带密码、令牌、SQL 明文或患者数据。API 层负责将其映射为一致的状态码与响应结构。</p>
 */
public class AgentModelUnavailableException extends RuntimeException {
    private final String code;

    public AgentModelUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
