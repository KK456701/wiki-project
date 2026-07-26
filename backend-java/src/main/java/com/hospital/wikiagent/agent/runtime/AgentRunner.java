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
import com.hospital.wikiagent.contract.AgentClarification;

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
    /** 检测查询原文中是否存在两个以上显式日期（如 2025.01.01、2025-01-01、2025/01/01）。 */
    private static final Pattern EXPLICIT_DATE_RANGE = Pattern.compile(
            "20\\d{2}[.\\-/年]\\d{1,2}[.\\-/月]\\d{1,2}");

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
    private final ClarificationPromptFactory clarificationPrompts;
    private final com.hospital.wikiagent.agent.model.AgentModelProperties modelProperties;

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
            PlanGoalAlignmentValidator alignmentValidator,
            ClarificationPromptFactory clarificationPrompts,
            com.hospital.wikiagent.agent.model.AgentModelProperties modelProperties) {
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
        this.clarificationPrompts = clarificationPrompts;
        this.modelProperties = modelProperties;
    }

    /**
     * 兼容现有单元测试中的完整构造方式；生产环境使用上方 Spring 注入构造器。
     */
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
        this(planner, validator, compiler, capabilities, controller, dispatch,
                gateway, verifier, finalAnswer, conversations, failureRouter,
                uploadPlanningContext, alignmentValidator, null, null);
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
                new AgentFailureRouter(new ReplanPolicy()), null, null, null, null);
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
        emit(observer, "agent_start", traceId, 0, Map.of("status", "running", "session_id", sessionId));
        TraceEvents.completed(observer, traceId, "memory_load", "storage", memoryStarted,
                subtaskId, Map.of("session_id", safe(request.sessionId())), Map.of(
                        "history_length", request.recentHistory().length(),
                        "structured_state_length", request.structuredState().length()));
        if (requestsRemovedImplementationValidation(request.query())) {
            // “全面实施验收”已从产品范围永久删除。这里在 Planner 之前短路，
            // 防止模型把旧意图改写成普通诊断或自行拼接已删除的工具链。
            String answer = "当前系统不提供“全面实施验收、上线验收、迁移核对或全链路验收”功能。"
                    + "您仍可查询指标口径、计算结果、生成受控 SQL，或排查两个结果不一致的原因。";
            long guardStarted = TraceEvents.started();
            TraceEvents.completed(observer, traceId, "unsupported_feature_guard", "code",
                    guardStarted, subtaskId,
                    Map.of("query", request.query()),
                    Map.of("code", "FEATURE_REMOVED", "answer", answer));
            RequestPlan unsupportedPlan = new RequestPlan(
                    RequestPlan.VERSION,
                    PlanIntent.UNKNOWN,
                    "说明当前系统不支持已废弃的全面实施验收功能",
                    new RequestPlan.TargetIndicator("", null),
                    new RequestPlan.TimeExpression("", null, null),
                    List.of(RequestedOutput.EXPLANATION),
                    List.of("removed_feature"),
                    List.of());
            AgentRunState unsupportedState = new AgentRunState();
            unsupportedState.subtaskId(subtaskId);
            saveConversation(observer, traceId, subtaskId, conversation,
                    request.principal(), answer, unsupportedState);
            emit(observer, "agent_message", traceId, 0,
                    Map.of("message", answer, "status", "completed"));
            emit(observer, "agent_done", traceId, 0, Map.of(
                    "stop_reason", "final_answer", "status", "completed",
                    "step_count", 0));
            return new AgentRunResult(
                    answer, "final_answer", traceId, sessionId,
                    0, unsupportedPlan, null);
        }
        PlannerResult modelPlan;
        RequestPlan followupPlan = deterministicSqlFollowup(
                request.query(), conversation, request.recentHistory(), resolvedIndicator);
        if (followupPlan != null) {
            // “这个 SQL 怎么写”已由上一轮结构化状态给出指标与周期，无需再次让小模型猜测。
            long followupStarted = TraceEvents.started();
            modelPlan = new PlannerResult(
                    followupPlan, "deterministic-sql-followup", request.modelId(), false);
            TraceEvents.completed(observer, traceId, "followup_plan_resolve", "code",
                    followupStarted, subtaskId, eventValues(
                            "query", request.query(),
                            "planner_invoked", false,
                            "planner_skip_reason",
                            "指标、统计周期和 SQL 展示目标可由结构化会话状态唯一确定",
                            "context_rule_id", followupPlan.targetIndicator().ruleId(),
                            "context_stat_start", followupPlan.timeExpression().startTime(),
                            "context_stat_end", followupPlan.timeExpression().endTime()), Map.of(
                            "intent", followupPlan.intent().name(),
                            "requested_outputs", followupPlan.requestedOutputs(),
                            "decision", "未调用 LLM Planner"));
        } else {
            emit(observer, "model_start", traceId, 0, Map.of("message", "规划业务目标"));
            long plannerStarted = TraceEvents.started();
            // Planner 只产出业务 RequestPlan，不接收工具 schema，也不能决定 SQL。
            try {
                modelPlan = planner.plan(new PlannerInput(
                        request.query(), request.modelId(), LocalDate.now(ZoneId.of("Asia/Shanghai")),
                        request.structuredState(), request.recentHistory()));
                TraceEvents.completed(observer, traceId, "planner_llm", "llm", plannerStarted,
                        subtaskId, plannerTraceInput(request, modelPlan), eventValues(
                                "raw_content", modelPlan.rawContent(),
                                "request_plan", tracePlan(modelPlan.plan()),
                                "normalized_plan", tracePlan(modelPlan.plan()),
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
        RequestPlan enrichedPlan = removeInternalImplementationOutput(
                downgradeUnsupportedDifferenceDiagnosis(
                request.query(), request.fileKey(),
                normalizeExplicitDifferenceDiagnosis(
                request.query(), upgradeToTrialRun(
                        request.query(), enrichFromResolvedIndicator(
                                enrichFromConversation(
                                        enrichFromUploadedFile(modelPlan.plan(), fileContext),
                                        conversation),
                                resolvedIndicator)))));
        // 低置信度意图澄清：在编译前检查 Planner 的置信度
        double threshold = modelProperties != null ? modelProperties.getConfidenceThreshold() : 0.7;
        // “还有哪些口径”属于服务端可以高置信识别的业务动作。即使小模型给出较低
        // confidence，也应先交给目标—计划一致性校验纠正为口径列表查询，不能在这里
        // 提前反问并中断。这样 8B 与 API 大模型会得到一致的确定性行为。
        boolean deterministicCaliberOptionsQuery = isCaliberOptionsQuery(request.query());
        if (enrichedPlan.confidence() < threshold && !deterministicCaliberOptionsQuery) {
            long clarificationStarted = TraceEvents.started();
            // 如果 Planner 给出了具体指标名但解析不出 ruleId，真正模糊的是指标而非意图，
            // 应请用户从目录重选指标，避免静默回退到上一轮指标算错对象。
            RequestPlan.TargetIndicator lowTarget = enrichedPlan.targetIndicator();
            boolean indicatorUnresolved = lowTarget.ruleId() == null && !lowTarget.rawName().isBlank();
            AgentClarification clarification = indicatorUnresolved
                    ? clarificationPrompts.indicatorUnresolved(
                            request.principal().hospitalId(), request.query(), lowTarget.rawName())
                    : buildIntentClarification(enrichedPlan);
            String clarificationMessage = indicatorUnresolved
                    ? "未能匹配到您说的指标，请确认要处理的指标："
                    : "无法确定您的意图，请确认您想要的操作：";
            TraceEvents.completed(observer, traceId, "low_confidence_clarification", "code",
                    clarificationStarted, subtaskId, Map.of(
                            "confidence", enrichedPlan.confidence(),
                            "threshold", threshold),
                    Map.of("options_count", clarification.options().size(),
                            "clarification_kind", indicatorUnresolved ? "indicator" : "intent"));
            emit(observer, "clarification_required", traceId, 0, eventValues(
                    "message", clarificationMessage,
                    "code", indicatorUnresolved ? "INDICATOR_NOT_FOUND" : "LOW_CONFIDENCE_INTENT",
                    "fallback_category", "USER_CLARIFICATION",
                    "clarification", clarification,
                    "stop_reason", "clarification"));
            emit(observer, "agent_done", traceId, 0, Map.of(
                    "stop_reason", "clarification", "status", "incomplete",
                    "step_count", 0));
            return new AgentRunResult(
                    clarificationMessage, "clarification", traceId, sessionId,
                    0, enrichedPlan, null, clarification);
        }
        // 复数指标指代兜底：用户说“这两个指标”“这些指标”但没给出可解析的指标名时，
        // Planner 常会误判为对上一轮单个指标的追问并给出高置信度，从而静默生成
        // 猜中指标的 SQL。单指标流水线无法满足复数请求，这里主动反问让用户明确选择。
        if (ClarificationPromptFactory.referencesMultipleIndicators(request.query())) {
            long pluralStarted = TraceEvents.started();
            AgentClarification pluralClarification = clarificationPrompts.indicatorMultipleReference(
                    request.principal().hospitalId(), request.query());
            String pluralMessage = "您提到了多个指标，请先选择要处理的指标：";
            TraceEvents.completed(observer, traceId, "multiple_indicator_clarification", "code",
                    pluralStarted, subtaskId, Map.of(),
                    Map.of("options_count", pluralClarification.options().size(),
                            "clarification_kind", "indicator_multiple"));
            emit(observer, "clarification_required", traceId, 0, eventValues(
                    "message", pluralMessage,
                    "code", "INDICATOR_MULTIPLE_REFERENCE",
                    "fallback_category", "USER_CLARIFICATION",
                    "clarification", pluralClarification,
                    "stop_reason", "clarification"));
            emit(observer, "agent_done", traceId, 0, Map.of(
                    "stop_reason", "clarification", "status", "incomplete",
                    "step_count", 0));
            return new AgentRunResult(
                    pluralMessage, "clarification", traceId, sessionId,
                    0, enrichedPlan, null, pluralClarification);
        }
        PlannerResult planned = new PlannerResult(
                enrichedPlan, modelPlan.rawContent(), modelPlan.modelId(), modelPlan.repaired());

        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        state.progressReporter(progress -> {
            boolean dualDatabaseStage = progress.nodeName().startsWith("source_data_")
                    || progress.nodeName().startsWith("business_")
                    || progress.nodeName().startsWith("real_")
                    || progress.nodeName().startsWith("dual_");
            TraceEvents.recorded(
                    observer,
                    traceId,
                    progress.nodeName(),
                    "code",
                    progress.status(),
                    progress.durationMs(),
                    subtaskId,
                    Map.of(
                            "workflow_version", dualDatabaseStage
                                    ? "dual-database-indicator-workflow-v1"
                                    : "indicator-difference-diagnosis-v1"),
                    progress.safeOutput(),
                    "capability", dualDatabaseStage
                            ? "execute_indicator_dual_store"
                            : "diagnose_indicator_difference");
        });
        state.currentRuleId(first(
                planned.plan().targetIndicator().ruleId(), conversation.ruleId()));
        state.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));
        state.lastIntent(planned.plan().intent().value());
        state.lastRuleName(planned.plan().targetIndicator().rawName());

        // 在编译 IR 之前核对原问题和模型计划。只有真正的方向性冲突才触发一次
        // Replanner；正常计划不会增加模型调用。
        planned = alignPlanBeforeCompile(
                request, observer, traceId, state, planned, conversation, resolvedIndicator);
        // Replanner 或服务端受控修正可能改变计划意图；运行态必须以最终计划为准，
        // 否则工具层会把“只展示 SQL”和“执行试运行”混为同一种操作。
        state.lastIntent(planned.plan().intent().value());

        // 编译器从目标事实反推前置能力，形成后续状态机唯一可执行的 IR。
        long compileStarted = TraceEvents.started();
        CompiledPlanIR compiled = compiler.compile(planned.plan());
        TraceEvents.completed(observer, traceId, "plan_compile", "code", compileStarted,
                subtaskId, Map.of("intent", planned.plan().intent().name()), Map.of(
                        "plan_id", compiled.planId(), "node_count", compiled.nodes().size(),
                        "ir_version", CompiledPlanIR.VERSION,
                        "required_facts", compiled.requiredFacts(),
                        "capabilities", compiled.nodes().stream()
                                .map(node -> node.capability().value()).toList()));
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
                        request, observer, traceId, sessionId, state,
                        planned.plan(), compiled, fallback);
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
                        request, observer, traceId, sessionId, state,
                        planned.plan(), compiled, decision);
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

    private static boolean isCaliberOptionsQuery(String query) {
        String normalized = query == null
                ? ""
                : query.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        if (!normalized.contains("口径")) {
            return false;
        }
        return List.of("还有", "其他", "哪些", "可选", "几种", "列表").stream()
                .anyMatch(normalized::contains);
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
            RequestPlan plan = downgradeUnsupportedDifferenceDiagnosis(
                    request.query(), request.fileKey(),
                    normalizeExplicitDifferenceDiagnosis(
                    request.query(), upgradeToTrialRun(
                            request.query(), enrichFromResolvedIndicator(
                                    enrichFromConversation(
                                            enrichFromUploadedFile(
                                                    raw.plan(),
                                                    resolveUploadPlanningContext(request)),
                                            conversation),
                                    resolvedIndicator))));
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
            RequestPlan plan = downgradeUnsupportedDifferenceDiagnosis(
                    request.query(), request.fileKey(),
                    normalizeExplicitDifferenceDiagnosis(
                    request.query(), upgradeToTrialRun(
                            request.query(), enrichFromResolvedIndicator(
                                    enrichFromConversation(
                                            enrichFromUploadedFile(
                                                    raw.plan(),
                                                    resolveUploadPlanningContext(request)),
                                            conversation),
                                    resolvedIndicator))));
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
        // SQL、候选口径模拟和差异归因由确定性代码回答，避免模型改写
        // 高风险事实；其他意图由 Final Answer LLM 按本轮选中的模板组织。
        String deterministicAnswer = composeDifferenceDiagnosisAnswer(plan, state);
        String deterministicNode = "difference_diagnosis_answer";
        if (deterministicAnswer == null) {
            deterministicAnswer = composeCaliberOptionsAnswer(plan, state);
            deterministicNode = "caliber_options_answer";
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
                        case "caliber_options_answer" -> "caliber-options-answer-v1";
                        case "caliber_simulation_answer" -> "caliber-simulation-answer-v1";
                        default -> "deterministic-answer-v1";
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
            AgentRunRequest request,
            AgentRunObserver observer,
            String traceId,
            String sessionId,
            AgentRunState state,
            com.hospital.wikiagent.agent.ir.RequestPlan plan,
            CompiledPlanIR compiled,
            ControllerDecision decision) {
        var clarification = clarificationPrompts == null ? null : clarificationPrompts.fromDecision(
                decision,
                plan,
                state,
                request.principal().hospitalId(),
                request.query());
        emit(observer, "clarification_required", traceId, state.stepCount(), eventValues(
                "message", decision.message(), "code", decision.code(),
                "fallback_category", decision.fallbackCategory() == null
                        ? null : decision.fallbackCategory().name(),
                "clarification", clarification,
                "stop_reason", "clarification"));
        emit(observer, "agent_done", traceId, state.stepCount(), Map.of(
                "stop_reason", "clarification", "status", "incomplete",
                "step_count", state.stepCount()));
        return new AgentRunResult(
                decision.message(), "clarification", traceId, sessionId,
                state.stepCount(), plan, compiled, clarification);
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
        if (conversation.evidenceContext() != null && !conversation.evidenceContext().isBlank()) {
            structured += "\n已验证证据（上一轮工具结果）：\n" + conversation.evidenceContext();
        }
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
        return plan.withTargetIndicator(target).withTargetCaliber(caliber).withTimeExpression(time);
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
        return plan.withTargetIndicator(target).withTimeExpression(time);
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
        return plan.withTargetIndicator(
                new RequestPlan.TargetIndicator(resolved.canonicalName(), resolved.ruleId()));
    }

    /**
     * 将具备完整上一轮事实的 SQL 追问确定性转换为业务计划。
     *
     * <p>该分支只接受明确出现 SQL/脚本的追问。指标身份必须来自已识别结果或结构化会话；
     * 统计区间缺失时交给 PlanValidator 使用“本月至今”的 SQL 展示默认值。SQL 展示不会
     * 执行数据库，因此不能因为缺少上一轮统计周期而再次交给小模型猜测任务类型。</p>
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
        if (ruleId == null) return null;
        boolean hasPeriod = statStart != null && statEnd != null;
        boolean candidateReference = conversation.caliberProfileId() != null
                && (compact.contains("这个口径")
                        || compact.contains("该口径")
                        || compact.contains("候选口径")
                        || compact.contains("刚才口径"));
        if (candidateReference) {
            // 候选口径 SQL 需要绑定已经确认的候选周期；缺少周期时继续走正常消歧，
            // 不能把当前口径的默认周期静默套到候选口径上。
            if (!hasPeriod) return null;
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
                hasPeriod
                        ? new RequestPlan.TimeExpression("沿用上一轮统计区间", statStart, statEnd)
                        : new RequestPlan.TimeExpression("", null, null),
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
     * 差异诊断降级：如果意图是 INDICATOR_DIFFERENCE_DIAGNOSIS 但没有上传文件、
     * 也没有明确比较对象文本，自动降级为 INDICATOR_DIAGNOSIS。
     *
     * <p>差异诊断需要外部比较对象（上传 Excel、明确“和系统对比”等）。
     * 单纯“结果不对”“数值有问题”属于异常诊断，不应走差异对比流程。</p>
     */
    private static RequestPlan downgradeUnsupportedDifferenceDiagnosis(
            String query, String fileKey, RequestPlan plan) {
        if (plan.intent() != PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS) {
            return plan;
        }
        // 有上传文件时保持差异诊断
        if (fileKey != null && !fileKey.isBlank()) {
            return plan;
        }
        // 有明确比较对象文本时保持差异诊断
        String compact = query == null ? "" : query.replaceAll("\\s+", "");
        boolean hasComparisonTarget = List.of(
                "对比", "核对", "比较", "和系统", "与系统", "和文件",
                "与文件", "和表格", "与表格", "我们", "我方", "上传")
                .stream().anyMatch(compact::contains);
        if (hasComparisonTarget) {
            return plan;
        }
        // 无外部比较对象，降级为异常诊断
        return plan.withIntent(PlanIntent.INDICATOR_DIAGNOSIS)
                .withRequestedOutputs(List.of(RequestedOutput.DIAGNOSIS));
    }
    
    /**
     * 对明确的“双方结果不一致”表达做服务端兖底路由。
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
        return plan.withIntent(PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS)
                .withTargetIndicator(target)
                .withTimeExpression(time)
                .withRequestedOutputs(List.of(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT));
    }

    /**
     * 当 Planner 返回的意图暗示用户实际想执行计算时，确定性升级为 INDICATOR_TRIAL_RUN。
     *
     * <p>处理两种常见误分类：</p>
     * <ul>
     *   <li>INDICATOR_SQL_PREPARE：用户未明确要求写 SQL 时升级</li>
     *   <li>RULE_EXPLANATION：用户提供了具体时间范围时升级（提供时间意味着想算出结果）</li>
     * </ul>
     */
    private static RequestPlan upgradeToTrialRun(
            String query,
            RequestPlan plan) {
        String compact = query == null ? "" : query.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);

        // 1) SQL_PREPARE → TRIAL_RUN：用户未明确要求写 SQL
        if (plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                && plan.requestedOutputs().contains(RequestedOutput.PREPARED_SQL_HANDLE)
                && !compact.contains("sql") && !compact.contains("脚本")) {
            return plan.withIntent(PlanIntent.INDICATOR_TRIAL_RUN)
                    .withRequestedOutputs(plan.requestedOutputs().stream()
                            .map(o -> o == RequestedOutput.PREPARED_SQL_HANDLE
                                    ? RequestedOutput.TRIAL_RESULT : o)
                            .toList());
        }

        // 2) RULE_EXPLANATION → TRIAL_RUN：用户提供了具体时间范围（提供时间意味着想算出结果）
        if (plan.intent() == PlanIntent.RULE_EXPLANATION
                && hasExplicitTimeRange(plan, compact)) {
            return plan.withIntent(PlanIntent.INDICATOR_TRIAL_RUN)
                    .withRequestedOutputs(List.of(RequestedOutput.TRIAL_RESULT));
        }

        return plan;
    }

    /**
     * 判断查询原文中是否包含明确的统计时间范围。
     *
     * <p>仅检查查询文本中的日期模式，不检查计划的 time_expression
     * （因为 time_expression 可能是从对话上下文继承的）。</p>
     */
    private static boolean hasExplicitTimeRange(RequestPlan plan, String compact) {
        if (compact.isBlank()) return false;
        // 检测显式日期模式，如 2025.01.01、2025-01-01、2025/01/01、2025年1月
        return EXPLICIT_DATE_RANGE.matcher(compact).find();
    }

    /**
     * 为低置信度意图生成澄清选项，供用户选择。
     *
     * <p>根据 Planner 识别的意图和语义歧义生成 2-3 个候选意图选项，
     * 用户选择后将作为下一轮 query 的 prepend 上下文重新进入 Planner。</p>
     */
    private static AgentClarification buildIntentClarification(RequestPlan plan) {
        List<AgentClarification.Option> options = new ArrayList<>();
        String indicatorName = plan.targetIndicator().rawName();
        // 根据当前识别的意图生成候选选项
        switch (plan.intent()) {
            case RULE_EXPLANATION -> {
                options.add(new AgentClarification.Option(
                        "intent:rule_explanation", "查看口径定义", "我想了解这个指标的定义和计算公式",
                        "解释 " + indicatorName + " 的定义、公式和分子分母口径", ""));
                options.add(new AgentClarification.Option(
                        "intent:trial_run", "计算结果", "我想得到这个指标的实际计算数值",
                        "执行 SQL 并返回 " + indicatorName + " 的统计结果", ""));
                options.add(new AgentClarification.Option(
                        "intent:sql_prepare", "查看 SQL", "我只想看看用于计算的 SQL 语句",
                        "生成但不执行 " + indicatorName + " 的计算 SQL", ""));
            }
            case INDICATOR_SQL_PREPARE -> {
                options.add(new AgentClarification.Option(
                        "intent:sql_prepare", "查看 SQL", "我只想看看用于计算的 SQL 语句",
                        "生成但不执行 " + indicatorName + " 的计算 SQL", ""));
                options.add(new AgentClarification.Option(
                        "intent:trial_run", "计算结果", "我想得到这个指标的实际计算数值",
                        "执行 SQL 并返回 " + indicatorName + " 的统计结果", ""));
            }
            default -> {
                options.add(new AgentClarification.Option(
                        "intent:" + plan.intent().value(), "当前意图", "继续按系统识别的意图执行",
                        "系统认为您想要：" + plan.goal(), ""));
                options.add(new AgentClarification.Option(
                        "intent:rule_explanation", "查看口径定义", "我想了解指标的定义和公式",
                        "解释 " + indicatorName + " 的口径", ""));
                options.add(new AgentClarification.Option(
                        "intent:trial_run", "计算结果", "我想得到指标的实际计算数值",
                        "执行 SQL 并返回 " + indicatorName + " 的统计结果", ""));
            }
        }
        return new AgentClarification(
                "LOW_CONFIDENCE_INTENT",
                "intent",
                "意图确认",
                "系统不太确定您的意图，请选择您想要的操作：",
                "您可以选择以下选项，或直接输入更具体的描述",
                "single",
                options,
                true,
                "或者输入更具体的描述...",
                "");
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

    private static String composePreparedSqlAnswer(RequestPlan plan, AgentRunState state) {
        if (plan.intent() != PlanIntent.INDICATOR_SQL_PREPARE
                && !plan.requestedOutputs().contains(RequestedOutput.PREPARED_SQL_HANDLE)) {
            return null;
        }
        ToolResult prepared = null;
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult candidate = state.lastToolResults().get(index);
            if (candidate.ok() && ("SQL_OBJECT_PREPARED".equals(candidate.code())
                    || "SQL_REFERENCE_PREPARED".equals(candidate.code()))) {
                prepared = candidate;
                break;
            }
        }
        if (prepared == null) return null;
        Object sql = prepared.data().get("sql_preview");
        if (sql == null || String.valueOf(sql).isBlank()) return null;
        ToolResult effectiveRule = latestSuccessful(state, "EFFECTIVE_RULE_FOUND",
                text(prepared.data().get("rule_id")));
        Map<String, Object> rule = effectiveRule == null ? Map.of() : effectiveRule.data();
        String indicatorName = first(
                text(prepared.data().get("rule_name")),
                text(rule.get("rule_name")),
                plan.targetIndicator().rawName(),
                text(prepared.data().get("rule_id")));
        String profileName = first(
                text(prepared.data().get("profile_name")),
                text(rule.get("profile_name")),
                "当前默认口径");
        String profileId = first(
                text(prepared.data().get("profile_id")),
                text(rule.get("profile_id")),
                "—");
        StringBuilder answer = new StringBuilder();
        answer.append("# ").append(indicatorName).append(" · 概览 SQL\n\n")
                .append("- 当前 Profile：").append(profileName)
                .append("（").append(profileId).append("）\n\n");
        boolean referenceOnly = Boolean.TRUE.equals(prepared.data().get("reference_only"));
        answer.append("## 概览 SQL\n\n```sql\n")
                .append(sql).append("\n```\n\n");
        if (!referenceOnly) {
            answer.append("- SQL 对象：").append(prepared.data().get("sql_id")).append('\n');
        }
        answer.append("- 统计区间：").append(prepared.data().get("stat_start"))
                .append(" 至 ").append(prepared.data().get("stat_end")).append("（左闭右开）\n");
        answer.append("- 参数：").append(prepared.data().getOrDefault("parameters", Map.of())).append('\n');
        if (state.statPeriodDefaulted()) {
            answer.append("\n> 未指定统计时间，已默认用本月至今生成 SQL；"
                    + "如需其他区间，直接告诉我具体起止时间即可调整。\n");
        }
        answer.append("\n> 本轮只展示知识库中的概览 SQL，未访问数据库、未执行统计。");
        return answer.toString();
    }

    /**
     * 将 Profile 目录渲染为确定性业务回答。
     *
     * <p>该回答不会触发候选试运行。即使 Planner 错把“有哪些口径”理解为模拟任务，
     * 一致性校验也会在 IR 编译前把它修正到本目录能力。</p>
     */
    private static String composeCaliberOptionsAnswer(
            RequestPlan plan,
            AgentRunState state) {
        if (plan.intent() != PlanIntent.INDICATOR_CALIBER_QUERY
                && !plan.requestedOutputs().contains(RequestedOutput.CALIBER_OPTIONS)) {
            return null;
        }
        ToolResult catalog = latestSuccessful(
                state, "CALIBER_OPTIONS_FOUND", state.currentRuleId());
        if (catalog == null) return null;
        List<Map<String, Object>> options = mapList(catalog.data().get("caliber_options"));
        Map<String, Object> current = options.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("is_current")))
                .findFirst()
                .orElse(options.isEmpty() ? Map.of() : options.get(0));
        String ruleName = first(
                text(catalog.data().get("rule_name")),
                plan.targetIndicator().rawName(),
                state.currentRuleId());
        StringBuilder answer = new StringBuilder();
        answer.append("# ").append(ruleName).append(" · 口径选项\n\n")
                .append("## 当前口径\n\n");
        appendProfileOption(answer, current, "当前默认口径");

        List<Map<String, Object>> alternatives = options.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("is_current")))
                .toList();
        answer.append("\n## 其他口径\n\n");
        if (alternatives.isEmpty()) {
            answer.append("当前只有一种口径，没有其他已发布候选。\n");
        } else {
            for (Map<String, Object> option : alternatives) {
                String status = text(option.get("option_status"));
                String statusLabel = switch (status == null ? "" : status) {
                    case "trial_available" -> "可试算候选";
                    case "explanation_only" -> "仅可解释候选";
                    default -> "草稿 / 未实现";
                };
                answer.append("### ").append(first(
                                text(option.get("profile_name")),
                                text(option.get("profile_id"))))
                        .append("\n\n")
                        .append("- 编号：").append(option.get("profile_id")).append('\n')
                        .append("- 状态：").append(statusLabel).append('\n')
                        .append("- 统计时间字段：")
                        .append(businessTimeLabel(option.get("time_dimension"))).append('\n');
                if (!"trial_available".equals(status)) {
                    answer.append("- 暂不可试算原因：")
                            .append(first(
                                    text(option.get("unavailable_reason")),
                                    "尚未通过候选试算门禁"))
                            .append('\n');
                }
                answer.append('\n');
            }
            if (alternatives.stream().anyMatch(item ->
                    "trial_available".equals(text(item.get("option_status"))))) {
                answer.append("> 你可以直接说“按「候选口径名称」计算”，"
                        + "系统会在不超过一个月的区间内对业务库和真实库受控试算。");
            }
        }
        return answer.toString().stripTrailing();
    }

    private static void appendProfileOption(
            StringBuilder answer,
            Map<String, Object> profile,
            String fallbackName) {
        answer.append("- 名称：").append(first(
                        text(profile.get("profile_name")), fallbackName))
                .append('\n')
                .append("- 编号：").append(first(
                        text(profile.get("profile_id")), "—"))
                .append('\n')
                .append("- 统计时间字段：")
                .append(businessTimeLabel(profile.get("time_dimension")))
                .append('\n');
    }

    private static String businessTimeLabel(Object raw) {
        String value = text(raw);
        if (value == null || value.isBlank()) return "当前证据未提供";
        return switch (value) {
            case "admitted_to_ward_at", "ward_entry_time" -> "首次入区时间";
            case "admit_time", "admitted_at" -> "入院时间";
            case "request_time", "requested_at" -> "申请时间";
            default -> value;
        };
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
            state.statPeriodDefaulted(validation.resolvedTime().defaulted());
        }
    }

    /**
     * 面向用户的普通回答不暴露知识发布和 SQL 门禁状态。
     *
     * <p>这些状态仍完整保留在工具 Evidence 和 Trace 中。这里仅移除 Planner 误加的
     * {@code implementation_status} 输出目标，避免它额外编译实施检查工具，并让
     * Final Answer 把内部治理状态当成业务口径展示。</p>
     */
    private static RequestPlan removeInternalImplementationOutput(RequestPlan plan) {
        if (!plan.requestedOutputs().contains(RequestedOutput.IMPLEMENTATION_STATUS)) {
            return plan;
        }
        List<RequestedOutput> outputs = plan.requestedOutputs().stream()
                .filter(output -> output != RequestedOutput.IMPLEMENTATION_STATUS)
                .toList();
        if (outputs.isEmpty() && plan.intent() == PlanIntent.RULE_EXPLANATION) {
            outputs = List.of(RequestedOutput.DEFINITION, RequestedOutput.FORMULA);
        }
        return plan.withRequestedOutputs(outputs);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>(
                        (Map<String, Object>) item))
                .toList();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value).strip();
    }

    private static boolean requestsRemovedImplementationValidation(String query) {
        String compact = query == null ? "" : query.replaceAll("\\s+", "");
        return List.of("全面实施验收", "全面实施验证", "上线验收", "迁移核对", "全链路验收")
                .stream()
                .anyMatch(compact::contains);
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
    private static Map<String, Object> plannerTraceInput(
            AgentRunRequest request,
            PlannerResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", request.query());
        value.put("structured_state", request.structuredState());
        value.put("recent_history", request.recentHistory());
        if (result.requestAudit() != null) {
            value.put("current_date", result.requestAudit().currentDate());
            value.put("system_prompt", result.requestAudit().systemPrompt());
            value.put("user_prompt", result.requestAudit().userPrompt());
            value.put("messages", result.requestAudit().messages());
            value.put("model_id", result.requestAudit().modelId());
            value.put("timeout_ms", result.requestAudit().timeoutMs());
            value.put("prompt_version", result.requestAudit().promptVersion());
            value.put("planner_version", result.requestAudit().plannerVersion());
            value.put("repair_attempt", result.requestAudit().repairAttempt());
        }
        return value;
    }

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
        value.put("confidence", plan.confidence());
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
            String reportId = candidate.ok()
                    ? String.valueOf(candidate.data().getOrDefault(
                            "diagnosis_report_id", ""))
                    : "";
            if (!reportId.isBlank() && !principal.mustChangePassword()
                    && principal.permissions().contains("indicator_detail_export")) {
                String marker = "{{diagnosis_export:" + reportId + "}}";
                return content.contains(marker) ? content
                        : content.stripTrailing()
                                + "\n\n本次双库核对支持导出业务库与真实库的逐条差异表：\n\n"
                                + marker;
            }
        }
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
