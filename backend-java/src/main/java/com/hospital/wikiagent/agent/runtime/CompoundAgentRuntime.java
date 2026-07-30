package com.hospital.wikiagent.agent.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.hospital.wikiagent.agent.batch.BatchIndicatorRuntime;
import com.hospital.wikiagent.agent.batch.BatchIntentVerifier;
import com.hospital.wikiagent.agent.batch.BatchRequestDetector;
import com.hospital.wikiagent.agent.batch.BatchRequestSpec;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryTarget;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlSynthesizer;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlSynthesizer.DetailSqlPair;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.SplitResult;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.SubtaskSpec;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.RequestKind;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.contract.AgentClarification;

import jakarta.annotation.PreDestroy;

/**
 * 多指标 fan-out/fan-in 外层；单指标执行仍由 AgentRunner 负责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class CompoundAgentRuntime {
    public static final String VERSION = "compound-runtime-v2";
    private static final Pattern POSITIONAL_REPLACEMENT = Pattern.compile(
            "第\\s*([一二三四五六七八九十\\d]+)\\s*个.*(?:换成|改成|替换为)");
    private static final Pattern POSITIONAL_REFERENCE = Pattern.compile(
            "第\\s*([一二三四五六七八九十\\d]+)\\s*个");
    private static final Pattern DETAIL_REQUEST = Pattern.compile(
            "(分母|分子|患者|病人|明细|详情|名单|列表).*(明细|详情|名单|列表|给我|看看|展示)"
                    + "|(明细|详情|名单|列表).*(分母|分子|患者|病人)"
                    + "|^(分母|分子|患者)?(明细|详情|名单|列表)$");

    private final AgentRunner runner;
    private final CompoundRequestSplitter splitter;
    private final AgentModelRegistry models;
    private final AgentModelProperties properties;
    private final AgentConversationMemory conversations;
    private final HybridIndicatorResolver indicatorResolver;
    private final ClarificationPromptFactory clarificationPrompts;
    private final BatchRequestDetector batchDetector;
    private final BatchIndicatorRuntime batchRuntime;
    private final BatchIntentVerifier batchIntentVerifier;
    private final MrasSqlExecutionService mrasExecution;
    private final MrasDetailSqlSynthesizer detailSynthesizer;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "java-agent-compound");
        thread.setDaemon(true);
        return thread;
    });

    public CompoundAgentRuntime(
            AgentRunner runner,
            CompoundRequestSplitter splitter,
            AgentModelRegistry models,
            AgentModelProperties properties,
            AgentConversationMemory conversations) {
        this(runner, splitter, models, properties, conversations, null, null, null, null, null, null, null);
    }

    public CompoundAgentRuntime(
            AgentRunner runner,
            CompoundRequestSplitter splitter,
            AgentModelRegistry models,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            HybridIndicatorResolver indicatorResolver) {
        this(runner, splitter, models, properties, conversations, indicatorResolver, null, null, null, null, null, null);
    }

    public CompoundAgentRuntime(
            AgentRunner runner,
            CompoundRequestSplitter splitter,
            AgentModelRegistry models,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            HybridIndicatorResolver indicatorResolver,
            ClarificationPromptFactory clarificationPrompts) {
        this(runner, splitter, models, properties, conversations, indicatorResolver,
                clarificationPrompts, null, null, null, null, null);
    }

    @Autowired
    public CompoundAgentRuntime(
            AgentRunner runner,
            CompoundRequestSplitter splitter,
            AgentModelRegistry models,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            HybridIndicatorResolver indicatorResolver,
            ClarificationPromptFactory clarificationPrompts,
            BatchRequestDetector batchDetector,
            BatchIndicatorRuntime batchRuntime,
            BatchIntentVerifier batchIntentVerifier,
            MrasSqlExecutionService mrasExecution,
            MrasDetailSqlSynthesizer detailSynthesizer) {
        this.runner = runner;
        this.splitter = splitter;
        this.models = models;
        this.properties = properties;
        this.conversations = conversations;
        this.indicatorResolver = indicatorResolver;
        this.clarificationPrompts = clarificationPrompts;
        this.batchDetector = batchDetector;
        this.batchRuntime = batchRuntime;
        this.batchIntentVerifier = batchIntentVerifier;
        this.mrasExecution = mrasExecution;
        this.detailSynthesizer = detailSynthesizer;
    }

    public AgentRunResult run(AgentRunRequest request) {
        return run(request, AgentRunObserver.noop());
    }

    public AgentRunResult run(AgentRunRequest request, AgentRunObserver observer) {
        var conversation = conversations.open(request.principal(), request.sessionId());
        // “计算所有指标结果”走确定性批量路径：意图由正则检测器确定，指标身份可枚举、
        // 时间来自父请求，全程 0 次 LLM 调用。未挂载批量组件时回退原有复合路径。
        if (batchDetector != null && batchRuntime != null) {
            BatchRequestSpec batchSpec = batchDetector.detect(
                    request.query(), conversation.queryScope(),
                    request.principal().hospitalId());
            // LLM 意图兜底：正则结果与会话状态存在歧义时让模型确认“全部还是这几个”
            if (batchIntentVerifier != null) {
                batchSpec = batchIntentVerifier.verify(
                        batchSpec, conversation.queryScope(), request.query());
            }
            if (batchSpec.batch()) {
                return batchRuntime.run(request, observer, batchSpec);
            }
            if ((conversation.queryScope() == null
                        || !conversation.queryScope().valid())
                    && batchDetector.isBareAllScope(request.query())) {
                return allIndicatorIntentClarification(
                        request, observer, conversation);
            }
            if ((conversation.queryScope() == null
                        || !conversation.queryScope().valid())
                    && batchDetector.isTimeOnlyChange(request.query())) {
                return missingOperationAndIndicatorClarification(
                        request, observer, conversation);
            }
        }
        // 明细查询拦截：用户请求“分母明细”“患者明细”等，直接走知识库 patientDetailSql
        if (mrasExecution != null && isDetailRequest(request.query())) {
            AgentRunResult detailResult = tryDetailView(request, observer, conversation);
            if (detailResult != null) {
                return detailResult;
            }
        }
        long splitStarted = TraceEvents.started();
        HybridIndicatorResolver.Resolution resolution = indicatorResolver == null
                ? HybridIndicatorResolver.Resolution.empty()
                : indicatorResolver.resolve(
                        request.query(), request.principal().hospitalId(), request.modelId(),
                        request.traceId(), "root", observer);
        if (resolution.needsClarification()) {
            return clarification(request, observer, conversation, resolution);
        }
        List<HybridIndicatorResolver.ResolvedIndicator> resolvedIndicators =
                conversationAwareIndicators(
                        request, observer, conversation, resolution.indicators());
        if ((conversation.queryScope() == null
                    || !conversation.queryScope().valid())
                && resolvedIndicators.size() == 1
                && isBareIndicatorQuery(
                        request.query(), resolvedIndicators.get(0).canonicalName())) {
            return indicatorIntentClarification(
                    request, observer, conversation,
                    resolvedIndicators.get(0).canonicalName());
        }
        if (resolvedIndicators.isEmpty()
                && batchDetector != null
                && batchDetector.isAllScope(request.query())
                && !batchDetector.isTrialRunRequest(request.query())) {
            resolvedIndicators = batchDetector.allActiveTargets(
                            request.principal().hospitalId()).stream()
                    .map(value -> new HybridIndicatorResolver.ResolvedIndicator(
                            value.ruleName(), value.ruleName(), value.ruleId(),
                            "RULE:" + value.ruleId(), "all_active_scope", 1.0, 0, 0))
                    .toList();
        }
        boolean continuesCalculation = batchDetector != null
                && (batchDetector.isTrialRunRequest(request.query())
                    || conversation.queryScope() != null
                        && conversation.queryScope().valid()
                        && "indicator_trial_run".equals(
                                conversation.queryScope().operation()));
        BatchRequestSpec.Target explicitSingleProfile =
                batchDetector == null || resolvedIndicators.size() != 1
                        ? null
                        : batchDetector.explicitProfileTarget(
                                request.query(),
                                resolvedIndicators.get(0).ruleId(),
                                resolvedIndicators.get(0).canonicalName(),
                                request.principal().hospitalId());
        if (batchDetector != null && batchRuntime != null
                && !resolvedIndicators.isEmpty()
                && continuesCalculation) {
            List<BatchRequestSpec.Target> targets =
                    explicitSingleProfile != null
                            ? List.of(explicitSingleProfile)
                            : resolvedIndicators.stream()
                                    .map(value -> new BatchRequestSpec.Target(
                                            value.ruleId(), value.canonicalName()))
                                    .toList();
            return batchRuntime.run(
                    request, observer,
                    BatchRequestSpec.selected(
                            request.query(),
                            batchDetector.timeTextFor(
                                    request.query(), conversation.queryScope()),
                            targets));
        }
        if (resolution.indicators().isEmpty()
                && batchDetector != null
                && conversation.queryScope() != null
                && conversation.queryScope().valid()
                && "SINGLE".equals(conversation.queryScope().targetMode())
                && batchDetector.isTimeOnlyChange(request.query())
                && !conversation.queryScope().targets().isEmpty()) {
            QueryTarget target = conversation.queryScope().targets().get(0);
            var remembered = new HybridIndicatorResolver.ResolvedIndicator(
                    target.ruleName(), target.ruleName(), target.ruleId(),
                    "RULE:" + target.ruleId(), "conversation_scope", 1.0, 0, 0);
            AgentRunResult result = runner.run(request, observer, remembered);
            rememberSingleScope(request, target.ruleId(), target.ruleName(), result);
            return result;
        }
        SplitResult split = splitter.split(
                request.query(), conversation.recentHistory(), request.principal().hospitalId(),
                resolvedIndicators, conversation.compoundTargets());
        if (split.compound()
                && split.kind() == RequestKind.TRIAL_RUN
                && batchDetector != null
                && batchRuntime != null) {
            List<HybridIndicatorResolver.ResolvedIndicator> selected =
                    split.tasks().stream().allMatch(task -> task.resolvedIndicator() != null)
                    ? split.tasks().stream().map(SubtaskSpec::resolvedIndicator).toList()
                    : resolveNames(
                            request, observer,
                            split.tasks().stream().map(SubtaskSpec::target).toList());
            if (selected.size() == split.tasks().size()) {
                List<BatchRequestSpec.Target> targets = selected.stream()
                        .map(value -> new BatchRequestSpec.Target(
                                value.ruleId(), value.canonicalName()))
                        .toList();
                return batchRuntime.run(
                        request, observer,
                        BatchRequestSpec.selected(
                                request.query(),
                                batchDetector.timeTextFor(
                                        request.query(), conversation.queryScope()),
                                targets));
            }
        }
        if (split.compound()
                && batchDetector != null
                && batchDetector.isBareAllScope(request.query())
                && conversation.queryScope() != null
                && conversation.queryScope().valid()
                && !"indicator_trial_run".equals(conversation.queryScope().operation())) {
            split = withKind(split, requestKind(conversation.queryScope().operation()));
        }
        if (split.compound()
                && split.commonTimeExpression() == null
                && (split.kind() == CompoundRequestSplitter.RequestKind.TRIAL_RUN
                    || split.kind() == CompoundRequestSplitter.RequestKind.DIAGNOSIS)
                && conversation.queryScope() != null
                && conversation.queryScope().statStart() != null
                && conversation.queryScope().statEnd() != null) {
            String inheritedPeriod = conversation.queryScope().statStart().replace(' ', 'T')
                    + "至" + conversation.queryScope().statEnd().replace(' ', 'T');
            List<SubtaskSpec> inheritedTasks = split.tasks().stream()
                    .map(task -> new SubtaskSpec(
                            task.index(), task.target(),
                            task.query() + "，统计周期" + inheritedPeriod,
                            task.resolvedIndicator()))
                    .toList();
            split = new SplitResult(
                    inheritedTasks, split.kind(), inheritedPeriod,
                    split.serialRequired(), split.followup());
        }
        /*
         * 从结构化会话恢复的复合目标最初只有正式名称，没有 ResolvedIndicator。
         * 所有意图在 fan-out 前都必须重新绑定 rule_id；不能只给计算批量绑定，
         * 否则定义、SQL 和诊断子会话会失去指标身份并重新调用 Planner。
         */
        if (indicatorResolver != null
                && split.compound()
                && split.tasks().stream()
                        .anyMatch(task -> task.resolvedIndicator() == null)) {
            List<HybridIndicatorResolver.ResolvedIndicator> rebound = resolveNames(
                    request, observer,
                    split.tasks().stream().map(SubtaskSpec::target).toList());
            if (rebound.size() == split.tasks().size()) {
                List<SubtaskSpec> boundTasks = new ArrayList<>();
                for (int index = 0; index < split.tasks().size(); index++) {
                    SubtaskSpec task = split.tasks().get(index);
                    boundTasks.add(new SubtaskSpec(
                            task.index(), rebound.get(index).canonicalName(),
                            task.query(), rebound.get(index)));
                }
                split = new SplitResult(
                        List.copyOf(boundTasks), split.kind(),
                        split.commonTimeExpression(),
                        split.serialRequired(), split.followup());
            }
        }
        if (!split.compound()) {
            var resolved = resolvedIndicators.size() == 1
                    ? resolvedIndicators.get(0) : null;
            AgentRunResult result = runner.run(request, observer, resolved);
            if (resolved != null && result.requestPlan() != null) {
                rememberSingleScope(
                        request, resolved.ruleId(), resolved.canonicalName(), result);
            }
            return result;
        }
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        // 确认为复合后，把整批指标名写入结构态；下一轮只补时间/指代时，
        // 即使历史 ## 小节被长 SQL 挤掉，仍能恢复整批重新展开为复合。
        conversations.rememberCompoundTargets(
                conversation, split.tasks().stream().map(SubtaskSpec::target).toList());
        String traceId = first(request.traceId(), id("TRACE_"));
        String requestId = first(request.requestId(), id("REQ_"));
        TraceEvents.completed(observer, traceId, "compound_split", "code", splitStarted,
                "root", Map.of("query", request.query()), Map.of(
                        "subtaskCount", split.tasks().size(),
                        "targets", split.tasks().stream().map(SubtaskSpec::target).toList(),
                        "commonTime", split.commonTimeExpression() == null
                                ? "" : split.commonTimeExpression(),
                        "serialRequired", split.serialRequired(),
                        "splitterVersion", CompoundRequestSplitter.VERSION));
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running",
                "sessionId", conversation.sessionId(),
                "compound", true,
                "subtaskCount", split.tasks().size(),
                "splitterVersion", CompoundRequestSplitter.VERSION));

        String provider = models.requireInfo(request.modelId()).provider();
        int configured = "ollama".equals(provider)
                ? properties.getCompoundOllamaConcurrency()
                : properties.getCompoundApiConcurrency();
        int concurrency = split.serialRequired() ? 1 : Math.max(1, Math.min(4, configured));
        Semaphore semaphore = new Semaphore(concurrency);
        List<Callable<SubtaskOutcome>> callables = new ArrayList<>();
        for (SubtaskSpec task : split.tasks()) {
            callables.add(() -> executeSubtask(
                    request, conversation.recentHistory(), conversation.structuredSummary(),
                    traceId, requestId, task, semaphore, observer));
        }

        List<SubtaskOutcome> outcomes = invoke(callables, split.tasks(), timeout(properties.getCompoundTimeout()));
        outcomes.sort(java.util.Comparator.comparingInt(value -> value.task().index()));
        long mergeStarted = TraceEvents.started();
        List<String> sections = new ArrayList<>();
        int successful = 0;
        int steps = 0;
        AgentRunResult representative = null;
        for (SubtaskOutcome outcome : outcomes) {
            if (outcome.result() != null) {
                steps += outcome.result().stepCount();
                if (representative == null) {
                    representative = outcome.result();
                }
                if ("final_answer".equals(outcome.result().stopReason())) {
                    successful++;
                }
            }
            sections.add("## " + outcome.task().target() + "\n\n" + outcome.answer());
        }
        String answer = String.join("\n\n---\n\n", sections);
        String stopReason = successful > 0 ? "final_answer" : "compound_failed";
        TraceEvents.completed(observer, traceId, "compound_merge", "code", mergeStarted,
                "root", Map.of("subtaskCount", outcomes.size()), Map.of(
                        "successfulSubtasks", successful,
                        "failedSubtasks", outcomes.size() - successful,
                        "orderedTargets", outcomes.stream().map(value -> value.task().target()).toList()));
        emit(observer, "assistant_message", traceId, steps, Map.of(
                "message", answer,
                "status", successful > 0 ? "completed" : "failed",
                "compound", true,
                "successfulSubtasks", successful,
                "failedSubtasks", outcomes.size() - successful));
        emit(observer, "agent_done", traceId, steps, Map.of(
                "stopReason", stopReason,
                "status", successful > 0 ? "completed" : "incomplete",
                "stepCount", steps,
                "subtaskCount", outcomes.size()));
        AgentRunState memoryState = new AgentRunState();
        memoryState.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));
        memoryState.lastIntent("compound");
        memoryState.lastRuleName(split.tasks().stream()
                .map(SubtaskSpec::target).collect(java.util.stream.Collectors.joining("、")));
        conversations.appendAssistant(conversation, request.principal(), answer, memoryState);
        rememberCompoundScope(request, split, representative);
        return new AgentRunResult(
                answer, stopReason, traceId, conversation.sessionId(), steps,
                representative == null ? null : representative.requestPlan(),
                representative == null ? null : representative.compiledPlan());
    }

    /**
     * 处理“第三个换成…”和“最后这个指标”等结构化指代。指标身份仍由正式目录
     * Resolver 确认，不从助手自然语言回答猜名称或 ruleId。
     */
    private List<HybridIndicatorResolver.ResolvedIndicator> conversationAwareIndicators(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation,
            List<HybridIndicatorResolver.ResolvedIndicator> current) {
        if (indicatorResolver == null) {
            return current;
        }
        List<String> remembered = conversation.compoundTargets();
        Matcher replacement = POSITIONAL_REPLACEMENT.matcher(request.query());
        if (replacement.find() && current.size() == 1 && remembered.size() >= 2) {
            int index = ordinal(replacement.group(1)) - 1;
            if (index >= 0 && index < remembered.size()) {
                List<String> names = new ArrayList<>(remembered);
                names.set(index, current.get(0).canonicalName());
                return resolveNames(request, observer, names);
            }
        }
        if (current.isEmpty() && !remembered.isEmpty()) {
            int index = -1;
            Matcher reference = POSITIONAL_REFERENCE.matcher(request.query());
            if (reference.find()) {
                index = ordinal(reference.group(1)) - 1;
            } else if (request.query().contains("最后这个")
                    || request.query().contains("最后一个")) {
                index = remembered.size() - 1;
            }
            if (index >= 0 && index < remembered.size()) {
                return resolveNames(request, observer, List.of(remembered.get(index)));
            }
        }
        return current;
    }

    private List<HybridIndicatorResolver.ResolvedIndicator> resolveNames(
            AgentRunRequest request, AgentRunObserver observer, List<String> names) {
        return indicatorResolver.resolve(
                String.join("、", names),
                request.principal().hospitalId(),
                request.modelId(),
                first(request.traceId(), id("TRACE_")),
                "root",
                observer).indicators();
    }

    private static int ordinal(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> -1;
            };
        }
    }

    private SubtaskOutcome executeSubtask(
            AgentRunRequest parent,
            String parentHistory,
            String parentState,
            String parentTraceId,
            String parentRequestId,
            SubtaskSpec task,
            Semaphore semaphore,
            AgentRunObserver observer) {
        boolean acquired = false;
        long subtaskStarted = TraceEvents.started();
        String subtaskId = parentRequestId + ":subtask:" + task.index();
        String subtaskNodeId = "SUBTASK_" + Integer.toUnsignedString(subtaskId.hashCode(), 36);
        try {
            semaphore.acquire();
            acquired = true;
            AgentRunRequest child = new AgentRunRequest(
                    task.query(),
                    first(parent.sessionId(), parent.principal().sessionId()) + ":compound:"
                            + task.index() + ":" + parentRequestId,
                    parent.modelId(), parent.fileKey(), subtaskId,
                    parentTraceId + "_S" + task.index(), parent.dbSourceId(),
                    parentState + "\ncompound_subtask_id=" + subtaskId,
                    parentHistory, parent.principal());
            AgentRunObserver childObserver = event -> forwardChildEvent(
                    observer, event, parentTraceId, subtaskId, subtaskNodeId, task.index());
            AgentRunResult result = task.resolvedIndicator() == null
                    ? runner.run(child, childObserver)
                    : runner.run(child, childObserver, task.resolvedIndicator());
            TraceEvents.completed(observer, parentTraceId, "compound_subtask", "code",
                    subtaskStarted, subtaskId, Map.of(
                            "target", task.target(), "query", task.query()), Map.of(
                            "stopReason", result.stopReason(), "stepCount", result.stepCount()),
                    "nodeId", subtaskNodeId);
            return new SubtaskOutcome(task, result, result.answer());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            TraceEvents.failed(observer, parentTraceId, "compound_subtask", "code",
                    subtaskStarted, subtaskId, "SUBTASK_CANCELLED", "子任务已取消。",
                    "nodeId", subtaskNodeId);
            return SubtaskOutcome.failed(task, "该指标子任务已取消，请单独重试。");
        } catch (RuntimeException exception) {
            TraceEvents.failed(observer, parentTraceId, "compound_subtask", "code",
                    subtaskStarted, subtaskId, "SUBTASK_FAILED", exception.getMessage(),
                    "nodeId", subtaskNodeId);
            return SubtaskOutcome.failed(task, "该指标子任务执行失败，请单独重试。");
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private List<SubtaskOutcome> invoke(
            List<Callable<SubtaskOutcome>> callables,
            List<SubtaskSpec> tasks,
            long timeoutSeconds) {
        try {
            List<Future<SubtaskOutcome>> futures = executor.invokeAll(
                    callables, timeoutSeconds, TimeUnit.SECONDS);
            List<SubtaskOutcome> values = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Future<SubtaskOutcome> future = futures.get(index);
                if (future.isCancelled()) {
                    values.add(SubtaskOutcome.failed(
                            tasks.get(index), "该指标子任务执行超时，请缩小范围后单独重试。"));
                    continue;
                }
                try {
                    values.add(future.get());
                } catch (ExecutionException exception) {
                    values.add(SubtaskOutcome.failed(
                            tasks.get(index), "该指标子任务执行失败，请单独重试。"));
                }
            }
            return values;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return tasks.stream()
                    .map(task -> SubtaskOutcome.failed(task, "复合任务已取消，请重新发送问题。"))
                    .toList();
        }
    }

    private static void forwardChildEvent(
            AgentRunObserver observer,
            Map<String, Object> event,
            String parentTraceId,
            String subtaskId,
            String subtaskNodeId,
            int subtaskIndex) {
        String type = String.valueOf(event.get("event"));
        if (List.of("agent_start", "assistant_message", "agent_done", "agent_error",
                "clarification_required").contains(type)) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>(event);
        safe.put("childTraceId", event.get("traceId"));
        safe.put("traceId", parentTraceId);
        safe.put("subtaskId", subtaskId);
        safe.put("subtaskIndex", subtaskIndex);
        if ("trace_node".equals(type)) {
            safe.put("parentNodeId", subtaskNodeId);
        }
        observer.onEvent(Map.copyOf(safe));
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

    private AgentRunResult clarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation,
            HybridIndicatorResolver.Resolution resolution) {
        String traceId = first(request.traceId(), id("TRACE_"));
        List<String> groups = new ArrayList<>();
        for (var ambiguity : resolution.ambiguities()) {
            String candidates = ambiguity.candidates().stream()
                    .map(value -> value.canonicalName() + "（" + value.ruleId() + "）")
                    .distinct().reduce((left, right) -> left + "、" + right).orElse("无候选");
            groups.add("“" + ambiguity.mention() + "”可能是：" + candidates);
        }
        String answer = "我识别到指标名称存在歧义，请明确要查询哪一个：\n\n- "
                + String.join("\n- ", groups);
        var clarification = clarificationPrompts == null ? null
                : clarificationPrompts.fromResolution(
                        resolution, request.principal().hospitalId(), request.query());
        if (clarification != null) {
            answer = "# " + clarification.title() + "\n\n"
                    + clarification.question() + "\n\n"
                    + "> " + clarification.helpText();
        }
        conversations.appendUser(conversation, request.principal(), request.query(), request.fileKey());
        AgentRunState state = new AgentRunState();
        conversations.appendAssistant(conversation, request.principal(), answer, state);
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "sessionId", conversation.sessionId(),
                "resolverVersion", HybridIndicatorResolver.VERSION));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", answer,
                "code", "INDICATOR_AMBIGUOUS",
                "fallbackCategory", "USER_CLARIFICATION",
                "clarification", clarification == null ? Map.of() : clarification,
                "stopReason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stopReason", "clarification", "status", "incomplete", "stepCount", 0));
        return new AgentRunResult(
                answer, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private AgentRunResult allIndicatorIntentClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation) {
        String traceId = first(request.traceId(), id("TRACE_"));
        var clarification = clarificationPrompts == null
                ? null : clarificationPrompts.intentForAllIndicators(request.query());
        String answer = clarification == null
                ? "已经确认范围是全部指标。请说明要计算结果、解释口径还是生成 SQL。"
                : "# " + clarification.title() + "\n\n"
                        + clarification.question() + "\n\n"
                        + "> " + clarification.helpText();
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        conversations.appendAssistant(
                conversation, request.principal(), answer, new AgentRunState());
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "sessionId", conversation.sessionId()));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", answer,
                "code", "INTENT_AMBIGUOUS",
                "fallbackCategory", "USER_CLARIFICATION",
                "clarification", clarification == null ? Map.of() : clarification,
                "stopReason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stopReason", "clarification", "status", "incomplete", "stepCount", 0));
        return new AgentRunResult(
                answer, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private AgentRunResult indicatorIntentClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation,
            String indicatorName) {
        AgentClarification clarification = clarificationPrompts == null
                ? null : clarificationPrompts.intentForIndicator(
                        request.query(), indicatorName);
        String answer = clarification == null
                ? "已经确认指标是“" + indicatorName
                        + "”。请说明要查询定义、口径、SQL、计算结果还是排查异常。"
                : "# " + clarification.title() + "\n\n"
                        + clarification.question() + "\n\n"
                        + "> " + clarification.helpText();
        return clarificationResponse(
                request, observer, conversation, clarification,
                answer, "INTENT_AMBIGUOUS");
    }

    private AgentRunResult missingOperationAndIndicatorClarification(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation) {
        AgentClarification clarification = clarificationPrompts == null
                ? null : clarificationPrompts.missingOperationAndIndicator(request.query());
        String answer = clarification == null
                ? "已识别到统计时间，请同时说明要处理的指标和操作。"
                : "# " + clarification.title() + "\n\n"
                        + clarification.question() + "\n\n"
                        + "> " + clarification.helpText();
        return clarificationResponse(
                request, observer, conversation, clarification,
                answer, "REQUEST_CONTEXT_MISSING");
    }

    private AgentRunResult clarificationResponse(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation,
            AgentClarification clarification,
            String answer,
            String code) {
        String traceId = first(request.traceId(), id("TRACE_"));
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        conversations.appendAssistant(
                conversation, request.principal(), answer, new AgentRunState());
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "sessionId", conversation.sessionId()));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", answer,
                "code", code,
                "fallbackCategory", "USER_CLARIFICATION",
                "clarification", clarification == null ? Map.of() : clarification,
                "stopReason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stopReason", "clarification", "status", "incomplete", "stepCount", 0));
        return new AgentRunResult(
                answer, "clarification", traceId, conversation.sessionId(), 0,
                null, null, clarification);
    }

    private static boolean isBareIndicatorQuery(String query, String indicatorName) {
        if (query == null || indicatorName == null) {
            return false;
        }
        String compactQuery = query.replaceAll("[\\s，,。；;？?!！]+", "");
        String compactName = indicatorName.replaceAll("\\s+", "");
        return compactQuery.equals(compactName);
    }

    private static long timeout(Duration duration) {
        return duration == null ? 300 : Math.max(30, duration.toSeconds());
    }

    private void rememberSingleScope(
            AgentRunRequest request, String ruleId, String ruleName, AgentRunResult result) {
        var updated = conversations.open(request.principal(), request.sessionId());
        String operation = result.requestPlan() == null
                ? "indicator_trial_run" : result.requestPlan().intent().value();
        String statStart = result.requestPlan() == null
                ? updated.statStart() : first(
                        result.requestPlan().timeExpression().startTime(), updated.statStart());
        String statEnd = result.requestPlan() == null
                ? updated.statEnd() : first(
                        result.requestPlan().timeExpression().endTime(), updated.statEnd());
        conversations.rememberQueryScope(updated, new QueryScopeState(
                operation,
                "SINGLE",
                List.of(new QueryTarget(ruleId, ruleName)),
                statStart,
                statEnd));
    }

    private void rememberCompoundScope(
            AgentRunRequest request, SplitResult split, AgentRunResult representative) {
        var updated = conversations.open(request.principal(), request.sessionId());
        String operation = operation(split.kind());
        String statStart = updated.queryScope() == null
                ? null : updated.queryScope().statStart();
        String statEnd = updated.queryScope() == null
                ? null : updated.queryScope().statEnd();
        if (representative != null && representative.requestPlan() != null) {
            statStart = first(
                    representative.requestPlan().timeExpression().startTime(), statStart);
            statEnd = first(
                    representative.requestPlan().timeExpression().endTime(), statEnd);
        }
        List<QueryTarget> targets = split.tasks().stream()
                .filter(task -> task.resolvedIndicator() != null)
                .map(task -> new QueryTarget(
                        task.resolvedIndicator().ruleId(),
                        task.resolvedIndicator().canonicalName()))
                .toList();
        if (targets.size() != split.tasks().size()) {
            return;
        }
        conversations.rememberQueryScope(updated, new QueryScopeState(
                operation,
                targets.size() == 35 ? "ALL" : "SUBSET",
                targets,
                statStart,
                statEnd));
    }

    private static SplitResult withKind(SplitResult source, RequestKind kind) {
        List<SubtaskSpec> tasks = source.tasks().stream()
                .map(task -> new SubtaskSpec(
                        task.index(), task.target(),
                        childQueryFor(task.target(), kind, source.commonTimeExpression()),
                        task.resolvedIndicator()))
                .toList();
        return new SplitResult(tasks, kind, source.commonTimeExpression(),
                source.serialRequired(), source.followup());
    }

    private static String childQueryFor(String target, RequestKind kind, String time) {
        String period = time == null ? "" : "，统计周期" + time;
        return switch (kind) {
            case SQL_PREPARE -> "生成“" + target + "”的受控 SQL" + period;
            case DIAGNOSIS -> "诊断“" + target + "”的异常或差异原因" + period;
            case TRIAL_RUN -> "计算“" + target + "”的具体结果" + period;
            case RULE_EXPLANATION -> "解释“" + target + "”的定义、公式和本院口径";
        };
    }

    private static RequestKind requestKind(String operation) {
        return switch (operation) {
            case "indicator_sql_prepare" -> RequestKind.SQL_PREPARE;
            case "indicator_diagnosis" -> RequestKind.DIAGNOSIS;
            case "indicator_trial_run" -> RequestKind.TRIAL_RUN;
            default -> RequestKind.RULE_EXPLANATION;
        };
    }

    private static String operation(RequestKind kind) {
        return switch (kind) {
            case SQL_PREPARE -> "indicator_sql_prepare";
            case DIAGNOSIS -> "indicator_diagnosis";
            case TRIAL_RUN -> "indicator_trial_run";
            case RULE_EXPLANATION -> "rule_explanation";
        };
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    private static boolean isDetailRequest(String query) {
        if (query == null || query.isBlank()) return false;
        String compact = query.replaceAll("[\\s，,。；;？?!！]+", "");
        return DETAIL_REQUEST.matcher(compact).find();
    }

    /** 明细请求是否针对分母：含「分母」→分母明细；其余（含「分子」或泛称）→分子明细。 */
    private static boolean detailKindDenominator(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String compact = query.replaceAll("[\\s，,。；;？?!！]+", "");
        return compact.contains("分母");
    }

    @SuppressWarnings("unchecked")
    private AgentRunResult tryDetailView(
            AgentRunRequest request,
            AgentRunObserver observer,
            AgentConversationMemory.ConversationSnapshot conversation) {
        // 从会话上下文取最近指标和时间范围
        String ruleId = conversation.ruleId();
        String ruleName = conversation.ruleName();
        if ((ruleId == null || ruleId.isBlank())
                && conversation.queryScope() != null
                && !conversation.queryScope().targets().isEmpty()) {
            QueryTarget target = conversation.queryScope().targets().get(0);
            ruleId = target.ruleId();
            ruleName = target.ruleName();
        }
        if (ruleId == null || ruleId.isBlank()) {
            return null; // 无法确定指标，回退到正常流程
        }
        if (!mrasExecution.supports(ruleId)) {
            return null; // 知识库不支持，回退到正常流程
        }
        java.time.LocalDateTime start = parseTime(conversation.statStart(),
                java.time.LocalDate.now().withDayOfYear(1).atStartOfDay());
        java.time.LocalDateTime end = parseTime(conversation.statEnd(),
                java.time.LocalDateTime.now());
        String traceId = first(request.traceId(), id("TRACE_"));
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "sessionId", conversation.sessionId()));
        long started = TraceEvents.started();
        boolean denominator = detailKindDenominator(request.query());
        String queryType = denominator ? "denominator_detail" : "numerator_detail";
        // 优先用小模型合成的分子/分母明细 SQL；合成或执行失败则回退知识库患者明细 SQL
        ToolResult result = null;
        String usedDetailSql = null;
        if (detailSynthesizer != null) {
            DetailSqlPair pair = detailSynthesizer.synthesize(ruleId, request.modelId());
            if (pair != null) {
                String detailSql = denominator ? pair.denominatorSql() : pair.numeratorSql();
                ToolResult generated = mrasExecution.executeGeneratedDetail(
                        ruleId, detailSql, start, end, queryType);
                if (generated.ok()) {
                    result = generated;
                    usedDetailSql = detailSql;
                }
            }
        }
        if (result == null) {
            result = mrasExecution.executePatientDetail(ruleId, start, end, null, null);
        }
        TraceEvents.completed(observer, traceId, "mras_patient_detail", "database",
                started, "root", Map.of("ruleId", ruleId),
                Map.of("ok", result.ok(), "code", result.code(), "queryType", queryType));
        String detailLabel = denominator ? "分母明细" : "分子明细";
        String answer;
        if (!result.ok()) {
            answer = "查询" + detailLabel + "失败：" + result.summary();
        } else {
            Object rowCount = result.data().get("rowCount");
            int rows = rowCount instanceof Number number ? number.intValue() : 0;
            if (rows == 0) {
                answer = "统计区间 " + start.toLocalDate() + " 至 " + end.toLocalDate()
                        + " 内，" + (ruleName == null ? ruleId : ruleName)
                        + " 无样本数据，无法展示" + detailLabel + "。";
            } else {
                answer = formatDetailTable(result.data(), ruleName, rows);
            }
            // 带上生成明细 SQL 一起输出
            if (usedDetailSql != null && !usedDetailSql.isBlank()) {
                answer = answer + "\n\n---\n\n**生成的" + detailLabel + " SQL：**\n\n```sql\n"
                        + usedDetailSql.strip() + "\n```";
            }
        }
        conversations.appendUser(conversation, request.principal(), request.query(), request.fileKey());
        AgentRunState state = new AgentRunState();
        state.lastIntent("indicator_detail_view");
        state.lastRuleName(ruleName);
        conversations.appendAssistant(conversation, request.principal(), answer, state);
        emit(observer, "assistant_message", traceId, 1, Map.of(
                "message", answer, "status", "completed"));
        emit(observer, "agent_done", traceId, 1, Map.of(
                "stopReason", "final_answer", "status", "completed", "stepCount", 1));
        return new AgentRunResult(answer, "final_answer", traceId,
                conversation.sessionId(), 1, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String formatDetailTable(
            Map<String, Object> data, String ruleName, int rowCount) {
        Object rawRows = data.get("rows");
        if (!(rawRows instanceof List<?> rowList) || rowList.isEmpty()) {
            return "共 " + rowCount + " 条明细记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ruleName == null ? "患者明细" : ruleName + " 患者明细")
                .append("\n\n");
        sb.append("共 ").append(rowCount).append(" 条记录。\n\n");
        // 取前 20 行展示
        int show = Math.min(20, rowList.size());
        if (rowList.get(0) instanceof Map<?, ?> firstRow) {
            List<String> cols = new ArrayList<>();
            for (Object key : firstRow.keySet()) {
                cols.add(String.valueOf(key));
            }
            sb.append("| ").append(String.join(" | ", cols)).append(" |\n");
            sb.append("| ").append(cols.stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |\n");
            for (int i = 0; i < show; i++) {
                Map<?, ?> row = (Map<?, ?>) rowList.get(i);
                sb.append("| ");
                for (int c = 0; c < cols.size(); c++) {
                    Object val = row.get(cols.get(c));
                    sb.append(val == null ? "" : String.valueOf(val));
                    if (c < cols.size() - 1) sb.append(" | ");
                }
                sb.append(" |\n");
            }
            if (rowCount > show) {
                sb.append("\n> 仅展示前 ").append(show).append(" 条，共 ")
                        .append(rowCount).append(" 条。\n");
            }
        }
        return sb.toString();
    }

    private static java.time.LocalDateTime parseTime(String value, java.time.LocalDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return java.time.LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private record SubtaskOutcome(SubtaskSpec task, AgentRunResult result, String answer) {
        static SubtaskOutcome failed(SubtaskSpec task, String answer) {
            return new SubtaskOutcome(task, null, answer);
        }
    }
}
