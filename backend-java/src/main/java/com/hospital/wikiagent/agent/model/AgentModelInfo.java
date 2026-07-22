package com.hospital.wikiagent.agent.model;

/**
 * 定义 {@code AgentModelInfo} 的不可变数据载体。
 */
public record AgentModelInfo(
        String id,
        String name,
        String provider,
        String model,
        boolean thinking,
        boolean available) {
}
