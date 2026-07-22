package com.hospital.wikiagent.agent.runtime;

import java.util.Map;

@FunctionalInterface
/**
 * 定义 {@code AgentRunObserver} 的稳定协作契约，便于替换实现和隔离测试。
 */
public interface AgentRunObserver {
    void onEvent(Map<String, Object> event);

    static AgentRunObserver noop() {
        return event -> { };
    }
}
