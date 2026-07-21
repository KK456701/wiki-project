package com.hospital.wikiagent.agent.model;

public record AgentModelInfo(
        String id,
        String name,
        String provider,
        String model,
        boolean thinking,
        boolean available) {
}
