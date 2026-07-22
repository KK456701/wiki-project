package com.hospital.wikiagent.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 定义 {@code AgentChatRequest} 的不可变数据载体。
 */
public record AgentChatRequest(
        @NotBlank @Size(max = 5000) String query,
        @Size(min = 1, max = 128) String sessionId,
        @Size(min = 1, max = 128) String modelId,
        @Size(min = 1, max = 255) @Pattern(regexp = "^[^/\\\\]+$") String fileKey) {

    public AgentChatRequest {
        query = trim(query);
        sessionId = trim(sessionId);
        modelId = trim(modelId);
        fileKey = trim(fileKey);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

}
