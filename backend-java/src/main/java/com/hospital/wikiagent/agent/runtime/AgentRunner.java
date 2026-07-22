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
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.CapabilityDispatchException;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidation;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.planning.PlanningExecution;
import com.hospital.wikiagent.agent.planning.ReplanPolicy;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;

/**
 * 执行单指标 Compiled Plan：装载会话、调用 Planner、按状态机调用受控工具、
 * 校验证据并生成最终回答。模型不能在这里绕过 Controller 自由调用工具。
 *
 * <p>每轮执行严格遵循“加载会话 → 规划 → 编译与校验 → 状态控制 → 工具网关 →
 * Evidence 校验 → 回答”的顺序。只有 {@link ReplanPolicy} 认定为语义计划错误时才允许一次
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
    private final ReplanPolicy replanPolicy;

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
            ReplanPolicy replanPolicy) {
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
        this.replanPolicy = replanPolicy;
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
                gateway, verifier, finalAnswer, conversations, new ReplanPolicy());
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
                                "recent_history", request.recentHistory()), Map.of(
                                "intent", modelPlan.plan().intent().name(),
                                "repaired", modelPlan.repaired()),
                        "model_id", modelPlan.modelId());
            } catch (RuntimeException exception) {
                TraceEvents.failed(observer, traceId, "planner_llm", "llm", plannerStarted,
                        subtaskId, "PLANNER_FAILED", exception.getMessage(),
                        "model_id", request.modelId());
                throw exception;
            }
        }
        long compileStarted = TraceEvents.started();
        RequestPlan enrichedPlan = normalizeExplicitImplementationValidation(
                request.query(), enrichFromResolvedIndicator(
                        enrichFromConversation(modelPlan.plan(), conversation), resolvedIndicator));
        PlannerResult planned = new PlannerResult(
                enrichedPlan, modelPlan.rawContent(), modelPlan.modelId(), modelPlan.repaired());
        // 编译器从目标事实反推前置能力，形成后续状态机唯一可执行的 IR。
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

        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        state.currentRuleId(first(
                planned.plan().targetIndicator().ruleId(), conversation.ruleId()));
        state.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));
        applyResolvedTime(state, validation);
        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId());

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
        if (!replanPolicy.canReplan(state, failureCode)) return null;
        replanPolicy.recordFailure(state, compiled.planId());
        long started = TraceEvents.started();
        try {
            PlannerResult raw = planner.replan(new ReplannerInput(
                    request.query(), request.modelId(), LocalDate.now(ZoneId.of("Asia/Shanghai")),
                    current.plan(), failureCode, failureReason,
                    "rule_id=" + safe(state.currentRuleId())
                            + "; evidence_ids=" + state.evidenceIds(),
                    compiled.planId()));
            RequestPlan plan = normalizeExplicitImplementationValidation(
                    request.query(), enrichFromResolvedIndicator(
                            enrichFromConversation(raw.plan(), conversation), resolvedIndicator));
            PlannerResult planned = new PlannerResult(
                    plan, raw.rawContent(), raw.modelId(), raw.repaired());
            CompiledPlanIR alternative = compiler.compile(plan);
            if (!replanPolicy.accepts(state, alternative.planId())) {
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
                            "known_evidence_ids", state.evidenceIds()), Map.of(
                            "plan_id", alternative.planId(),
                            "intent", plan.intent().name(),
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
        long verifyStarted = TraceEvents.started();
        List<com.hospital.wikiagent.agent.evidence.VerifiedEvidence> evidence = verifier.verifyMany(
                state.evidenceIds(), context,
                new VerificationExpectations(
                        state.subtaskId(), state.currentRuleId(), statStart, statEnd, sqlId, currentResults));
        TraceEvents.completed(observer, traceId, "plan_verify", "code", verifyStarted,
                state.subtaskId(), Map.of(
                        "evidence_ids", state.evidenceIds()), eventValues(
                        "verified_count", evidence.size(), "rule_id", state.currentRuleId()),
                "rule_id", state.currentRuleId(), "sql_id", sqlId);
        // SQL 展示和实施验收可由确定性代码直接回答，避免模型改写已校验 SQL 或状态。
        String deterministicAnswer = composeImplementationValidationAnswer(plan, state);
        String deterministicNode = "implementation_validation_answer";
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
                    "workflow_version", deterministicNode.equals("prepared_sql_answer")
                            ? "prepared-sql-answer-v2" : "implementation-validation-mvp-v1");
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
                request.query(), plan.goal(), modelId,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), request.recentHistory(), evidence));
        TraceEvents.completed(observer, traceId, "final_answer_llm", "llm", finalStarted,
                state.subtaskId(), Map.of(
                        "query", request.query(), "verified_evidence_count", evidence.size()),
                Map.of("answer_length", answer.content().length(), "corrected", answer.corrected()),
                "model_id", answer.modelId(),
                "deterministic_fallback", answer.deterministicFallback());
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
        if ("SQL_OBJECT_PREPARED".equals(result.code())) {
            Object sqlId = result.data().get("sql_id");
            if (sqlId != null && !sqlId.toString().isBlank()
                    && !state.validatedSqlIds().contains(sqlId.toString())) {
                state.validatedSqlIds().add(sqlId.toString());
            }
        }
        if ("INDICATOR_DIAGNOSED".equals(result.code())) {
            Object reportId = result.data().get("report_id");
            if (reportId != null && !reportId.toString().isBlank()) {
                state.lastDiagnosisId(reportId.toString());
            }
        }
        if ("TRIAL_RUN_COMPLETED".equals(result.code())) {
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
        if (target.rawName().isBlank() && target.ruleId() == null
                && conversation.ruleId() != null) {
            target = new RequestPlan.TargetIndicator(
                    first(conversation.ruleName(), conversation.ruleId()), conversation.ruleId());
        }
        RequestPlan.TimeExpression time = plan.timeExpression();
        if (time.rawText().isBlank() && time.startTime() == null && time.endTime() == null
                && conversation.statStart() != null && conversation.statEnd() != null) {
            time = new RequestPlan.TimeExpression(
                    "沿用上一轮统计区间", conversation.statStart(), conversation.statEnd());
        }
        return new RequestPlan(
                plan.schemaVersion(), plan.intent(), plan.goal(), target, time,
                plan.requestedOutputs(), plan.constraints(), plan.semanticAmbiguities());
    }

    private static RequestPlan enrichFromResolvedIndicator(
            RequestPlan plan,
            HybridIndicatorResolver.ResolvedIndicator resolved) {
        if (resolved == null) return plan;
        return new RequestPlan(
                plan.schemaVersion(), plan.intent(), plan.goal(),
                new RequestPlan.TargetIndicator(resolved.canonicalName(), resolved.ruleId()),
                plan.timeExpression(), plan.requestedOutputs(), plan.constraints(),
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
