package com.hospital.wikiagent.contract;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 定义 {@code AgentChatRequest} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record AgentChatRequest(
        @NotBlank @Size(max = 5000) String query,
        @Size(min = 1, max = 128) String sessionId,
        @Size(min = 1, max = 128) String modelId,
        @Size(min = 1, max = 255) @Pattern(regexp = "^[^/\\\\]+$") String fileKey,
        @Valid ClarificationResponse clarificationResponse) {

    public AgentChatRequest(String query, String sessionId, String modelId, String fileKey) {
        this(query, sessionId, modelId, fileKey, null);
    }

    public AgentChatRequest {
        query = trim(query);
        sessionId = trim(sessionId);
        modelId = trim(modelId);
        fileKey = trim(fileKey);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public record ClarificationResponse(
            @NotBlank @Size(max = 64) String clarificationId,
            @Size(min = 1, max = 50) List<@Size(max = 160) String> selectedOptionIds,
            @NotBlank @Size(max = 32_000) String resumeToken) {
        public ClarificationResponse {
            clarificationId = trim(clarificationId);
            selectedOptionIds = selectedOptionIds == null
                    ? List.of() : selectedOptionIds.stream().map(AgentChatRequest::trim).toList();
            resumeToken = trim(resumeToken);
        }
    }

}
