package com.hospital.wikiagent.agent.batch;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.EvidenceAccessException;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier.VerificationExpectations;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidation;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.planning.PlanningExecution;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.ToolGateway;

/**
 * 批量场景下单个指标的确定性执行器。
 *
 * <p>与 {@code AgentRunner} 不同，这里不调用任何 LLM：计划意图固定为试运行、指标身份与统计
 * 周期由编排器预先确定，因此直接复用 PlanCompiler / PlanValidator / AgentStateController /
 * DeterministicDispatch / ToolGateway / EvidenceVerifier 等确定性组件，走“读取生效规则 →
 * 准备受控 SQL → 只读试运行”三步工具链，并从工具结果中提取结构化数值。每个指标使用独立的
 * 运行状态，单个指标失败不会污染其他指标。</p>
 */
@Component
public class PreparedIndicatorExecutor {
    /** 试运行链路只需 3 步工具调用，留出冗余以防异常重试。 */
    private static final int MAX_STEPS = 6;
    private static final DateTimeFormatter EVIDENCE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PlanCompiler compiler;
    private final PlanValidator validator;
    private final AgentStateController controller;
    private final DeterministicDispatch dispatch;
    private final ToolGateway gateway;
    private final CapabilitySpecRegistry capabilities;
    private final EvidenceVerifier verifier;

    @Autowired
    public PreparedIndicatorExecutor(
            PlanCompiler compiler,
            PlanValidator validator,
            AgentStateController controller,
            DeterministicDispatch dispatch,
            ToolGateway gateway,
            CapabilitySpecRegistry capabilities,
            EvidenceVerifier verifier) {
        this.compiler = compiler;
        this.validator = validator;
        this.controller = controller;
        this.dispatch = dispatch;
        this.gateway = gateway;
        this.capabilities = capabilities;
        this.verifier = verifier;
    }

    /**
     * 以确定性方式计算单个指标的试运行结果。任何异常都会被收敛为 FAILED 结果，绝不抛出，
     * 由编排器做失败隔离。
     */
    public IndicatorExecutionResult execute(
            String ruleId,
            String ruleName,
            String subtaskId,
            String rawTimeText,
            AgentRuntimeContext context) {
        long started = System.nanoTime();
        try {
            return doExecute(ruleId, ruleName, subtaskId, rawTimeText, context, started);
        } catch (RuntimeException exception) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, "BATCH_INDICATOR_ERROR", exception.getMessage());
        }
    }

    private IndicatorExecutionResult doExecute(
            String ruleId,
            String ruleName,
            String subtaskId,
            String rawTimeText,
            AgentRuntimeContext context,
            long started) {
        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        // 预先固定指标身份，使状态机跳过 RESOLVE_INDICATOR；统计周期由校验器解析后写入。
        state.currentRuleId(ruleId);
        state.lastIntent(PlanIntent.INDICATOR_TRIAL_RUN.value());
        state.lastRuleName(ruleName);

        RequestPlan plan = new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.INDICATOR_TRIAL_RUN,
                "批量计算指标「" + ruleName + "」的试运行结果",
                new RequestPlan.TargetIndicator(ruleName, ruleId),
                null,
                new RequestPlan.TimeExpression(rawTimeText, null, null),
                List.of(RequestedOutput.TRIAL_RESULT),
                List.of(),
                List.of(),
                1.0);

        CompiledPlanIR compiled = compiler.compile(plan);
        PlanValidation validation = validator.validate(plan);
        if (!validation.ok()) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, validation.code(), validation.message());
        }
        applyResolvedTime(state, validation);
        PlanningExecution execution = new PlanningExecution(plan, compiled, validation, capabilities);

        while (state.stepCount() < MAX_STEPS) {
            ControllerDecision decision = controller.nextDecision(compiled, validation, state);
            if (decision.action() == ControllerAction.FALLBACK) {
                return IndicatorExecutionResult.failed(
                        ruleId, ruleName, decision.code(), decision.message());
            }
            if (decision.action() == ControllerAction.COMPOSE_ANSWER) {
                break;
            }
            DeterministicDispatch.ToolCall call =
                    dispatch.buildToolCall(execution, decision, state, rawTimeText);
            state.incrementStep();
            ToolResult result = gateway.execute(call.name(), call.arguments(), context, state).join();
            updateState(state, result);
            if (!result.ok()) {
                return IndicatorExecutionResult.failed(
                        ruleId, ruleName, result.code(), result.summary());
            }
        }

        IndicatorExecutionResult verificationFailure =
                verifyEvidence(ruleId, ruleName, subtaskId, state, validation, context);
        if (verificationFailure != null) {
            return verificationFailure;
        }

        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return extract(ruleId, ruleName, state, durationMs);
    }

    /**
     * 复刻单指标路径的证据校验：回答只能消费已验证证据。校验失败返回 FAILED 结果，成功返回
     * {@code null}。
     */
    private IndicatorExecutionResult verifyEvidence(
            String ruleId,
            String ruleName,
            String subtaskId,
            AgentRunState state,
            PlanValidation validation,
            AgentRuntimeContext context) {
        Map<String, ToolResult> currentResults = new LinkedHashMap<>();
        for (ToolResult result : state.lastToolResults()) {
            for (String evidenceId : result.evidenceIds()) {
                currentResults.put(evidenceId, result);
            }
        }
        String statStart = validation.resolvedTime().startTime().format(EVIDENCE_TIME);
        String statEnd = validation.resolvedTime().endTime().format(EVIDENCE_TIME);
        String sqlId = state.validatedSqlIds().isEmpty()
                ? null : state.validatedSqlIds().get(state.validatedSqlIds().size() - 1);
        try {
            verifier.verifyMany(
                    state.evidenceIds(), context,
                    new VerificationExpectations(
                            subtaskId, ruleId, statStart, statEnd, sqlId, currentResults));
            return null;
        } catch (EvidenceAccessException exception) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, exception.code(), exception.getMessage());
        }
    }

    /**
     * 从试运行与生效规则工具结果中提取结构化数值与目标值约定字段。
     */
    private IndicatorExecutionResult extract(
            String ruleId, String ruleName, AgentRunState state, long durationMs) {
        ToolResult trial = latestSuccessful(state, "TRIAL_RUN_COMPLETED");
        if (trial == null) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, "TRIAL_RESULT_MISSING", "未取得试运行结果。");
        }
        Map<String, Object> data = trial.data();
        ToolResult effective = latestSuccessful(state, "EFFECTIVE_RULE_FOUND");
        Object targetValue = null;
        String targetDirection = null;
        String unit = null;
        String displayName = ruleName;
        if (effective != null) {
            Map<String, Object> params = objectMap(effective.data().get("effective_params"));
            targetValue = params.get("target_value");
            targetDirection = text(params.get("target_direction"));
            unit = text(effective.data().get("result_unit"));
            String effectiveName = text(effective.data().get("rule_name"));
            if (effectiveName != null) {
                displayName = effectiveName;
            }
        }
        Double resultValue = number(data.get("result_value"));
        Long numerator = longValue(data.get("numerator_count"));
        Long denominator = longValue(data.get("denominator_count"));
        boolean noSample = Boolean.TRUE.equals(data.get("no_sample")) || resultValue == null;
        IndicatorExecutionResult.Status status = noSample
                ? IndicatorExecutionResult.Status.NO_SAMPLE
                : IndicatorExecutionResult.Status.SUCCESS;
        return new IndicatorExecutionResult(
                ruleId, displayName, status, resultValue, numerator, denominator, unit,
                targetValue, targetDirection,
                text(data.get("stat_start")), text(data.get("stat_end")),
                text(data.get("run_id")), null, null, durationMs);
    }

    private static void updateState(AgentRunState state, ToolResult result) {
        if (!result.ok()) {
            return;
        }
        if ("SQL_OBJECT_PREPARED".equals(result.code())) {
            Object sqlId = result.data().get("sql_id");
            if (sqlId != null && !sqlId.toString().isBlank()
                    && !state.validatedSqlIds().contains(sqlId.toString())) {
                state.validatedSqlIds().add(sqlId.toString());
            }
        }
        if ("TRIAL_RUN_COMPLETED".equals(result.code())) {
            Object runId = result.data().get("run_id");
            if (runId != null && !runId.toString().isBlank()) {
                state.lastRunId(runId.toString());
            }
        }
    }

    private static void applyResolvedTime(AgentRunState state, PlanValidation validation) {
        if (validation != null && validation.resolvedTime() != null) {
            state.statPeriod(
                    validation.resolvedTime().startTime().format(EVIDENCE_TIME),
                    validation.resolvedTime().endTime().format(EVIDENCE_TIME));
            state.statPeriodDefaulted(validation.resolvedTime().defaulted());
        }
    }

    private static ToolResult latestSuccessful(AgentRunState state, String code) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            if (result.ok() && code.equals(result.code())) {
                return result;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
