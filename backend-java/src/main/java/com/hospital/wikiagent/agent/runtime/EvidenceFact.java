package com.hospital.wikiagent.agent.runtime;

import java.util.Set;

public record EvidenceFact(String sourceId, Set<String> factTypes) {
    public EvidenceFact {
        factTypes = factTypes == null ? Set.of() : Set.copyOf(factTypes);
    }
}
