package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

class PlanValidatorTest {
    private final PlanValidator validator = new PlanValidator(new TimeRangeResolver());

    @Test
    void rejectsDatabaseConflictWithoutReplanClass() {
        RequestPlan plan = plan(List.of("no_database_access"),
                new RequestPlan.TimeExpression("1月", "2026-01-01", "2026-02-01"));

        PlanValidation result = validator.validate(plan);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("DATABASE_ACCESS_CONFLICT");
        assertThat(result.failureClass()).isEqualTo(FailureClass.DATABASE_ERROR);
    }

    @Test
    void classifiesIntentAndOutputConflictAsReplannableSemanticFailure() {
        RequestPlan conflicting = new RequestPlan(
                null,
                PlanIntent.INDICATOR_SQL_PREPARE,
                "生成 SQL 但又要求试运行",
                new RequestPlan.TargetIndicator("急会诊及时到位率", null),
                new RequestPlan.TimeExpression("", null, null),
                List.of(RequestedOutput.TRIAL_RESULT),
                List.of(),
                List.of());

        PlanValidation result = validator.validate(conflicting);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("PLAN_INTENT_MISMATCH");
        assertThat(result.failureClass()).isEqualTo(FailureClass.SEMANTIC_PLAN_ERROR);
    }

    @Test
    void acceptsExplicitHalfOpenPeriod() {
        PlanValidation result = validator.validate(plan(
                List.of(),
                new RequestPlan.TimeExpression("1月至3月", "2026-01-01", "2026-04-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.resolvedTime().startTime().toString()).isEqualTo("2026-01-01T00:00");
        assertThat(result.resolvedTime().endTime().toString()).isEqualTo("2026-04-01T00:00");
    }

    @Test
    void resolvesChineseMonthRangeWithoutTrustingPlannerDates() {
        TimeRangeResolver resolver = new TimeRangeResolver(Clock.fixed(
                Instant.parse("2026-07-21T08:00:00Z"),
                ZoneId.of("Asia/Shanghai")));
        PlanValidator fixedValidator = new PlanValidator(resolver);

        PlanValidation result = fixedValidator.validate(plan(
                List.of(),
                new RequestPlan.TimeExpression("从26年一月份到现在", null, null)));

        assertThat(result.ok()).isTrue();
        assertThat(result.resolvedTime().startTime().toString()).isEqualTo("2026-01-01T00:00");
        assertThat(result.resolvedTime().endTime().toString()).isEqualTo("2026-07-21T16:00");
    }

    @Test
    void relativeUserTextOverridesModelInventedAbsoluteDates() {
        TimeRangeResolver resolver = new TimeRangeResolver(Clock.fixed(
                Instant.parse("2026-07-22T04:30:00Z"),
                ZoneId.of("Asia/Shanghai")));
        PlanValidator fixedValidator = new PlanValidator(resolver);

        PlanValidation result = fixedValidator.validate(plan(
                List.of(),
                new RequestPlan.TimeExpression(
                        "从一月份到现在",
                        "2025-01-01 00:00:00",
                        "2026-07-22 00:00:00")));

        assertThat(result.resolvedTime().startTime().toString()).isEqualTo("2026-01-01T00:00");
        assertThat(result.resolvedTime().endTime().toString()).isEqualTo("2026-07-22T12:30");
    }

    private static RequestPlan plan(List<String> constraints, RequestPlan.TimeExpression time) {
        return new RequestPlan(
                null,
                PlanIntent.INDICATOR_TRIAL_RUN,
                "计算指标",
                new RequestPlan.TargetIndicator("急会诊及时到位率", null),
                time,
                List.of(RequestedOutput.TRIAL_RESULT),
                constraints,
                List.of());
    }
}
