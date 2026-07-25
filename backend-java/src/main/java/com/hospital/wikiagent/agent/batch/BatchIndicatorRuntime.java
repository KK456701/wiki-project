package com.hospital.wikiagent.agent.batch;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.batch.IndicatorExecutionResult.Status;
import com.hospital.wikiagent.agent.ir.RequestPlan.TimeExpression;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.ConversationSnapshot;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;
import com.hospital.wikiagent.agent.planning.TimeRangeResolver;
import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunRequest;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.contract.AgentClarification;
import com.hospital.wikiagent.rules.WikiRuleKnowledgeSource;

/**
 * “计算所有指标结果”批量请求的编排器：一次解析时间、枚举全部活跃指标、有界并发地走
 * 确定性执行器，最后聚合为 Markdown 表格并轻量落库。
 *
 * <p>整条链路 0 次 LLM 调用：意图由 {@link BatchRequestDetector} 的正则确定，指标身份来自
 * 知识库枚举，时间来自父请求。单个指标失败被隔离为 FAILED 结果，不阻断整批；数据库并发
 * 由 {@code ToolGateway} 的信号量兜底，这里只控制 worker 数量。</p>
 */
@Component
public class BatchIndicatorRuntime {
    public static final String VERSION = "batch-runtime-v1";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchIndicatorRuntime.class);

    private final WikiRuleKnowledgeSource rules;
    private final PreparedIndicatorExecutor executor;
    private final BatchResultAggregator aggregator;
    private final BatchJobStore jobStore;
    private final TimeRangeResolver timeResolver;
    private final AgentModelProperties properties;
    private final AgentConversationMemory conversations;

    public BatchIndicatorRuntime(
            WikiRuleKnowledgeSource rules,
            PreparedIndicatorExecutor executor,
            BatchResultAggregator aggregator,
            BatchJobStore jobStore,
            TimeRangeResolver timeResolver,
            AgentModelProperties properties,
            AgentConversationMemory conversations) {
        this.rules = rules;
        this.executor = executor;
        this.aggregator = aggregator;
        this.jobStore = jobStore;
        this.timeResolver = timeResolver;
        this.properties = properties;
        this.conversations = conversations;
    }

    public AgentRunResult run(
            AgentRunRequest request, AgentRunObserver observer, BatchRequestSpec spec) {
        String traceId = first(request.traceId(), id("TRACE_"));
        String requestId = first(request.requestId(), id("REQ_"));
        ConversationSnapshot conversation =
                conversations.open(request.principal(), request.sessionId());
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());

        // 公共时间一次性解析：解析失败则整批只澄清一次，不猜测。
        ResolvedTimeRange resolved =
                timeResolver.resolve(new TimeExpression(request.query(), null, null));
        if (resolved == null) {
            return timeClarification(request, observer, conversation, traceId);
        }
        String statStart = resolved.startTime().format(TIME_FORMAT);
        String statEnd = resolved.endTime().format(TIME_FORMAT);

        List<Map<String, String>> indicators = rules.activeIndicatorNames(
                request.principal().hospitalId(), properties.getBatchMaxIndicators());
        if (indicators.isEmpty()) {
            return emptyIndicators(observer, conversation, request, traceId);
        }

        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running",
                "session_id", conversation.sessionId(),
                "batch", true,
                "subtask_count", indicators.size(),
                "runtime_version", VERSION));

        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId());
        List<IndicatorExecutionResult> results =
                executeAll(request, observer, traceId, requestId, context, indicators);

        String answer = aggregator.aggregate(results, statStart, statEnd);
        persist(conversation, request, results, statStart, statEnd);

        long succeeded = count(results, Status.SUCCESS);
        long noSample = count(results, Status.NO_SAMPLE);
        long failed = count(results, Status.FAILED);
        boolean anyOk = succeeded + noSample > 0;
        String stopReason = anyOk ? "final_answer" : "compound_failed";

        emit(observer, "assistant_message", traceId, results.size(), Map.of(
                "message", answer,
                "status", anyOk ? "completed" : "failed",
                "batch", true,
                "succeeded", succeeded,
                "no_sample", noSample,
                "failed", failed));
        emit(observer, "agent_done", traceId, results.size(), Map.of(
                "stop_reason", stopReason,
                "status", anyOk ? "completed" : "incomplete",
                "step_count", results.size(),
                "subtask_count", indicators.size()));

        AgentRunState memoryState = new AgentRunState();
        memoryState.lastIntent("batch");
        memoryState.lastRuleName(indicators.stream()
                .map(indicator -> indicator.get("rule_name"))
                .collect(Collectors.joining("、")));
        memoryState.statPeriod(statStart, statEnd);
        conversations.appendAssistant(conversation, request.principal(), answer, memoryState);

        return new AgentRunResult(
                answer, stopReason, traceId, conversation.sessionId(),
                results.size(), null, null);
    }

    /**
     * 以有界并发执行全部指标。每个指标独立 try/catch，异常收敛为 FAILED 结果，绝不抛出；
     * 结果按枚举顺序返回，进度按完成先后推送。
     */
    private List<IndicatorExecutionResult> executeAll(
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            String requestId,
            AgentRuntimeContext context,
            List<Map<String, String>> indicators) {
        int total = indicators.size();
        int concurrency = Math.max(1, properties.getBatchWorkerConcurrency());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "batch-indicator-worker");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger completed = new AtomicInteger();
        try {
            List<Callable<IndicatorExecutionResult>> callables = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                Map<String, String> indicator = indicators.get(index);
                String ruleId = indicator.get("rule_id");
                String ruleName = indicator.get("rule_name");
                String subtaskId = requestId + ":batch:" + index;
                callables.add(() -> {
                    IndicatorExecutionResult result;
                    try {
                        result = executor.execute(
                                ruleId, ruleName, subtaskId, request.query(), context);
                    } catch (RuntimeException exception) {
                        result = IndicatorExecutionResult.failed(
                                ruleId, ruleName, "BATCH_INDICATOR_ERROR",
                                exception.getMessage());
                    }
                    emitProgress(observer, traceId, completed.incrementAndGet(), total,
                            ruleName, result.ok());
                    return result;
                });
            }
            List<Future<IndicatorExecutionResult>> futures = pool.invokeAll(callables);
            List<IndicatorExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.add(futures.get(index).get());
                } catch (Exception exception) {
                    Map<String, String> indicator = indicators.get(index);
                    results.add(IndicatorExecutionResult.failed(
                            indicator.get("rule_id"), indicator.get("rule_name"),
                            "BATCH_INDICATOR_ERROR", exception.getMessage()));
                }
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return indicators.stream()
                    .map(indicator -> IndicatorExecutionResult.failed(
                            indicator.get("rule_id"), indicator.get("rule_name"),
                            "BATCH_CANCELLED", "批量任务已取消，请重新发送问题。"))
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 持久化整批作业与逐指标任务。持久化是旁路能力，失败仅告警，不影响最终回答。
     */
    private void persist(
            ConversationSnapshot conversation,
            AgentRunRequest request,
            List<IndicatorExecutionResult> results,
            String statStart,
            String statEnd) {
        try {
            String jobId = jobStore.createJob(
                    conversation.storageKey(),
                    request.principal().hospitalId(),
                    request.principal().userId(),
                    request.query(),
                    results.size(),
                    statStart,
                    statEnd);
            for (int index = 0; index < results.size(); index++) {
                jobStore.recordTask(jobId, index, results.get(index));
            }
            long succeeded = count(results, Status.SUCCESS);
            long noSample = count(results, Status.NO_SAMPLE);
            long failed = count(results, Status.FAILED);
            String status = failed == 0
                    ? "COMPLETED"
                    : succeeded + noSample == 0 ? "FAILED" : "PARTIAL_SUCCESS";
            jobStore.finishJob(jobId, status, (int) succeeded, (int) noSample, (int) failed);
        } catch (RuntimeException exception) {
            LOGGER.warn("批量作业持久化失败，不影响回答：{}", exception.getMessage());
        }
    }

    private AgentRunResult timeClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            ConversationSnapshot conversation,
            String traceId) {
        String message = "请明确要统计的时间范围，我会一次性计算全部指标。"
                + "例如“今年”“本月”或“2026年1月至3月”。";
        AgentClarification clarification = timeClarification();
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "session_id", conversation.sessionId(),
                "batch", true, "runtime_version", VERSION));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", message,
                "code", "TIME_RANGE_AMBIGUOUS",
                "fallback_category", "USER_CLARIFICATION",
                "clarification", clarification,
                "stop_reason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stop_reason", "clarification", "status", "incomplete", "step_count", 0));
        AgentRunState state = new AgentRunState();
        state.lastIntent("batch");
        conversations.appendAssistant(conversation, request.principal(), message, state);
        return new AgentRunResult(
                message, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private AgentRunResult emptyIndicators(
            AgentRunObserver observer,
            ConversationSnapshot conversation,
            AgentRunRequest request,
            String traceId) {
        String message = "当前没有已配置的活跃指标，请先在知识库中配置指标规则后再试。";
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "session_id", conversation.sessionId(),
                "batch", true, "subtask_count", 0, "runtime_version", VERSION));
        emit(observer, "assistant_message", traceId, 0, Map.of(
                "message", message, "status", "completed", "batch", true));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stop_reason", "final_answer", "status", "completed", "step_count", 0));
        AgentRunState state = new AgentRunState();
        state.lastIntent("batch");
        conversations.appendAssistant(conversation, request.principal(), message, state);
        return new AgentRunResult(
                message, "final_answer", traceId, conversation.sessionId(), 0,
                null, null);
    }

    private static AgentClarification timeClarification() {
        List<AgentClarification.Option> options = List.of(
                new AgentClarification.Option(
                        "year-to-date", "今年至今", "今年至今",
                        "从今年1月1日统计到今天", "time"),
                new AgentClarification.Option(
                        "current-month", "本月", "本月",
                        "从本月1日统计到今天", "time"),
                new AgentClarification.Option(
                        "previous-month", "上个月", "上个月",
                        "统计上个自然月", "time"));
        return new AgentClarification(
                "TIME_RANGE_AMBIGUOUS",
                "time_range",
                "还需要一个统计时间",
                "请选择常用时间范围，或在下方输入自定义开始和结束日期。",
                "计算全部指标结果需要明确的统计周期，整批只会询问一次。",
                "single",
                options,
                true,
                "例如：2026-01-01 至 2026-03-31",
                "统计时间为：");
    }

    private static void emitProgress(
            AgentRunObserver observer,
            String traceId,
            int done,
            int total,
            String ruleName,
            boolean ok) {
        emit(observer, "stage_update", traceId, done, Map.of(
                "node_name", "batch_indicator",
                "node_type", "code",
                "message", "正在计算指标 (" + done + "/" + total + ")：" + ruleName,
                "status", ok ? "success" : "failed"));
    }

    private static long count(List<IndicatorExecutionResult> results, Status status) {
        return results.stream().filter(result -> result.status() == status).count();
    }

    private static void emit(
            AgentRunObserver observer,
            String event,
            String traceId,
            int step,
            Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("trace_id", traceId);
        payload.put("step", step);
        payload.putAll(values);
        observer.onEvent(Map.copyOf(payload));
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
