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
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryTarget;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;
import com.hospital.wikiagent.agent.planning.StatPeriodPolicy;
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
        long routeStarted = System.currentTimeMillis();
        String traceId = first(request.traceId(), id("TRACE_"));
        String requestId = first(request.requestId(), id("REQ_"));
        ConversationSnapshot conversation =
                conversations.open(request.principal(), request.sessionId());
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running",
                "session_id", conversation.sessionId(),
                "batch", true,
                "subtask_count", spec.allActive()
                        ? properties.getBatchMaxIndicators() : spec.targets().size(),
                "runtime_version", VERSION));
        emitTrace(observer, traceId, "batch_scope_resolve", "success", routeStarted,
                "root", Map.of("query", request.query()), Map.of(
                        "scope", spec.scope().name(),
                        "selected_count", spec.targets().size()));

        // 公共时间一次性解析：解析失败则整批只澄清一次，不猜测。
        long timeStarted = System.currentTimeMillis();
        ResolvedTimeRange resolved =
                timeResolver.resolve(new TimeExpression(spec.timeText(), null, null));
        if (resolved == null) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("time_text", spec.timeText()), Map.of(
                            "error_code", "TIME_RANGE_AMBIGUOUS"));
            return timeClarification(request, observer, conversation, traceId, spec);
        }
        StatPeriodPolicy.Validation period = StatPeriodPolicy.validate(
                resolved.startTime(), resolved.endTime());
        if (!period.ok()) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("time_text", spec.timeText()), Map.of(
                            "error_code", period.code(),
                            "latest_end", String.valueOf(period.latestEnd())));
            return periodClarification(
                    request, observer, conversation, traceId, spec, period);
        }
        String statStart = resolved.startTime().format(TIME_FORMAT);
        String statEnd = resolved.endTime().format(TIME_FORMAT);
        emitTrace(observer, traceId, "batch_time_resolve", "success", timeStarted,
                "root", Map.of("time_text", spec.timeText()), Map.of(
                        "stat_start", statStart, "stat_end", statEnd));

        long enumerateStarted = System.currentTimeMillis();
        List<Map<String, String>> indicators = spec.allActive()
                ? rules.activeIndicatorNames(
                        request.principal().hospitalId(), properties.getBatchMaxIndicators())
                : spec.targets().stream()
                        .map(target -> Map.of(
                                "rule_id", target.ruleId(),
                                "rule_name", target.ruleName()))
                        .toList();
        emitTrace(observer, traceId, "batch_indicator_enumerate", "success",
                enumerateStarted, "root", Map.of("scope", spec.scope().name()),
                Map.of("indicator_count", indicators.size(),
                        "rule_ids", indicators.stream()
                                .map(value -> value.get("rule_id")).toList()));
        if (indicators.isEmpty()) {
            return emptyIndicators(observer, conversation, request, traceId);
        }
        List<ProfileTarget> profileTargets = enumerateProfiles(
                indicators, request.principal().hospitalId(), spec);
        emitTrace(observer, traceId, "batch_profile_enumerate", "success",
                enumerateStarted, "root", Map.of("indicator_count", indicators.size()),
                Map.of(
                        "approved_profile_count", profileTargets.size(),
                        "profile_ids", profileTargets.stream()
                                .map(ProfileTarget::profileId).toList()));

        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId(), true);
        List<IndicatorExecutionResult> results =
                executeAll(
                        observer, traceId, requestId, context, profileTargets,
                        spec.timeText(), statStart, statEnd);

        long mergeStarted = System.currentTimeMillis();
        String answer = aggregator.aggregateProfiles(
                results, statStart, statEnd, indicators.size());

        long succeeded = count(results, Status.SUCCESS);
        long noSample = count(results, Status.NO_SAMPLE);
        long failed = count(results, Status.FAILED);
        emitTrace(observer, traceId, "batch_result_merge", "success", mergeStarted,
                "root", Map.of(
                        "indicator_count", indicators.size(),
                        "profile_count", results.size()), Map.of(
                        "succeeded", succeeded,
                        "no_sample", noSample,
                        "failed", failed));
        persist(
                conversation, request, traceId, results, statStart, statEnd, observer);
        boolean anyOk = succeeded + noSample > 0;
        String stopReason = anyOk ? "final_answer" : "compound_failed";

        emit(observer, "assistant_message", traceId, results.size(), Map.of(
                "message", answer,
                "status", anyOk ? "completed" : "failed",
                "batch", true,
                "indicator_count", indicators.size(),
                "profile_count", results.size(),
                "succeeded", succeeded,
                "no_sample", noSample,
                "failed", failed));
        emit(observer, "agent_done", traceId, results.size(), Map.of(
                "stop_reason", stopReason,
                "status", anyOk ? "completed" : "incomplete",
                "step_count", results.size(),
                "subtask_count", results.size()));

        AgentRunState memoryState = new AgentRunState();
        memoryState.lastIntent("batch");
        memoryState.lastRuleName(indicators.stream()
                .map(indicator -> indicator.get("rule_name"))
                .collect(Collectors.joining("、")));
        memoryState.statPeriod(statStart, statEnd);
        conversations.appendAssistant(conversation, request.principal(), answer, memoryState);
        conversations.rememberCompoundTargets(
                conversation,
                indicators.stream().map(value -> value.get("rule_name")).toList());
        conversations.rememberQueryScope(conversation, new QueryScopeState(
                "indicator_trial_run",
                scopeMode(spec, indicators.size()),
                indicators.stream()
                        .map(value -> new QueryTarget(
                                value.get("rule_id"), value.get("rule_name")))
                        .toList(),
                statStart,
                statEnd));

        return new AgentRunResult(
                answer, stopReason, traceId, conversation.sessionId(),
                results.size(), null, null);
    }

    /**
     * 以有界并发执行全部指标。每个指标独立 try/catch，异常收敛为 FAILED 结果，绝不抛出；
     * 结果按枚举顺序返回，进度按完成先后推送。
     */
    private List<IndicatorExecutionResult> executeAll(
            AgentRunObserver observer,
            String traceId,
            String requestId,
            AgentRuntimeContext context,
            List<ProfileTarget> targets,
            String timeText,
            String statStart,
            String statEnd) {
        int total = targets.size();
        // 真实库是全局共享快照；不同 Profile 必须严格串行完成替换与双库查询。
        int concurrency = 1;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "batch-indicator-worker");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger completed = new AtomicInteger();
        try {
            List<Callable<IndicatorExecutionResult>> callables = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                ProfileTarget target = targets.get(index);
                String ruleId = target.ruleId();
                String ruleName = target.ruleName();
                String subtaskId = requestId + ":batch:" + index;
                callables.add(() -> {
                    long indicatorStarted = System.currentTimeMillis();
                    IndicatorExecutionResult result;
                    try {
                        result = target.profileId() == null
                                ? IndicatorExecutionResult.failed(
                                        ruleId, ruleName, null, target.profileName(), null,
                                        "PROFILE_NOT_EXECUTABLE",
                                        "当前指标没有可执行的已审批 Profile。")
                                : executor.execute(
                                        ruleId, ruleName,
                                        target.profileId(), target.profileName(), target.eventNo(),
                                        subtaskId, timeText, statStart, statEnd, context);
                    } catch (RuntimeException exception) {
                        result = IndicatorExecutionResult.failed(
                                ruleId, ruleName,
                                target.profileId(), target.profileName(), target.eventNo(),
                                "BATCH_INDICATOR_ERROR",
                                exception.getMessage());
                    }
                    Map<String, Object> traceOutput = new LinkedHashMap<>();
                    traceOutput.put("status", result.status().name());
                    if (result.runId() != null) {
                        traceOutput.put("run_id", result.runId());
                    }
                    if (result.errorCode() != null) {
                        traceOutput.put("error_code", result.errorCode());
                    }
                    if (result.extractionId() != null) {
                        traceOutput.put("extraction_id", result.extractionId());
                    }
                    if (result.extractionStatus() != null) {
                        traceOutput.put("snapshot_status", result.extractionStatus());
                    }
                    Map<String, Object> traceInput = new LinkedHashMap<>();
                    traceInput.put("rule_id", ruleId);
                    traceInput.put("rule_name", ruleName);
                    traceInput.put("profile_id", target.profileId());
                    traceInput.put("profile_name", target.profileName());
                    if (target.eventNo() != null) {
                        traceInput.put("event_no", target.eventNo());
                    }
                    emitTrace(observer, traceId, "batch_profile",
                            result.status() == Status.FAILED ? "failed" : "success",
                            indicatorStarted, subtaskId, traceInput, traceOutput);
                    emitProgress(observer, traceId, completed.incrementAndGet(), total,
                            ruleName + " / " + target.profileName(), result.ok());
                    return result;
                });
            }
            List<Future<IndicatorExecutionResult>> futures = pool.invokeAll(callables);
            List<IndicatorExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.add(futures.get(index).get());
                } catch (Exception exception) {
                    ProfileTarget target = targets.get(index);
                    results.add(IndicatorExecutionResult.failed(
                            target.ruleId(), target.ruleName(),
                            target.profileId(), target.profileName(), target.eventNo(),
                            "BATCH_INDICATOR_ERROR", exception.getMessage()));
                }
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return targets.stream()
                    .map(target -> IndicatorExecutionResult.failed(
                            target.ruleId(), target.ruleName(),
                            target.profileId(), target.profileName(), target.eventNo(),
                            "BATCH_CANCELLED", "批量任务已取消，请重新发送问题。"))
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 将指标范围展开为已审批 Profile 任务。知识库返回顺序即发布顺序，确保回答、
     * Trace 和批次表均能稳定复现用户看到的顺序。
     */
    private List<ProfileTarget> enumerateProfiles(
            List<Map<String, String>> indicators,
            String hospitalId,
            BatchRequestSpec spec) {
        List<ProfileTarget> targets = new ArrayList<>();
        for (Map<String, String> indicator : indicators) {
            String ruleId = indicator.get("rule_id");
            String ruleName = indicator.get("rule_name");
            List<Map<String, Object>> profiles = rules.caliberProfiles(ruleId, hospitalId);
            BatchRequestSpec.Target requested = spec.targets().stream()
                    .filter(target -> ruleId.equals(target.ruleId()))
                    .findFirst()
                    .orElse(null);
            if (requested != null && requested.profileId() != null) {
                profiles = profiles.stream()
                        .filter(profile -> requested.profileId().equals(
                                text(profile.get("profile_id"))))
                        .toList();
            }
            if (profiles.isEmpty()) {
                continue;
            }
            for (Map<String, Object> profile : profiles) {
                Map<String, Object> extraction =
                        objectMap(profile.get("extraction_contract"));
                targets.add(new ProfileTarget(
                        ruleId,
                        ruleName,
                        text(profile.get("profile_id")),
                        first(
                                text(profile.get("profile_name")),
                                text(profile.get("label")),
                                text(profile.get("profile_id"))),
                        text(extraction.get("event_no"))));
            }
        }
        return List.copyOf(targets);
    }

    /**
     * 持久化整批作业与逐指标任务。持久化是旁路能力，失败仅告警，不影响最终回答。
     */
    private String persist(
            ConversationSnapshot conversation,
            AgentRunRequest request,
            String traceId,
            List<IndicatorExecutionResult> results,
            String statStart,
            String statEnd,
            AgentRunObserver observer) {
        long started = System.currentTimeMillis();
        try {
            String jobId = jobStore.createJob(
                    conversation.storageKey(),
                    request.principal().hospitalId(),
                    request.principal().userId(),
                    request.query(),
                    results.size(),
                    statStart,
                    statEnd,
                    traceId);
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
            emitTrace(observer, traceId, "batch_job_persist", "success", started,
                    "root", Map.of("task_count", results.size()),
                    Map.of("job_id", jobId, "status", status));
            return jobId;
        } catch (RuntimeException exception) {
            LOGGER.warn("批量作业持久化失败，不影响回答：{}", exception.getMessage());
            emitTrace(observer, traceId, "batch_job_persist", "failed", started,
                    "root", Map.of("task_count", results.size()),
                    Map.of("error_code", "BATCH_JOB_PERSIST_FAILED"));
            return null;
        }
    }

    private AgentRunResult timeClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            ConversationSnapshot conversation,
            String traceId,
            BatchRequestSpec spec) {
        String message = "请明确一个月以内的统计时间范围。"
                + "例如“本月”或“2026年6月”。";
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
        conversations.rememberCompoundTargets(
                conversation,
                spec.targets().stream().map(BatchRequestSpec.Target::ruleName).toList());
        QueryScopeState previous = conversation.queryScope();
        conversations.rememberQueryScope(conversation, new QueryScopeState(
                "indicator_trial_run",
                spec.allActive() ? "ALL"
                        : spec.targets().size() == 1 ? "SINGLE" : "SUBSET",
                spec.targets().stream()
                        .map(value -> new QueryTarget(value.ruleId(), value.ruleName()))
                        .toList(),
                previous == null ? null : previous.statStart(),
                previous == null ? null : previous.statEnd()));
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
                "计算全部指标结果，统计时间为：");
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

    private static void emitTrace(
            AgentRunObserver observer,
            String traceId,
            String nodeName,
            String status,
            long startedAt,
            String subtaskId,
            Map<String, Object> input,
            Map<String, Object> output) {
        long endedAt = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("trace_id", traceId);
        event.put("node_id", id("NODE_"));
        event.put("node_name", nodeName);
        event.put("node_type", "code");
        event.put("status", status);
        event.put("started_at_epoch_ms", startedAt);
        event.put("ended_at_epoch_ms", endedAt);
        event.put("duration_ms", Math.max(0, endedAt - startedAt));
        event.put("subtask_id", subtaskId);
        event.put("input", input == null ? Map.of() : input);
        event.put("output", output == null ? Map.of() : output);
        observer.onEvent(Map.copyOf(event));
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

    private AgentRunResult periodClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            ConversationSnapshot conversation,
            String traceId,
            BatchRequestSpec spec,
            StatPeriodPolicy.Validation validation) {
        String message = validation.message()
                + " 请重新给出一个月以内的统计区间；系统不会自动拆月或相加。";
        AgentClarification clarification = timeClarification();
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", message,
                "code", validation.code(),
                "fallback_category", "USER_CLARIFICATION",
                "clarification", clarification,
                "stop_reason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stop_reason", "clarification",
                "status", "incomplete",
                "step_count", 0));
        AgentRunState state = new AgentRunState();
        state.lastIntent("batch");
        conversations.appendAssistant(conversation, request.principal(), message, state);
        conversations.rememberCompoundTargets(
                conversation,
                spec.targets().stream().map(BatchRequestSpec.Target::ruleName).toList());
        return new AgentRunResult(
                message, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private static String scopeMode(BatchRequestSpec spec, int indicatorCount) {
        if (spec.allActive()) {
            return "ALL";
        }
        return indicatorCount == 1 ? "SINGLE" : "SUBSET";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record ProfileTarget(
            String ruleId,
            String ruleName,
            String profileId,
            String profileName,
            String eventNo) {}
}
