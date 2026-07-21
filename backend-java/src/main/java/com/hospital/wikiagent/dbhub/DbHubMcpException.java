package com.hospital.wikiagent.dbhub;

public class DbHubMcpException extends RuntimeException {

    public DbHubMcpException(String message) {
        super(message);
    }

    public DbHubMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
