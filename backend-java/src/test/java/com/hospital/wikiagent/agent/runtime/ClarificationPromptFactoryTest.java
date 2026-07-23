package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestPlan.TargetIndicator;
import com.hospital.wikiagent.agent.ir.RequestPlan.TimeExpression;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.planning.FallbackCategory;
import com.hospital.wikiagent.rules.RuleReadRepository;

class ClarificationPromptFactoryTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-23T08:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @Test
    void listsRecommendedAndAllHospitalIndicatorsForAmbiguousPluralRequest() {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        when(rules.activeIndicatorNames("hospital_001", 500)).thenReturn(List.of(
                Map.of("rule_id", "MQSI2025_001",
                        "rule_name", "患者入院 48 小时内转科的比例"),
                Map.of("rule_id", "MQSI2025_005",
                        "rule_name", "急会诊及时到位率")));
        AgentRunState state = new AgentRunState();
        state.lastToolResults().add(new ToolResult(
                false, "validation_failed", "RULE_SEARCHED", "存在多个候选",
                Map.of("matches", List.of(Map.of(
                        "rule_id", "MQSI2025_005",
                        "rule_name", "急会诊及时到位率"))),
                false, false, List.of()));
        ClarificationPromptFactory factory = new ClarificationPromptFactory(rules, CLOCK);

        var result = factory.fromDecision(
                decision("INDICATOR_AMBIGUOUS", "找到多个可能指标"),
                plan(PlanIntent.INDICATOR_SQL_PREPARE),
                state,
                "hospital_001",
                "这两个指标的 SQL 怎么写");

        assertThat(result.kind()).isEqualTo("indicator_selection");
        assertThat(result.selectionMode()).isEqualTo("multiple");
        assertThat(result.options()).extracting(value -> value.label()).containsExactly(
                "急会诊及时到位率", "患者入院 48 小时内转科的比例");
        assertThat(result.options().get(0).group()).isEqualTo("推荐匹配");
        assertThat(result.resumePrefix()).contains("这两个指标的 SQL 怎么写");
    }

    @Test
    void offersDeterministicTimePresetsAndFreeText() {
        ClarificationPromptFactory factory = new ClarificationPromptFactory(
                mock(RuleReadRepository.class), CLOCK);

        var result = factory.fromDecision(
                decision("TIME_RANGE_AMBIGUOUS", "请明确统计时间"),
                plan(PlanIntent.INDICATOR_TRIAL_RUN),
                new AgentRunState(),
                "hospital_001",
                "计算这个指标的结果");

        assertThat(result.kind()).isEqualTo("time_range");
        assertThat(result.allowFreeText()).isTrue();
        assertThat(result.options()).extracting(value -> value.label()).containsExactly(
                "今年至今", "本月", "上一个自然月", "最近30天");
        assertThat(result.options().get(0).value()).isEqualTo("2026-01-01 至 2026-07-23");
        assertThat(result.freeTextPlaceholder()).contains("2026-01-01");
    }

    @Test
    void offersBusinessActionsWhenIntentIsUnclear() {
        ClarificationPromptFactory factory = new ClarificationPromptFactory(
                mock(RuleReadRepository.class), CLOCK);

        var result = factory.fromDecision(
                decision("INTENT_AMBIGUOUS", "目标不明确"),
                plan(PlanIntent.UNKNOWN),
                new AgentRunState(),
                "hospital_001",
                "帮我看看这个");

        assertThat(result.kind()).isEqualTo("intent_selection");
        assertThat(result.options()).extracting(value -> value.label()).contains(
                "查看定义和计算口径", "计算具体结果", "生成受控 SQL", "排查结果或异常");
    }

    @Test
    void doesNotTurnSystemOrImplementationFailuresIntoUserQuestions() {
        ClarificationPromptFactory factory = new ClarificationPromptFactory(
                mock(RuleReadRepository.class), CLOCK);
        ControllerDecision failure = new ControllerDecision(
                ControllerAction.FALLBACK,
                null,
                List.of(),
                "TOOL_EXECUTION_FAILED",
                "工具执行失败",
                FallbackCategory.IMPLEMENTATION_SUPPORT,
                FailureClass.TOOL_ERROR);

        assertThat(factory.fromDecision(
                failure,
                plan(PlanIntent.INDICATOR_TRIAL_RUN),
                new AgentRunState(),
                "hospital_001",
                "计算具体结果")).isNull();
    }

    private static ControllerDecision decision(String code, String message) {
        return new ControllerDecision(
                ControllerAction.FALLBACK,
                null,
                List.of(),
                code,
                message,
                FallbackCategory.USER_CLARIFICATION,
                FailureClass.classify(code));
    }

    private static RequestPlan plan(PlanIntent intent) {
        return new RequestPlan(
                RequestPlan.VERSION,
                intent,
                "测试澄清",
                new TargetIndicator("", null),
                new TimeExpression("", null, null),
                List.of(),
                List.of(),
                List.of());
    }
}
