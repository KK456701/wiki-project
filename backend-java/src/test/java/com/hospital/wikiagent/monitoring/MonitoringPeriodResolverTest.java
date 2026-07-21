package com.hospital.wikiagent.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.monitoring.MonitoringPeriodResolver.Period;

class MonitoringPeriodResolverTest {
    private final MonitoringPeriodResolver resolver = new MonitoringPeriodResolver(
            Clock.fixed(Instant.parse("2026-07-21T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void resolvesDefaultCompletedPeriodsInHospitalTimezone() {
        Period daily = resolver.resolve("daily", null, "Asia/Shanghai");
        Period monthly = resolver.resolve("monthly", null, "Asia/Shanghai");

        assertThat(daily.label()).isEqualTo("2026-07-20 00:00:00~2026-07-21 00:00:00");
        assertThat(monthly.label()).isEqualTo("2026-06-01 00:00:00~2026-07-01 00:00:00");
    }

    @Test
    void treatsDateOnlyEndAsInclusiveCalendarDayAndBuildsBaselines() {
        Period period = resolver.resolve("monthly", "2026-01-01~2026-03-31", "Asia/Shanghai");

        assertThat(period.label()).isEqualTo("2026-01-01 00:00:00~2026-04-01 00:00:00");
        assertThat(resolver.compare(period, "mom").label())
                .isEqualTo("2025-12-01 00:00:00~2026-01-01 00:00:00");
        assertThat(resolver.compare(period, "yoy").label())
                .isEqualTo("2025-01-01 00:00:00~2025-04-01 00:00:00");
    }
}
