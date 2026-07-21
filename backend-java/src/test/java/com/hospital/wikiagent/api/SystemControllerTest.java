package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SystemControllerTest {

    @Test
    void healthMatchesCurrentFastApiContract() {
        Map<String, Object> response = new SystemController().health();

        assertThat(response).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ok",
                "agent_orchestration", "plan_compile_control"));
    }
}
