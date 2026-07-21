package com.hospital.wikiagent.rules;

public class RuleNotFoundException extends RuntimeException {
    public RuleNotFoundException(String message) {
        super(message);
    }
}
