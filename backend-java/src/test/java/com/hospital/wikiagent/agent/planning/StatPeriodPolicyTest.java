package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class StatPeriodPolicyTest {
    @Test
    void keepsOneYearLimitForNormalRequests() {
        var result = StatPeriodPolicy.validate(
                LocalDateTime.parse("2025-02-01T00:00:00"),
                LocalDateTime.parse("2026-07-26T23:30:00"));

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo(StatPeriodPolicy.EXCEEDED_CODE);
    }

    @Test
    void explicitBypassIsReservedForNonExecutingSqlDisplay() {
        var result = StatPeriodPolicy.validate(
                LocalDateTime.parse("2025-02-01T00:00:00"),
                LocalDateTime.parse("2026-07-26T23:30:00"),
                false);

        assertThat(result.ok()).isTrue();
    }
}
