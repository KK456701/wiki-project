package com.hospital.wikiagent.agent.batch;

import java.time.LocalDateTime;
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
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;
import com.hospital.wikiagent.agent.planning.TimeRangeResolver;
import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunRequest;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
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
    private final MrasSqlExecutionService mrasExecution;

    public BatchIndicatorRuntime(
            WikiRuleKnowledgeSource rules,
            PreparedIndicatorExecutor executor,
            BatchResultAggregator aggregator,
            BatchJobStore jobStore,
            TimeRangeResolver timeResolver,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            MrasSqlExecutionService mrasExecution) {
        this.rules = rules;
        this.executor = executor;
        this.aggregator = aggregator;
        this.jobStore = jobStore;
        this.timeResolver = timeResolver;
        this.properties = properties;
        this.conversations = conversations;
        this.mrasExecution = mrasExecution;
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
        // 用户未指定时间且无历史范围：直接澄清
        if (spec.timeText() == null) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("time_text", ""), Map.of(
                            "error_code", "TIME_RANGE_AMBIGUOUS"));
            return timeClarification(request, observer, conversation, traceId, spec, null);
        }
        ResolvedTimeRange resolved =
                timeResolver.resolve(new TimeExpression(spec.timeText(), null, null));
        if (resolved == null) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("time_text", spec.timeText()), Map.of(
                            "error_code", "TIME_RANGE_AMBIGUOUS"));
            return timeClarification(
                    request, observer, conversation, traceId, spec, spec.timeText());
        }
        String statStart = resolved.startTime().format(TIME_FORMAT);
        String statEnd = resolved.endTime().format(TIME_FORMAT);
        emitTrace(observer, traceId, "batch_time_resolve", "success", timeStarted,
                "root", Map.of("time_text", spec.timeText()), Map.of(
                        "stat_start", statStart, "stat_end", statEnd));

        long enumerateStarted = System.currentTimeMillis();
        List<BatchRequestSpec.Target> indicatorTargets = spec.allActive()
                ? rules.activeIndicatorNames(
                        request.principal().hospitalId(), properties.getBatchMaxIndicators()).stream()
                        .map(value -> new BatchRequestSpec.Target(
                                value.get("rule_id"), value.get("rule_name")))
                        .toList()
                : spec.targets();
        List<Map<String, String>> indicators = indicatorTargets.stream()
                .map(target -> Map.of(
                        "rule_id", target.ruleId(),
                        "rule_name", target.ruleName()))
                .toList();
        List<ProfileExecutionTarget> executionTargets = expandProfiles(
                indicatorTargets, request.principal().hospitalId());
        emitTrace(observer, traceId, "batch_indicator_enumerate", "success",
                enumerateStarted, "root", Map.of("scope", spec.scope().name()),
                Map.of("indicator_count", indicators.size(),
                        "profile_count", executionTargets.size(),
                        "rule_ids", indicators.stream()
                                .map(value -> value.get("rule_id")).toList()));
        if (indicators.isEmpty() || executionTargets.isEmpty()) {
            return emptyIndicators(observer, conversation, request, traceId);
        }

        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId(), true);
        List<IndicatorExecutionResult> results =
                executeAll(
                        observer, traceId, requestId, context, executionTargets,
                        spec.timeText(), statStart, statEnd);

        long mergeStarted = System.currentTimeMillis();
        String answer = aggregator.aggregate(
                results, indicators.size(), statStart, statEnd);

        long succeeded = count(results, Status.SUCCESS);
        long noSample = count(results, Status.NO_SAMPLE);
        long failed = count(results, Status.FAILED);
        emitTrace(observer, traceId, "batch_result_merge", "success", mergeStarted,
                "root", Map.of("indicator_count", results.size()), Map.of(
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
                "succeeded", succeeded,
                "no_sample", noSample,
                "failed", failed));
        emit(observer, "agent_done", traceId, results.size(), Map.of(
                "stop_reason", stopReason,
                "status", anyOk ? "completed" : "incomplete",
                "step_count", results.size(),
                "subtask_count", executionTargets.size()));

        AgentRunState memoryState = new AgentRunState();
        memoryState.lastIntent("batch");
        memoryState.lastRuleName(indicators.stream()
                .map(indicator -> indicator.get("rule_name"))
                .collect(Collectors.joining("、")));
        memoryState.statPeriod(statStart, statEnd);
        // 持久化逐指标卡片载荷（与 SSE batch_indicator_result 同形态），
        // 切换会话后前端可从消息历史直接恢复卡片展示。
        List<Map<String, Object>> cardPayloads = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            cardPayloads.add(indicatorResultPayload(
                    index + 1, results.size(), results.get(index)));
        }
        conversations.appendAssistant(
                conversation, request.principal(), answer, memoryState, cardPayloads);
        conversations.rememberCompoundTargets(
                conversation,
                indicators.stream().map(value -> value.get("rule_name")).toList());
        conversations.rememberQueryScope(conversation, new QueryScopeState(
                "indicator_trial_run",
                targetMode(spec),
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
            List<ProfileExecutionTarget> targets,
            String timeText,
            String statStart,
            String statEnd) {
        int total = targets.size();
        /*
         * 真实库是全局单快照：每个 Profile 都会清理并替换同一组受控表，然后在持锁
         * 状态下计算。worker 并发会把后续任务的锁等待计入工具超时，形成假性
         * TOOL_TIMEOUT；因此 Profile 批次必须从任务入口就串行，而不只依赖数据库锁。
         */
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
                ProfileExecutionTarget target = targets.get(index);
                String ruleId = target.ruleId();
                String ruleName = target.ruleName();
                String subtaskId = requestId + ":batch:" + index;
                callables.add(() -> {
                    long indicatorStarted = System.currentTimeMillis();
                    // MRAS 路径由 executeViaMras 自行发出带真实耗时的抽取/查询节点，
                    // 不再走 emitProfileWorkflowTrace 的 0ms 占位节点。
                    boolean viaMras = mrasExecution != null && mrasExecution.supports(ruleId);
                    IndicatorExecutionResult result;
                    try {
                        if (viaMras) {
                            result = executeViaMras(
                                    ruleId, ruleName, target, statStart, statEnd,
                                    observer, traceId, subtaskId);
                        } else if (target.profileId() == null) {
                            result = executor.execute(
                                    ruleId, ruleName, subtaskId, timeText,
                                    statStart, statEnd, context);
                        } else {
                            result = executor.execute(
                                    ruleId, ruleName,
                                    target.profileId(), target.profileLabel(),
                                    target.eventNo(),
                                    subtaskId, timeText, statStart, statEnd, context);
                        }
                    } catch (RuntimeException exception) {
                        result = IndicatorExecutionResult.failed(
                                ruleId, ruleName,
                                target.profileId(), target.profileLabel(), target.eventNo(),
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
                    if (!viaMras) {
                        emitProfileWorkflowTrace(
                                observer, traceId, subtaskId, target, result);
                    }
                    emitTrace(observer, traceId, "batch_indicator",
                            result.status() == Status.FAILED ? "failed" : "success",
                            indicatorStarted, subtaskId,
                            profileTraceInput(target),
                            traceOutput);
                    emitProgress(observer, traceId, completed.incrementAndGet(), total,
                            taskDisplay(target), result.ok());
                    emitIndicatorResult(observer, traceId, completed.get(), total, result);
                    return result;
                });
            }
            List<Future<IndicatorExecutionResult>> futures = pool.invokeAll(callables);
            List<IndicatorExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    results.add(futures.get(index).get());
                } catch (Exception exception) {
                    ProfileExecutionTarget target = targets.get(index);
                    results.add(IndicatorExecutionResult.failed(
                            target.ruleId(), target.ruleName(),
                            target.profileId(), target.profileLabel(), target.eventNo(),
                            "BATCH_INDICATOR_ERROR", exception.getMessage()));
                }
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return targets.stream()
                    .map(target -> IndicatorExecutionResult.failed(
                            target.ruleId(), target.ruleName(),
                            target.profileId(), target.profileLabel(), target.eventNo(),
                            "BATCH_CANCELLED", "批量任务已取消，请重新发送问题。"))
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private List<ProfileExecutionTarget> expandProfiles(
            List<BatchRequestSpec.Target> indicators,
            String hospitalId) {
        List<ProfileExecutionTarget> targets = new ArrayList<>();
        for (BatchRequestSpec.Target indicator : indicators) {
            List<Map<String, Object>> approved =
                    rules.caliberProfiles(indicator.ruleId(), hospitalId);
            if (indicator.profileId() != null) {
                approved = approved.stream()
                        .filter(profile -> indicator.profileId().equals(
                                text(profile.get("profile_id"))))
                        .toList();
            }
            for (Map<String, Object> profile : approved) {
                String profileId = text(profile.get("profile_id"));
                Map<String, Object> effective =
                        rules.effectiveRule(indicator.ruleId(), hospitalId, profileId);
                String profileLabel = first(
                        indicator.profileLabel(),
                        first(
                                text(effective.get("profile_name")),
                                first(text(profile.get("label")), profileId)));
                Map<String, Object> extraction =
                        objectMap(effective.get("extraction_contract"));
                targets.add(new ProfileExecutionTarget(
                        indicator.ruleId(),
                        indicator.ruleName(),
                        profileId,
                        profileLabel,
                        first(
                                text(extraction.get("event_no")),
                                text(extraction.get("eventNo")))));
            }
        }
        return List.copyOf(targets);
    }

    /**
     * 通过领导知识库 MrasSqlExecutionService 执行概览查询，转换为批量结果。
     * 抽取与 SQL 执行分别发出独立计时的 trace 节点，耗时归属不再全部算在
     * “完成单项指标计算”一个节点上。
     */
    private IndicatorExecutionResult executeViaMras(
            String ruleId, String ruleName,
            ProfileExecutionTarget target,
            String statStart, String statEnd,
            AgentRunObserver observer, String traceId, String subtaskId) {
        long started = System.currentTimeMillis();
        LocalDateTime start = LocalDateTime.parse(statStart, TIME_FORMAT);
        LocalDateTime end = LocalDateTime.parse(statEnd, TIME_FORMAT);
        ToolResult toolResult = mrasExecution.executeOverview(
                ruleId, start, end, null, null);
        long durationMs = System.currentTimeMillis() - started;
        emitMrasPhaseTrace(observer, traceId, subtaskId, target, toolResult);

        if (!toolResult.ok()) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName,
                    target.profileId(), target.profileLabel(), target.eventNo(),
                    toolResult.code(), toolResult.summary());
        }

        Map<String, Object> data = toolResult.data();
        // 抽取失败时结果基于中间表旧数据，用 dataFreshness 标记并透传到前端
        String dataFreshness = data.get("extraction_warning") instanceof String
                ? "extraction_failed_stale" : null;
        Boolean noSample = (Boolean) data.get("no_sample");
        if (Boolean.TRUE.equals(noSample)) {
            return new IndicatorExecutionResult(
                    ruleId, ruleName, Status.NO_SAMPLE, null, 0L, 0L,
                    null, "percentage", null, 0L, null, null,
                    statStart, statEnd, "mras-" + ruleId, null, null, durationMs,
                    dataFreshness, target.profileId(), target.profileLabel(), null, null,
                    target.eventNo());
        }

        Number resultValue = (Number) data.get("result_value");
        Long numerator = data.get("numerator_count") instanceof Number n
                ? n.longValue() : null;
        Long denominator = data.get("denominator_count") instanceof Number n
                ? n.longValue() : null;
        Object targetValue = data.get("target_value");

        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.SUCCESS,
                resultValue != null ? resultValue.doubleValue() : null,
                numerator, denominator,
                null, "percentage", null,
                denominator, targetValue, null,
                statStart, statEnd, "mras-" + ruleId, null, null, durationMs,
                dataFreshness, target.profileId(), target.profileLabel(), null, null,
                target.eventNo());
    }

    private static Map<String, Object> profileTraceInput(
            ProfileExecutionTarget target) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rule_id", target.ruleId());
        values.put("rule_name", target.ruleName());
        if (target.profileId() != null) {
            values.put("profile_id", target.profileId());
        }
        if (target.profileLabel() != null) {
            values.put("profile_label", target.profileLabel());
        }
        if (target.eventNo() != null && !target.eventNo().isBlank()) {
            values.put("event_no", target.eventNo());
        }
        return Map.copyOf(values);
    }

    private static String taskDisplay(ProfileExecutionTarget target) {
        return target.ruleName() + " / " + target.profileLabel();
    }

    /**
     * 把 MRAS 执行结果里的阶段耗时（抽取 / 真实库 SQL 执行）各自发为独立 trace 节点，
     * 时长取自 MrasSqlExecutionService 的实测值，不再是 0ms 占位。
     */
    private static void emitMrasPhaseTrace(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ProfileExecutionTarget target,
            ToolResult toolResult) {
        Map<String, Object> data = toolResult.data() == null ? Map.of() : toolResult.data();
        long now = System.currentTimeMillis();
        if (data.get("extraction_duration_ms") instanceof Number extraction) {
            boolean extractionFailed = data.get("extraction_warning") != null;
            emitTrace(observer, traceId, "source_data_extraction",
                    extractionFailed ? "failed" : "success",
                    now - Math.max(0, extraction.longValue()), subtaskId,
                    profileTraceInput(target),
                    extractionFailed
                            ? Map.of("warning", String.valueOf(data.get("extraction_warning")))
                            : Map.of("status", "extracted", "source_id", "winex_aima"));
        }
        if (toolResult.ok() && data.get("duration_ms") instanceof Number query) {
            emitTrace(observer, traceId, "real_database_overview", "success",
                    now - Math.max(0, query.longValue()), subtaskId,
                    profileTraceInput(target),
                    Map.of("source_id", "winex_aima",
                            "row_count", data.getOrDefault("row_count", 0)));
        }
    }

    private static void emitProfileWorkflowTrace(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ProfileExecutionTarget target,
            IndicatorExecutionResult result) {
        long now = System.currentTimeMillis();
        boolean sourceContractMissing =
                "SOURCE_EXTRACT_SQL_UNAVAILABLE".equals(result.errorCode());
        emitTrace(
                observer, traceId, "source_extraction_prepare",
                sourceContractMissing ? "failed" : "success",
                now, subtaskId, profileTraceInput(target),
                sourceContractMissing
                        ? Map.of("error_code", result.errorCode())
                        : Map.of("status", "prepared"));
        if (result.extractionId() != null) {
            emitTrace(
                    observer, traceId, "source_data_extraction", "success",
                    now, subtaskId, profileTraceInput(target),
                    Map.of(
                            "extraction_id", result.extractionId(),
                            "snapshot_status", first(
                                    result.extractionStatus(), "SUCCESS")));
        } else if (result.errorCode() != null
                && result.errorCode().startsWith("SOURCE_EXTRACTION")) {
            emitTrace(
                    observer, traceId, "source_data_extraction", "failed",
                    now, subtaskId, profileTraceInput(target),
                    Map.of("error_code", result.errorCode()));
        }
        if (result.runId() != null) {
            emitTrace(
                    observer, traceId, "real_database_overview", "success",
                    now, subtaskId, profileTraceInput(target),
                    Map.of("run_id", result.runId(), "source_id", "winex_aima"));
        }
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
            BatchRequestSpec spec,
            String invalidTimeText) {
        // 澄清话术必须说明失败原因并复述指标范围：时间起止倒序等解析失败时，
        // 不能让用户误以为“没给时间”；续答前缀也不能把已点名的指标退化成“全部指标”。
        String scopeText = scopeText(spec);
        // timeText 可能是整句原文，过长时不整句引用，只给出简短原因提示。
        String lead;
        if (invalidTimeText == null || invalidTimeText.isBlank()) {
            lead = "请明确要统计的时间范围，";
        } else if (invalidTimeText.strip().length() <= 30) {
            lead = "无法识别统计时间“" + invalidTimeText.strip()
                    + "”（请检查起止顺序是否颠倒），请重新明确时间范围，";
        } else {
            lead = "无法识别你给出的统计时间（请检查起止顺序是否颠倒），请重新明确时间范围，";
        }
        String message = lead + "我会一次性计算" + scopeText + "。"
                + "例如“今年”“本月”或“2026年1月至3月”。";
        AgentClarification clarification = timeClarification(spec, scopeText);
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
                targetMode(spec),
                spec.targets().stream()
                        .map(value -> new QueryTarget(value.ruleId(), value.ruleName()))
                        .toList(),
                previous == null ? null : previous.statStart(),
                previous == null ? null : previous.statEnd()));
        return new AgentRunResult(
                message, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private static String targetMode(BatchRequestSpec spec) {
        if (spec.allActive()) {
            return "ALL";
        }
        return spec.targets().size() == 1 ? "SINGLE" : "SUBSET";
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

    /** 澄清后的续答范围文本：点名指标时复述指标名，避免退化为“全部指标”。 */
    private static String scopeText(BatchRequestSpec spec) {
        if (spec.allActive() || spec.targets().isEmpty()) {
            return "全部指标";
        }
        return spec.targets().stream()
                .map(BatchRequestSpec.Target::ruleName)
                .collect(Collectors.joining("、"));
    }

    private static AgentClarification timeClarification(
            BatchRequestSpec spec, String scopeText) {
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
        boolean allScope = spec.allActive() || spec.targets().isEmpty();
        return new AgentClarification(
                "TIME_RANGE_AMBIGUOUS",
                "time_range",
                "还需要一个统计时间",
                "请选择常用时间范围，或在下方输入自定义开始和结束日期。",
                "计算" + scopeText + "的结果需要明确的统计周期，整批只会询问一次。",
                "single",
                options,
                true,
                "例如：2026-01-01 至 2026-03-31",
                allScope
                        ? "计算全部指标结果，统计时间为："
                        : "计算" + scopeText + "的结果，统计时间为：");
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

    /**
     * 每完成一个指标立即通过 SSE 推送该指标结果，前端可逐条渲染而不等全部完成。
     */
    private static void emitIndicatorResult(
            AgentRunObserver observer,
            String traceId,
            int done,
            int total,
            IndicatorExecutionResult result) {
        emit(observer, "batch_indicator_result", traceId, done,
                indicatorResultPayload(done, total, result));
    }

    /**
     * 单指标结果载荷：SSE 逐条推送与会话消息持久化共用同一形态，
     * 保证切换会话后恢复的卡片字段与实时推送完全一致。
     */
    static Map<String, Object> indicatorResultPayload(
            int done,
            int total,
            IndicatorExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule_id", result.ruleId());
        payload.put("rule_name", result.ruleName());
        payload.put("status", result.status().name());
        payload.put("done", done);
        payload.put("total", total);
        // 同一指标多口径时，前端靠 profile_id 去重、靠 profile_label 区分卡片，
        // 缺失时多张卡片会完全同形。
        if (result.profileId() != null) {
            payload.put("profile_id", result.profileId());
        }
        if (result.profileLabel() != null) {
            payload.put("profile_label", result.profileLabel());
        }
        if (result.resultValue() != null) {
            payload.put("result_value", result.resultValue());
        }
        if (result.numerator() != null) {
            payload.put("numerator_count", result.numerator());
        }
        if (result.denominator() != null) {
            payload.put("denominator_count", result.denominator());
        }
        if (result.unit() != null) {
            payload.put("unit", result.unit());
        }
        if (result.calculationDisplay() != null) {
            payload.put("calculation_display", result.calculationDisplay());
        }
        if (result.statStart() != null) {
            payload.put("stat_start", result.statStart());
        }
        if (result.statEnd() != null) {
            payload.put("stat_end", result.statEnd());
        }
        if (result.runId() != null) {
            payload.put("run_id", result.runId());
        }
        if (result.dataFreshness() != null) {
            payload.put("data_freshness", result.dataFreshness());
        }
        if (result.errorCode() != null) {
            payload.put("error_code", result.errorCode());
        }
        if (result.errorMessage() != null) {
            payload.put("error_message", result.errorMessage());
        }
        return payload;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? "" : String.valueOf(value).strip();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record ProfileExecutionTarget(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo) {}
}
