package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
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
    void queryWithIndicatorContentIsNotTimeOnlyChange() {
        // 含指标语义内容的句子不是纯时间修改，必须放行给语义召回 + LLM 兜底识别指标
        assertThat(detector.isTimeOnlyChange("算去年患者入院内转科的比例")).isFalse();
        assertThat(detector.isTimeOnlyChange("去年的会诊情况")).isFalse();
        // 不含指标内容的纯时间修改仍然命中
        assertThat(detector.isTimeOnlyChange("时间改成去年")).isTrue();
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
    void oneOrMoreExplicitIndicatorsUseDeterministicSelectedBatch() {
        List<Map<String, String>> catalog = List.of(
                Map.of("ruleId", "R1", "ruleName", "患者入院8小时内查房率"),
                Map.of("ruleId", "R2", "ruleName", "急会诊及时到位率"));

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
    void approvedProfilesExpandUnlessUserExplicitlyNamesOne() {
        WikiRuleKnowledgeSource rules = mock(WikiRuleKnowledgeSource.class);
        BatchRequestDetector profileDetector = new BatchRequestDetector(rules);
        List<Map<String, Object>> profiles = List.of(
                Map.of(
                        "profileId", "R1-default",
                        "profileName", "默认统计口径"),
                Map.of(
                        "profileId", "R1-candidate",
                        "profileName", "候选统计口径"));
        when(rules.caliberProfiles("R1", "hospital_001")).thenReturn(profiles);

        assertThat(profileDetector.requiresProfileExpansion(
                "计算指标一", "R1", "指标一", "hospital_001")).isTrue();
        BatchRequestSpec.Target explicit = profileDetector.explicitProfileTarget(
                "按候选统计口径计算指标一",
                "R1", "指标一", "hospital_001");
        assertThat(explicit.profileId()).isEqualTo("R1-candidate");
        assertThat(profileDetector.requiresProfileExpansion(
                "按候选统计口径计算指标一",
                "R1", "指标一", "hospital_001")).isFalse();
    }
}
