package com.hospital.wikiagent.agent.model;

import java.time.Duration;

/**
 * 定义 {@code AgentModelInvoker} 的稳定协作契约，便于替换实现和隔离测试。
 */
public interface AgentModelInvoker {
    ModelCompletion complete(
            String modelId,
            String systemPrompt,
            String userPrompt,
            Duration timeout);

    record ModelCompletion(String modelId, String content) {
        public ModelCompletion {
            content = content == null ? "" : content;
        }
    }
}
