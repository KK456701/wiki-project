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
        return execute(
                ruleId, ruleName, subtaskId, rawTimeText, null, null, context);
    }

    /**
     * 批量编排器已统一解析时间时，所有 worker 必须复用完全相同的边界，不能分别把“现在”
     * 解析为不同秒数。
     */
    public IndicatorExecutionResult execute(
            String ruleId,
            String ruleName,
            String subtaskId,
            String rawTimeText,
            String fixedStatStart,
            String fixedStatEnd,
            AgentRuntimeContext context) {
        return execute(
                ruleId, ruleName, null, null, null, subtaskId, rawTimeText,
                fixedStatStart, fixedStatEnd, context);
    }

    /**
     * 计算一个明确的已审批 Profile。Profile 身份来自服务端知识契约，写入计划后仍会由
     * 规则读取和 SQL 准备工具再次校验，不能用用户文本替换。
     */
    public IndicatorExecutionResult execute(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
            String subtaskId,
            String rawTimeText,
            String fixedStatStart,
            String fixedStatEnd,
            AgentRuntimeContext context) {
        long started = System.nanoTime();
        try {
            return doExecute(
                    ruleId, ruleName, profileId, profileLabel, eventNo,
                    subtaskId, rawTimeText, fixedStatStart, fixedStatEnd,
                    context, started);
        } catch (RuntimeException exception) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, profileId, profileLabel, eventNo,
                    "BATCH_INDICATOR_ERROR", exception.getMessage());
        }
    }

    private IndicatorExecutionResult doExecute(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
            String subtaskId,
            String rawTimeText,
            String fixedStatStart,
            String fixedStatEnd,
            AgentRuntimeContext context,
            long started) {
        AgentRunState state = new AgentRunState();
        state.subtaskId(subtaskId);
        // 预先固定指标身份，使状态机跳过 RESOLVE_INDICATOR；统计周期由校验器解析后写入。
        state.currentRuleId(ruleId);
        state.lastIntent(PlanIntent.INDICATOR_TRIAL_RUN.value());
        state.lastRuleName(ruleName);

        boolean fixedPeriod = fixedStatStart != null && !fixedStatStart.isBlank()
                && fixedStatEnd != null && !fixedStatEnd.isBlank();
        RequestPlan plan = new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.INDICATOR_TRIAL_RUN,
                "批量计算指标「" + ruleName + "」"
                        + (profileLabel == null ? "" : "口径「" + profileLabel + "」")
                        + "的试运行结果",
                new RequestPlan.TargetIndicator(ruleName, ruleId),
                profileId == null
                        ? null
                        : new RequestPlan.TargetCaliber(
                                profileLabel == null ? profileId : profileLabel,
                                profileId),
                fixedPeriod
                        ? new RequestPlan.TimeExpression(
                                "批量固定统计周期", fixedStatStart, fixedStatEnd)
                        : new RequestPlan.TimeExpression(rawTimeText, null, null),
                List.of(RequestedOutput.TRIAL_RESULT),
                List.of(),
                List.of(),
                1.0);

        CompiledPlanIR compiled = compiler.compile(plan);
        PlanValidation validation = validator.validateBatch(plan);
        if (!validation.ok()) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, profileId, profileLabel, eventNo,
                    validation.code(), validation.message());
        }
        applyResolvedTime(state, validation);
        PlanningExecution execution = new PlanningExecution(plan, compiled, validation, capabilities);

        while (state.stepCount() < MAX_STEPS) {
            ControllerDecision decision = controller.nextDecision(compiled, validation, state);
            if (decision.action() == ControllerAction.FALLBACK) {
                return IndicatorExecutionResult.failed(
                        ruleId, ruleName, profileId, profileLabel, eventNo,
                        decision.code(), decision.message());
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
                        ruleId, ruleName, profileId, profileLabel, eventNo,
                        result.code(), result.summary());
            }
        }

        IndicatorExecutionResult verificationFailure =
                verifyEvidence(
                        ruleId, ruleName, profileId, profileLabel, eventNo,
                        subtaskId, state, validation, context);
        if (verificationFailure != null) {
            return verificationFailure;
        }

        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return extract(
                ruleId, ruleName, profileId, profileLabel, eventNo,
                state, durationMs);
    }

    /**
     * 复刻单指标路径的证据校验：回答只能消费已验证证据。校验失败返回 FAILED 结果，成功返回
     * {@code null}。
     */
    private IndicatorExecutionResult verifyEvidence(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
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
                    ruleId, ruleName, profileId, profileLabel, eventNo,
                    exception.code(), exception.getMessage());
        }
    }

    /**
     * 从试运行与生效规则工具结果中提取结构化数值与目标值约定字段。
     */
    private IndicatorExecutionResult extract(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
            AgentRunState state,
            long durationMs) {
        ToolResult trial = latestSuccessful(state, "TRIAL_RUN_COMPLETED");
        if (trial == null) {
            return IndicatorExecutionResult.failed(
                    ruleId, ruleName, profileId, profileLabel, eventNo,
                    "TRIAL_RESULT_MISSING", "未取得试运行结果。");
        }
        Map<String, Object> data = trial.data();
        ToolResult effective = latestSuccessful(state, "EFFECTIVE_RULE_FOUND");
        Object targetValue = null;
        Object contractTargetValue = null;
        String targetDirection = null;
        String valueType = null;
        String unit = null;
        String displayName = ruleName;
        if (effective != null) {
            Map<String, Object> params = objectMap(effective.data().get("effective_params"));
            targetValue = params.get("target_value");
            targetDirection = text(params.get("target_direction"));
            Map<String, Object> resultContract =
                    objectMap(effective.data().get("result_contract"));
            contractTargetValue = resultContract.get("target_value");
            targetDirection = first(
                    targetDirection, text(resultContract.get("target_direction")));
            valueType = text(resultContract.get("value_type"));
            unit = text(effective.data().get("result_unit"));
            String effectiveName = text(effective.data().get("rule_name"));
            if (effectiveName != null) {
                displayName = effectiveName;
            }
        }
        Double resultValue = number(data.get("result_value"));
        Long numerator = longValue(data.get("numerator_count"));
        Long denominator = longValue(data.get("denominator_count"));
        Long sampleCount = longValue(data.get("sample_count"));
        boolean noSample = Boolean.TRUE.equals(data.get("no_sample")) || resultValue == null;
        if (noSample && "median_duration".equals(valueType) && sampleCount == null) {
            sampleCount = 0L;
        }
        String calculationDisplay = calculationDisplay(
                valueType,
                text(data.get("component_left")),
                text(data.get("component_right")),
                sampleCount);
        Object configuredTarget = contractTargetValue != null
                ? contractTargetValue : targetValue;
        configuredTarget = normalizeConfiguredTarget(
                configuredTarget, valueType, unit);
        if (Boolean.TRUE.equals(data.get("target_conflict"))) {
            targetValue = "目标配置不一致";
        } else if (data.get("target_value") != null) {
            Double dynamicTarget = number(data.get("target_value"));
            boolean profileFallback =
                    "profile".equals(text(data.get("target_source")));
            // 耗时类 SQL 的 TargetValue CTE 在没有配置行时可能返回 0；0 分钟不是
            // 有效运行目标，应视为缺失并回退审批 Profile 的静态目标（当前为 5 分钟）。
            targetValue = profileFallback
                    ? normalizeConfiguredTarget(
                            data.get("target_value"), valueType, unit)
                    : "median_duration".equals(valueType)
                    && dynamicTarget != null
                    && dynamicTarget <= 0
                    && configuredTarget != null
                    ? configuredTarget
                    : normalizeDynamicTarget(
                            data.get("target_value"), valueType, unit);
        } else if (targetValue == null) {
            targetValue = configuredTarget;
        } else {
            targetValue = normalizeConfiguredTarget(
                    targetValue, valueType, unit);
        }
        IndicatorExecutionResult.Status status = noSample
                ? IndicatorExecutionResult.Status.NO_SAMPLE
                : IndicatorExecutionResult.Status.SUCCESS;
        return new IndicatorExecutionResult(
                ruleId, displayName, status, resultValue, numerator, denominator,
                valueType, unit, calculationDisplay, sampleCount, targetValue, targetDirection,
                text(data.get("stat_start")), text(data.get("stat_end")),
                text(data.get("run_id")), null, null, durationMs,
                text(data.get("data_freshness")),
                first(profileId, text(data.get("profile_id"))),
                profileLabel,
                text(data.get("extraction_id")),
                text(data.get("extraction_status")),
                eventNo);
    }

    /**
     * 概览 SQL 的百分比目标统一来自 {@code TARGET_COMP_VAL / 100.0}，
     * 而 Java 为了展示分子/分母计算结果时使用百分数值（例如 95.00）。
     * 因此动态目标必须转换为同一量纲后再展示和判定。
     */
    private static Object normalizeDynamicTarget(
            Object target, String valueType, String unit) {
        Double numeric = number(target);
        if (numeric == null || !isPercentage(valueType, unit)) {
            return target;
        }
        return numeric * 100;
    }

    /**
     * 历史审批 Profile 的静态目标同时存在 0.95 与 95 两种写法；不大于 1
     * 的百分比按比例小数解释，其余按百分数值解释。
     */
    private static Object normalizeConfiguredTarget(
            Object target, String valueType, String unit) {
        Double numeric = number(target);
        if (numeric == null || !isPercentage(valueType, unit)) {
            return target;
        }
        return Math.abs(numeric) <= 1 ? numeric * 100 : numeric;
    }

    private static boolean isPercentage(String valueType, String unit) {
        String normalizedType = text(valueType);
        String normalizedUnit = text(unit);
        return "percentage".equalsIgnoreCase(normalizedType)
                || "percentage".equalsIgnoreCase(normalizedUnit)
                || "percent".equalsIgnoreCase(normalizedUnit)
                || "%".equals(normalizedUnit);
    }

    private static String calculationDisplay(
            String valueType, String left, String right, Long sampleCount) {
        if ("rate_ratio".equals(valueType)) {
            if (left == null && right == null) {
                return null;
            }
            return first(left, "无数据") + " : " + first(right, "无数据");
        }
        if ("median_duration".equals(valueType)) {
            return sampleCount == null ? "中位数" : "中位数，n=" + sampleCount;
        }
        return null;
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

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
