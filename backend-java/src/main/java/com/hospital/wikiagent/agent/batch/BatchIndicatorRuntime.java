package com.hospital.wikiagent.agent.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator;
import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator.RealSnapshotValidation;
import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator.ValidationTarget;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.Decision;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.ProfileValidation;
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
    private final BatchDataInitializationValidator initializationValidator;

    public BatchIndicatorRuntime(
            WikiRuleKnowledgeSource rules,
            PreparedIndicatorExecutor executor,
            BatchResultAggregator aggregator,
            BatchJobStore jobStore,
            TimeRangeResolver timeResolver,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            MrasSqlExecutionService mrasExecution,
            BatchDataInitializationValidator initializationValidator) {
        this.rules = rules;
        this.executor = executor;
        this.aggregator = aggregator;
        this.jobStore = jobStore;
        this.timeResolver = timeResolver;
        this.properties = properties;
        this.conversations = conversations;
        this.mrasExecution = mrasExecution;
        this.initializationValidator = initializationValidator;
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
                "sessionId", conversation.sessionId(),
                "batch", true,
                "subtaskCount", spec.allActive()
                        ? properties.getBatchMaxIndicators() : spec.targets().size(),
                "runtimeVersion", VERSION));
        emitTrace(observer, traceId, "batch_scope_resolve", "success", routeStarted,
                "root", Map.of("query", request.query()), Map.of(
                        "scope", spec.scope().name(),
                        "selectedCount", spec.targets().size()));

        // 公共时间一次性解析：解析失败则整批只澄清一次，不猜测。
        long timeStarted = System.currentTimeMillis();
        // 用户未指定时间且无历史范围：直接澄清
        if (spec.timeText() == null) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("timeText", ""), Map.of(
                            "errorCode", "TIME_RANGE_AMBIGUOUS"));
            return timeClarification(request, observer, conversation, traceId, spec, null);
        }
        ResolvedTimeRange resolved =
                timeResolver.resolve(new TimeExpression(spec.timeText(), null, null));
        if (resolved == null) {
            emitTrace(observer, traceId, "batch_time_resolve", "failed", timeStarted,
                    "root", Map.of("timeText", spec.timeText()), Map.of(
                            "errorCode", "TIME_RANGE_AMBIGUOUS"));
            return timeClarification(
                    request, observer, conversation, traceId, spec, spec.timeText());
        }
        String statStart = resolved.startTime().format(TIME_FORMAT);
        String statEnd = resolved.endTime().format(TIME_FORMAT);
        emitTrace(observer, traceId, "batch_time_resolve", "success", timeStarted,
                "root", Map.of("timeText", spec.timeText()), Map.of(
                        "statStart", statStart, "statEnd", statEnd));

        long enumerateStarted = System.currentTimeMillis();
        List<BatchRequestSpec.Target> indicatorTargets = spec.allActive()
                ? rules.activeIndicatorNames(
                        request.principal().hospitalId(), properties.getBatchMaxIndicators()).stream()
                        .map(value -> new BatchRequestSpec.Target(
                                value.get("ruleId"), value.get("ruleName")))
                        .toList()
                : spec.targets();
        List<Map<String, String>> indicators = indicatorTargets.stream()
                .map(target -> Map.of(
                        "ruleId", target.ruleId(),
                        "ruleName", target.ruleName()))
                .toList();
        List<ProfileExecutionTarget> executionTargets = expandProfiles(
                indicatorTargets, request.principal().hospitalId());
        emitTrace(observer, traceId, "batch_indicator_enumerate", "success",
                enumerateStarted, "root", Map.of("scope", spec.scope().name()),
                Map.of("indicatorCount", indicators.size(),
                        "profileCount", executionTargets.size(),
                        "ruleIds", indicators.stream()
                                .map(value -> value.get("ruleId")).toList()));
        if (indicators.isEmpty() || executionTargets.isEmpty()) {
            return emptyIndicators(observer, conversation, request, traceId);
        }

        String batchRunId = beginJob(
                conversation, request, traceId, executionTargets.size(),
                statStart, statEnd, observer);
        long validationStarted = System.currentTimeMillis();
        emit(observer, "stage_update", traceId, 0, Map.of(
                "nodeName", "batch_data_initialization_validation",
                "nodeType", "code",
                "subtaskId", "root",
                "status", "running",
                "message", "数据初始化校验 · 正在解析本次口径依赖"));
        InitializationValidationReport initialization = initializationValidator.validate(
                batchRunId,
                request.principal().hospitalId(),
                executionTargets.stream().map(BatchIndicatorRuntime::validationTarget).toList(),
                resolved.startTime(), resolved.endTime(), statStart, statEnd,
                progress -> emit(observer, "stage_update", traceId, progress.completed(), Map.of(
                        "nodeName", "batch_data_initialization_validation",
                        "nodeType", "code",
                        "subtaskId", "root",
                        // DONE 仍维持 running，由紧随其后的持久化 Trace 事件原位收口；
                        // 若这里先置 success，前端会把同一节点误建成“进度节点 + 结果节点”两条。
                        "status", "running",
                        "phase", progress.phase(),
                        "completed", progress.completed(),
                        "total", progress.total(),
                        "message", "数据初始化校验 · " + progress.message()
                                + (progress.total() > 0 && progress.completed() > 0
                                    ? "（" + progress.completed() + "/" + progress.total() + "）" : ""))));
        String validationStatus = "ALL_BLOCKED".equals(initialization.qualityStatus())
                ? "failed"
                : "NORMAL".equals(initialization.qualityStatus()) ? "success" : "warning";
        emitTrace(observer, traceId, "batch_data_initialization_validation",
                validationStatus, validationStarted, "root",
                Map.of("indicatorCount", indicators.size(),
                        "profileCount", executionTargets.size(),
                        "statStart", statStart,
                        "statEnd", statEnd),
                initialization.toTraceOutput());
        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId(), true);
        List<IndicatorExecutionResult> results =
                executeAll(
                        observer, traceId, requestId, context, executionTargets,
                        spec.timeText(), statStart, statEnd, batchRunId, initialization,
                        request.principal().hospitalId());

        long mergeStarted = System.currentTimeMillis();
        String answer = aggregator.aggregate(
                results, indicators.size(), statStart, statEnd);

        long succeeded = count(results, Status.SUCCESS);
        long noSample = count(results, Status.NO_SAMPLE);
        long failed = count(results, Status.FAILED);
        emitTrace(observer, traceId, "batch_result_merge", "success", mergeStarted,
                "root", Map.of("indicatorCount", results.size()), Map.of(
                        "succeeded", succeeded,
                        "noSample", noSample,
                        "failed", failed));
        finishJob(batchRunId, results, traceId, observer);
        boolean anyOk = succeeded + noSample > 0;
        String stopReason = anyOk ? "final_answer" : "compound_failed";

        emit(observer, "assistant_message", traceId, results.size(), withBatchRunId(Map.of(
                "message", answer,
                "status", anyOk ? "completed" : "failed",
                "batch", true,
                "succeeded", succeeded,
                "noSample", noSample,
                "failed", failed), batchRunId));
        emit(observer, "agent_done", traceId, results.size(), withBatchRunId(Map.of(
                "stopReason", stopReason,
                "status", anyOk ? "completed" : "incomplete",
                "stepCount", results.size(),
                "subtaskCount", executionTargets.size()), batchRunId));

        AgentRunState memoryState = new AgentRunState();
        memoryState.lastIntent("batch");
        memoryState.lastRuleName(indicators.stream()
                .map(indicator -> indicator.get("ruleName"))
                .collect(Collectors.joining("、")));
        memoryState.statPeriod(statStart, statEnd);
        // 持久化逐指标卡片载荷（与 SSE batch_indicator_result 同形态），
        // 切换会话后前端可从消息历史直接恢复卡片展示。
        List<Map<String, Object>> cardPayloads = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            IndicatorExecutionResult result = results.get(index);
            cardPayloads.add(indicatorResultPayload(
                    index + 1, results.size(), result, batchRunId,
                    qualityStatus(initialization, result)));
        }
        conversations.appendAssistant(
                conversation, request.principal(), answer, memoryState, cardPayloads);
        conversations.rememberCompoundTargets(
                conversation,
                indicators.stream().map(value -> value.get("ruleName")).toList());
        conversations.rememberQueryScope(conversation, new QueryScopeState(
                "indicator_trial_run",
                targetMode(spec),
                indicators.stream()
                        .map(value -> new QueryTarget(
                                value.get("ruleId"), value.get("ruleName")))
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
            String statEnd,
            String batchRunId,
            InitializationValidationReport initialization,
            String hospitalId) {
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
                int position = index;
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
                        ProfileValidation validation = initialization.decision(target.profileId());
                        if (validation != null && validation.decision() == Decision.BLOCKED) {
                            result = IndicatorExecutionResult.failed(
                                    ruleId, ruleName,
                                    target.profileId(), target.profileLabel(), target.eventNo(),
                                    validation.errorCode(), validation.message());
                        } else if (validation != null
                                && validation.decision() == Decision.SKIPPED) {
                            result = IndicatorExecutionResult.failed(
                                    ruleId, ruleName,
                                    target.profileId(), target.profileLabel(), target.eventNo(),
                                    validation.errorCode(), validation.message());
                        } else if (validation != null
                                && validation.decision() == Decision.NO_SAMPLE) {
                            result = IndicatorExecutionResult.noSample(
                                    ruleId, ruleName,
                                    target.profileId(), target.profileLabel(), target.eventNo(),
                                    validation.errorCode(), validation.message(), statStart, statEnd);
                        } else if (viaMras) {
                            result = executeViaMras(
                                    ruleId, ruleName, target, statStart, statEnd,
                                    observer, traceId, subtaskId,
                                    validation, hospitalId);
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
                    String qualityStatus = qualityStatus(initialization, result);
                    recordTask(batchRunId, position, result, qualityStatus);
                    Map<String, Object> traceOutput = new LinkedHashMap<>();
                    traceOutput.put("status", result.status().name());
                    if (result.runId() != null) {
                        traceOutput.put("runId", result.runId());
                    }
                    if (result.errorCode() != null) {
                        traceOutput.put("errorCode", result.errorCode());
                    }
                    if (result.errorMessage() != null) {
                        traceOutput.put("errorMessage", result.errorMessage());
                    }
                    if (result.extractionId() != null) {
                        traceOutput.put("extractionId", result.extractionId());
                    }
                    if (result.extractionStatus() != null) {
                        traceOutput.put("snapshotStatus", result.extractionStatus());
                    }
                    traceOutput.put("qualityStatus", qualityStatus);
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
                            taskDisplay(target), result);
                    emitIndicatorResult(
                            observer, traceId, completed.get(), total, result, batchRunId,
                            qualityStatus);
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
                                text(profile.get("profileId"))))
                        .toList();
            }
            for (Map<String, Object> profile : approved) {
                String profileId = text(profile.get("profileId"));
                Map<String, Object> effective =
                        rules.effectiveRule(indicator.ruleId(), hospitalId, profileId);
                String profileLabel = first(
                        indicator.profileLabel(),
                        first(
                                text(effective.get("profileName")),
                                first(text(profile.get("label")), profileId)));
                Map<String, Object> extraction =
                        objectMap(effective.get("extractionContract"));
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
     * 通过知识库 MrasSqlExecutionService 执行概览查询，转换为批量结果。
     * 抽取与 SQL 执行分别发出独立计时的 trace 节点，耗时归属不再全部算在
     * “完成单项指标计算”一个节点上。
     */
    private IndicatorExecutionResult executeViaMras(
            String ruleId, String ruleName,
            ProfileExecutionTarget target,
            String statStart, String statEnd,
            AgentRunObserver observer, String traceId, String subtaskId,
            ProfileValidation validation, String hospitalId) {
        long started = System.currentTimeMillis();
        LocalDateTime start = LocalDateTime.parse(statStart, TIME_FORMAT);
        LocalDateTime end = LocalDateTime.parse(statEnd, TIME_FORMAT);

        boolean directReal = validation != null
                && "DIRECT_REAL_QUERY".equals(validation.executionType());
        if (directReal) {
            Map<String, Object> directOutput = new LinkedHashMap<>();
            directOutput.put("ruleId", ruleId);
            directOutput.put("profileId", target.profileId());
            directOutput.put("profileLabel", target.profileLabel());
            directOutput.put("matched", true);
            directOutput.put("validationMode", "DIRECT_REAL_QUERY");
            directOutput.put("message", "本口径直接查询真实库已有表，无独立抽取快照需要核对。");
            emitTrace(observer, traceId, "real_snapshot_data_validation",
                    "success", System.currentTimeMillis(), subtaskId,
                    profileTraceInput(target), Map.copyOf(directOutput));
        } else {
            ToolResult extraction = mrasExecution.prepareExtraction(
                    ruleId, target.profileId(), hospitalId, start, end);
            emitExtractionTrace(observer, traceId, subtaskId, target, extraction);
            if (!extraction.ok()) {
                return IndicatorExecutionResult.failed(
                        ruleId, ruleName,
                        target.profileId(), target.profileLabel(), target.eventNo(),
                        extraction.code(), extraction.summary());
            }

            long snapshotStarted = System.currentTimeMillis();
            RealSnapshotValidation snapshot = initializationValidator.validateRealSnapshot(
                    validationTarget(target),
                    validation == null ? null : validation.businessSourceCount(),
                    hospitalId, start, end);
            emitTrace(observer, traceId, "real_snapshot_data_validation",
                    snapshot.ok() ? "success" : "failed", snapshotStarted, subtaskId,
                    profileTraceInput(target), snapshot.output());
            if (!snapshot.ok()) {
                return IndicatorExecutionResult.failed(
                        ruleId, ruleName,
                        target.profileId(), target.profileLabel(), target.eventNo(),
                        snapshot.errorCode(), snapshot.message());
            }
        }

        ToolResult toolResult = mrasExecution.executeOverview(
                ruleId, target.profileId(), hospitalId, start, end, null, null);
        long durationMs = System.currentTimeMillis() - started;
        emitMrasOverviewTrace(observer, traceId, subtaskId, target, toolResult);

        if (!toolResult.ok()) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName,
                    target.profileId(), target.profileLabel(), target.eventNo(),
                    toolResult.code(), toolResult.summary());
        }

        Map<String, Object> data = toolResult.data();
        // 抽取失败时结果基于中间表旧数据，用 dataFreshness 标记并透传到前端
        String dataFreshness = data.get("extractionWarning") instanceof String
                ? "extraction_failed_stale" : null;
        // 单位由 MRAS 服务按概念页计量单位确定（百分比类为 percentage，数值类为空），
        // 不能在这里硬编码 percentage，否则分钟数等数值型结果会被前端误加 % 后缀
        String unit = data.get("unit") instanceof String s ? s : "percentage";
        Boolean noSample = (Boolean) data.get("noSample");
        // 目标值/达标方向来自 TargetValue CTE 与实体页“指标导向”，与样本无关，
        // 无样本卡片也一并透传，保持展示信息一致
        Object targetValue = data.get("targetValue");
        // 达标判定方向由 MRAS 服务按实体页“指标导向”解析（逐步降低→"<"），
        // 缺失时聚合层按 ">=" 默认判定
        String targetDirection = data.get("targetDirection") instanceof String d ? d : null;
        String resultDisplay = data.get("resultDisplay") instanceof String display
                ? display : null;
        if (Boolean.TRUE.equals(noSample)) {
            return new IndicatorExecutionResult(
                    ruleId, ruleName, Status.NO_SAMPLE, null, 0L, 0L,
                    null, unit, resultDisplay, 0L, targetValue, targetDirection,
                    statStart, statEnd, "mras-" + ruleId, null, null, durationMs,
                    dataFreshness, target.profileId(), target.profileLabel(), null, null,
                    target.eventNo(), text(data.get("overviewSqlHash")),
                    text(data.get("detailKind")), text(data.get("detailContractVersion")));
        }

        Number resultValue = (Number) data.get("resultValue");
        Long numerator = data.get("numeratorCount") instanceof Number n
                ? n.longValue() : null;
        Long denominator = data.get("denominatorCount") instanceof Number n
                ? n.longValue() : null;

        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.SUCCESS,
                resultValue != null ? resultValue.doubleValue() : null,
                numerator, denominator,
                null, unit, resultDisplay,
                denominator, targetValue, targetDirection,
                statStart, statEnd, "mras-" + ruleId, null, null, durationMs,
                dataFreshness, target.profileId(), target.profileLabel(), null, null,
                target.eventNo(), text(data.get("overviewSqlHash")),
                text(data.get("detailKind")), text(data.get("detailContractVersion")));
    }

    private static Map<String, Object> profileTraceInput(
            ProfileExecutionTarget target) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ruleId", target.ruleId());
        values.put("ruleName", target.ruleName());
        if (target.profileId() != null) {
            values.put("profileId", target.profileId());
        }
        if (target.profileLabel() != null) {
            values.put("profileLabel", target.profileLabel());
        }
        if (target.eventNo() != null && !target.eventNo().isBlank()) {
            values.put("eventNo", target.eventNo());
        }
        return Map.copyOf(values);
    }

    private static ValidationTarget validationTarget(ProfileExecutionTarget target) {
        return new ValidationTarget(
                target.ruleId(), target.ruleName(), target.profileId(), target.profileLabel());
    }

    private static String taskDisplay(ProfileExecutionTarget target) {
        return target.ruleName() + " / " + target.profileLabel();
    }

    /**
     * 把 MRAS 执行结果里的阶段耗时（抽取 / 真实库 SQL 执行）各自发为独立 trace 节点，
     * 时长取自 MrasSqlExecutionService 的实测值，不再是 0ms 占位。
     */
    private static void emitExtractionTrace(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ProfileExecutionTarget target,
            ToolResult toolResult) {
        Map<String, Object> data = toolResult.data() == null ? Map.of() : toolResult.data();
        long now = System.currentTimeMillis();
        long duration = data.get("extractionDurationMs") instanceof Number extraction
                ? Math.max(0, extraction.longValue()) : 0L;
        emitTrace(observer, traceId, "source_data_extraction",
                toolResult.ok() ? "success" : "failed",
                now - duration, subtaskId,
                profileTraceInput(target),
                toolResult.ok()
                        ? Map.of("status", "extracted", "sourceId", "winex_aima",
                                "targetTable", data.getOrDefault("targetTable", ""))
                        : Map.of("errorCode", toolResult.code(),
                                "message", toolResult.summary()));
    }

    private static void emitMrasOverviewTrace(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ProfileExecutionTarget target,
            ToolResult toolResult) {
        Map<String, Object> data = toolResult.data() == null ? Map.of() : toolResult.data();
        long now = System.currentTimeMillis();
        if (toolResult.ok() && data.get("durationMs") instanceof Number query) {
            emitTrace(observer, traceId, "real_database_overview", "success",
                    now - Math.max(0, query.longValue()), subtaskId,
                    profileTraceInput(target),
                    Map.of("sourceId", "winex_aima",
                            "rowCount", data.getOrDefault("rowCount", 0)));
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
                        ? Map.of("errorCode", result.errorCode())
                        : Map.of("status", "prepared"));
        if (result.extractionId() != null) {
            emitTrace(
                    observer, traceId, "source_data_extraction", "success",
                    now, subtaskId, profileTraceInput(target),
                    Map.of(
                            "extractionId", result.extractionId(),
                            "snapshotStatus", first(
                                    result.extractionStatus(), "SUCCESS")));
        } else if (result.errorCode() != null
                && result.errorCode().startsWith("SOURCE_EXTRACTION")) {
            emitTrace(
                    observer, traceId, "source_data_extraction", "failed",
                    now, subtaskId, profileTraceInput(target),
                    Map.of("errorCode", result.errorCode()));
        }
        if (result.runId() != null) {
            emitTrace(
                    observer, traceId, "real_database_overview", "success",
                    now, subtaskId, profileTraceInput(target),
                    Map.of("runId", result.runId(), "sourceId", "winex_aima"));
        }
    }

    /** 在批量执行前建立真实批次运行标识，供逐指标结果和后续明细绑定。 */
    private String beginJob(
            ConversationSnapshot conversation,
            AgentRunRequest request,
            String traceId,
            int total,
            String statStart,
            String statEnd,
            AgentRunObserver observer) {
        long started = System.currentTimeMillis();
        try {
            String knowledgeReleaseId = rules.knowledgeReleaseId(
                    request.principal().hospitalId());
            String jobId = knowledgeReleaseId == null || knowledgeReleaseId.isBlank()
                    ? jobStore.createJob(
                            conversation.storageKey(), request.principal().hospitalId(),
                            request.principal().userId(), request.query(), total,
                            statStart, statEnd, traceId)
                    : jobStore.createJob(
                            conversation.storageKey(), request.principal().hospitalId(),
                            request.principal().userId(), request.query(), total,
                            statStart, statEnd, traceId, knowledgeReleaseId);
            Map<String, Object> startOutput = new LinkedHashMap<>();
            startOutput.put("batchRunId", jobId);
            startOutput.put("status", "RUNNING");
            if (knowledgeReleaseId != null && !knowledgeReleaseId.isBlank()) {
                startOutput.put("knowledgeReleaseId", knowledgeReleaseId);
            }
            emitTrace(observer, traceId, "batch_job_start", "success", started,
                    "root", Map.of("taskCount", total),
                    Map.copyOf(startOutput));
            return jobId;
        } catch (RuntimeException exception) {
            LOGGER.warn("批量作业启动持久化失败，结果仍返回但明细不可绑定：{}", exception.getMessage());
            emitTrace(observer, traceId, "batch_job_start", "failed", started,
                    "root", Map.of("taskCount", total),
                    Map.of("errorCode", "BATCH_JOB_PERSIST_FAILED"));
            return null;
        }
    }

    private void recordTask(
            String batchRunId, int position, IndicatorExecutionResult result,
            String qualityStatus) {
        if (batchRunId == null) {
            return;
        }
        try {
            jobStore.recordTask(batchRunId, position, result, qualityStatus);
        } catch (RuntimeException exception) {
            LOGGER.warn("批次 {} 第 {} 项持久化失败：{}",
                    batchRunId, position + 1, exception.getMessage());
        }
    }

    private void finishJob(
            String batchRunId,
            List<IndicatorExecutionResult> results,
            String traceId,
            AgentRunObserver observer) {
        if (batchRunId == null) {
            return;
        }
        long started = System.currentTimeMillis();
        long succeeded = count(results, Status.SUCCESS);
        long noSample = count(results, Status.NO_SAMPLE);
        long failed = count(results, Status.FAILED);
        String status = failed == 0
                ? "COMPLETED"
                : succeeded + noSample == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        try {
            jobStore.finishJob(
                    batchRunId, status, (int) succeeded, (int) noSample, (int) failed);
            emitTrace(observer, traceId, "batch_job_finish", "success", started,
                    "root", Map.of("taskCount", results.size()),
                    Map.of("batchRunId", batchRunId, "status", status));
        } catch (RuntimeException exception) {
            LOGGER.warn("批次 {} 收尾持久化失败：{}", batchRunId, exception.getMessage());
            emitTrace(observer, traceId, "batch_job_finish", "failed", started,
                    "root", Map.of("taskCount", results.size()),
                    Map.of("batchRunId", batchRunId,
                            "errorCode", "BATCH_JOB_FINISH_FAILED"));
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
                "status", "running", "sessionId", conversation.sessionId(),
                "batch", true, "runtimeVersion", VERSION));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", message,
                "code", "TIME_RANGE_AMBIGUOUS",
                "fallbackCategory", "USER_CLARIFICATION",
                "clarification", clarification,
                "stopReason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stopReason", "clarification", "status", "incomplete", "stepCount", 0));
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
                "status", "running", "sessionId", conversation.sessionId(),
                "batch", true, "subtaskCount", 0, "runtimeVersion", VERSION));
        emit(observer, "assistant_message", traceId, 0, Map.of(
                "message", message, "status", "completed", "batch", true));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stopReason", "final_answer", "status", "completed", "stepCount", 0));
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
        LocalDate today = LocalDate.now();
        YearMonth previousMonth = YearMonth.from(today).minusMonths(1);
        List<AgentClarification.Option> options = List.of(
                new AgentClarification.Option(
                        "year-to-date", "今年至今",
                        LocalDate.of(today.getYear(), 1, 1) + " 至 " + today,
                        "从今年1月1日统计到今天", "time"),
                new AgentClarification.Option(
                        "current-month", "本月",
                        YearMonth.from(today).atDay(1) + " 至 " + today,
                        "从本月1日统计到今天", "time"),
                new AgentClarification.Option(
                        "previous-month", "上个月",
                        previousMonth.atDay(1) + " 至 " + previousMonth.atEndOfMonth(),
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
            IndicatorExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "batch_indicator");
        payload.put("nodeType", "code");
        payload.put("message", result.ok()
                ? "完成指标计算（" + done + "/" + total + "）：" + ruleName
                : "指标未计算（" + done + "/" + total + "）：" + ruleName);
        payload.put("status", result.ok() ? "success" : "failed");
        if (result.errorCode() != null) payload.put("errorCode", result.errorCode());
        if (result.errorMessage() != null) payload.put("errorMessage", result.errorMessage());
        emit(observer, "stage_update", traceId, done, Map.copyOf(payload));
    }

    /**
     * 每完成一个指标立即通过 SSE 推送该指标结果，前端可逐条渲染而不等全部完成。
     */
    private static void emitIndicatorResult(
            AgentRunObserver observer,
            String traceId,
            int done,
            int total,
            IndicatorExecutionResult result,
            String batchRunId,
            String qualityStatus) {
        emit(observer, "batch_indicator_result", traceId, done,
                indicatorResultPayload(done, total, result, batchRunId, qualityStatus));
    }

    /**
     * 单指标结果载荷：SSE 逐条推送与会话消息持久化共用同一形态，
     * 保证切换会话后恢复的卡片字段与实时推送完全一致。
     */
    static Map<String, Object> indicatorResultPayload(
            int done,
            int total,
            IndicatorExecutionResult result) {
        return indicatorResultPayload(done, total, result, null);
    }

    static Map<String, Object> indicatorResultPayload(
            int done,
            int total,
            IndicatorExecutionResult result,
            String batchRunId) {
        return indicatorResultPayload(done, total, result, batchRunId,
                result.status() == Status.SUCCESS ? "NORMAL" : "ABNORMAL");
    }

    static Map<String, Object> indicatorResultPayload(
            int done,
            int total,
            IndicatorExecutionResult result,
            String batchRunId,
            String qualityStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", result.ruleId());
        payload.put("ruleName", result.ruleName());
        payload.put("status", result.status().name());
        payload.put("done", done);
        payload.put("total", total);
        if (batchRunId != null && !batchRunId.isBlank()) {
            payload.put("batchRunId", batchRunId);
        }
        // 同一指标多口径时，前端靠 profile_id 去重、靠 profile_label 区分卡片，
        // 缺失时多张卡片会完全同形。
        if (result.profileId() != null) {
            payload.put("profileId", result.profileId());
        }
        if (result.profileLabel() != null) {
            payload.put("profileLabel", result.profileLabel());
        }
        if (result.resultValue() != null) {
            payload.put("resultValue", result.resultValue());
        }
        if (result.numerator() != null) {
            payload.put("numeratorCount", result.numerator());
        }
        if (result.denominator() != null) {
            payload.put("denominatorCount", result.denominator());
        }
        if (result.sampleCount() != null) {
            payload.put("sampleCount", result.sampleCount());
        }
        if (result.unit() != null) {
            payload.put("unit", result.unit());
        }
        // 目标值与达标方向随卡片透传，前端/回放脚本才能展示达标判定依据
        if (result.targetValue() != null) {
            payload.put("targetValue", result.targetValue());
        }
        if (result.targetDirection() != null) {
            payload.put("targetDirection", result.targetDirection());
        }
        if (result.calculationDisplay() != null) {
            payload.put("calculationDisplay", result.calculationDisplay());
        }
        if (result.statStart() != null) {
            payload.put("statStart", result.statStart());
        }
        if (result.statEnd() != null) {
            payload.put("statEnd", result.statEnd());
        }
        if (result.runId() != null) {
            payload.put("runId", result.runId());
        }
        if (result.dataFreshness() != null) {
            payload.put("dataFreshness", result.dataFreshness());
        }
        payload.put("qualityStatus", qualityStatus);
        if (result.errorCode() != null) {
            payload.put("errorCode", result.errorCode());
        }
        if (result.errorMessage() != null) {
            payload.put("errorMessage", result.errorMessage());
        }
        if (result.overviewSqlHash() != null) {
            payload.put("overviewSqlHash", result.overviewSqlHash());
        }
        if (result.detailKind() != null) {
            payload.put("detailKind", result.detailKind());
        }
        if (result.detailContractVersion() != null) {
            payload.put("detailContractVersion", result.detailContractVersion());
        }
        return payload;
    }

    /**
     * 数据质量只依据确定性事实分级。无法完成检查不等于数据异常，纯展示字段空值也
     * 不改变分子分母；已确认影响计算的数据问题，以及无法形成可信结果的执行状态，
     * 统一归入异常。未实现属于口径配置状态，不属于数据质量异常；POSSIBLE
     * 只表示需要人工关注，在没有确定证据时不能把整个指标判为异常。
     */
    private static String qualityStatus(
            InitializationValidationReport initialization,
            IndicatorExecutionResult result) {
        if ("PROFILE_NOT_IMPLEMENTED".equals(result.errorCode())) {
            return "NORMAL";
        }
        if (result.status() != Status.SUCCESS
                || "extraction_failed_stale".equals(result.dataFreshness())) {
            return "ABNORMAL";
        }
        boolean dataProblem = initialization.items().stream()
                .filter(item -> result.ruleId().equals(item.ruleId()))
                .filter(item -> Objects.equals(result.profileId(), item.profileId()))
                .anyMatch(item -> "CONFIRMED".equals(item.impactLevel()));
        return dataProblem ? "ABNORMAL" : "NORMAL";
    }

    private static Map<String, Object> withBatchRunId(
            Map<String, Object> values, String batchRunId) {
        if (batchRunId == null || batchRunId.isBlank()) {
            return values;
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("batchRunId", batchRunId);
        return result;
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
        event.put("traceId", traceId);
        event.put("nodeId", id("NODE_"));
        event.put("nodeName", nodeName);
        event.put("nodeType", "code");
        event.put("status", status);
        event.put("startedAtEpochMs", startedAt);
        event.put("endedAtEpochMs", endedAt);
        event.put("durationMs", Math.max(0, endedAt - startedAt));
        event.put("subtaskId", subtaskId);
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
        payload.put("traceId", traceId);
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
