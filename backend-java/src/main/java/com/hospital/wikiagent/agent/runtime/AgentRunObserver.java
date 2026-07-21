package com.hospital.wikiagent.agent.runtime;

import java.util.Map;

@FunctionalInterface
public interface AgentRunObserver {
    void onEvent(Map<String, Object> event);

    static AgentRunObserver noop() {
        return event -> { };
    }
}
