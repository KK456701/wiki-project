package com.hospital.wikiagent.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier.VerificationExpectations;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.ConversationSnapshot;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer.FinalAnswerInput;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.PlannerInput;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.PlannerResult;
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
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;

@Component
public class AgentRunner {
    public static final String VERSION = "java-agent-runner-v1";
    private static final int MAX_STEPS = 12;
    private static final DateTimeFormatter EVIDENCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
            AgentConversationMemory conversations) {
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

    public AgentRunResult run(AgentRunRequest request) {
        return run(request, AgentRunObserver.noop());
    }

    public AgentRunResult run(AgentRunRequest request, AgentRunObserver observer) {
        ConversationSnapshot conversation = conversations.open(
                request.principal(), request.sessionId());
        request = withConversationContext(request, conversation);
        conversations.appendUser(
                conversation, request.principal(), request.query(), request.fileKey());
        String requestId = blankTo(request.requestId(), id("REQ_"));
        String traceId = blankTo(request.traceId(), id("TRACE_"));
        String sessionId = conversation.sessionId();
        String subtaskId = requestId.contains(":subtask:") ? requestId : id("SUB_");
        emit(observer, "agent_start", traceId, 0, Map.of("status", "running"));
        emit(observer, "model_start", traceId, 0, Map.of("message", "规划业务目标"));

        PlannerResult modelPlan = planner.plan(new PlannerInput(
                request.query(), request.modelId(), LocalDate.now(ZoneId.of("Asia/Shanghai")),
                request.structuredState(), request.recentHistory()));
        RequestPlan enrichedPlan = enrichFromConversation(modelPlan.plan(), conversation);
        PlannerResult planned = new PlannerResult(
                enrichedPlan, modelPlan.rawContent(), modelPlan.modelId(), modelPlan.repaired());
        PlanValidation validation = validator.validate(planned.plan());
        CompiledPlanIR compiled = compiler.compile(planned.plan());
        PlanningExecution execution = new PlanningExecution(
                planned.plan(), compiled, validation, capabilities);

        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        state.currentRuleId(first(
                planned.plan().targetIndicator().ruleId(), conversation.ruleId()));
        state.currentUploadFileKey(first(request.fileKey(), conversation.uploadFileKey()));
        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId());

        while (state.stepCount() < MAX_STEPS) {
            ControllerDecision decision = controller.nextDecision(compiled, validation, state);
            if (decision.action() == ControllerAction.FALLBACK) {
                AgentRunResult result = finishFallback(
                        observer, traceId, sessionId, state, planned.plan(), compiled, decision);
                conversations.appendAssistant(
                        conversation, request.principal(), result.answer(), state);
                return result;
            }
            if (decision.action() == ControllerAction.COMPOSE_ANSWER) {
                AgentRunResult result = compose(
                        request, observer, traceId, sessionId, state, planned.modelId(),
                        planned.plan(), compiled, validation, context);
                conversations.appendAssistant(
                        conversation, request.principal(), result.answer(), state);
                return result;
            }

            DeterministicDispatch.ToolCall call;
            try {
                call = dispatch.buildToolCall(execution, decision, state, request.query());
            } catch (CapabilityDispatchException exception) {
                AgentRunResult result = finishFailure(
                        observer, traceId, sessionId, state, planned.plan(), compiled,
                        exception.getMessage(), exception.code());
                conversations.appendAssistant(
                        conversation, request.principal(), result.answer(), state);
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
            updateState(state, result);
            if (!result.ok()) {
                AgentRunResult failure = finishFailure(
                        observer, traceId, sessionId, state, planned.plan(), compiled,
                        result.summary(), result.code());
                conversations.appendAssistant(
                        conversation, request.principal(), failure.answer(), state);
                return failure;
            }
        }
        AgentRunResult failure = finishFailure(
                observer, traceId, sessionId, state, planned.plan(), compiled,
                "已达到最大处理步骤，请缩小问题范围后重试。", "MAX_STEPS_EXCEEDED");
        conversations.appendAssistant(conversation, request.principal(), failure.answer(), state);
        return failure;
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
        List<com.hospital.wikiagent.agent.evidence.VerifiedEvidence> evidence = verifier.verifyMany(
                state.evidenceIds(), context,
                new VerificationExpectations(
                        state.subtaskId(), state.currentRuleId(), statStart, statEnd, sqlId, currentResults));
        emit(observer, "model_start", traceId, state.stepCount(), Map.of("message", "生成最终回答"));
        var answer = finalAnswer.compose(new FinalAnswerInput(
                request.query(), plan.goal(), modelId,
                LocalDate.now(ZoneId.of("Asia/Shanghai")), request.recentHistory(), evidence));
        String answerContent = appendExportMarker(answer.content(), state, request.principal());
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
