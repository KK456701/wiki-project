package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.RequestPlan.TimeExpression;

class TimeRangeResolverTest {
    @Test
    void resolvesNumericMonthWithColloquialMonthSuffixToNow() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-26T15:30:00Z"),
                ZoneId.of("Asia/Shanghai"));
        TimeRangeResolver resolver = new TimeRangeResolver(clock);

        var range = resolver.resolve(new TimeExpression("时间改成从25年2月份开始", null, null));

        assertThat(range).isNotNull();
        assertThat(range.startTime().toString()).isEqualTo("2025-02-01T00:00");
        assertThat(range.endTime().toString()).isEqualTo("2026-07-26T23:30");
    }

    @Test
    void resolvesPersistedBatchPeriodWithSecondPrecision() {
        TimeRangeResolver resolver = new TimeRangeResolver();

        var range = resolver.resolve(new TimeExpression(
                "2025-02-01T00:00:00至2026-07-26T23:29:51", null, null));

        assertThat(range).isNotNull();
        assertThat(range.startTime().toString()).isEqualTo("2025-02-01T00:00");
        assertThat(range.endTime().toString()).isEqualTo("2026-07-26T23:29:51");
    }

    @Test
    void resolvesFullDateToNowWithoutLeadingFromInsideRequest() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-27T04:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        TimeRangeResolver resolver = new TimeRangeResolver(clock);

        var range = resolver.resolve(new TimeExpression(
                "计算2025年2月1日至今急会诊及时到位率", null, null));

        assertThat(range).isNotNull();
        assertThat(range.startTime().toString()).isEqualTo("2025-02-01T00:00");
        assertThat(range.endTime().toString()).isEqualTo("2026-07-27T12:00");
    }

    @Test
    void resolvesSingleAbsoluteMonthInsideCalculationRequest() {
        TimeRangeResolver resolver = new TimeRangeResolver();

        var range = resolver.resolve(new TimeExpression(
                "计算2026年6月急会诊及时到位率", null, null));

        assertThat(range).isNotNull();
        assertThat(range.startTime().toString()).isEqualTo("2026-06-01T00:00");
        assertThat(range.endTime().toString()).isEqualTo("2026-07-01T00:00");
    }

    @Test
    void singleMonthPatternDoesNotConsumeAFullDate() {
        TimeRangeResolver resolver = new TimeRangeResolver();

        var range = resolver.resolve(new TimeExpression(
                "计算2026年6月1日至2026年7月1日急会诊及时到位率", null, null));

        assertThat(range).isNotNull();
        assertThat(range.startTime().toString()).isEqualTo("2026-06-01T00:00");
        assertThat(range.endTime().toString()).isEqualTo("2026-07-01T00:00");
    }
}
