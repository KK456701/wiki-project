package com.hospital.wikiagent.dbhub;

/**
 * 表示 {@code DbHubMcpException} 对应的业务失败，供上层统一处理错误语义。
 */
public class DbHubMcpException extends RuntimeException {

    public DbHubMcpException(String message) {
        super(message);
    }

    public DbHubMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
