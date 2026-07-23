package com.hospital.wikiagent.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier.VerificationExpectations;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.ConversationSnapshot;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer.FinalAnswerInput;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.PlannerInput;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.PlannerResult;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.ReplannerInput;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.AlignmentReviewInput;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.AgentFailureRouter;
import com.hospital.wikiagent.agent.planning.AgentFailureRouter.FailureRoute;
import com.hospital.wikiagent.agent.planning.CapabilityDispatchException;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanGoalAlignmentValidator;
import com.hospital.wikiagent.agent.planning.PlanGoalAlignmentValidator.AlignmentDecision;
import com.hospital.wikiagent.agent.planning.PlanGoalAlignmentValidator.AlignmentStatus;
import com.hospital.wikiagent.agent.planning.PlanValidation;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.planning.PlanningExecution;
import com.hospital.wikiagent.agent.planning.ReplanPolicy;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;
import com.hospital.wikiagent.agent.upload.UploadedFilePlanningContext;
import com.hospital.wikiagent.agent.upload.UploadedFilePlanningContext.PlanningContext;

/**
 * 执行单指标 Compiled Plan：装载会话、调用 Planner、按状态机调用受控工具、
 * 校验证据并生成最终回答。模型不能在这里绕过 Controller 自由调用工具。
 *
 * <p>每轮执行严格遵循“加载会话 → 规划 → 编译与校验 → 状态控制 → 工具网关 →
 * Evidence 校验 → 回答”的顺序。所有失败先由 {@link AgentFailureRouter} 统一分类，只有
 * {@link ReplanPolicy} 认定为语义计划错误时才允许一次
 * Replan；权限、数据库、缺时间和证据冲突等执行错误会直接终止，避免重复走同一失败路径。</p>
 */
@Component
public class AgentRunner {
    public static final String VERSION = "java-agent-runner-v1";
    private static final int MAX_STEPS = 12;
    private static final DateTimeFormatter EVIDENCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern ISO_HISTORY_TIME = Pattern.compile(
            "(20\\d{2}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern CHINESE_HISTORY_TIME = Pattern.compile(
            "(20\\d{2})年(1[0-2]|0?[1-9])月(3[01]|[12]?\\d)日"
                    + "(?:\\s*(2[0-3]|[01]?\\d):(\\d{2})(?::(\\d{2}))?)?");
    private static final Pattern CURRENT_QUERY_TIME = Pattern.compile(
            "20\\d{2}[-年]|(?:\\d{2}|[一二三四五六七八九十]{2,4})年|"
                    + "(?:1[0-2]|0?[1-9]|[一二三四五六七八九十]{1,3})月份?|"
                    + "至今|到现在|本月|这个月|上月|今年|去年|今天|昨天");

    private final ModelRequestPlanner planner;
    private final PlanValidator validator;
    private final PlanCompiler compiler;
    private final CapabilitySpecRegistry capabilities;
    private final AgentStateController controller;
    private final DeterministicDispatch dispatch;
    private final ToolGateway gateway;
    private final EvidenceVerifier verifier;
    private final FinalAnswerComposer finalAnswer;
    private final AgentConversationMemory conversations;
    private final AgentFailureRouter failureRouter;
    private final UploadedFilePlanningContext uploadPlanningContext;
    private final PlanGoalAlignmentValidator alignmentValidator;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunner(
            ModelRequestPlanner planner,
            PlanValidator validator,
            PlanCompiler compiler,
            CapabilitySpecRegistry capabilities,
            AgentStateController controller,
            DeterministicDispatch dispatch,
            ToolGateway gateway,
            EvidenceVerifier verifier,
            FinalAnswerComposer finalAnswer,
            AgentConversationMemory conversations,
            AgentFailureRouter failureRouter,
            UploadedFilePlanningContext uploadPlanningContext,
            PlanGoalAlignmentValidator alignmentValidator) {
        this.planner = planner;
        this.validator = validator;
        this.compiler = compiler;
        this.capabilities = capabilities;
        this.controller = controller;
        this.dispatch = dispatch;
        this.gateway = gateway;
        this.verifier = verifier;
        this.finalAnswer = finalAnswer;
        this.conversations = conversations;
        this.failureRouter = failureRouter;
        this.uploadPlanningContext = uploadPlanningContext;
        this.alignmentValidator = alignmentValidator;
    }

    public AgentRunner(
            ModelRequestPlanner planner,
            PlanValidator validator,
            PlanCompiler compiler,
            CapabilitySpecRegistry capabilities,
            AgentStateController controller,
            DeterministicDispatch dispatch,
            ToolGateway gateway,
            EvidenceVerifier verifier,
            FinalAnswerComposer finalAnswer,
            AgentConversationMemory conversations) {
        this(planner, validator, compiler, capabilities, controller, dispatch,
                gateway, verifier, finalAnswer, conversations,
                new AgentFailureRouter(new ReplanPolicy()), null, null);
    }

    public AgentRunner(
            ModelRequestPlanner planner,
            PlanValidator validator,
            PlanCompiler compiler,
            CapabilitySpecRegistry capabilities,
            AgentStateController controller,
            DeterministicDispatch dispatch,
            ToolGateway gateway,
            EvidenceVerifier verifier,
            FinalAnswerComposer finalAnswer) {
        this(planner, validator, compiler, capabilities, controller, dispatch,
                gateway, verifier, finalAnswer, AgentConversationMemory.noop());
    }

    /**
     * 使用空观察器执行一次完整请求，适合不需要实时 Trace 的内部调用。
     */
    public AgentRunResult run(AgentRunRequest request) {
        return run(request, AgentRunObserver.noop(), null);
    }

    /**
     * 执行请求并把每个确定性节点、模型节点和工具节点发送给观察器。
     */
    public AgentRunResult run(AgentRunRequest request, AgentRunObserver observer) {
        return run(request, observer, null);
    }

    /**
     * 执行单指标子任务。
     *
     * @param request 已完成身份注入的请求；医院编号只能来自登录主体
     * @param observer Trace/SSE 观察器，传入 {@code null} 时由调用方使用空实现
     * @param resolvedIndicator 复合请求拆分阶段已经确认的指标身份，可为空
     * @return 包含最终回答、停止原因、计划和 Trace 编号的执行结果
     */
    public AgentRunResult run(
            AgentRunRequest request,
            AgentRunObserver observer,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        String requestId = blankTo(request.requestId(), id("REQ_"));
        String traceId = blankTo(request.traceId(), id("TRACE_"));
        long memoryStarted = TraceEvents.started();
        // 先固定会话快照，再把本轮消息写入；Planner 只能看到受控轮数的历史。
        ConversationSnapshot conversation = conversations.open(
                request.principal(), request.sessionId());
        request = withConversationContext(request, conversation);
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        String sessionId = conversation.sessionId();
        String subtaskId = requestId.contains(":subtask:") ? requestId : id("SUB_");
        emit(observer, "agent_start", traceId, 0, Map.of("status", "running"));
        TraceEvents.completed(observer, traceId, "memory_load", "storage", memoryStarted,
                subtaskId, Map.of("session_id", safe(request.sessionId())), Map.of(
                        "history_length", request.recentHistory().length(),
                        "structured_state_length", request.structuredState().length()));
        PlannerResult modelPlan;
        RequestPlan followupPlan = deterministicSqlFollowup(
                request.query(), conversation, request.recentHistory(), resolvedIndicator);
        if (followupPlan != null) {
            // “这个 SQL 怎么写”已由上一轮结构化状态给出指标与周期，无需再次让小模型猜测。
            long followupStarted = TraceEvents.started();
            modelPlan = new PlannerResult(
                    followupPlan, "deterministic-sql-followup", request.modelId(), false);
            TraceEvents.completed(observer, traceId, "followup_plan_resolve", "code",
                    followupStarted, subtaskId, Map.of(
                            "query", request.query(),
                            "context_rule_id", followupPlan.targetIndicator().ruleId(),
                            "context_stat_start", followupPlan.timeExpression().startTime(),
                            "context_stat_end", followupPlan.timeExpression().endTime()), Map.of(
                            "intent", followupPlan.intent().name(),
                            "requested_outputs", followupPlan.requestedOutputs()));
        } else {
            emit(observer, "model_start", traceId, 0, Map.of("message", "规划业务目标"));
            long plannerStarted = TraceEvents.started();
            // Planner 只产出业务 RequestPlan，不接收工具 schema，也不能决定 SQL。
            try {
                modelPlan = planner.plan(new PlannerInput(
                        request.query(), request.modelId(), LocalDate.now(ZoneId.of("Asia/Shanghai")),
                        request.structuredState(), request.recentHistory()));
                TraceEvents.completed(observer, traceId, "planner_llm", "llm", plannerStarted,
                        subtaskId, Map.of(
                                "query", request.query(),
                                "structured_state", request.structuredState(),
                                "recent_history", request.recentHistory()), eventValues(
                                "raw_content", modelPlan.rawContent(),
                                "request_plan", tracePlan(modelPlan.plan()),
                                "repaired", modelPlan.repaired()),
                        "model_id", modelPlan.modelId());
            } catch (RuntimeException exception) {
                TraceEvents.failed(observer, traceId, "planner_llm", "llm", plannerStarted,
                        subtaskId, "PLANNER_FAILED", exception.getMessage(),
                        "model_id", request.modelId());
                throw exception;
            }
        }
        PlanningContext fileContext = resolveUploadPlanningContext(request);
        RequestPlan enrichedPlan = normalizeExplicitDifferenceDiagnosis(
                request.query(), normalizeExplicitImplementationValidation(
                        request.query(), enrichFromResolvedIndicator(
                                enrichFromConversation(
                                        enrichFromUploadedFile(modelPlan.plan(), fileContext),
                                        conversation),
                                resolvedIndicator)));
        PlannerResult planned = new PlannerResult(
                enrichedPlan, modelPlan.rawContent(), modelPlan.modelId(), modelPlan.repaired());

        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        state.progressReporter(progress -> TraceEvents.recorded(
                observer,
                traceId,
                progress.nodeName(),
                "code",
                progress.status(),
                progress.durationMs(),
                subtaskId,
                Map.of("workflow_version", "indicator-difference-diagnosis-v1"),
                progress.safeOutput(),
                "capability", "diagnose_indicator_difference"));
        state.currentRuleId(first(
                planned.plan().targetIndicator().ruleId(), conversation.ruleId()));
        state.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));

        // 在编译 IR 之前核对原问题和模型计划。只有真正的方向性冲突才触发一次
        // Replanner；正常计划不会增加模型调用。
        planned = alignPlanBeforeCompile(
                request, observer, traceId, state, planned, conversation, resolvedIndicator);

        // 编译器从目标事实反推前置能力，形成后续状态机唯一可执行的 IR。
        long compileStarted = TraceEvents.started();
        CompiledPlanIR compiled = compiler.compile(planned.plan());
        TraceEvents.completed(observer, traceId, "plan_compile", "code", compileStarted,
                subtaskId, Map.of("intent", planned.plan().intent().name()), Map.of(
                        "plan_id", compiled.planId(), "node_count", compiled.nodes().size(),
                        "ir_version", CompiledPlanIR.VERSION));
        long validationStarted = TraceEvents.started();
        PlanValidation validation = validator.validate(planned.plan());
        TraceEvents.completed(observer, traceId, "plan_validate", "code", validationStarted,
                subtaskId, Map.of("plan_id", compiled.planId()), Map.of(
                        "valid", validation.ok(),
                        "code", validation.code()));
        PlanningExecution execution = new PlanningExecution(
                planned.plan(), compiled, validation, capabilities);

        state.currentRuleId(first(
                planned.plan().targetIndicator().ruleId(), state.currentRuleId()));
        applyResolvedTime(state, validation);
        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId());

        // 计划校验失败也必须先进入统一失败路由。方向性错误可以在调用任何工具前纠正一次；
        // 缺时间、权限和数据库冲突等不可重规划问题仍按原校验结果直接兜底。
        if (!validation.ok()) {
            ReplanOutcome replanned = tryReplan(
                    request, observer, traceId, state, planned, compiled,
                    validation.code(), validation.message(), conversation, resolvedIndicator);
            if (replanned != null) {
                planned = replanned.planned();
                execution = replanned.execution();
                compiled = execution.compiledPlan();
                validation = execution.validation();
                applyResolvedTime(state, validation);
            } else {
                ControllerDecision fallback = controller.nextDecision(compiled, validation, state);
                AgentRunResult result = finishFallback(
                        observer, traceId, sessionId, state, planned.plan(), compiled, fallback);
                saveConversation(observer, traceId, subtaskId, conversation,
                        request.principal(), result.answer(), state);
                return result;
            }
        }

        // 有界循环只推进尚未满足的事实；MAX_STEPS 是最后一道失控保护。
        while (state.stepCount() < MAX_STEPS) {
            long controllerStarted = TraceEvents.started();
            ControllerDecision decision = controller.nextDecision(compiled, validation, state);
            TraceEvents.completed(observer, traceId, "state_controller", "code",
                    controllerStarted, subtaskId, Map.of(
                            "evidence_count", state.evidenceIds().size(),
                            "step_count", state.stepCount()), eventValues(
                            "action", decision.action().name(),
                            "capability", decision.capability()),
                    "capability", decision.capability());
            if (decision.action() == ControllerAction.FALLBACK) {
                AgentRunResult result = finishFallback(
                        observer, traceId, sessionId, state, planned.plan(), compiled, decision);
                saveConversation(observer, traceId, subtaskId, conversation,
                        request.principal(), result.answer(), state);
                return result;
            }
            if (decision.action() == ControllerAction.COMPOSE_ANSWER) {
                AgentRunResult result = compose(
                        request, observer, traceId, sessionId, state, planned.modelId(),
                        planned.plan(), compiled, validation, context);
                saveConversation(observer, traceId, subtaskId, conversation,
                        request.principal(), result.answer(), state);
                return result;
            }

            DeterministicDispatch.ToolCall call;
            long dispatchStarted = TraceEvents.started();
            try {
                call = dispatch.buildToolCall(execution, decision, state, request.query());
                TraceEvents.completed(observer, traceId, "deterministic_tool_dispatch", "code",
                        dispatchStarted, subtaskId, Map.of(
                                "capability", decision.capability()), Map.of(
                                "tool_name", call.name(),
                                "argument_names", call.arguments().keySet()),
                        "capability", decision.capability(), "tool_name", call.name());
            } catch (CapabilityDispatchException exception) {
                TraceEvents.failed(observer, traceId, "deterministic_tool_dispatch", "code",
                        dispatchStarted, subtaskId, exception.code(), exception.getMessage(),
                        "capability", decision.capability());
                ReplanOutcome replanned = tryReplan(
                        request, observer, traceId, state, planned, compiled,
                        exception.code(), exception.getMessage(), conversation, resolvedIndicator);
                if (replanned != null) {
                    planned = replanned.planned();
                    execution = replanned.execution();
                    compiled = execution.compiledPlan();
                    validation = execution.validation();
                    applyResolvedTime(state, validation);
                    continue;
                }
                AgentRunResult result = finishFailure(
                        observer, traceId, sessionId, state, planned.plan(), compiled,
                        exception.getMessage(), exception.code());
                saveConversation(observer, traceId, subtaskId, conversation,
                        request.principal(), result.answer(), state);
                return result;
            }
            state.incrementStep();
            emit(observer, "tool_call", traceId, state.stepCount(), Map.of(
                    "tool_name", call.name(), "status", "running"));
            long started = System.nanoTime();
            ToolResult result = gateway.execute(
                    call.name(), call.arguments(), context, state).join();
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            emit(observer, "tool_result", traceId, state.stepCount(), eventValues(
                    "tool_name", call.name(),
                    "status", result.status(),
                    "code", result.code(),
                    "message", result.summary(),
                    "retryable", result.retryable(),
                    "reused", result.cacheReused(),
                    "duration_ms", durationMs));
            long toolStartedEpoch = System.currentTimeMillis() - durationMs;
            if (result.ok()) {
                TraceEvents.completed(observer, traceId, "tool_result", "tool",
                        toolStartedEpoch, subtaskId, Map.of(
                                "tool_name", call.name(),
                                "argument_names", call.arguments().keySet()), Map.of(
                                "code", result.code(), "summary", result.summary(),
                                "data", result.data()),
                        "tool_name", call.name(), "capability", decision.capability(),
                        "cache_reused", result.cacheReused(),
                        "rule_id", state.currentRuleId());
            } else {
                TraceEvents.failed(observer, traceId, "tool_result", "tool",
                        toolStartedEpoch, subtaskId, result.code(), result.summary(),
                        "tool_name", call.name(), "capability", decision.capability(),
                        "cache_reused", result.cacheReused(),
                        "rule_id", state.currentRuleId());
            }
            emitImplementationValidationStages(observer, traceId, subtaskId, result);
            updateState(state, result);
            if (!result.ok()) {
                ReplanOutcome replanned = tryReplan(
                        request, observer, traceId, state, planned, compiled,
                        result.code(), result.summary(), conversation, resolvedIndicator);
                if (replanned != null) {
                    planned = replanned.planned();
                    execution = replanned.execution();
                    compiled = execution.compiledPlan();
                    validation = execution.validation();
                    applyResolvedTime(state, validation);
                    continue;
                }
                AgentRunResult failure = finishFailure(
                        observer, traceId, sessionId, state, planned.plan(), compiled,
                        result.summary(), result.code());
                saveConversation(observer, traceId, subtaskId, conversation,
                        request.principal(), failure.answer(), state);
                return failure;
            }
        }
        AgentRunResult failure = finishFailure(
                observer, traceId, sessionId, state, planned.plan(), compiled,
                "已达到最大处理步骤，请缩小问题范围后重试。", "MAX_STEPS_EXCEEDED");
        saveConversation(observer, traceId, subtaskId, conversation,
                request.principal(), failure.answer(), state);
        return failure;
    }

    /**
     * 仅为可恢复的语义计划错误生成一次替代计划，并拒绝重复失败的 planId。
     */
    private PlannerResult alignPlanBeforeCompile(
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            AgentRunState state,
            PlannerResult current,
            ConversationSnapshot conversation,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        if (alignmentValidator == null) return current;
        long started = TraceEvents.started();
        AlignmentDecision decision = alignmentValidator.assess(
                request.query(), current.plan(), request.principal().hospitalId());
        TraceEvents.completed(observer, traceId, "plan_goal_alignment", "code", started,
                state.subtaskId(), Map.of(
                        "query", request.query(),
                        "intent", current.plan().intent().name()), Map.of(
                        "status", decision.status().name(),
                        "failure_code", decision.failureCode(),
                        "reason", decision.reason(),
                        "candidate_profiles", safeCandidateProfiles(decision)),
                "failure_class", decision.aligned()
                        ? "" : FailureClass.TASK_TYPE_ERROR.value());
        if (decision.aligned()) return current;

        RequestPlan reviewedCorrection = null;
        if (decision.status() == AlignmentStatus.REVIEW_REQUIRED) {
            long reviewStarted = TraceEvents.started();
            try {
                var review = planner.reviewAlignment(new AlignmentReviewInput(
                        request.query(),
                        request.modelId(),
                        current.plan(),
                        request.structuredState(),
                        safeCandidateProfiles(decision).toString()));
                TraceEvents.completed(
                        observer, traceId, "plan_alignment_review_llm", "llm",
                        reviewStarted, state.subtaskId(), Map.of(
                                "intent", current.plan().intent().name(),
                                "candidate_profiles", safeCandidateProfiles(decision)), Map.of(
                                "aligned", review.aligned(),
                                "reason", review.reason(),
                                "suggested_profile_id", review.suggestedProfileId()),
                        "model_id", review.modelId());
                if (review.aligned()) return current;
                reviewedCorrection = alignmentValidator.correctionForReviewedProfile(
                        current.plan(), decision, review.suggestedProfileId(), request.query());
                decision = AlignmentDecision.mismatch(
                        "TASK_TYPE_MISMATCH",
                        review.reason().isBlank() ? decision.reason() : review.reason(),
                        reviewedCorrection,
                        decision.candidates());
            } catch (RuntimeException exception) {
                TraceEvents.failed(
                        observer, traceId, "plan_alignment_review_llm", "llm",
                        reviewStarted, state.subtaskId(),
                        "PLAN_ALIGNMENT_REVIEW_FAILED",
                        exception.getMessage(),
                        "model_id", request.modelId());
                // 审核模型不可用时不能把可疑计划当作正确；后续由 Replanner 或明确兜底处理。
            }
        }

        PlannerResult replanned = tryAlignmentReplan(
                request, observer, traceId, state, current,
                "TASK_TYPE_MISMATCH", decision.reason(),
                conversation, resolvedIndicator);
        if (replanned != null) {
            PlannerResult alternative = replanned;
            long revalidateStarted = TraceEvents.started();
            AlignmentDecision revalidated = alignmentValidator.assess(
                    request.query(), alternative.plan(), request.principal().hospitalId());
            TraceEvents.completed(
                    observer, traceId, "plan_alignment_revalidate", "code",
                    revalidateStarted, state.subtaskId(), Map.of(
                            "intent", alternative.plan().intent().name()), Map.of(
                            "status", revalidated.status().name(),
                            "reason", revalidated.reason()),
                    "failure_class", revalidated.aligned()
                            ? "" : FailureClass.TASK_TYPE_ERROR.value());
            if (revalidated.aligned()) {
                return alternative;
            }
        }

        RequestPlan fallback = firstNonNull(
                reviewedCorrection,
                alignmentValidator.deterministicFallback(decision));
        if (fallback != null) {
            long fallbackStarted = TraceEvents.started();
            TraceEvents.completed(
                    observer, traceId, "plan_alignment_deterministic_fallback", "code",
                    fallbackStarted, state.subtaskId(), Map.of(
                            "failure_reason", decision.reason()), Map.of(
                            "intent", fallback.intent().name(),
                            "target_caliber",
                            safe(text(fallback.targetCaliber().profileId()))));
            return new PlannerResult(
                    fallback,
                    "deterministic-plan-alignment-fallback",
                    current.modelId(),
                    true);
        }

        // 无唯一安全修正方向时给 PlanValidator 一个明确阻断标记，不能继续按错误计划回答。
        List<String> constraints = new ArrayList<>(current.plan().constraints());
        constraints.add("alignment_blocked");
        RequestPlan blocked = new RequestPlan(
                RequestPlan.VERSION,
                current.plan().intent(),
                current.plan().goal(),
                current.plan().targetIndicator(),
                current.plan().targetCaliber(),
                current.plan().timeExpression(),
                current.plan().requestedOutputs(),
                constraints,
                current.plan().semanticAmbiguities());
        return new PlannerResult(
                blocked, current.rawContent(), current.modelId(), current.repaired());
    }

    private static List<Map<String, Object>> safeCandidateProfiles(AlignmentDecision decision) {
        if (decision == null) return List.of();
        return decision.candidates().stream().map(item -> Map.<String, Object>of(
                "profile_id", safe(text(item.get("profile_id"))),
                "label", safe(text(item.get("label"))),
                "source_version", safe(text(item.get("source_version"))))).toList();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    /**
     * 在 IR 编译之前执行一次受限 Replan。
     *
     * <p>这里不能复用运行期 {@link #tryReplan}：运行期已经拥有 CompiledPlan，
     * 而目标一致性校验要求“替代计划先复核、后编译”。预编译阶段使用 RequestPlan
     * 指纹记录失败路径，避免为了 planId 提前编译错误计划。</p>
     */
    private PlannerResult tryAlignmentReplan(
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            AgentRunState state,
            PlannerResult current,
            String failureCode,
            String failureReason,
            ConversationSnapshot conversation,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        long routeStarted = TraceEvents.started();
        FailureRoute route = failureRouter.route(state, failureCode);
        TraceEvents.completed(observer, traceId, "failure_router", "code", routeStarted,
                state.subtaskId(), Map.of(
                        "failure_code", route.failureCode(),
                        "failure_class", route.failureClass().value(),
                        "replan_count", state.replanCount()), Map.of(
                        "action", route.action().name()),
                "failure_class", route.failureClass().value());
        if (!route.shouldReplan()) return null;

        String failedPlanId = precompilePlanId(current.plan());
        failureRouter.recordReplan(state, failedPlanId);
        long started = TraceEvents.started();
        try {
            PlannerResult raw = planner.replan(new ReplannerInput(
                    request.query(), request.modelId(),
                    LocalDate.now(ZoneId.of("Asia/Shanghai")),
                    current.plan(), failureCode, failureReason,
                    "rule_id=" + safe(state.currentRuleId())
                            + "; evidence_ids=" + state.evidenceIds(),
                    failedPlanId));
            RequestPlan plan = normalizeExplicitDifferenceDiagnosis(
                    request.query(), normalizeExplicitImplementationValidation(
                            request.query(), enrichFromResolvedIndicator(
                                    enrichFromConversation(
                                            enrichFromUploadedFile(
                                                    raw.plan(),
                                                    resolveUploadPlanningContext(request)),
                                            conversation),
                                    resolvedIndicator)));
            String alternativeId = precompilePlanId(plan);
            if (!failureRouter.acceptsAlternative(state, alternativeId)) {
                TraceEvents.failed(observer, traceId, "plan_replan", "llm", started,
                        state.subtaskId(), "REPLAN_REPEATED_FAILED_PATH",
                        "重规划重复了已经失败的计划方向。",
                        "model_id", raw.modelId(),
                        "failure_class", FailureClass.classify(failureCode).value());
                return null;
            }
            PlannerResult planned = new PlannerResult(
                    plan, raw.rawContent(), raw.modelId(), raw.repaired());
            TraceEvents.completed(observer, traceId, "plan_replan", "llm", started,
                    state.subtaskId(), Map.of(
                            "original_plan_id", failedPlanId,
                            "failure_code", failureCode,
                            "failure_reason", failureReason,
                            "known_evidence_ids", state.evidenceIds()), eventValues(
                            "raw_content", raw.rawContent(),
                            "candidate_plan_id", alternativeId,
                            "request_plan", tracePlan(plan),
                            "replan_count", state.replanCount()),
                    "model_id", raw.modelId(),
                    "failure_class", FailureClass.classify(failureCode).value(),
                    "max_replan_count", ReplanPolicy.MAX_REPLAN_COUNT);
            return planned;
        } catch (RuntimeException exception) {
            TraceEvents.failed(observer, traceId, "plan_replan", "llm", started,
                    state.subtaskId(), "REPLAN_FAILED", exception.getMessage(),
                    "failure_class", FailureClass.classify(failureCode).value(),
                    "max_replan_count", ReplanPolicy.MAX_REPLAN_COUNT);
            return null;
        }
    }

    private static String precompilePlanId(RequestPlan plan) {
        return "PREPLAN_" + Integer.toUnsignedString(tracePlan(plan).hashCode(), 16);
    }

    private ReplanOutcome tryReplan(
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            AgentRunState state,
            PlannerResult current,
            CompiledPlanIR compiled,
            String failureCode,
            String failureReason,
            ConversationSnapshot conversation,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        long routeStarted = TraceEvents.started();
        FailureRoute route = failureRouter.route(state, failureCode);
        TraceEvents.completed(observer, traceId, "failure_router", "code", routeStarted,
                state.subtaskId(), Map.of(
                        "failure_code", route.failureCode(),
                        "failure_class", route.failureClass().value(),
                        "replan_count", state.replanCount()), Map.of(
                        "action", route.action().name()),
                "failure_class", route.failureClass().value());
        if (!route.shouldReplan()) return null;
        failureRouter.recordReplan(state, compiled.planId());
        long started = TraceEvents.started();
        try {
            PlannerResult raw = planner.replan(new ReplannerInput(
                    request.query(), request.modelId(), LocalDate.now(ZoneId.of("Asia/Shanghai")),
                    current.plan(), failureCode, failureReason,
                    "rule_id=" + safe(state.currentRuleId())
                            + "; evidence_ids=" + state.evidenceIds(),
                    compiled.planId()));
            RequestPlan plan = normalizeExplicitDifferenceDiagnosis(
                    request.query(), normalizeExplicitImplementationValidation(
                            request.query(), enrichFromResolvedIndicator(
                                    enrichFromConversation(
                                            enrichFromUploadedFile(
                                                    raw.plan(),
                                                    resolveUploadPlanningContext(request)),
                                            conversation),
                                    resolvedIndicator)));
            PlannerResult planned = new PlannerResult(
                    plan, raw.rawContent(), raw.modelId(), raw.repaired());
            CompiledPlanIR alternative = compiler.compile(plan);
            if (!failureRouter.acceptsAlternative(state, alternative.planId())) {
                TraceEvents.failed(observer, traceId, "plan_replan", "llm", started,
                        state.subtaskId(), "REPLAN_REPEATED_FAILED_PATH",
                        "重规划重复了已经失败的计划方向。",
                        "model_id", raw.modelId(),
                        "failure_class", FailureClass.classify(failureCode).value());
                return null;
            }
            PlanValidation alternativeValidation = validator.validate(plan);
            PlanningExecution execution = new PlanningExecution(
                    plan, alternative, alternativeValidation, capabilities);
            TraceEvents.completed(observer, traceId, "plan_replan", "llm", started,
                    state.subtaskId(), Map.of(
                            "original_plan_id", compiled.planId(),
                            "failure_code", failureCode,
                            "failure_reason", failureReason,
                            "known_evidence_ids", state.evidenceIds()), eventValues(
                            "raw_content", raw.rawContent(),
                            "plan_id", alternative.planId(),
                            "request_plan", tracePlan(plan),
                            "replan_count", state.replanCount(),
                            "valid", alternativeValidation.ok()),
                    "model_id", raw.modelId(),
                    "failure_class", FailureClass.classify(failureCode).value(),
                    "max_replan_count", ReplanPolicy.MAX_REPLAN_COUNT);
            return new ReplanOutcome(planned, execution);
        } catch (RuntimeException exception) {
            TraceEvents.failed(observer, traceId, "plan_replan", "llm", started,
                    state.subtaskId(), "REPLAN_FAILED", exception.getMessage(),
                    "failure_class", FailureClass.classify(failureCode).value(),
                    "max_replan_count", ReplanPolicy.MAX_REPLAN_COUNT);
            return null;
        }
    }

    private AgentRunResult compose(
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            String sessionId,
            AgentRunState state,
            String modelId,
            com.hospital.wikiagent.agent.ir.RequestPlan plan,
            CompiledPlanIR compiled,
            PlanValidation validation,
            AgentRuntimeContext context) {
        // 最终回答前重新绑定本轮 ToolResult，防止旧 SQL 或其他子任务的 Evidence 被复用。
        Map<String, ToolResult> currentResults = new LinkedHashMap<>();
        for (ToolResult result : state.lastToolResults()) {
            for (String evidenceId : result.evidenceIds()) {
                currentResults.put(evidenceId, result);
            }
        }
        String statStart = validation.resolvedTime() == null
                ? null : validation.resolvedTime().startTime().format(EVIDENCE_TIME);
        String statEnd = validation.resolvedTime() == null
                ? null : validation.resolvedTime().endTime().format(EVIDENCE_TIME);
        String sqlId = state.validatedSqlIds().isEmpty()
                ? null : state.validatedSqlIds().get(state.validatedSqlIds().size() - 1);
        ToolResult currentRuleEvidence = latestSuccessful(
                state, "EFFECTIVE_RULE_FOUND", state.currentRuleId());
        String currentRuleVersion = currentRuleEvidence == null
                ? null
                : first(
                        text(currentRuleEvidence.data().get("hospital_version")),
                        text(currentRuleEvidence.data().get("version")),
                        text(currentRuleEvidence.data().get("national_version")));
        long verifyStarted = TraceEvents.started();
        List<com.hospital.wikiagent.agent.evidence.VerifiedEvidence> evidence = verifier.verifyMany(
                state.evidenceIds(), context,
                new VerificationExpectations(
                        state.subtaskId(), state.currentRuleId(), statStart, statEnd, sqlId,
                        state.currentCaliberProfileId(), currentRuleVersion, currentResults));
        TraceEvents.completed(observer, traceId, "plan_verify", "code", verifyStarted,
                state.subtaskId(), Map.of(
                        "evidence_ids", state.evidenceIds()), eventValues(
                        "verified_count", evidence.size(), "rule_id", state.currentRuleId()),
                "rule_id", state.currentRuleId(), "sql_id", sqlId);
        // 模板选择只依赖已校验的计划，不允许模型自行挑选版式。即使下方某些高风险报告
        // 使用确定性代码渲染，Trace 也记录与该意图对应的模板编号和版本。
        var selectedTemplate = finalAnswer.selectTemplate(plan.intent(), plan.requestedOutputs());
        // SQL、候选口径模拟、实施验收和差异归因由确定性代码回答，避免模型改写
        // 高风险事实；其他意图由 Final Answer LLM 按本轮选中的模板组织。
        String deterministicAnswer = composeDifferenceDiagnosisAnswer(plan, state);
        String deterministicNode = "difference_diagnosis_answer";
        if (deterministicAnswer == null) {
            deterministicAnswer = composeImplementationValidationAnswer(plan, state);
            deterministicNode = "implementation_validation_answer";
        }
        if (deterministicAnswer == null) {
            deterministicAnswer = composeCaliberSimulationAnswer(plan, state);
            deterministicNode = "caliber_simulation_answer";
        }
        if (deterministicAnswer == null) {
            deterministicAnswer = composePreparedSqlAnswer(plan, state);
            deterministicNode = "prepared_sql_answer";
        }
        if (deterministicAnswer != null) {
            long answerStarted = TraceEvents.started();
            TraceEvents.completed(observer, traceId, deterministicNode, "code",
                    answerStarted, state.subtaskId(), Map.of(
                            "verified_evidence_count", evidence.size()), Map.of(
                            "answer_length", deterministicAnswer.length()),
                    "workflow_version", switch (deterministicNode) {
                        case "prepared_sql_answer" -> "prepared-sql-answer-v2";
                        case "difference_diagnosis_answer" -> "indicator-difference-diagnosis-v1";
                        case "caliber_simulation_answer" -> "caliber-simulation-answer-v1";
                        default -> "implementation-validation-mvp-v1";
                    },
                    "answer_template_id", selectedTemplate.id(),
                    "answer_template_version", selectedTemplate.version(),
                    "answer_template_mode", "deterministic");
            long guardStarted = TraceEvents.started();
            String answerContent = appendExportMarker(deterministicAnswer, state, request.principal());
            TraceEvents.completed(observer, traceId, "response_guard", "code", guardStarted,
                    state.subtaskId(), Map.of("answer_length", deterministicAnswer.length()), Map.of(
                            "accepted", true, "export_marker_added",
                            !answerContent.equals(deterministicAnswer)));
            emit(observer, "assistant_message", traceId, state.stepCount(), Map.of(
                    "message", answerContent, "status", "completed"));
            emit(observer, "agent_done", traceId, state.stepCount(), Map.of(
                    "stop_reason", "final_answer", "status", "completed",
                    "step_count", state.stepCount()));
            return new AgentRunResult(
                    answerContent, "final_answer", traceId, sessionId,
                    state.stepCount(), plan, compiled);
        }
        emit(observer, "model_start", traceId, state.stepCount(), Map.of("message", "生成最终回答"));
        long finalStarted = TraceEvents.started();
        var answer = finalAnswer.compose(new FinalAnswerInput(
                request.query(), plan.goal(), plan.intent(), plan.requestedOutputs(), modelId,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), request.recentHistory(), evidence));
        TraceEvents.completed(observer, traceId, "final_answer_llm", "llm", finalStarted,
                state.subtaskId(), Map.of(
                        "query", request.query(),
                        "verified_evidence_count", evidence.size(),
                        "answer_template_id", answer.templateId(),
                        "answer_template_version", answer.templateVersion()),
                Map.of(
                        "answer_length", answer.content().length(),
                        "corrected", answer.corrected(),
                        "answer_contract_validated", answer.contractValidated()),
                "model_id", answer.modelId(),
                "deterministic_fallback", answer.deterministicFallback(),
                "answer_template_id", answer.templateId(),
                "answer_template_version", answer.templateVersion());
        long guardStarted = TraceEvents.started();
        String answerContent = appendExportMarker(answer.content(), state, request.principal());
        TraceEvents.completed(observer, traceId, "response_guard", "code", guardStarted,
                state.subtaskId(), Map.of("answer_length", answer.content().length()), Map.of(
                        "accepted", true, "export_marker_added",
                        !answerContent.equals(answer.content())));
        emit(observer, "assistant_message", traceId, state.stepCount(), Map.of(
                "message", answerContent, "status", "completed"));
        emit(observer, "agent_done", traceId, state.stepCount(), Map.of(
                "stop_reason", "final_answer", "status", "completed",
                "step_count", state.stepCount()));
        return new AgentRunResult(
                answerContent, "final_answer", traceId, sessionId,
                state.stepCount(), plan, compiled);
    }

    private AgentRunResult finishFallback(
            AgentRunObserver observer,
            String traceId,
            String sessionId,
            AgentRunState state,
            com.hospital.wikiagent.agent.ir.RequestPlan plan,
            CompiledPlanIR compiled,
            ControllerDecision decision) {
        emit(observer, "clarification_required", traceId, state.stepCount(), eventValues(
                "message", decision.message(), "code", decision.code(),
                "fallback_category", decision.fallbackCategory() == null
                        ? null : decision.fallbackCategory().name(),
                "stop_reason", "clarification"));
        emit(observer, "agent_done", traceId, state.stepCount(), Map.of(
                "stop_reason", "clarification", "status", "incomplete",
                "step_count", state.stepCount()));
        return new AgentRunResult(
                decision.message(), "clarification", traceId, sessionId,
                state.stepCount(), plan, compiled);
    }

    private AgentRunResult finishFailure(
            AgentRunObserver observer,
            String traceId,
            String sessionId,
            AgentRunState state,
            com.hospital.wikiagent.agent.ir.RequestPlan plan,
            CompiledPlanIR compiled,
            String message,
            String code) {
        emit(observer, "agent_error", traceId, state.stepCount(), Map.of(
                "message", message, "failure_code", code,
                "stop_reason", "tool_error", "status", "failed"));
        return new AgentRunResult(
                message, "tool_error", traceId, sessionId,
                state.stepCount(), plan, compiled);
    }

    private static void updateState(AgentRunState state, ToolResult result) {
        if (!result.ok()) {
            return;
        }
        if ("RULE_SEARCHED".equals(result.code())) {
            Object ruleId = result.data().get("resolved_rule_id");
            if (ruleId != null && !ruleId.toString().isBlank()) {
                state.currentRuleId(ruleId.toString());
            }
        }
        if ("SQL_OBJECT_PREPARED".equals(result.code())
                || "CALIBER_SQL_PREPARED".equals(result.code())) {
            Object sqlId = result.data().get("sql_id");
            if (sqlId != null && !sqlId.toString().isBlank()
                    && !state.validatedSqlIds().contains(sqlId.toString())) {
                state.validatedSqlIds().add(sqlId.toString());
            }
        }
        if ("INDICATOR_DIAGNOSED".equals(result.code())
                || "DIFFERENCE_DIAGNOSIS_COMPLETED".equals(result.code())) {
            Object reportId = result.data().get("report_id");
            if (reportId != null && !reportId.toString().isBlank()) {
                state.lastDiagnosisId(reportId.toString());
            }
        }
        if ("CALIBER_PROFILE_RESOLVED".equals(result.code())) {
            state.currentCaliber(
                    text(result.data().get("caliber_profile_id")),
                    text(result.data().get("caliber_label")));
        }
        if ("TRIAL_RUN_COMPLETED".equals(result.code())
                || "CALIBER_TRIAL_RUN_COMPLETED".equals(result.code())) {
            Object runId = result.data().get("run_id");
            if (runId != null && !runId.toString().isBlank()) {
                state.lastRunId(runId.toString());
            }
        }
    }

    private void saveConversation(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ConversationSnapshot conversation,
            com.hospital.wikiagent.auth.HospitalPrincipal principal,
            String answer,
            AgentRunState state) {
        long started = TraceEvents.started();
        conversations.appendAssistant(conversation, principal, answer, state);
        TraceEvents.completed(observer, traceId, "memory_save", "storage", started,
                subtaskId, Map.of("session_id", conversation.sessionId()), Map.of(
                        "answer_length", answer == null ? 0 : answer.length(),
                        "evidence_count", state.evidenceIds().size(),
                        "rule_id", safe(state.currentRuleId()),
                        "stat_start", safe(state.statStart()),
                        "stat_end", safe(state.statEnd())));
    }

    private static AgentRunRequest withConversationContext(
            AgentRunRequest request,
            ConversationSnapshot conversation) {
        String structured = "请求携带状态：\n" + safe(request.structuredState())
                + "\n服务端会话状态：\n" + safe(conversation.structuredSummary());
        String history = join(conversation.recentHistory(), request.recentHistory());
        return new AgentRunRequest(
                request.query(), conversation.sessionId(), request.modelId(), request.fileKey(),
                request.requestId(), request.traceId(), request.dbSourceId(),
                structured, history, request.principal());
    }

    private static RequestPlan enrichFromConversation(
            RequestPlan plan,
            ConversationSnapshot conversation) {
        RequestPlan.TargetIndicator target = plan.targetIndicator();
        if (target.ruleId() == null
                && conversation.ruleId() != null
                && canReuseConversationIndicator(target.rawName(), conversation)) {
            target = new RequestPlan.TargetIndicator(
                    first(target.rawName(), conversation.ruleName(), conversation.ruleId()),
                    conversation.ruleId());
        }
        RequestPlan.TargetCaliber caliber = plan.targetCaliber();
        if (caliber.rawText().isBlank() && caliber.profileId() == null
                && conversation.caliberProfileId() != null) {
            caliber = new RequestPlan.TargetCaliber(
                    first(conversation.caliberLabel(), conversation.caliberProfileId()),
                    conversation.caliberProfileId());
        }
        RequestPlan.TimeExpression time = plan.timeExpression();
        if (time.rawText().isBlank() && time.startTime() == null && time.endTime() == null
                && conversation.statStart() != null && conversation.statEnd() != null) {
            time = new RequestPlan.TimeExpression(
                    "沿用上一轮统计区间", conversation.statStart(), conversation.statEnd());
        }
        return new RequestPlan(
                plan.schemaVersion(), plan.intent(), plan.goal(), target, caliber, time,
                plan.requestedOutputs(), plan.constraints(), plan.semanticAmbiguities());
    }

    /**
     * 判断 Planner 返回的“只有名称、没有 rule_id”的目标是否仍是上一轮指标。
     *
     * <p>不能因为存在历史指标就覆盖任意新名称，否则用户切换指标时可能串用旧规则。
     * 这里只接受空名称、明确指代词或去除空格标点后完全相同的名称；新指标仍交给
     * HybridIndicatorResolver 重新确认。</p>
     */
    private static boolean canReuseConversationIndicator(
            String rawName,
            ConversationSnapshot conversation) {
        String target = normalizeIndicatorReference(rawName);
        if (target.isBlank()
                || Set.of("这个指标", "该指标", "当前指标", "这个", "它").contains(target)) {
            return true;
        }
        String previousName = normalizeIndicatorReference(conversation.ruleName());
        String previousId = normalizeIndicatorReference(conversation.ruleId());
        return target.equals(previousName) || target.equals(previousId);
    }

    private static String normalizeIndicatorReference(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s，。、“”‘’：:；;？?（）()【】\\[\\]_-]+", "");
    }

    /**
     * 使用上传文件中已解析的非患者元数据补全缺失计划字段。
     *
     * <p>这里只补空值，因此不会覆盖用户本轮明确输入；调用顺序位于会话补全之前，确保
     * 文件统计区间优先于历史会话区间。文件与本轮明确区间的冲突仍由 Workflow 预检
     * 负责阻断并要求用户确认。</p>
     */
    private static RequestPlan enrichFromUploadedFile(
            RequestPlan plan,
            PlanningContext file) {
        if (file == null) return plan;
        RequestPlan.TargetIndicator target = plan.targetIndicator();
        if (target.rawName().isBlank() && target.ruleId() == null
                && (file.ruleId() != null || file.ruleName() != null)) {
            target = new RequestPlan.TargetIndicator(
                    first(file.ruleName(), file.ruleId()), file.ruleId());
        }
        RequestPlan.TimeExpression time = plan.timeExpression();
        if (time.rawText().isBlank() && time.startTime() == null && time.endTime() == null
                && file.hasTimeRange()) {
            time = new RequestPlan.TimeExpression(
                    first(file.rawPeriod(), "上传文件统计区间"),
                    file.statStart(),
                    file.statEnd());
        }
        return new RequestPlan(
                plan.schemaVersion(), plan.intent(), plan.goal(), target, plan.targetCaliber(), time,
                plan.requestedOutputs(), plan.constraints(), plan.semanticAmbiguities());
    }

    private PlanningContext resolveUploadPlanningContext(AgentRunRequest request) {
        if (uploadPlanningContext == null || request.fileKey() == null
                || request.fileKey().isBlank()) {
            return PlanningContext.empty();
        }
        try {
            return uploadPlanningContext.resolve(
                    request.fileKey(), request.principal().hospitalId());
        } catch (RuntimeException ignored) {
            // 规划阶段不得将文件解析异常转换成猜测值；正式错误由受控上传工具返回。
            return PlanningContext.empty();
        }
    }

    private static RequestPlan enrichFromResolvedIndicator(
            RequestPlan plan,
            HybridIndicatorResolver.ResolvedIndicator resolved) {
        if (resolved == null) return plan;
        return new RequestPlan(
                plan.schemaVersion(), plan.intent(), plan.goal(),
                new RequestPlan.TargetIndicator(resolved.canonicalName(), resolved.ruleId()),
                plan.targetCaliber(), plan.timeExpression(), plan.requestedOutputs(), plan.constraints(),
                plan.semanticAmbiguities());
    }

    /**
     * 将具备完整上一轮事实的 SQL 追问确定性转换为业务计划。
     *
     * <p>该分支只接受明确出现 SQL/脚本的追问，并要求指标身份和完整统计区间都已存在；
     * 任何事实缺失仍交给 Planner 或用户澄清，不能从历史回答猜造规则或日期。</p>
     */
    private static RequestPlan deterministicSqlFollowup(
            String query,
            ConversationSnapshot conversation,
            String recentHistory,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        String compact = query == null ? "" : query.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (!compact.contains("sql") && !compact.contains("脚本")) return null;
        // 当前问题提供了新时间时必须重新走正常解析，不能被上一轮区间覆盖。
        if (CURRENT_QUERY_TIME.matcher(compact).find()) return null;
        String ruleId = first(
                resolvedIndicator == null ? null : resolvedIndicator.ruleId(), conversation.ruleId());
        String ruleName = first(
                resolvedIndicator == null ? null : resolvedIndicator.canonicalName(),
                conversation.ruleName(), ruleId);
        String statStart = conversation.statStart();
        String statEnd = conversation.statEnd();
        if (statStart == null || statEnd == null) {
            List<String> historyTimes = historyTimes(recentHistory);
            if (historyTimes.size() >= 2) {
                statStart = historyTimes.get(historyTimes.size() - 2);
                statEnd = historyTimes.get(historyTimes.size() - 1);
            }
        }
        if (ruleId == null || statStart == null || statEnd == null) return null;
        boolean candidateReference = conversation.caliberProfileId() != null
                && (compact.contains("这个口径")
                        || compact.contains("该口径")
                        || compact.contains("候选口径")
                        || compact.contains("刚才口径"));
        if (candidateReference) {
            return new RequestPlan(
                    RequestPlan.VERSION,
                    PlanIntent.INDICATOR_CALIBER_SIMULATION,
                    query == null ? "生成上一轮候选口径的受控 SQL" : query,
                    new RequestPlan.TargetIndicator(ruleName, ruleId),
                    new RequestPlan.TargetCaliber(
                            first(conversation.caliberLabel(),
                                    conversation.caliberProfileId()),
                            conversation.caliberProfileId()),
                    new RequestPlan.TimeExpression(
                            "沿用上一轮统计区间", statStart, statEnd),
                    List.of(
                            RequestedOutput.CALIBER_EXPLANATION,
                            RequestedOutput.CALIBER_PREPARED_SQL_HANDLE),
                    List.of(),
                    List.of());
        }
        return new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.INDICATOR_SQL_PREPARE,
                query == null ? "生成上一轮指标的受控 SQL" : query,
                new RequestPlan.TargetIndicator(ruleName, ruleId),
                new RequestPlan.TimeExpression("沿用上一轮统计区间", statStart, statEnd),
                List.of(RequestedOutput.PREPARED_SQL_HANDLE),
                List.of(),
                List.of());
    }

    private static List<String> historyTimes(String history) {
        List<String> values = new ArrayList<>();
        Matcher iso = ISO_HISTORY_TIME.matcher(safe(history));
        while (iso.find()) {
            values.add(iso.group(1).replace('T', ' '));
        }
        if (values.size() >= 2) return values;
        values.clear();
        Matcher chinese = CHINESE_HISTORY_TIME.matcher(safe(history));
        while (chinese.find()) {
            int hour = chinese.group(4) == null ? 0 : Integer.parseInt(chinese.group(4));
            int minute = chinese.group(5) == null ? 0 : Integer.parseInt(chinese.group(5));
            int second = chinese.group(6) == null ? 0 : Integer.parseInt(chinese.group(6));
            LocalDateTime parsed = LocalDateTime.of(
                    Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)),
                    Integer.parseInt(chinese.group(3)), hour, minute, second);
            values.add(parsed.format(EVIDENCE_TIME));
        }
        return values;
    }

    /**
     * 对明确的“双方结果不一致”表达做服务端兜底路由。
     *
     * <p>Planner 仍会给出完整 RequestPlan；此处只在用户文本已经明确包含比较对象和差异
     * 诉求时收敛为分层诊断，避免 4B 模型把上传对比误判成普通文件分析或通用异常诊断。
     * 单纯“指标为什么偏低”没有双方比较对象，不会命中本分支。</p>
     */
    private static RequestPlan normalizeExplicitDifferenceDiagnosis(
            String query,
            RequestPlan plan) {
        String compact = query == null ? "" : query.replaceAll("\\s+", "");
        boolean differencePhrase = List.of(
                "不一样", "不一致", "差异", "差在哪", "为什么我们",
                "为什么你们", "为什么系统", "与系统核对", "和系统核对",
                "与本院对比", "和本院对比", "具体差异记录")
                .stream().anyMatch(compact::contains);
        boolean twoSided = List.of("我们", "我方", "用户", "文件", "表格")
                .stream().anyMatch(compact::contains)
                && List.of("系统", "你们", "本院", "平台")
                .stream().anyMatch(compact::contains);
        boolean explicitCompare = compact.contains("对比") || compact.contains("核对");
        boolean pureUploadAnalysis = plan.intent() == PlanIntent.UPLOAD_ANALYSIS
                && !differencePhrase && !twoSided && !explicitCompare;
        if (pureUploadAnalysis || !(differencePhrase || twoSided || explicitCompare)) {
            return plan;
        }
        RequestPlan.TargetIndicator target = plan.targetIndicator();
        if (target.rawName().isBlank() && target.ruleId() == null && query != null) {
            target = new RequestPlan.TargetIndicator(query, null);
        }
        RequestPlan.TimeExpression time = plan.timeExpression();
        if (time.rawText().isBlank() && time.startTime() == null && time.endTime() == null
                && query != null) {
            time = new RequestPlan.TimeExpression(query, null, null);
        }
        return new RequestPlan(
                plan.schemaVersion(),
                PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS,
                plan.goal(),
                target,
                plan.targetCaliber(),
                time,
                List.of(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT),
                plan.constraints(),
                plan.semanticAmbiguities());
    }

    private static RequestPlan normalizeExplicitImplementationValidation(
            String query,
            RequestPlan plan) {
        String compact = query == null ? "" : query.replaceAll("\\s+", "");
        boolean explicit = List.of("全面实施验收", "全面实施验证", "上线验收", "迁移核对", "全链路验收")
                .stream().anyMatch(compact::contains);
        if (!explicit) return plan;
        RequestPlan.TargetIndicator target = plan.targetIndicator();
        if (target.rawName().isBlank() && target.ruleId() == null && query != null) {
            target = new RequestPlan.TargetIndicator(query, null);
        }
        RequestPlan.TimeExpression time = plan.timeExpression();
        if (time.rawText().isBlank() && time.startTime() == null && time.endTime() == null
                && query != null) {
            time = new RequestPlan.TimeExpression(query, null, null);
        }
        return new RequestPlan(
                plan.schemaVersion(),
                PlanIntent.IMPLEMENTATION_VALIDATION,
                plan.goal(),
                target,
                plan.targetCaliber(),
                time,
                List.of(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT),
                plan.constraints(),
                plan.semanticAmbiguities());
    }

    @SuppressWarnings("unchecked")
    private static void emitImplementationValidationStages(
            AgentRunObserver observer,
            String traceId,
            String subtaskId,
            ToolResult result) {
        if (!result.ok() || !"IMPLEMENTATION_VALIDATION_COMPLETED".equals(result.code())
                || !(result.data().get("stages") instanceof List<?> stages)) {
            return;
        }
        for (Object raw : stages) {
            if (!(raw instanceof Map<?, ?> rawStage)) continue;
            Map<String, Object> stage = (Map<String, Object>) rawStage;
            String stageId = String.valueOf(stage.getOrDefault("stage_id", "stage")).toLowerCase();
            String stageStatus = String.valueOf(stage.getOrDefault("status", "failed"));
            String traceStatus = switch (stageStatus) {
                case "passed", "skipped" -> "success";
                case "warning" -> "warning";
                default -> "failed";
            };
            long duration = stage.get("duration_ms") instanceof Number number ? number.longValue() : 0;
            List<?> findings = stage.get("finding_codes") instanceof List<?> values ? values : List.of();
            String failureCode = findings.isEmpty()
                    ? "IMPLEMENTATION_VALIDATION_FAILED"
                    : String.valueOf(findings.get(0));
            TraceEvents.recorded(observer, traceId,
                    "implementation_validation_" + stageId, "code", traceStatus,
                    duration, subtaskId,
                    Map.of("workflow_version", "implementation-validation-mvp-v1"),
                    stage,
                    "capability", "validate_implementation",
                    "error_code", "failed".equals(traceStatus) ? failureCode : null,
                    "failure_class", "failed".equals(traceStatus)
                            ? FailureClass.classify(failureCode).value()
                            : null);
        }
    }

    /**
     * 差异报告使用确定性模板呈现，避免模型把“证据不足”改写成未经确认的原因。
     */
    @SuppressWarnings("unchecked")
    private static String composeDifferenceDiagnosisAnswer(
            RequestPlan plan,
            AgentRunState state) {
        if (!plan.requestedOutputs().contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT)) {
            return null;
        }
        ToolResult report = null;
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (candidate.ok() && "DIFFERENCE_DIAGNOSIS_COMPLETED".equals(candidate.code())) {
                report = candidate;
                break;
            }
        }
        if (report == null) return null;
        Map<String, Object> data = report.data();
        Map<String, Object> baseline = data.get("baseline_result") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        Map<String, Object> external = data.get("external_evidence") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        StringBuilder answer = new StringBuilder("# 指标结果差异诊断\n\n");
        answer.append("- 报告编号：").append(data.getOrDefault("report_id", "—")).append('\n');
        answer.append("- 统计区间：").append(data.getOrDefault("stat_start", "—"))
                .append(" 至 ").append(data.getOrDefault("stat_end", "—")).append('\n');
        answer.append("- 结论代码：").append(data.getOrDefault("conclusion_code", "—")).append('\n');
        answer.append("- 停止层级：第 ").append(data.getOrDefault("stopped_layer", "—")).append(" 层\n\n");
        if (!baseline.isEmpty()) {
            answer.append("## 当前生效口径结果\n\n")
                    .append("- 分子：").append(baseline.getOrDefault("numerator_count", "—")).append('\n')
                    .append("- 分母：").append(baseline.getOrDefault("denominator_count", "—")).append('\n')
                    .append("- 指标值：").append(baseline.getOrDefault("result_value", "—")).append("%\n\n");
        }
        if (!external.isEmpty()) {
            answer.append("## 用户或文件结果\n\n")
                    .append("- 分子：").append(external.getOrDefault("numerator", "未提供")).append('\n')
                    .append("- 分母：").append(external.getOrDefault("denominator", "未提供")).append('\n')
                    .append("- 指标值：").append(external.getOrDefault("rate", "未提供")).append("\n\n");
        }
        appendCaliberCandidates(answer, data.get("caliber_candidates"));
        answer.append("## 诊断结论\n\n")
                .append(data.getOrDefault("user_summary", "诊断已完成。")).append('\n');
        if (data.get("confirmed_findings") instanceof List<?> findings && !findings.isEmpty()) {
            answer.append("\n已确认事实：\n\n");
            findings.forEach(item -> answer.append("- ").append(markdown(item)).append('\n'));
        }
        answer.append("\n证据限制：")
                .append(data.getOrDefault("evidence_limit",
                        "未发现系统异常不等于用户结果必然错误。"));
        return answer.toString();
    }

    /**
     * 将候选口径的真实试运行聚合值和逐维比较结论放入最终回答。
     *
     * <p>这里只展示分子、分母、指标率等安全汇总数据；SQL 正文和患者级记录仍留在
     * 受权限保护的对象中。这样用户可以直接看出“哪个口径接近、还差在哪个维度”，
     * 不必只依赖一条笼统的原因描述。</p>
     */
    private static void appendCaliberCandidates(StringBuilder answer, Object rawCandidates) {
        if (!(rawCandidates instanceof List<?> candidates) || candidates.isEmpty()) return;
        answer.append("## 候选口径试算\n\n")
                .append("| 候选口径 | 分子 | 分母 | 指标率 | 匹配等级 | 原因判断 |\n")
                .append("|---|---:|---:|---:|---|---|\n");
        for (Object raw : candidates) {
            if (!(raw instanceof Map<?, ?> candidate)) continue;
            answer.append("| ").append(markdown(candidate.get("label")))
                    .append(" | ").append(markdown(candidateValue(candidate, "numerator_count")))
                    .append(" | ").append(markdown(candidateValue(candidate, "denominator_count")))
                    .append(" | ").append(markdown(candidateValue(candidate, "result_value")))
                    .append("% | ").append(candidateMatchLabel(candidate.get("match_level")))
                    .append(" | ").append(candidateLikelihoodLabel(candidate.get("cause_likelihood")))
                    .append(" |\n");
        }
        answer.append('\n');
        for (Object raw : candidates) {
            if (!(raw instanceof Map<?, ?> candidate)) continue;
            Object matching = candidate.get("matching_dimensions");
            Object mismatched = candidate.get("mismatched_dimensions");
            if (!(matching instanceof List<?> matched) || matched.isEmpty()) continue;
            answer.append("- ").append(markdown(candidate.get("label")))
                    .append("：已匹配 ").append(candidateDimensions(matched));
            if (mismatched instanceof List<?> missed && !missed.isEmpty()) {
                answer.append("；仍有差异 ").append(candidateDimensions(missed));
                if (candidate.get("metric_differences") instanceof List<?> differences
                        && !differences.isEmpty()) {
                    answer.append("（");
                    boolean first = true;
                    for (Object differenceRaw : differences) {
                        if (!(differenceRaw instanceof Map<?, ?> difference)) continue;
                        if (!first) answer.append("；");
                        answer.append(candidateDimensionLabel(difference.get("dimension")))
                                .append("：候选 ")
                                .append(markdown(difference.get("candidate_value")))
                                .append("，用户/文件 ")
                                .append(markdown(difference.get("external_value")))
                                .append("，差值 ")
                                .append(markdown(difference.get("delta")));
                        first = false;
                    }
                    answer.append('）');
                }
            }
            answer.append("。\n");
        }
        answer.append('\n');
    }

    private static Object candidateValue(Map<?, ?> candidate, String key) {
        Object value = candidate.get(key);
        return value == null || String.valueOf(value).isBlank() ? "—" : value;
    }

    private static String candidateMatchLabel(Object value) {
        return switch (String.valueOf(value)) {
            case "exact" -> "完全匹配";
            case "partial" -> "部分匹配";
            case "none" -> "未匹配";
            default -> "未比较";
        };
    }

    private static String candidateLikelihoodLabel(Object value) {
        return switch (String.valueOf(value)) {
            case "confirmed" -> "已确认";
            case "likely" -> "高度相关";
            case "possible" -> "可能相关";
            default -> "未发现关联";
        };
    }

    private static String candidateDimensions(List<?> dimensions) {
        return dimensions.stream()
                .map(AgentRunner::candidateDimensionLabel)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("—");
    }

    private static String candidateDimensionLabel(Object value) {
        return switch (String.valueOf(value)) {
            case "numerator" -> "分子";
            case "denominator" -> "分母";
            case "rate" -> "指标率";
            default -> markdown(value);
        };
    }

    @SuppressWarnings("unchecked")
    private static String composeImplementationValidationAnswer(
            RequestPlan plan,
            AgentRunState state) {
        if (!plan.requestedOutputs().contains(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT)) {
            return null;
        }
        ToolResult report = null;
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (candidate.ok() && "IMPLEMENTATION_VALIDATION_COMPLETED".equals(candidate.code())) {
                report = candidate;
                break;
            }
        }
        if (report == null) return null;
        Map<String, Object> data = report.data();
        String overall = switch (String.valueOf(data.get("overall_status"))) {
            case "passed" -> "通过";
            case "warning" -> "有警告";
            case "failed" -> "未通过";
            default -> "已完成";
        };
        StringBuilder answer = new StringBuilder("# 指标全面实施验收报告\n\n");
        answer.append("- 报告编号：").append(data.get("report_id")).append('\n');
        answer.append("- 指标：").append(data.getOrDefault("rule_name", ""))
                .append("（").append(data.get("rule_id")).append("）\n");
        answer.append("- 统计区间：").append(data.get("stat_start"))
                .append(" 至 ").append(data.get("stat_end")).append('\n');
        answer.append("- 总体结论：").append(overall).append("\n\n");
        answer.append("| 阶段 | 状态 | 结论 |\n|---|---|---|\n");
        if (data.get("stages") instanceof List<?> stages) {
            for (Object raw : stages) {
                if (!(raw instanceof Map<?, ?> stage)) continue;
                answer.append("| ").append(markdown(stage.get("stage_id"))).append(' ')
                        .append(markdown(stage.get("stage_name"))).append(" | ")
                        .append(stageStatus(stage.get("status"))).append(" | ")
                        .append(markdown(stage.get("summary"))).append(" |\n");
            }
        }
        if (data.get("run_id") != null) {
            answer.append("\n试运行结果：分子 ").append(data.getOrDefault("numerator_count", "—"))
                    .append("，分母 ").append(data.getOrDefault("denominator_count", "—"))
                    .append("，指标值 ").append(data.getOrDefault("result_value", "—"))
                    .append("%。\n");
        }
        return answer.toString().stripTrailing();
    }

    private static String composePreparedSqlAnswer(RequestPlan plan, AgentRunState state) {
        if (plan.intent() != PlanIntent.INDICATOR_SQL_PREPARE
                && !plan.requestedOutputs().contains(RequestedOutput.PREPARED_SQL_HANDLE)) {
            return null;
        }
        ToolResult prepared = null;
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (candidate.ok() && "SQL_OBJECT_PREPARED".equals(candidate.code())) {
                prepared = candidate;
                break;
            }
        }
        if (prepared == null) return null;
        Object sql = prepared.data().get("sql_preview");
        if (sql == null || String.valueOf(sql).isBlank()) return null;
        ToolResult effectiveRule = latestSuccessful(state, "EFFECTIVE_RULE_FOUND",
                text(prepared.data().get("rule_id")));
        StringBuilder answer = new StringBuilder();
        if (effectiveRule != null) {
            Map<String, Object> rule = effectiveRule.data();
            answer.append("## 本院生效口径\n\n");
            appendCaliber(answer, "指标", rule.get("rule_name"));
            appendCaliber(answer, "定义", rule.get("definition"));
            appendCaliber(answer, "公式", rule.get("formula"));
            appendCaliber(answer, "分子口径", rule.get("numerator_rule"));
            appendCaliber(answer, "分母口径", rule.get("denominator_rule"));
            appendCaliber(answer, "纳入/过滤条件", rule.get("filter_rule"));
            appendCaliber(answer, "排除条件", rule.get("exclude_rule"));
            answer.append('\n');
        }
        answer.append("## 已校验 SQL\n\n```sql\n")
                .append(sql).append("\n```\n\n");
        answer.append("- SQL 对象：").append(prepared.data().get("sql_id")).append('\n');
        answer.append("- 统计区间：").append(prepared.data().get("stat_start"))
                .append(" 至 ").append(prepared.data().get("stat_end")).append("（左闭右开）\n");
        answer.append("- 参数：").append(prepared.data().getOrDefault("parameters", Map.of())).append('\n');
        answer.append("\n该请求只生成并校验 SQL，不执行数据库。");
        return answer.toString();
    }

    /**
     * 用候选口径工具的已验证结果生成确定性回答。
     *
     * <p>该回答明确区分“当前生效规则”和“候选模拟口径”，并在输出数值前再次核对
     * profile、SQL 和统计周期。模型不参与改写这些事实，避免把候选结果误称为正式口径。</p>
     */
    private static String composeCaliberSimulationAnswer(
            RequestPlan plan,
            AgentRunState state) {
        if (plan.intent() != PlanIntent.INDICATOR_CALIBER_SIMULATION
                && !plan.requestedOutputs().contains(RequestedOutput.CALIBER_EXPLANATION)
                && !plan.requestedOutputs().contains(RequestedOutput.CALIBER_TRIAL_RESULT)) {
            return null;
        }
        ToolResult profile = latestSuccessful(
                state, "CALIBER_PROFILE_RESOLVED", state.currentRuleId());
        if (profile == null) return null;
        String profileId = text(profile.data().get("caliber_profile_id"));
        if (profileId == null || !profileId.equals(state.currentCaliberProfileId())) {
            return "候选口径结果未通过 profile 一致性校验，本轮不输出模拟数值。";
        }

        ToolResult trial = latestSuccessful(
                state, "CALIBER_TRIAL_RUN_COMPLETED", state.currentRuleId());
        ToolResult prepared = latestSuccessful(
                state, "CALIBER_SQL_PREPARED", state.currentRuleId());
        boolean wantsTrial = plan.requestedOutputs().contains(
                RequestedOutput.CALIBER_TRIAL_RESULT);
        boolean wantsPreparedSql = plan.requestedOutputs().contains(
                RequestedOutput.CALIBER_PREPARED_SQL_HANDLE);
        if (wantsTrial && trial == null) return null;
        if (wantsPreparedSql && prepared == null) return null;
        if (prepared != null
                && (!profileId.equals(text(prepared.data().get("caliber_profile_id")))
                        || !java.util.Objects.equals(
                                text(profile.data().get("caliber_version")),
                                text(prepared.data().get("caliber_version"))))) {
            return "候选口径 SQL 未通过 profile 一致性校验，本轮不输出 SQL。";
        }
        if (trial != null) {
            String trialProfileId = text(trial.data().get("caliber_profile_id"));
            String trialProfileVersion = text(trial.data().get("caliber_version"));
            String resolvedProfileVersion = text(profile.data().get("caliber_version"));
            String sqlId = text(trial.data().get("sql_id"));
            String caliberSqlId = text(trial.data().get("caliber_sql_id"));
            if (!profileId.equals(trialProfileId)
                    || !java.util.Objects.equals(
                            resolvedProfileVersion, trialProfileVersion)
                    || sqlId == null || !sqlId.equals(caliberSqlId)
                    || !sameTime(state.statStart(), text(trial.data().get("stat_start")))
                    || !sameTime(state.statEnd(), text(trial.data().get("stat_end")))) {
                return "候选口径结果未通过规则、周期或 SQL 证据链校验，本轮不输出模拟数值。";
            }
        }

        Map<String, Object> values = trial != null
                ? trial.data()
                : prepared != null ? prepared.data() : profile.data();
        StringBuilder answer = new StringBuilder();
        answer.append("## 候选口径模拟\n\n");
        answer.append("> 这是一项候选/假设口径试算，不是本院当前生效规则，")
                .append("不会修改或发布医院正式口径。\n\n");
        appendCaliber(answer, "指标", first(
                text(values.get("rule_name")), plan.targetIndicator().rawName()));
        appendCaliber(answer, "候选口径", values.get("caliber_label"));
        appendCaliber(answer, "候选 profile", values.get("caliber_profile_id"));
        appendCaliber(answer, "候选版本", values.get("caliber_version"));
        appendCaliber(answer, "口径定义", values.get("caliber_definition"));
        appendCaliber(answer, "统计周期时间字段", values.get("period_anchor_label"));
        appendCaliber(answer, "48 小时耗时起点", values.get("elapsed_anchor_label"));
        appendCaliber(answer, "分子口径", values.get("caliber_numerator_rule"));
        appendCaliber(answer, "分母口径", values.get("caliber_denominator_rule"));
        if (trial != null) {
            answer.append("\n## 只读试运行结果\n\n");
            answer.append("- 统计区间：").append(values.get("stat_start"))
                    .append(" 至 ").append(values.get("stat_end"))
                    .append("（左闭右开）\n");
            answer.append("- 分子：").append(values.getOrDefault("numerator_count", "—"))
                    .append('\n');
            answer.append("- 分母：").append(values.getOrDefault("denominator_count", "—"))
                    .append('\n');
            answer.append("- 指标率：").append(values.getOrDefault("result_value", "—"))
                    .append("%\n");
            answer.append("- SQL 对象：").append(values.get("sql_id")).append('\n');
            answer.append("- 运行对象：").append(values.get("run_id")).append('\n');
        } else if (prepared != null) {
            answer.append("\n## 已校验候选口径 SQL\n\n```sql\n")
                    .append(prepared.data().get("sql_preview"))
                    .append("\n```\n\n");
            answer.append("- 统计区间：").append(prepared.data().get("stat_start"))
                    .append(" 至 ").append(prepared.data().get("stat_end"))
                    .append("（左闭右开）\n");
            answer.append("- SQL 对象：").append(prepared.data().get("sql_id")).append('\n');
            answer.append("- 参数：")
                    .append(prepared.data().getOrDefault("parameters", Map.of()))
                    .append('\n');
            answer.append("\n该请求只生成并校验候选口径 SQL，不执行数据库。");
        } else {
            answer.append("\n当前只解释候选公式；如需具体数值，请提供统计时间范围。");
        }
        return answer.toString().stripTrailing();
    }

    private static ToolResult latestSuccessful(
            AgentRunState state,
            String code,
            String expectedRuleId) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (!candidate.ok() || !code.equals(candidate.code())) continue;
            String candidateRuleId = text(candidate.data().get("rule_id"));
            if (expectedRuleId == null || expectedRuleId.equals(candidateRuleId)) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendCaliber(StringBuilder answer, String label, Object rawValue) {
        String value = text(rawValue);
        if (value != null) {
            answer.append("- ").append(label).append("：").append(value).append('\n');
        }
    }

    private static boolean sameTime(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return expected.replace('T', ' ').equals(actual.replace('T', ' '));
    }

    private static void applyResolvedTime(AgentRunState state, PlanValidation validation) {
        if (validation != null && validation.resolvedTime() != null) {
            state.statPeriod(
                    validation.resolvedTime().startTime().format(EVIDENCE_TIME),
                    validation.resolvedTime().endTime().format(EVIDENCE_TIME));
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value).strip();
    }

    private static String stageStatus(Object value) {
        return switch (String.valueOf(value)) {
            case "passed" -> "通过";
            case "warning" -> "警告";
            case "failed" -> "未通过";
            case "skipped" -> "已跳过";
            default -> "未知";
        };
    }

    private static String markdown(Object value) {
        return value == null ? "" : String.valueOf(value).replace("|", "\\|").replace("\n", " ");
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

    private static Map<String, Object> eventValues(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    /**
     * 把 Planner 的完整业务计划写入 Trace。
     *
     * <p>Planner 本身只负责自然语言到业务计划的转换；如果 Trace 只记录 intent，
     * 就无法判断指标名、时间表达、输出目标或歧义究竟在哪一步丢失。这里保留所有
     * RequestPlan 字段（包括值为 null 的字段），便于对比不同模型的结构化输出。</p>
     */
    private static Map<String, Object> tracePlan(RequestPlan plan) {
        Map<String, Object> targetIndicator = new LinkedHashMap<>();
        targetIndicator.put("raw_name", plan.targetIndicator().rawName());
        targetIndicator.put("rule_id", plan.targetIndicator().ruleId());
        Map<String, Object> targetCaliber = new LinkedHashMap<>();
        targetCaliber.put("raw_text", plan.targetCaliber().rawText());
        targetCaliber.put("profile_id", plan.targetCaliber().profileId());

        Map<String, Object> timeExpression = new LinkedHashMap<>();
        timeExpression.put("raw_text", plan.timeExpression().rawText());
        timeExpression.put("start_time", plan.timeExpression().startTime());
        timeExpression.put("end_time", plan.timeExpression().endTime());

        List<Map<String, Object>> ambiguities = plan.semanticAmbiguities().stream()
                .map(ambiguity -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("field", ambiguity.field());
                    value.put("description", ambiguity.description());
                    return value;
                })
                .toList();

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", plan.schemaVersion());
        value.put("intent", plan.intent().name());
        value.put("goal", plan.goal());
        value.put("target_indicator", targetIndicator);
        value.put("target_caliber", targetCaliber);
        value.put("time_expression", timeExpression);
        value.put("requested_outputs", plan.requestedOutputs().stream().map(Enum::name).toList());
        value.put("constraints", plan.constraints());
        value.put("semantic_ambiguities", ambiguities);
        return value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "{}" : value.strip();
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right.strip();
        }
        if (right == null || right.isBlank()) {
            return left.strip();
        }
        return left.strip() + "\n" + right.strip();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record ReplanOutcome(PlannerResult planned, PlanningExecution execution) { }

    private static String appendExportMarker(
            String content,
            AgentRunState state,
            com.hospital.wikiagent.auth.HospitalPrincipal principal) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (!candidate.ok() || !"DIFFERENCE_DIAGNOSIS_COMPLETED".equals(candidate.code())) {
                continue;
            }
            String reportId = String.valueOf(candidate.data().getOrDefault("report_id", ""));
            if (!reportId.isBlank() && !principal.mustChangePassword()
                    && principal.permissions().contains("indicator_detail_export")) {
                String marker = "{{diagnosis_export:" + reportId + "}}";
                return content.contains(marker) ? content
                        : content.stripTrailing()
                                + "\n\n本次诊断支持导出当前证据允许的系统明细或逐条差异表：\n\n"
                                + marker;
            }
            return content;
        }
        ToolResult uploadAnalysis = null;
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (candidate.ok() && "UPLOAD_ANALYZED".equals(candidate.code())) {
                uploadAnalysis = candidate;
                break;
            }
        }
        if (uploadAnalysis != null) {
            if (Boolean.TRUE.equals(uploadAnalysis.data().get("row_level_comparison_available"))
                    && state.lastRunId() != null && state.currentUploadFileKey() != null
                    && !principal.mustChangePassword()
                    && principal.permissions().contains("indicator_detail_export")) {
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        state.currentUploadFileKey().getBytes(StandardCharsets.UTF_8));
                String marker = "{{upload_comparison_export:" + state.lastRunId() + ":" + token + "}}";
                if (!content.contains(marker)) {
                    return content.stripTrailing() + "\n\n本次对比支持导出双方都有、仅系统有、仅上传文件有的逐条差异表：\n\n"
                            + marker;
                }
            }
            return content;
        }
        String runId = state.lastRunId();
        if (runId == null || runId.isBlank() || principal.mustChangePassword()
                || !principal.permissions().contains("indicator_detail_view")) {
            return content;
        }
        String marker = "{{detail_export:" + runId + "}}";
        if (content.contains(marker)) {
            return content;
        }
        return content.stripTrailing() + "\n\n本次统计支持查看分子、分母明细并导出 Excel：\n\n" + marker;
    }
}
