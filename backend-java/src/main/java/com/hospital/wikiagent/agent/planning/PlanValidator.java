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
        Set<String> constraints = new HashSet<>();
        plan.constraints().forEach(value -> constraints.add(value.strip().toLowerCase(Locale.ROOT)));
        if (constraints.contains("patient_level_detail")) {
            return PlanValidation.invalid(
                    "PATIENT_DETAIL_FORBIDDEN",
                    "当前 Agent 不允许访问或返回患者明细。",
                    FallbackCategory.SECURITY_DENIAL);
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
        boolean needsDatabase = outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT)
                || outputs.contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT);
        boolean needsTime = outputs.contains(RequestedOutput.PREPARED_SQL_HANDLE)
                || outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT)
                || outputs.contains(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT)
                || plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                || plan.intent() == PlanIntent.INDICATOR_TRIAL_RUN
                || plan.intent() == PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS
                || plan.intent() == PlanIntent.IMPLEMENTATION_VALIDATION;

        if (needsDatabase && constraints.contains("no_database_access")) {
            return PlanValidation.invalid(
                    "DATABASE_ACCESS_CONFLICT",
                    "实际指标结果需要执行医院业务库只读聚合查询。",
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
            return PlanValidation.invalid(
                    "TIME_RANGE_AMBIGUOUS",
                    "请明确需要统计的开始时间和结束时间。",
                    FallbackCategory.USER_CLARIFICATION);
        }
        return PlanValidation.valid(resolved);
    }
}
