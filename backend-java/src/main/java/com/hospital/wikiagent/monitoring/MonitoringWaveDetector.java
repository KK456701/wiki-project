package com.hospital.wikiagent.monitoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * 实现 {@code MonitoringWaveDetector} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class MonitoringWaveDetector {
    public Wave detect(Double current, Double mom, Double yoy, boolean momEnabled, double momThreshold,
            boolean yoyEnabled, double yoyThreshold, boolean noSample) {
        if (noSample) return new Wave("no_sample", false, null, null);
        if (current == null) return new Wave("baseline_insufficient", false, null, null);
        Double momRate = momEnabled ? rate(current, mom) : null;
        Double yoyRate = yoyEnabled ? rate(current, yoy) : null;
        boolean momExceeded = momRate != null && Math.abs(momRate) > momThreshold;
        boolean yoyExceeded = yoyRate != null && Math.abs(yoyRate) > yoyThreshold;
        String code = momExceeded && yoyExceeded ? "mom_yoy_threshold_exceeded"
                : momExceeded ? "mom_threshold_exceeded"
                : yoyExceeded ? "yoy_threshold_exceeded"
                : momRate != null || yoyRate != null ? "within_threshold" : "baseline_insufficient";
        return new Wave(code, code.endsWith("threshold_exceeded"), momRate, yoyRate);
    }

    private static Double rate(double current, Double baseline) {
        if (baseline == null || baseline == 0) return null;
        return BigDecimal.valueOf((current - baseline) / Math.abs(baseline) * 100)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record Wave(String conclusionCode, boolean abnormal, Double momChangeRate, Double yoyChangeRate) { }
}
