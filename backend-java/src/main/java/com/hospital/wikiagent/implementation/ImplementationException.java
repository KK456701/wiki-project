package com.hospital.wikiagent.implementation;

public class ImplementationException extends RuntimeException {
    private final String code;
    private final int status;

    public ImplementationException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
