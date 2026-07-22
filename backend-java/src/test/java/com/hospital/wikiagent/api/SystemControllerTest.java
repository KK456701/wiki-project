package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SystemControllerTest {

    @Test
    void healthIdentifiesTheJavaRuntime() {
        Map<String, Object> response = new SystemController().health();

        assertThat(response).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ok",
                "runtime", "java",
                "agent_orchestration", "compiled_plan"));
    }

    @Test
    void runtimeStatusDescribesTheSingleJavaDeployment() {
        SystemController controller = new SystemController();

        assertThat(controller.runtimeStatus())
                .containsEntry("runtime", "java")
                .containsEntry("frontend", "vue3")
                .containsEntry("rule_source", "wiki")
                .containsEntry("runtime_store", "sqlite")
                .containsEntry("business_database_access", "dbhub");
    }
}
