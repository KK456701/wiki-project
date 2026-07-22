package com.hospital.wikiagent.agent.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR.PlanNode;
import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.EvidenceFact;
import com.hospital.wikiagent.agent.runtime.ToolResult;

/**
 * 根据计划所需事实和当前已验证状态选择下一项业务能力。
 * 缺时间、权限拒绝和工具故障在此进入明确兜底，不交给模型猜测下一步。
 */
@Component
public class AgentStateController {
    private static final Map<String, FallbackCategory> BLOCKING = Map.of(
            "TRIAL_RUN_FAILED", FallbackCategory.SYSTEM_OPERATOR,
            "DIAGNOSIS_FAILED", FallbackCategory.SYSTEM_OPERATOR,
            "TOOL_TIMEOUT", FallbackCategory.SYSTEM_OPERATOR,
            "TOOL_EXECUTION_FAILED", FallbackCategory.SYSTEM_OPERATOR,
            "FIELD_PRECHECK_FAILED", FallbackCategory.IMPLEMENTATION_SUPPORT,
            "PERMISSION_DENIED", FallbackCategory.SECURITY_DENIAL,
            "PATIENT_DETAIL_FORBIDDEN", FallbackCategory.SECURITY_DENIAL);

    private final CapabilitySpecRegistry registry;

    public AgentStateController(CapabilitySpecRegistry registry) {
        this.registry = registry;
    }

    public ControllerDecision nextDecision(
            CompiledPlanIR plan,
            PlanValidation validation,
            AgentRunState state) {
        if (!validation.ok()) {
            return fallback(
                    null,
                    validation.code(),
                    validation.message(),
                    validation.fallbackCategory());
        }
        ControllerDecision blocking = blockingFailure(state);
        if (blocking != null) {
            return blocking;
        }
        String ambiguity = indicatorAmbiguity(state);
        if (!ambiguity.isEmpty()) {
            return fallback(
                    PlanCapability.RESOLVE_INDICATOR,
                    "INDICATOR_AMBIGUOUS",
                    ambiguity,
                    FallbackCategory.USER_CLARIFICATION);
        }
        if (indicatorNotFound(state)) {
            return fallback(
                    PlanCapability.RESOLVE_INDICATOR,
                    "INDICATOR_NOT_FOUND",
                    "未找到匹配指标，请确认指标名称。",
                    FallbackCategory.USER_CLARIFICATION);
        }

        Set<String> facts = stateFacts(state, validation);
        for (PlanNode node : plan.nodes()) {
            if (node.capability() == PlanCapability.COMPOSE_ANSWER) {
                continue;
            }
            CapabilitySpec spec = registry.require(node.capability());
            if (spec.verifier().verify(facts, spec)) {
                continue;
            }
            if (node.capability() == PlanCapability.RESOLVE_TIME_RANGE) {
                return fallback(
                        node.capability(),
                        "TIME_RANGE_AMBIGUOUS",
                        "请明确需要统计的开始时间和结束时间。",
                        FallbackCategory.USER_CLARIFICATION);
            }
            return new ControllerDecision(
                    ControllerAction.EXECUTE_TOOL,
                    node.capability(),
                    spec.toolName() == null ? List.of() : List.of(spec.toolName()),
                    "NEXT_CAPABILITY",
                    "",
                    null,
                    null);
        }
        return new ControllerDecision(
                ControllerAction.COMPOSE_ANSWER,
                PlanCapability.COMPOSE_ANSWER,
                List.of(),
                "PLAN_FACTS_READY",
                "",
                null,
                null);
    }

    static Set<String> stateFacts(AgentRunState state, PlanValidation validation) {
        Set<String> facts = new HashSet<>();
        for (EvidenceFact evidence : state.evidence()) {
            for (String raw : evidence.factTypes()) {
                String fact = "upload_analysis".equals(raw) ? "file_analysis" : raw;
                if (!"rule_identity".equals(fact)
                        || (evidence.sourceId() != null && !evidence.sourceId().isBlank())) {
                    facts.add(fact);
                }
            }
        }
        for (ToolResult result : state.lastToolResults()) {
            if (!result.ok()) {
                continue;
            }
            switch (result.code()) {
                case "RULE_SEARCHED" -> {
                    Object ruleId = result.data().get("resolved_rule_id");
                    if (ruleId != null && !ruleId.toString().isBlank()) {
                        facts.add("rule_identity");
                    }
                }
                case "EFFECTIVE_RULE_FOUND" -> {
                    facts.add("effective_rule");
                    facts.add("definition");
                    facts.add("formula");
                }
                case "IMPLEMENTATION_INSPECTED" -> {
                    facts.add("implementation_status");
                    facts.add("field_mapping");
                }
                case "SQL_OBJECT_PREPARED" -> facts.add("sql_validation");
                case "TRIAL_RUN_COMPLETED" -> facts.add("trial_run");
                case "DIAGNOSIS_COMPLETED", "INDICATOR_DIAGNOSED" -> facts.add("diagnosis");
                case "RULE_CHANGE_PREVIEWED" -> facts.add("rule_change_preview");
                case "UPLOAD_ANALYZED" -> facts.add("file_analysis");
                case "IMPLEMENTATION_VALIDATION_COMPLETED" -> facts.add("implementation_validation_report");
                default -> {
                }
            }
        }
        if (validation.resolvedTime() != null) {
            facts.add("stat_period");
        }
        if (state.currentRuleId() != null) {
            facts.add("rule_identity");
        }
        return facts;
    }

    private static ControllerDecision blockingFailure(AgentRunState state) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            if (result.ok()) {
                continue;
            }
            FallbackCategory category = BLOCKING.get(result.code());
            if (category != null) {
                return fallback(null, result.code(), result.summary(), category);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String indicatorAmbiguity(AgentRunState state) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            if (!"RULE_SEARCHED".equals(result.code())) {
                continue;
            }
            if (result.data().get("resolved_rule_id") != null) {
                return "";
            }
            Object rawMatches = result.data().get("matches");
            if (!(rawMatches instanceof List<?> matches) || matches.size() <= 1) {
                return "";
            }
            List<String> names = new ArrayList<>();
            for (Object raw : matches.stream().limit(3).toList()) {
                if (raw instanceof Map<?, ?> item) {
                    Object name = item.get("rule_name") != null ? item.get("rule_name") : item.get("rule_id");
                    if (name != null && !name.toString().isBlank()) {
                        names.add(name.toString());
                    }
                }
            }
            return "找到多个可能的指标，请明确选择：" + String.join("、", names);
        }
        return "";
    }

    private static boolean indicatorNotFound(AgentRunState state) {
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            if (!"RULE_SEARCHED".equals(result.code())) {
                continue;
            }
            if (result.data().get("resolved_rule_id") != null) {
                return false;
            }
            Object rawMatches = result.data().get("matches");
            return rawMatches instanceof List<?> matches && matches.isEmpty();
        }
        return false;
    }

    private static ControllerDecision fallback(
            PlanCapability capability,
            String code,
            String message,
            FallbackCategory category) {
        return new ControllerDecision(
                ControllerAction.FALLBACK,
                capability,
                List.of(),
                code,
                message,
                category,
                FailureClass.classify(code));
    }
}
