package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.ConversationSnapshot;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;
import com.hospital.wikiagent.agent.planning.TimeRangeResolver;
import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunRequest;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.WikiRuleKnowledgeSource;

/**
 * 验证批量编排器：有界并发、失败隔离（PARTIAL_SUCCESS）、时间澄清、空指标列表与进度事件。
 */
class BatchIndicatorRuntimeTest {
    private static final String START = "2026-01-01 00:00:00";
    private static final String END = "2026-04-01 00:00:00";

    private WikiRuleKnowledgeSource rules;
    private PreparedIndicatorExecutor executor;
    private BatchJobStore jobStore;
    private TimeRangeResolver timeResolver;
    private AgentConversationMemory conversations;
    private AgentModelProperties properties;
    private BatchIndicatorRuntime runtime;

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final AgentRunObserver observer = events::add;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();

    @BeforeEach
    void setUp() {
        rules = mock(WikiRuleKnowledgeSource.class);
        executor = mock(PreparedIndicatorExecutor.class);
        jobStore = mock(BatchJobStore.class);
        timeResolver = mock(TimeRangeResolver.class);
        conversations = mock(AgentConversationMemory.class);
        properties = new AgentModelProperties();
        properties.setBatchWorkerConcurrency(2);
        properties.setBatchMaxIndicators(35);
        runtime = new BatchIndicatorRuntime(
                rules, executor, new BatchResultAggregator(), jobStore,
                timeResolver, properties, conversations);

        events.clear();
        active.set(0);
        maxActive.set(0);

        when(conversations.open(any(), any())).thenReturn(new ConversationSnapshot(
                "storage_key", "session_1", "", "", null, null, null, null,
                null, null, null, null, List.of(), ""));
        when(timeResolver.resolve(any())).thenReturn(new ResolvedTimeRange(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0), "今年"));
        when(jobStore.createJob(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn("BJOB_test");
    }

    @Test
    void successRunsAllIndicatorsAndPersistsJob() {
        stubIndicators("R1", "R2", "R3");
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains("共 3 项：3 项成功、0 项无样本、0 项失败");
        assertThat(result.answer()).contains("| 指标一 | 92.50% |");
        verify(executor, times(3)).execute(
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
        verify(jobStore).createJob(
                eq("storage_key"), eq("hospital_001"), eq("user_1"), anyString(),
                eq(3), eq(START), eq(END));
        verify(jobStore, times(3)).recordTask(eq("BJOB_test"), anyInt(), any());
        verify(jobStore).finishJob(eq("BJOB_test"), eq("COMPLETED"), eq(3), eq(0), eq(0));
    }

    @Test
    void emitsAgentLifecycleAndProgressEvents() {
        stubIndicators("R1", "R2", "R3");
        stubExecutorSuccess();

        runtime.run(request(), observer, batchSpec());

        List<String> types = events.stream()
                .map(event -> String.valueOf(event.get("event"))).toList();
        assertThat(types.get(0)).isEqualTo("agent_start");
        assertThat(types.get(types.size() - 2)).isEqualTo("assistant_message");
        assertThat(types.get(types.size() - 1)).isEqualTo("agent_done");
        long progress = types.stream().filter("stage_update"::equals).count();
        assertThat(progress).isEqualTo(3);
        Map<String, Object> start = events.get(0);
        assertThat(start.get("batch")).isEqualTo(true);
        assertThat(start.get("subtask_count")).isEqualTo(3);
    }

    @Test
    void singleFailureIsIsolatedAsPartialSuccess() {
        stubIndicators("R1", "RULE_FAIL", "R3");
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains("共 3 项：2 项成功、0 项无样本、1 项失败");
        assertThat(result.answer()).contains("**失败指标**");
        verify(jobStore).finishJob(eq("BJOB_test"), eq("PARTIAL_SUCCESS"), eq(2), eq(0), eq(1));
    }

    @Test
    void allFailedReturnsCompoundFailed() {
        stubIndicators("RULE_FAIL", "RULE_FAIL");
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("compound_failed");
        verify(jobStore).finishJob(eq("BJOB_test"), eq("FAILED"), eq(0), eq(0), eq(2));
    }

    @Test
    void ambiguousTimeTriggersSingleClarification() {
        when(timeResolver.resolve(any())).thenReturn(null);
        stubIndicators("R1");

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("clarification");
        assertThat(result.clarification()).isNotNull();
        assertThat(result.clarification().code()).isEqualTo("TIME_RANGE_AMBIGUOUS");
        verify(executor, never()).execute(
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
        assertThat(events.stream().map(event -> event.get("event")))
                .contains("clarification_required");
    }

    @Test
    void emptyIndicatorListReturnsFriendlyMessage() {
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(List.of());

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains("没有已配置的活跃指标");
        verify(executor, never()).execute(
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
    }

    @Test
    void concurrencyIsBoundedByConfiguredWorkers() {
        properties.setBatchWorkerConcurrency(2);
        stubIndicators("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8");
        when(executor.execute(anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class)))
                .thenAnswer(invocation -> {
                    int current = active.incrementAndGet();
                    maxActive.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                    return success(invocation.getArgument(0), invocation.getArgument(1));
                });

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(maxActive.get()).isLessThanOrEqualTo(2);
        assertThat(result.answer()).contains("共 8 项：8 项成功");
    }

    private void stubIndicators(String... ruleIds) {
        List<Map<String, String>> indicators = new ArrayList<>();
        for (String ruleId : ruleIds) {
            indicators.add(Map.of("rule_id", ruleId, "rule_name", nameFor(ruleId)));
        }
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(indicators);
    }

    private void stubExecutorSuccess() {
        when(executor.execute(anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class)))
                .thenAnswer(invocation -> {
                    String ruleId = invocation.getArgument(0);
                    String ruleName = invocation.getArgument(1);
                    if ("RULE_FAIL".equals(ruleId)) {
                        return IndicatorExecutionResult.failed(
                                ruleId, ruleName, "TRIAL_RUN_FAILED", "试运行失败。");
                    }
                    return success(ruleId, ruleName);
                });
    }

    private static IndicatorExecutionResult success(String ruleId, String ruleName) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.SUCCESS, 92.5, 185L, 200L, "percent",
                95, ">=", START, END, "RUN_1", null, null, 10);
    }

    private static String nameFor(String ruleId) {
        return switch (ruleId) {
            case "R1" -> "指标一";
            case "R2" -> "指标二";
            case "R3" -> "指标三";
            case "R4" -> "指标四";
            case "R5" -> "指标五";
            case "R6" -> "指标六";
            case "R7" -> "指标七";
            case "R8" -> "指标八";
            default -> "失败指标";
        };
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest(
                "计算今年所有指标的结果", "session_1", "model_1", null,
                "REQ_1", "TRACE_1", "db_main", "", "",
                new HospitalPrincipal(
                        "user_1", "account_1", "hospital_001",
                        Set.of("agent:run"), false, "session_1"));
    }

    private static BatchRequestSpec batchSpec() {
        return BatchRequestSpec.allActive("计算今年所有指标的结果");
    }
}
