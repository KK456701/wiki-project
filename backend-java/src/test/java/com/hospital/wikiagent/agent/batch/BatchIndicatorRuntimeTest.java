package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
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
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
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
    private static final String END = "2026-02-01 00:00:00";

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
                LocalDateTime.of(2026, 2, 1, 0, 0), "一月"));
        when(jobStore.createJob(
                any(), any(), any(), any(), anyInt(), any(), any(), anyString()))
                .thenReturn("BJOB_test");
        when(rules.caliberProfiles(anyString(), anyString())).thenAnswer(invocation -> {
            String ruleId = invocation.getArgument(0);
            return List.of(Map.of(
                    "profile_id", ruleId + "_P1",
                    "profile_name", "默认口径",
                    "label", "默认口径",
                    "extraction_contract", Map.of("event_no", "EVENT_" + ruleId)));
        });
    }

    @Test
    void successRunsAllIndicatorsAndPersistsJob() {
        stubIndicators("R1", "R2", "R3");
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains(
                "共 3 项指标、3 个已审批口径：3 个口径成功、0 个口径无样本、0 个口径失败");
        assertThat(result.answer()).contains("| 指标一 | 默认口径（R1_P1） | 92.50% |");
        verify(executor, times(3)).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(START), eq(END),
                any(AgentRuntimeContext.class));
        verify(jobStore).createJob(
                eq("storage_key"), eq("hospital_001"), eq("user_1"), anyString(),
                eq(3), eq(START), eq(END), eq("TRACE_1"));
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
        assertThat(types).contains("agent_start");
        assertThat(types.get(types.size() - 2)).isEqualTo("assistant_message");
        assertThat(types.get(types.size() - 1)).isEqualTo("agent_done");
        long progress = types.stream().filter("stage_update"::equals).count();
        assertThat(progress).isEqualTo(3);
        Map<String, Object> start = events.stream()
                .filter(event -> "agent_start".equals(event.get("event")))
                .findFirst().orElseThrow();
        assertThat(start.get("batch")).isEqualTo(true);
        assertThat(start.get("subtask_count")).isEqualTo(35);
        assertThat(events.stream()
                .filter(event -> "trace_node".equals(event.get("event")))
                .filter(event -> "batch_profile".equals(event.get("node_name")))
                .count()).isEqualTo(3);
    }

    @Test
    void selectedScopeRunsOnlyRequestedIndicators() {
        stubExecutorSuccess();
        BatchRequestSpec selected = BatchRequestSpec.selected(
                "计算指标一和指标三的结果",
                "2026-01-01 至 2026-04-01",
                List.of(
                        new BatchRequestSpec.Target("R1", "指标一"),
                        new BatchRequestSpec.Target("R3", "指标三")));

        AgentRunResult result = runtime.run(request(), observer, selected);

        assertThat(result.answer()).contains("共 2 项指标、2 个已审批口径");
        verify(rules, never()).activeIndicatorNames(anyString(), anyInt());
        verify(executor, times(2)).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(START), eq(END),
                any(AgentRuntimeContext.class));
        verify(jobStore).createJob(
                eq("storage_key"), eq("hospital_001"), eq("user_1"), anyString(),
                eq(2), eq(START), eq(END), eq("TRACE_1"));
    }

    @Test
    void selectedScopeCreatesExactlyFiveAndThirtyFourTasks() {
        stubExecutorSuccess();
        for (int count : List.of(5, 34)) {
            List<BatchRequestSpec.Target> targets = new ArrayList<>();
            for (int index = 1; index <= count; index++) {
                targets.add(new BatchRequestSpec.Target(
                        "R" + index, "指标" + index));
            }

            AgentRunResult result = runtime.run(
                    request(), observer,
                    BatchRequestSpec.selected("计算指定指标", "今年", targets));

            assertThat(result.stepCount()).isEqualTo(count);
            assertThat(result.answer()).contains("共 " + count + " 项指标、" + count + " 个已审批口径");
            verify(executor, times(count)).execute(
                    anyString(), anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), eq(START), eq(END),
                    any(AgentRuntimeContext.class));
            verify(jobStore, times(count))
                    .recordTask(eq("BJOB_test"), anyInt(), any());
            clearInvocations(executor, jobStore, conversations);
        }
    }

    @Test
    void singleFailureIsIsolatedAsPartialSuccess() {
        stubIndicators("R1", "RULE_FAIL", "R3");
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains(
                "共 3 项指标、3 个已审批口径：2 个口径成功、0 个口径无样本、1 个口径失败");
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
        QueryScopeState previousScope = new QueryScopeState(
                "indicator_trial_run", "ALL", List.of(),
                "2026-06-01 00:00:00", "2026-07-01 00:00:00");
        when(conversations.open(any(), any())).thenReturn(new ConversationSnapshot(
                "storage_key", "session_1", "", "", null, null, null, null,
                null, null, null, null, List.of(), previousScope, ""));
        when(timeResolver.resolve(any())).thenReturn(null);
        stubIndicators("R1");

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("clarification");
        assertThat(result.clarification()).isNotNull();
        assertThat(result.clarification().code()).isEqualTo("TIME_RANGE_AMBIGUOUS");
        String continuation = result.clarification().resumePrefix()
                + "2026-06-01 至 2026-07-01";
        assertThat(new BatchRequestDetector().detect(continuation).batch()).isTrue();
        verify(executor, never()).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
        ArgumentCaptor<QueryScopeState> scopeCaptor =
                ArgumentCaptor.forClass(QueryScopeState.class);
        verify(conversations).rememberQueryScope(any(), scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().statStart()).isEqualTo("2026-06-01 00:00:00");
        assertThat(scopeCaptor.getValue().statEnd()).isEqualTo("2026-07-01 00:00:00");
        assertThat(events.stream().map(event -> event.get("event")))
                .contains("clarification_required");
    }

    @Test
    void periodLongerThanOneMonthClarifiesBeforeEnumeratingProfilesOrExecuting() {
        when(timeResolver.resolve(any())).thenReturn(new ResolvedTimeRange(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 1),
                "超出一个月"));
        stubIndicators("R1", "R2");

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("clarification");
        assertThat(result.answer()).contains("最多允许一个月", "不会自动拆月");
        verify(rules, never()).caliberProfiles(anyString(), anyString());
        verify(executor, never()).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
    }

    @Test
    void emptyIndicatorListReturnsFriendlyMessage() {
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(List.of());

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains("没有已配置的活跃指标");
        verify(executor, never()).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class));
    }

    @Test
    void concurrencyIsBoundedByConfiguredWorkers() {
        properties.setBatchWorkerConcurrency(2);
        stubIndicators("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8");
        when(executor.execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
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
                    return success(invocation.getArgument(0), invocation.getArgument(1))
                            .withProfile(
                                    invocation.getArgument(2), invocation.getArgument(3),
                                    invocation.getArgument(4), "EXT_1", "COMPLETED");
                });

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(maxActive.get()).isEqualTo(1);
        assertThat(result.answer()).contains("共 8 项指标、8 个已审批口径");
    }

    @Test
    void thirtyFiveIndicatorsExpandToFortyTwoIndependentProfileTasks() {
        List<Map<String, String>> indicators = new ArrayList<>();
        for (int index = 1; index <= 35; index++) {
            indicators.add(Map.of(
                    "rule_id", "R" + index,
                    "rule_name", "指标" + index));
        }
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(indicators);
        when(rules.caliberProfiles(anyString(), anyString())).thenAnswer(invocation -> {
            String ruleId = invocation.getArgument(0);
            int index = Integer.parseInt(ruleId.substring(1));
            int count = index == 35 ? 0 : (index <= 8 ? 2 : 1);
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (int profile = 1; profile <= count; profile++) {
                profiles.add(Map.of(
                        "profile_id", ruleId + "_P" + profile,
                        "profile_name", "口径" + profile,
                        "extraction_contract", Map.of("event_no", "SHARED_EVENT")));
            }
            return profiles;
        });
        stubExecutorSuccess();

        AgentRunResult result = runtime.run(request(), observer, batchSpec());

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.stepCount()).isEqualTo(42);
        assertThat(result.answer()).contains("共 35 项指标、42 个已审批口径");
        verify(executor, times(42)).execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(START), eq(END),
                any(AgentRuntimeContext.class));
        verify(executor, never()).execute(
                eq("R35"), anyString(), any(), any(), any(),
                anyString(), anyString(), eq(START), eq(END),
                any(AgentRuntimeContext.class));
        verify(jobStore, times(42)).recordTask(eq("BJOB_test"), anyInt(), any());
    }

    private void stubIndicators(String... ruleIds) {
        List<Map<String, String>> indicators = new ArrayList<>();
        for (String ruleId : ruleIds) {
            indicators.add(Map.of("rule_id", ruleId, "rule_name", nameFor(ruleId)));
        }
        when(rules.activeIndicatorNames(anyString(), anyInt())).thenReturn(indicators);
    }

    private void stubExecutorSuccess() {
        when(executor.execute(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                any(AgentRuntimeContext.class)))
                .thenAnswer(invocation -> {
                    String ruleId = invocation.getArgument(0);
                    String ruleName = invocation.getArgument(1);
                    if ("RULE_FAIL".equals(ruleId)) {
                        return IndicatorExecutionResult.failed(
                                ruleId, ruleName,
                                invocation.getArgument(2), invocation.getArgument(3),
                                invocation.getArgument(4),
                                "TRIAL_RUN_FAILED", "试运行失败。");
                    }
                    return success(ruleId, ruleName).withProfile(
                            invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4), "EXT_1", "COMPLETED");
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
