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

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.SplitResult;
import com.hospital.wikiagent.agent.runtime.CompoundRequestSplitter.SubtaskSpec;

import jakarta.annotation.PreDestroy;

/** 多指标 fan-out/fan-in 外层；单指标执行仍由 AgentRunner 负责。 */
@Component
public class CompoundAgentRuntime {
    public static final String VERSION = "compound-runtime-v2";

    private final AgentRunner runner;
    private final CompoundRequestSplitter splitter;
    private final AgentModelRegistry models;
    private final AgentModelProperties properties;
    private final AgentConversationMemory conversations;
    private final HybridIndicatorResolver indicatorResolver;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
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
        this(runner, splitter, models, properties, conversations, null);
    }

    @Autowired
    public CompoundAgentRuntime(
            AgentRunner runner,
            CompoundRequestSplitter splitter,
            AgentModelRegistry models,
            AgentModelProperties properties,
            AgentConversationMemory conversations,
            HybridIndicatorResolver indicatorResolver) {
        this.runner = runner;
        this.splitter = splitter;
        this.models = models;
        this.properties = properties;
        this.conversations = conversations;
        this.indicatorResolver = indicatorResolver;
    }

    public AgentRunResult run(AgentRunRequest request) {
        return run(request, AgentRunObserver.noop());
    }

    public AgentRunResult run(AgentRunRequest request, AgentRunObserver observer) {
        long splitStarted = TraceEvents.started();
        var conversation = conversations.open(request.principal(), request.sessionId());
        HybridIndicatorResolver.Resolution resolution = indicatorResolver == null
                ? HybridIndicatorResolver.Resolution.empty()
                : indicatorResolver.resolve(
                        request.query(), request.principal().hospitalId(), request.modelId(),
                        request.traceId(), "root", observer);
        if (resolution.needsClarification()) {
            return clarification(request, observer, conversation, resolution);
        }
        SplitResult split = splitter.split(
                request.query(), conversation.recentHistory(), request.principal().hospitalId(),
                resolution.indicators());
        if (!split.compound()) {
            var resolved = resolution.indicators().size() == 1
                    ? resolution.indicators().get(0) : null;
            return runner.run(request, observer, resolved);
        }
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        String traceId = first(request.traceId(), id("TRACE_"));
        String requestId = first(request.requestId(), id("REQ_"));
        TraceEvents.completed(observer, traceId, "compound_split", "code", splitStarted,
                "root", Map.of("query", request.query()), Map.of(
                        "subtask_count", split.tasks().size(),
                        "targets", split.tasks().stream().map(SubtaskSpec::target).toList(),
                        "common_time", split.commonTimeExpression() == null
                                ? "" : split.commonTimeExpression(),
                        "serial_required", split.serialRequired(),
                        "splitter_version", CompoundRequestSplitter.VERSION));
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running",
                "compound", true,
                "subtask_count", split.tasks().size(),
                "splitter_version", CompoundRequestSplitter.VERSION));

        String provider = models.requireInfo(request.modelId()).provider();
        int configured = "ollama".equals(provider)
                ? properties.getCompoundOllamaConcurrency()
                : properties.getCompoundApiConcurrency();
        int concurrency = split.serialRequired() ? 1 : Math.max(1, Math.min(2, configured));
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
                "root", Map.of("subtask_count", outcomes.size()), Map.of(
                        "successful_subtasks", successful,
                        "failed_subtasks", outcomes.size() - successful,
                        "ordered_targets", outcomes.stream().map(value -> value.task().target()).toList()));
        emit(observer, "assistant_message", traceId, steps, Map.of(
                "message", answer,
                "status", successful > 0 ? "completed" : "failed",
                "compound", true,
                "successful_subtasks", successful,
                "failed_subtasks", outcomes.size() - successful));
        emit(observer, "agent_done", traceId, steps, Map.of(
                "stop_reason", stopReason,
                "status", successful > 0 ? "completed" : "incomplete",
                "step_count", steps,
                "subtask_count", outcomes.size()));
        AgentRunState memoryState = new AgentRunState();
        memoryState.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));
        conversations.appendAssistant(conversation, request.principal(), answer, memoryState);
        return new AgentRunResult(
                answer, stopReason, traceId, conversation.sessionId(), steps,
                representative == null ? null : representative.requestPlan(),
                representative == null ? null : representative.compiledPlan());
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
                            "stop_reason", result.stopReason(), "step_count", result.stepCount()),
                    "node_id", subtaskNodeId);
            return new SubtaskOutcome(task, result, result.answer());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            TraceEvents.failed(observer, parentTraceId, "compound_subtask", "code",
                    subtaskStarted, subtaskId, "SUBTASK_CANCELLED", "子任务已取消。",
                    "node_id", subtaskNodeId);
            return SubtaskOutcome.failed(task, "该指标子任务已取消，请单独重试。");
        } catch (RuntimeException exception) {
            TraceEvents.failed(observer, parentTraceId, "compound_subtask", "code",
                    subtaskStarted, subtaskId, "SUBTASK_FAILED", exception.getMessage(),
                    "node_id", subtaskNodeId);
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
        safe.put("child_trace_id", event.get("trace_id"));
        safe.put("trace_id", parentTraceId);
        safe.put("subtask_id", subtaskId);
        safe.put("subtask_index", subtaskIndex);
        if ("trace_node".equals(type)) {
            safe.put("parent_node_id", subtaskNodeId);
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
        payload.put("trace_id", traceId);
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
        conversations.appendUser(conversation, request.principal(), request.query(), request.fileKey());
        AgentRunState state = new AgentRunState();
        conversations.appendAssistant(conversation, request.principal(), answer, state);
        emit(observer, "agent_start", traceId, 0, Map.of(
                "status", "running", "resolver_version", HybridIndicatorResolver.VERSION));
        emit(observer, "clarification_required", traceId, 0, Map.of(
                "message", answer,
                "code", "INDICATOR_AMBIGUOUS",
                "fallback_category", "USER_CLARIFICATION",
                "stop_reason", "clarification"));
        emit(observer, "agent_done", traceId, 0, Map.of(
                "stop_reason", "clarification", "status", "incomplete", "step_count", 0));
        return new AgentRunResult(
                answer, "clarification", traceId, conversation.sessionId(), 0, null, null);
    }

    private static long timeout(Duration duration) {
        return duration == null ? 300 : Math.max(30, duration.toSeconds());
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

    private record SubtaskOutcome(SubtaskSpec task, AgentRunResult result, String answer) {
        static SubtaskOutcome failed(SubtaskSpec task, String answer) {
            return new SubtaskOutcome(task, null, answer);
        }
    }
}
