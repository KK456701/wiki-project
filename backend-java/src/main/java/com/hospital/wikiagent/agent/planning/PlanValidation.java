package com.hospital.wikiagent.agent.planning;

import java.time.LocalDateTime;

import com.hospital.wikiagent.agent.ir.FailureClass;

public record PlanValidation(
        boolean ok,
        String code,
        String message,
        ResolvedTimeRange resolvedTime,
        FallbackCategory fallbackCategory,
        FailureClass failureClass) {

    public static PlanValidation valid(ResolvedTimeRange resolvedTime) {
        return new PlanValidation(true, "PLAN_VALID", "", resolvedTime, null, null);
    }

    public static PlanValidation invalid(
            String code,
            String message,
            FallbackCategory category) {
        return new PlanValidation(
                false,
                code,
                message,
                null,
                category,
                FailureClass.classify(code));
    }

    public record ResolvedTimeRange(
            LocalDateTime startTime,
            LocalDateTime endTime,
            String rawText) {
        public ResolvedTimeRange {
            if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
                throw new IllegalArgumentException("统计周期必须是有效的左闭右开区间");
            }
            rawText = rawText == null ? "" : rawText;
        }
    }
}
