package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryTarget;
import com.hospital.wikiagent.rules.WikiRuleKnowledgeSource;

class BatchRequestDetectorTest {
    private final BatchRequestDetector detector = new BatchRequestDetector();

    @Test
    void detectsAllActiveBatchRequests() {
        assertThat(detector.detect("计算所有指标的结果").batch()).isTrue();
        assertThat(detector.detect("把全部核心指标都算一遍").batch()).isTrue();
        assertThat(detector.detect("今年全部指标的达标情况").batch()).isTrue();
        assertThat(detector.detect("帮我计算全院指标的结果").batch()).isTrue();
        assertThat(detector.detect("逐一计算每个指标的数值").batch()).isTrue();
        assertThat(detector.detect("把所有重点指标算一下").batch()).isTrue();
    }

    @Test
    void allActiveFlagIsSetForBatchRequests() {
        BatchRequestSpec spec = detector.detect("计算所有指标的结果");
        assertThat(spec.allActive()).isTrue();
        assertThat(spec.rawQuery()).isEqualTo("计算所有指标的结果");
    }

    @Test
    void rejectsDefinitionOrCaliberQuestions() {
        assertThat(detector.detect("所有指标的定义是什么").batch()).isFalse();
        assertThat(detector.detect("所有指标的口径").batch()).isFalse();
        assertThat(detector.detect("全部指标的公式解释").batch()).isFalse();
        assertThat(detector.detect("怎么算所有指标").batch()).isFalse();
    }

    @Test
    void rejectsSingleIndicatorRequests() {
        assertThat(detector.detect("计算急会诊及时到位率").batch()).isFalse();
        assertThat(detector.detect("急会诊及时到位率的结果").batch()).isFalse();
    }

    @Test
    void rejectsRequestsWithoutResultIntent() {
        assertThat(detector.detect("所有指标").batch()).isFalse();
        assertThat(detector.detect("全部指标有哪些").batch()).isFalse();
        assertThat(detector.isBareAllScope("全部指标")).isTrue();
        assertThat(detector.isBareAllScope("我选择的指标是：全部指标")).isTrue();
        assertThat(detector.isBareAllScope("全部指标的公式")).isFalse();
    }

    @Test
    void rejectsBlankAndIndicatorFreeQueries() {
        assertThat(detector.detect(null).batch()).isFalse();
        assertThat(detector.detect("  ").batch()).isFalse();
        assertThat(detector.detect("计算所有手术的结果").batch()).isFalse();
    }

    @Test
    void timeOnlyFollowupRetainsAllIndicatorBatchScope() {
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "ALL", List.of(),
                "2026-06-01 00:00:00", "2026-07-01 00:00:00");

        BatchRequestSpec spec = detector.detect(
                "时间改成从25年2月份开始", previous, List.of());

        assertThat(spec.batch()).isTrue();
        assertThat(spec.allActive()).isTrue();
        assertThat(spec.timeText()).isEqualTo("时间改成从25年2月份开始");
    }

    @Test
    void pluralCalculationReferenceIsNotMisclassifiedAsTimeOnlyChange() {
        assertThat(detector.isTimeOnlyChange("按上次统计时间计算这三个指标")).isFalse();
        assertThat(detector.isTimeOnlyChange("时间改成从25年2月份开始")).isTrue();
        assertThat(detector.isTimeOnlyChange("从二月份开始")).isTrue();
    }

    @Test
    void lastStatPeriodReferenceUsesStructuredPreviousPeriod() {
        QueryScopeState previous = new QueryScopeState(
                "sql_prepare", "SINGLE",
                List.of(new QueryTarget("R1", "指标一")),
                "2025-02-01 00:00:00", "2026-07-27 08:00:00");

        assertThat(detector.timeTextFor("按上次统计时间计算这三个指标", previous))
                .isEqualTo("2025-02-01T00:00:00至2026-07-27T08:00:00");
    }

    @Test
    void allIndicatorSelectionRetainsPreviousTrialIntentAndPeriod() {
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(
                        new QueryTarget("R1", "指标一"),
                        new QueryTarget("R2", "指标二")),
                "2026-06-01 00:00:00", "2026-07-01 00:00:00");

        BatchRequestSpec spec = detector.detect(
                "我选择的指标是：全部指标", previous, List.of());

        assertThat(spec.allActive()).isTrue();
        assertThat(spec.timeText())
                .isEqualTo("2026-06-01T00:00:00至2026-07-01T00:00:00");
    }

    @Test
    void nonTrialScopeIsValidButCannotAccidentallyTriggerCalculationBatch() {
        QueryScopeState definition = new QueryScopeState(
                "rule_explanation", "SUBSET",
                List.of(
                        new QueryTarget("R1", "指标一"),
                        new QueryTarget("R2", "指标二")),
                "2026-06-01 00:00:00", "2026-07-01 00:00:00");

        assertThat(definition.valid()).isTrue();
        assertThat(detector.detect("全部指标", definition, List.of()).batch()).isFalse();
        assertThat(detector.detect(
                "时间改成本月", definition, List.of()).batch()).isFalse();
    }

    @Test
    void explicitCalculatedIndicatorsUseProfileAwareBatchForOneOrMoreTargets() {
        List<Map<String, String>> catalog = List.of(
                Map.of("rule_id", "R1", "rule_name", "患者入院8小时内查房率"),
                Map.of("rule_id", "R2", "rule_name", "急会诊及时到位率"));

        BatchRequestSpec selected = detector.detect(
                "计算患者入院8小时内查房率、急会诊及时到位率的结果",
                null, catalog);
        BatchRequestSpec single = detector.detect(
                "计算急会诊及时到位率的结果", null, catalog);

        assertThat(selected.scope()).isEqualTo(BatchRequestSpec.Scope.SELECTED);
        assertThat(selected.targets()).extracting("ruleId").containsExactly("R1", "R2");
        assertThat(single.scope()).isEqualTo(BatchRequestSpec.Scope.SELECTED);
        assertThat(single.targets()).extracting("ruleId").containsExactly("R2");
    }

    @Test
    void explicitProfileNarrowsSingleIndicatorOtherwiseAllProfilesRemainSelected() {
        WikiRuleKnowledgeSource rules = mock(WikiRuleKnowledgeSource.class);
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(List.of(
                Map.of("rule_id", "R1", "rule_name", "急会诊及时到位率")));
        when(rules.caliberProfiles("R1", "hospital_001")).thenReturn(List.of(
                Map.of("profile_id", "R1-P1", "profile_name", "申请时间口径"),
                Map.of("profile_id", "R1-P2", "profile_name", "到达时间口径")));
        BatchRequestDetector profileAware = new BatchRequestDetector(rules);

        BatchRequestSpec allProfiles = profileAware.detect(
                "计算本月急会诊及时到位率", null, "hospital_001");
        BatchRequestSpec oneProfile = profileAware.detect(
                "按到达时间口径计算本月急会诊及时到位率", null, "hospital_001");

        assertThat(allProfiles.targets()).hasSize(1);
        assertThat(allProfiles.targets().get(0).profileId()).isNull();
        assertThat(oneProfile.targets()).singleElement().satisfies(target -> {
            assertThat(target.profileId()).isEqualTo("R1-P2");
            assertThat(target.profileName()).isEqualTo("到达时间口径");
        });
    }
}
