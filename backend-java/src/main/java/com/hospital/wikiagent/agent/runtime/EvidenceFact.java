package com.hospital.wikiagent.agent.runtime;

import java.util.Set;

/**
 * 定义 {@code EvidenceFact} 的不可变数据载体。
 */
public record EvidenceFact(String sourceId, Set<String> factTypes) {
    public EvidenceFact {
        factTypes = factTypes == null ? Set.of() : Set.copyOf(factTypes);
    }
}
