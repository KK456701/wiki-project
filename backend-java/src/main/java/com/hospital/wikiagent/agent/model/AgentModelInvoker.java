package com.hospital.wikiagent.agent.model;

import java.time.Duration;

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
