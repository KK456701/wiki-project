package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.planning.PlanGoalAlignmentValidator.AlignmentStatus;
import com.hospital.wikiagent.rules.RuleReadRepository;

class PlanGoalAlignmentValidatorTest {

    @Test
    void convertsWardEntryFollowupIntoCandidateTrialPlanWhenPeriodExists() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        PlanGoalAlignmentValidator validator = new PlanGoalAlignmentValidator(rules);

        var decision = validator.assess(
                "那根据入区怎么算",
                currentRulePlan(true),
                "hospital_001");

        assertThat(decision.status()).isEqualTo(AlignmentStatus.MISMATCH);
        assertThat(decision.failureCode()).isEqualTo("TASK_TYPE_MISMATCH");
        assertThat(decision.suggestedPlan().intent())
                .isEqualTo(PlanIntent.INDICATOR_CALIBER_SIMULATION);
        assertThat(decision.suggestedPlan().targetCaliber().profileId())
                .isEqualTo("hospital_001_ward_entry_anchor");
        assertThat(decision.suggestedPlan().requestedOutputs())
                .containsExactly(
                        RequestedOutput.CALIBER_EXPLANATION,
                        RequestedOutput.CALIBER_TRIAL_RESULT);
        assertThat(decision.suggestedPlan().timeExpression().startTime())
                .isEqualTo("2026-01-01T00:00:00");
    }

    @Test
    void keepsCurrentCaliberQuestionAsRuleExplanation() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        PlanGoalAlignmentValidator validator = new PlanGoalAlignmentValidator(rules);

        var decision = validator.assess(
                "现在是按入院还是入区算的",
                currentRulePlan(true),
                "hospital_001");

        assertThat(decision.aligned()).isTrue();
    }

    @Test
    void correctsCurrentCaliberFollowupWhenPlannerMistakesItForSimulation() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        PlanGoalAlignmentValidator validator = new PlanGoalAlignmentValidator(rules);

        var decision = validator.assess(
                "根据什么口径算的",
                candidateSimulationPlan(),
                "hospital_001");

        assertThat(decision.status()).isEqualTo(AlignmentStatus.MISMATCH);
        assertThat(decision.failureCode()).isEqualTo("TASK_TYPE_MISMATCH");
        assertThat(decision.suggestedPlan().intent())
                .isEqualTo(PlanIntent.RULE_EXPLANATION);
        assertThat(decision.suggestedPlan().targetCaliber().profileId()).isNull();
        assertThat(decision.suggestedPlan().requestedOutputs())
                .containsExactly(
                        RequestedOutput.DEFINITION,
                        RequestedOutput.FORMULA);
        assertThat(decision.suggestedPlan().timeExpression().startTime())
                .isEqualTo("2026-01-01T00:00:00");
    }

    @Test
    void candidateFormulaWithoutPeriodDoesNotInventTrialRange() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        PlanGoalAlignmentValidator validator = new PlanGoalAlignmentValidator(rules);

        var decision = validator.assess(
                "根据首次入区时间怎么算",
                currentRulePlan(false),
                "hospital_001");

        assertThat(decision.suggestedPlan().requestedOutputs())
                .containsExactly(RequestedOutput.CALIBER_EXPLANATION);
    }

    @Test
    void candidateSqlRequestPreparesSqlWithoutTrialRun() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        when(rules.diagnosticProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(wardEntryProfile()));
        PlanGoalAlignmentValidator validator = new PlanGoalAlignmentValidator(rules);

        var decision = validator.assess(
                "按入区口径的 SQL 怎么写",
                currentRulePlan(true),
                "hospital_001");

        assertThat(decision.suggestedPlan().requestedOutputs())
                .containsExactly(
                        RequestedOutput.CALIBER_EXPLANATION,
                        RequestedOutput.CALIBER_PREPARED_SQL_HANDLE);
    }

    private static RequestPlan currentRulePlan(boolean withPeriod) {
        return new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.RULE_EXPLANATION,
                "解释当前生效规则",
                new RequestPlan.TargetIndicator(
                        "患者入院48小时内转科的比例", "MQSI2025_001"),
                new RequestPlan.TargetCaliber("", null),
                withPeriod
                        ? new RequestPlan.TimeExpression(
                                "沿用上一轮统计区间",
                                "2026-01-01T00:00:00",
                                "2026-07-23T00:00:00")
                        : new RequestPlan.TimeExpression("", null, null),
                List.of(RequestedOutput.DEFINITION, RequestedOutput.FORMULA),
                List.of(),
                List.of());
    }

    private static RequestPlan candidateSimulationPlan() {
        return new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.INDICATOR_CALIBER_SIMULATION,
                "按候选口径解释结果",
                new RequestPlan.TargetIndicator(
                        "患者入院48小时内转科的比例", "MQSI2025_001"),
                new RequestPlan.TargetCaliber("什么口径", null),
                new RequestPlan.TimeExpression(
                        "沿用上一轮统计区间",
                        "2026-01-01T00:00:00",
                        "2026-07-23T00:00:00"),
                List.of(RequestedOutput.CALIBER_EXPLANATION),
                List.of(),
                List.of());
    }

    private static Map<String, Object> wardEntryProfile() {
        return Map.of(
                "profile_id", "hospital_001_ward_entry_anchor",
                "label", "首次入区时间统计及48小时口径",
                "aliases", List.of("入区", "首次入区"),
                "source_version", "2026-07",
                "status", "approved");
    }
}
