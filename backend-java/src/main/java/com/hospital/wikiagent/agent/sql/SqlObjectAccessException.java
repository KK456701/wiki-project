package com.hospital.wikiagent.agent.sql;

/**
 * 表示 {@code SqlObjectAccessException} 对应的业务失败，供上层统一处理错误语义。
 */
public class SqlObjectAccessException extends RuntimeException {
    private final String code;

    public SqlObjectAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
