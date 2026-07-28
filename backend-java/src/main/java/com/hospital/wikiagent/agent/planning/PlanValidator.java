package com.hospital.wikiagent.agent.planning;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;

/**
 * 校验业务目标冲突并确定性解析统计时间，阻止不完整计划进入工具层。
 *
 * <p>校验结果由确定性代码给出，不能依赖模型自我声明成功。任何医院、规则版本、统计周期或 SQL 链路不一致都必须阻止后续执行。</p>
 */
@Component
public class PlanValidator {
    private final TimeRangeResolver timeResolver;

    public PlanValidator(TimeRangeResolver timeResolver) {
        this.timeResolver = timeResolver;
    }

    public PlanValidation validate(RequestPlan plan) {
        return validate(plan, true);
    }

    /**
     * 批量任务的每个 Profile 都会独立抽取并替换真实库快照，因此批量入口也必须在
     * 生成任务前拒绝超过一个月的统计区间，不能依靠 worker 数或查询超时放宽限制。
     */
    public PlanValidation validateBatch(RequestPlan plan) {
        return validate(plan, true);
    }

    private PlanValidation validate(RequestPlan plan, boolean enforceOneMonthLimit) {
        Set<String> constraints = new HashSet<>();
        plan.constraints().forEach(value -> constraints.add(value.strip().toLowerCase(Locale.ROOT)));
        if (constraints.contains("alignment_blocked")) {
            return PlanValidation.invalid(
                    "TASK_TYPE_MISMATCH",
                    "当前问题与业务计划仍不一致，请明确希望使用的指标口径或输出结果。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (constraints.contains("patient_level_detail")) {
            return PlanValidation.invalid(
                    "PATIENT_DETAIL_FORBIDDEN",
                    "当前 Agent 不允许访问或返回患者明细。",
                    FallbackCategory.SECURITY_DENIAL);
        }
        if (plan.intent() == PlanIntent.UNKNOWN) {
            return PlanValidation.invalid(
                    "INTENT_AMBIGUOUS",
                    "我还不能确定你是想看口径、计算结果、生成 SQL，还是排查差异。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        for (RequestPlan.SemanticAmbiguity ambiguity : plan.semanticAmbiguities()) {
            String field = ambiguity.field().toLowerCase(Locale.ROOT);
            if ((field.contains("indicator") || field.contains("指标"))
                    && plan.targetIndicator().ruleId() == null
                    && plan.targetIndicator().rawName().isBlank()) {
                return PlanValidation.invalid(
                        "TARGET_INDICATOR_AMBIGUOUS",
                        ambiguity.description(),
                        FallbackCategory.USER_CLARIFICATION);
            }
            if (field.contains("intent") || field.contains("意图") || field.contains("output")) {
                return PlanValidation.invalid(
                        "INTENT_AMBIGUOUS",
                        ambiguity.description(),
                        FallbackCategory.USER_CLARIFICATION);
            }
        }
        Set<RequestedOutput> outputs = Set.copyOf(plan.requestedOutputs());
        // 意图与输出目标的矛盾属于方向性计划错误，必须交给 FailureRouter 决定是否
        // Replan；不能混入 Planner 的 JSON 修复，否则会丢失原计划和明确失败原因。
        if (plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                && (!outputs.contains(RequestedOutput.PREPARED_SQL_HANDLE)
                || outputs.contains(RequestedOutput.TRIAL_RESULT))) {
            return PlanValidation.invalid(
                    "PLAN_INTENT_MISMATCH",
                    "SQL 准备意图只能生成受控 SQL，不能同时要求执行试运行。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (plan.intent() == PlanIntent.INDICATOR_TRIAL_RUN
                && !outputs.contains(RequestedOutput.TRIAL_RESULT)) {
            return PlanValidation.invalid(
                    "PLAN_INTENT_MISMATCH",
                    "指标试运行计划缺少具体结果输出目标。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (plan.intent() == PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS
                && !outputs.contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT)) {
            return PlanValidation.invalid(
                    "PLAN_INTENT_MISMATCH",
                    "差异诊断计划缺少分层诊断报告输出目标。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (plan.intent() == PlanIntent.INDICATOR_CALIBER_QUERY
                && !outputs.contains(RequestedOutput.CALIBER_OPTIONS)) {
            return PlanValidation.invalid(
                    "PLAN_INTENT_MISMATCH",
                    "口径列表查询计划缺少候选口径列表输出目标。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION
                && plan.targetCaliber().rawText().isBlank()
                && plan.targetCaliber().profileId() == null) {
            return PlanValidation.invalid(
                    "CALIBER_PROFILE_MISSING",
                    "请明确希望模拟的候选口径，例如“首次入区时间口径”。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION
                && !outputs.contains(RequestedOutput.CALIBER_EXPLANATION)
                && !outputs.contains(RequestedOutput.CALIBER_PREPARED_SQL_HANDLE)
                && !outputs.contains(RequestedOutput.CALIBER_TRIAL_RESULT)) {
            return PlanValidation.invalid(
                    "PLAN_INTENT_MISMATCH",
                    "候选口径计划缺少解释或试运行输出目标。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        boolean needsDatabase = outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.CALIBER_TRIAL_RESULT)
                || outputs.contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT)
                || plan.intent() == PlanIntent.INDICATOR_DIAGNOSIS;
        boolean needsTime = outputs.contains(RequestedOutput.PREPARED_SQL_HANDLE)
                || outputs.contains(RequestedOutput.CALIBER_PREPARED_SQL_HANDLE)
                || outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.CALIBER_TRIAL_RESULT)
                || outputs.contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT)
                || plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                || plan.intent() == PlanIntent.INDICATOR_TRIAL_RUN
                || plan.intent() == PlanIntent.INDICATOR_DIAGNOSIS
                || (plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION
                        && outputs.contains(RequestedOutput.CALIBER_TRIAL_RESULT))
                || plan.intent() == PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS;

        if (needsDatabase && constraints.contains("no_database_access")) {
            return PlanValidation.invalid(
                    "DATABASE_ACCESS_CONFLICT",
                    "实际指标结果需要先抽取数据并执行真实库只读聚合查询。",
                    FallbackCategory.BUSINESS_CONFIRMATION);
        }
        if (needsDatabase
                && plan.targetIndicator().rawName().isBlank()
                && plan.targetIndicator().ruleId() == null) {
            return PlanValidation.invalid(
                    "TARGET_INDICATOR_AMBIGUOUS",
                    "请明确需要查询或对比的本院指标名称。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        ResolvedTimeRange resolved = needsTime ? timeResolver.resolve(plan.timeExpression()) : null;
        if (needsTime && resolved == null) {
            // 纯 SQL 准备（只生成受控 SQL、不执行试运行）未给时间时，用默认统计周期兜底，
            // 并在回答里标注可调整；试运行等要算出真实数值的意图仍必须让用户明确时间。
            boolean sqlPrepareOnly = (plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                    || outputs.contains(RequestedOutput.PREPARED_SQL_HANDLE))
                    && !outputs.contains(RequestedOutput.TRIAL_RESULT);
            if (sqlPrepareOnly) {
                resolved = timeResolver.defaultRange();
            } else {
                return PlanValidation.invalid(
                        "TIME_RANGE_AMBIGUOUS",
                        "请明确需要统计的开始时间和结束时间。",
                        FallbackCategory.USER_CLARIFICATION);
            }
        }
        if (needsDatabase && resolved != null && enforceOneMonthLimit) {
            StatPeriodPolicy.Validation period = StatPeriodPolicy.validate(
                    resolved.startTime(), resolved.endTime());
            if (!period.ok()) {
                return PlanValidation.invalid(
                        period.code(),
                        period.message(),
                        FallbackCategory.USER_CLARIFICATION);
            }
        }
        return PlanValidation.valid(resolved);
    }
}
