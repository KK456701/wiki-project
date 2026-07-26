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
                "SQL_OBJECT_PREPARED", "SQL 已准备。", Map.of("sql_id", "SQL_1"));
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
        assertThat(result.targetValue()).isEqualTo(95);
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
    void unresolvableTimeFailsValidationWithoutAnyToolCall() {
        IndicatorExecutionResult result =
                executor.execute(RULE_ID, RULE_NAME, "SUB_1", "某个模糊的时间", context);

        assertThat(result.status()).isEqualTo(IndicatorExecutionResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("TIME_RANGE_AMBIGUOUS");
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
                    ToolResult result = switch (toolName) {
                        case "get_effective_rule" -> {
                            effectiveRuleIds.add(arguments.get("rule_id"));
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target_value", targetValue);
        params.put("target_direction", direction);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule_id", ruleId);
        data.put("rule_name", ruleName);
        data.put("effective_params", params);
        data.put("result_unit", unit);
        return ToolResult.success("EFFECTIVE_RULE_FOUND", "已找到生效规则。", data);
    }

    private static ToolResult trialRun(
            Double resultValue, Long numerator, Long denominator, boolean noSample) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sql_id", "SQL_1");
        data.put("run_id", "RUN_1");
        data.put("rule_id", RULE_ID);
        data.put("no_sample", noSample);
        if (resultValue != null) {
            data.put("result_value", resultValue);
        }
        if (numerator != null) {
            data.put("numerator_count", numerator);
        }
        if (denominator != null) {
            data.put("denominator_count", denominator);
        }
        data.put("stat_start", "2026-01-01 00:00:00");
        data.put("stat_end", "2026-02-01 00:00:00");
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
