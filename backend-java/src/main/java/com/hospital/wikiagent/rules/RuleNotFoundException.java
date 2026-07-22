package com.hospital.wikiagent.rules;

/**
 * 表示 {@code RuleNotFoundException} 对应的业务失败，供上层统一处理错误语义。
 */
public class RuleNotFoundException extends RuntimeException {
    public RuleNotFoundException(String message) {
        super(message);
    }
}
