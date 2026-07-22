package com.hospital.wikiagent.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CutoverSafetyGuardTest {
    @Test
    void defaultShadowModeIsSafe() {
        assertThatCode(() -> new CutoverSafetyGuard(new MigrationProperties()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void unknownAuthorityRuntimeIsRejected() {
        MigrationProperties properties = new MigrationProperties();
        properties.setAuthorityRuntime("automatic");

        assertThatThrownBy(() -> new CutoverSafetyGuard(properties).validate())
                .hasMessageContaining("python 或 java");
    }

    @Test
    void javaAuthorityRequiresApprovalAndReadinessReport() {
        MigrationProperties properties = new MigrationProperties();
        properties.setAuthorityRuntime("java");
        CutoverSafetyGuard guard = new CutoverSafetyGuard(properties);
        assertThatThrownBy(guard::validate).hasMessageContaining("CUTOVER_APPROVED");

        properties.setCutoverApproved(true);
        assertThatThrownBy(guard::validate).hasMessageContaining("READINESS_REPORT_ID");

        properties.setReadinessReportId("CUTOVER_20260722_001");
        assertThatCode(guard::validate).doesNotThrowAnyException();
    }
}
