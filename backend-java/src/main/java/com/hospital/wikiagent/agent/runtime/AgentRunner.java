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
        String requestId = blankTo(request.requestId(), id("REQ_"));
        String traceId = blankTo(request.traceId(), id("TRACE_"));
        long memoryStarted = TraceEvents.started();
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
        emit(observer, "model_start", traceId, 0, Map.of("message", "规划业务目标"));

        long plannerStarted = TraceEvents.started();
        PlannerResult modelPlan;
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
        long compileStarted = TraceEvents.started();
        RequestPlan enrichedPlan = normalizeExplicitImplementationValidation(
                request.query(), enrichFromConversation(modelPlan.plan(), conversation));
        PlannerResult planned = new PlannerResult(
                enrichedPlan, modelPlan.rawContent(), modelPlan.modelId(), modelPlan.repaired());
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
        AgentRuntimeContext context = new AgentRuntimeContext(
                request.principal(), requestId, traceId, request.dbSourceId());

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
        String validationAnswer = composeImplementationValidationAnswer(plan, state);
        if (validationAnswer != null) {
            long answerStarted = TraceEvents.started();
            TraceEvents.completed(observer, traceId, "implementation_validation_answer", "code",
                    answerStarted, state.subtaskId(), Map.of(
                            "verified_evidence_count", evidence.size()), Map.of(
                            "answer_length", validationAnswer.length()),
                    "workflow_version", "implementation-validation-mvp-v1");
            long guardStarted = TraceEvents.started();
            String answerContent = appendExportMarker(validationAnswer, state, request.principal());
            TraceEvents.completed(observer, traceId, "response_guard", "code", guardStarted,
                    state.subtaskId(), Map.of("answer_length", validationAnswer.length()), Map.of(
                            "accepted", true, "export_marker_added",
                            !answerContent.equals(validationAnswer)));
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
                "model_id", answer.modelId());
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
                        "evidence_count", state.evidenceIds().size()));
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
