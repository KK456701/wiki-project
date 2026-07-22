package com.hospital.wikiagent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.migration.MigrationProperties;

class SystemControllerTest {

    @Test
    void healthMatchesCurrentFastApiContract() {
        Map<String, Object> response = new SystemController(new MigrationProperties()).health();

        assertThat(response).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ok",
                "agent_orchestration", "plan_compile_control"));
    }

    @Test
    void migrationDefaultsRemainShadowedAndGateClosed() {
        SystemController controller = new SystemController(new MigrationProperties());

        assertThat(controller.migrationStatus())
                .containsEntry("authority_runtime", "python")
                .containsEntry("java_runtime", "compatibility_shadow")
                .containsEntry("cutover_gate", "closed");
        assertThat(controller.readiness())
                .containsEntry("serving_authority", false)
                .containsEntry("cutover_approved", false);
    }
}
