package com.hospital.wikiagent.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MonitoringWaveDetectorTest {
    private final MonitoringWaveDetector detector = new MonitoringWaveDetector();

    @Test
    void onlyAlertsWhenConfiguredThresholdIsExceeded() {
        var exact = detector.detect(120.0, 100.0, null, true, 20.0, false, 30.0, false);
        var exceeded = detector.detect(121.0, 100.0, 80.0, true, 20.0, true, 30.0, false);

        assertThat(exact.abnormal()).isFalse();
        assertThat(exact.conclusionCode()).isEqualTo("within_threshold");
        assertThat(exceeded.abnormal()).isTrue();
        assertThat(exceeded.conclusionCode()).isEqualTo("mom_yoy_threshold_exceeded");
        assertThat(exceeded.momChangeRate()).isEqualTo(21.0);
        assertThat(exceeded.yoyChangeRate()).isEqualTo(51.25);
    }

    @Test
    void distinguishesNoSampleFromMissingBaseline() {
        assertThat(detector.detect(null, null, null, true, 20, true, 30, true).conclusionCode())
                .isEqualTo("no_sample");
        assertThat(detector.detect(2.83, null, null, true, 20, true, 30, false).conclusionCode())
                .isEqualTo("baseline_insufficient");
    }
}
