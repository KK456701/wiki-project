package com.hospital.wikiagent.agent.planning;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.planning.PlanValidation.ResolvedTimeRange;

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
        boolean needsDatabase = outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT);
        boolean needsTime = outputs.contains(RequestedOutput.PREPARED_SQL_HANDLE)
                || outputs.contains(RequestedOutput.TRIAL_RESULT)
                || outputs.contains(RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT)
                || plan.intent() == PlanIntent.INDICATOR_SQL_PREPARE
                || plan.intent() == PlanIntent.INDICATOR_TRIAL_RUN
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
