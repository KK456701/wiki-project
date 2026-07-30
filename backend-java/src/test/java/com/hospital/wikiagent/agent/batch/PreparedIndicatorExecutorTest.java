package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.CapabilitySpec;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.planning.StatPeriodPolicy;
import com.hospital.wikiagent.agent.planning.TimeRangeResolver;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;
import com.hospital.wikiagent.auth.HospitalPrincipal;

/**
 * 验证批量场景下单指标确定性执行器：恰好 3 次工具调用、状态隔离、失败与无样本分支。
 *
 * <p>PlanCompiler / PlanValidator / AgentStateController / DeterministicDispatch /
 * CapabilitySpecRegistry 使用真实组件，仅 mock ToolGateway 与 EvidenceVerifier；
 * mock 通过 thenAnswer 复刻真实网关“把结果写回 state.lastToolResults()”的副作用，
 * 否则状态机无法推进。</p>
 */
class PreparedIndicatorExecutorTest {
    private static final String RULE_ID = "MQSI2025_001";
    private static final String RULE_NAME = "患者入院48小时内转科的比例";
    private static final String TIME_TEXT = "2026年1月1日至2026年2月1日";

    private ToolGateway gateway;
    private EvidenceVerifier verifier;
    private PreparedIndicatorExecutor executor;
    private AgentRuntimeContext context;

    private ToolResult effectiveResult;
    private ToolResult prepareResult;
    private ToolResult trialResult;
    private final List<String> toolCalls = new ArrayList<>();
    private final List<Object> effectiveRuleIds = new ArrayList<>();
    private final List<Map<String, Object>> toolArguments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateway = mock(ToolGateway.class);
        verifier = mock(EvidenceVerifier.class);
        executor = new PreparedIndicatorExecutor(
                new PlanCompiler(defaultRegistry(), new ObjectMapper()),
                new PlanValidator(new TimeRangeResolver()),
                new AgentStateController(defaultRegistry()),
                new DeterministicDispatch(),
                gateway,
                defaultRegistry(),
                verifier);
        context = new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user_1", "account_1", "hospital_001", Set.of("agent:run"), false, "session_1"),
                "REQ_1", "TRACE_1", "db_main");

        effectiveResult = effectiveRule(RULE_ID, RULE_NAME, 95, ">=", "percent");
        prepareResult = ToolResult.success(
                "SQL_OBJECT_PREPARED", "SQL 已准备。", Map.of("sqlId", "SQL_1"));
        trialResult = trialRun(92.5, 185L, 200L, false);

        when(verifier.verifyMany(any(), any(), any())).thenReturn(List.of());
        stubGateway();
    }

    @Test
    void successExecutesExactlyThreeDeterministicToolCalls() {
        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.SUCCESS);
        assertThat(result.ruleId()).isEqualTo(RULE_ID);
        assertThat(result.ruleName()).isEqualTo(RULE_NAME);
        assertThat(result.resultValue()).isEqualTo(92.5);
        assertThat(result.numerator()).isEqualTo(185L);
        assertThat(result.denominator()).isEqualTo(200L);
        assertThat(result.targetValue()).isEqualTo(95.0);
        assertThat(result.targetDirection()).isEqualTo(">=");
        assertThat(result.unit()).isEqualTo("percent");
        assertThat(result.runId()).isEqualTo("RUN_1");
        assertThat(result.ok()).isTrue();

        assertThat(toolCalls).containsExactly(
                "get_effective_rule", "prepare_indicator_sql", "trial_run_indicator_sql");
    }

    @Test
    void eachIndicatorUsesItsOwnIsolatedState() {
        IndicatorExecutionResult first =
                executor.execute("RULE_A", "指标A", "SUB_A", TIME_TEXT, context);
        IndicatorExecutionResult second =
                executor.execute("RULE_B", "指标B", "SUB_B", TIME_TEXT, context);

        assertThat(first.status()).isEqualTo(IndicatorExecutionResult.Status.SUCCESS);
        assertThat(second.status()).isEqualTo(IndicatorExecutionResult.Status.SUCCESS);
        // 每次执行都独立预设 currentRuleId，get_effective_rule 各自携带自己的 rule_id。
        assertThat(effectiveRuleIds).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    void toolFailureIsCollapsedIntoFailedResult() {
        prepareResult = ToolResult.failure(
                "error", "SQL_PREPARE_FAILED", "SQL 准备失败。", false);

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("SQL_PREPARE_FAILED");
        assertThat(result.ok()).isFalse();
        // 失败后不应继续调用试运行工具。
        assertThat(toolCalls).containsExactly("get_effective_rule", "prepare_indicator_sql");
    }

    @Test
    void noSampleTrialIsReportedAsNoSample() {
        trialResult = trialRun(null, null, null, true);

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.NO_SAMPLE);
        assertThat(result.resultValue()).isNull();
        assertThat(result.ok()).isTrue();
    }

    @Test
    void noSampleMedianUsesZeroSampleComposition() {
        effectiveResult = effectiveRule(
                RULE_ID, RULE_NAME, 5, "<=", "minutes", "median_duration");
        trialResult = ToolResult.success(
                "TRIAL_RUN_COMPLETED", "无样本。", Map.of(
                        "runId", "RUN_EMPTY",
                        "statStart", "2026-01-01 00:00:00",
                        "statEnd", "2026-02-01 00:00:00",
                        "noSample", true));

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.NO_SAMPLE);
        assertThat(result.sampleCount()).isZero();
        assertThat(result.calculationDisplay()).isEqualTo("中位数，n=0");
    }

    @Test
    void zeroDynamicMedianTargetFallsBackToApprovedStaticTarget() {
        effectiveResult = effectiveRule(
                RULE_ID, RULE_NAME, 5, "<=", "minutes", "median_duration");
        trialResult = ToolResult.success(
                "TRIAL_RUN_COMPLETED", "试运行完成。", Map.of(
                        "runId", "RUN_MEDIAN",
                        "statStart", "2026-01-01 00:00:00",
                        "statEnd", "2026-02-01 00:00:00",
                        "resultValue", 4.5,
                        "sampleCount", 12,
                        "targetValue", 0));

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.targetValue()).isEqualTo(5);
    }

    @Test
    void percentageTargetsUseSamePercentPointScaleAsCalculatedResult() {
        effectiveResult = effectiveRule(
                RULE_ID, RULE_NAME, 0.95, ">=", "percent", "percentage");
        trialResult = ToolResult.success(
                "TRIAL_RUN_COMPLETED", "试运行完成。", Map.of(
                        "runId", "RUN_PERCENT",
                        "statStart", "2026-01-01 00:00:00",
                        "statEnd", "2026-02-01 00:00:00",
                        "resultValue", 96.0,
                        "numeratorCount", 96,
                        "denominatorCount", 100,
                        "targetValue", 0.95));

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.resultValue()).isEqualTo(96.0);
        assertThat(result.targetValue()).isEqualTo(95.0);
    }

    @Test
    void profileFallbackPercentageTargetIsNotScaledTwice() {
        effectiveResult = effectiveRule(
                RULE_ID, RULE_NAME, 95, ">=", "percent", "percentage");
        trialResult = ToolResult.success(
                "TRIAL_RUN_COMPLETED", "试运行完成。", Map.of(
                        "runId", "RUN_PROFILE_TARGET",
                        "statStart", "2026-01-01 00:00:00",
                        "statEnd", "2026-02-01 00:00:00",
                        "resultValue", 96.0,
                        "numeratorCount", 96,
                        "denominatorCount", 100,
                        "targetValue", 95,
                        "targetSource", "profile"));

        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", TIME_TEXT, context);

        assertThat(result.targetValue()).isEqualTo(95.0);
    }

    @Test
    void explicitProfileIsBoundIntoRuleAndSqlPreparation() {
        trialResult = ToolResult.success(
                "TRIAL_RUN_COMPLETED", "试运行完成。", Map.of(
                        "runId", "RUN_PROFILE",
                        "profileId", "PROFILE_2",
                        "extractionId", "EXT_PROFILE_2",
                        "extractionStatus", "COMPLETED",
                        "statStart", "2026-01-01 00:00:00",
                        "statEnd", "2026-02-01 00:00:00",
                        "resultValue", 92.5,
                        "numeratorCount", 185,
                        "denominatorCount", 200));

        IndicatorExecutionResult result = executor.execute(
                RULE_ID, RULE_NAME,
                "PROFILE_2", "第二口径", "CORE_TEST",
                "SUB_2", TIME_TEXT,
                "2026-01-01 00:00:00", "2026-02-01 00:00:00",
                context);

        assertThat(result.profileId()).isEqualTo("PROFILE_2");
        assertThat(result.profileLabel()).isEqualTo("第二口径");
        assertThat(result.extractionId()).isEqualTo("EXT_PROFILE_2");
        assertThat(result.eventNo()).isEqualTo("CORE_TEST");
        assertThat(toolArguments.get(0)).containsEntry("profileId", "PROFILE_2");
        assertThat(toolArguments.get(1)).containsEntry("profileId", "PROFILE_2");
    }

    @Test
    void unresolvableTimeFailsValidationWithoutAnyToolCall() {
        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", "某个模糊的时间", context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("TIME_RANGE_AMBIGUOUS");
        assertThat(toolCalls).isEmpty();
    }

    @Test
    void fixedCrossMonthBatchPeriodIsRejectedBeforeAnyWorkerToolCall() {
        IndicatorExecutionResult result = executor.execute(
                RULE_ID, RULE_NAME, "SUB_1", "时间改成从25年2月份开始",
                "2025-02-01 00:00:00", "2026-07-26 23:30:00", context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo(StatPeriodPolicy.EXCEEDED_CODE);
        assertThat(toolCalls).isEmpty();
    }

    private void stubGateway() {
        when(gateway.execute(anyString(), anyMap(), any(AgentRuntimeContext.class),
                any(AgentRunState.class)))
                .thenAnswer(invocation -> {
                    String toolName = invocation.getArgument(0);
                    Map<String, Object> arguments = invocation.getArgument(1);
                    AgentRunState state = invocation.getArgument(3);
                    toolCalls.add(toolName);
                    toolArguments.add(Map.copyOf(arguments));
                    ToolResult result = switch (toolName) {
                        case "get_effective_rule" -> {
                            effectiveRuleIds.add(arguments.get("ruleId"));
                            yield effectiveResult;
                        }
                        case "prepare_indicator_sql" -> prepareResult;
                        case "trial_run_indicator_sql" -> trialResult;
                        default -> ToolResult.failure(
                                "not_found", "TOOL_NOT_FOUND", "未知工具：" + toolName, false);
                    };
                    // 复刻真实 ToolGateway 的副作用：结果写回状态，状态机才能推进。
                    state.lastToolResults().add(result);
                    return CompletableFuture.completedFuture(result);
                });
    }

    private static ToolResult effectiveRule(
            String ruleId, String ruleName, Object targetValue, String direction, String unit) {
        return effectiveRule(
                ruleId, ruleName, targetValue, direction, unit, null);
    }

    private static ToolResult effectiveRule(
            String ruleId,
            String ruleName,
            Object targetValue,
            String direction,
            String unit,
            String valueType) {
        Map<String, Object> params = new LinkedHashMap<>();
        // effective_params/result_contract 内层是知识 Profile 透传键，保持 snake
        params.put("target_value", targetValue);
        params.put("target_direction", direction);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", ruleId);
        data.put("ruleName", ruleName);
        data.put("effectiveParams", params);
        data.put("resultUnit", unit);
        if (valueType != null) {
            data.put("resultContract", Map.of("value_type", valueType));
        }
        return ToolResult.success("EFFECTIVE_RULE_FOUND", "已找到生效规则。", data);
    }

    private static ToolResult trialRun(
            Double resultValue, Long numerator, Long denominator, boolean noSample) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sqlId", "SQL_1");
        data.put("runId", "RUN_1");
        data.put("ruleId", RULE_ID);
        data.put("noSample", noSample);
        if (resultValue != null) {
            data.put("resultValue", resultValue);
        }
        if (numerator != null) {
            data.put("numeratorCount", numerator);
        }
        if (denominator != null) {
            data.put("denominatorCount", denominator);
        }
        data.put("statStart", "2026-01-01 00:00:00");
        data.put("statEnd", "2026-02-01 00:00:00");
        return ToolResult.success("TRIAL_RUN_COMPLETED", "试运行完成。", data);
    }

    private static CapabilitySpecRegistry defaultRegistry() {
        List<CapabilitySpec> specs = CapabilitySpecRegistry.defaultSpecs();
        Set<String> tools = specs.stream()
                .map(CapabilitySpec::toolName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return new CapabilitySpecRegistry(specs, tools);
    }
}
