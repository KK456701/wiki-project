package com.hospital.wikiagent.agent.sql;

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
