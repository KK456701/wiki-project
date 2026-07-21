package com.hospital.wikiagent.agent.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.EvidenceFact;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolRegistry;

@Component
public class CapabilitySpecRegistry {
    public static final String VERSION = "capability-registry-v1";
    private static final Set<String> KNOWN_VERIFIERS = Set.of("fact_present");
    private static final Map<PlanCapability, Integer> PHASE_ORDER = phaseOrder();

    private final Map<PlanCapability, CapabilitySpec> specs;

    @Autowired
    public CapabilitySpecRegistry(ToolRegistry tools) {
        this(defaultSpecs(), tools.names());
    }

    public CapabilitySpecRegistry(List<CapabilitySpec> values, Set<String> knownTools) {
        Map<PlanCapability, CapabilitySpec> mapped = new EnumMap<>(PlanCapability.class);
        for (CapabilitySpec spec : values) {
            if (mapped.putIfAbsent(spec.capability(), spec) != null) {
                throw new IllegalArgumentException("重复业务能力: " + spec.capability().value());
            }
        }
        specs = Map.copyOf(mapped);
        validate(knownTools);
    }

    public CapabilitySpec require(PlanCapability capability) {
        CapabilitySpec spec = specs.get(capability);
        if (spec == null) {
            throw new IllegalArgumentException("未知业务能力: " + capability.value());
        }
        return spec;
    }

    public CapabilitySpec producerFor(String fact) {
        CapabilitySpec match = null;
        for (CapabilitySpec spec : specs.values()) {
            if (!spec.produces().contains(fact)) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException("重复 Fact Producer: " + fact);
            }
            match = spec;
        }
        return match;
    }

    public Set<String> requiredOutputFacts(RequestPlan plan) {
        Map<RequestedOutput, String> mapping = Map.of(
                RequestedOutput.DEFINITION, "definition",
                RequestedOutput.FORMULA, "formula",
                RequestedOutput.IMPLEMENTATION_STATUS, "implementation_status",
                RequestedOutput.PREPARED_SQL_HANDLE, "sql_validation",
                RequestedOutput.TRIAL_RESULT, "trial_run",
                RequestedOutput.DIAGNOSIS, "diagnosis",
                RequestedOutput.CHANGE_PREVIEW, "rule_change_preview",
                RequestedOutput.FILE_ANALYSIS, "file_analysis",
                RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT, "implementation_validation_report");
        Set<String> facts = new LinkedHashSet<>();
        for (RequestedOutput output : plan.requestedOutputs()) {
            if (mapping.containsKey(output)) {
                facts.add(mapping.get(output));
            }
        }
        if (!facts.isEmpty()) {
            return facts;
        }
        return switch (plan.intent()) {
            case RULE_EXPLANATION -> Set.of("effective_rule");
            case INDICATOR_SQL_PREPARE -> Set.of("sql_validation");
            case INDICATOR_TRIAL_RUN -> Set.of("trial_run");
            case INDICATOR_DIAGNOSIS -> Set.of("diagnosis");
            case RULE_CHANGE_PREVIEW -> Set.of("rule_change_preview");
            case UPLOAD_ANALYSIS -> Set.of("file_analysis");
            case IMPLEMENTATION_VALIDATION -> Set.of("implementation_validation_report");
            default -> Set.of();
        };
    }

    public List<PlanCapability> compileCapabilities(Set<String> targetFacts) {
        List<PlanCapability> ordered = new ArrayList<>();
        Set<PlanCapability> visited = new HashSet<>();
        List<String> facts = new ArrayList<>(targetFacts);
        facts.sort(Comparator.naturalOrder());
        for (String fact : facts) {
            addFact(fact, ordered, visited);
        }
        ordered.sort(Comparator.comparingInt(capability -> PHASE_ORDER.getOrDefault(capability, 1000)));
        ordered.add(PlanCapability.COMPOSE_ANSWER);
        return ordered;
    }

    public void validate(Set<String> knownTools) {
        Map<String, PlanCapability> producers = new HashMap<>();
        for (CapabilitySpec spec : specs.values()) {
            if (!KNOWN_VERIFIERS.contains(spec.verifierName()) || spec.verifier() == null) {
                throw new IllegalArgumentException("未知 Verifier: " + spec.verifierName());
            }
            if (spec.toolName() != null && spec.argumentCompiler() == null) {
                throw new IllegalArgumentException("能力缺少参数编译器: " + spec.capability().value());
            }
            if (spec.toolName() != null && !knownTools.contains(spec.toolName())) {
                throw new IllegalArgumentException("未知工具: " + spec.toolName());
            }
            for (String fact : spec.produces()) {
                PlanCapability previous = producers.putIfAbsent(fact, spec.capability());
                if (previous != null && previous != spec.capability()) {
                    throw new IllegalArgumentException("重复 Fact Producer: " + fact);
                }
            }
        }
        validateCycles(producers);
    }

    private void addFact(String fact, List<PlanCapability> ordered, Set<PlanCapability> visited) {
        CapabilitySpec producer = producerFor(fact);
        if (producer == null || visited.contains(producer.capability())) {
            return;
        }
        List<String> requirements = new ArrayList<>(producer.requires());
        requirements.sort(Comparator.naturalOrder());
        requirements.forEach(required -> addFact(required, ordered, visited));
        visited.add(producer.capability());
        ordered.add(producer.capability());
    }

    private void validateCycles(Map<String, PlanCapability> producers) {
        Set<PlanCapability> visiting = new HashSet<>();
        Set<PlanCapability> visited = new HashSet<>();
        for (PlanCapability capability : specs.keySet()) {
            visit(capability, producers, visiting, visited);
        }
    }

    private void visit(
            PlanCapability capability,
            Map<String, PlanCapability> producers,
            Set<PlanCapability> visiting,
            Set<PlanCapability> visited) {
        if (visiting.contains(capability)) {
            throw new IllegalArgumentException("业务能力存在依赖环: " + capability.value());
        }
        if (visited.contains(capability)) {
            return;
        }
        visiting.add(capability);
        for (String fact : require(capability).requires()) {
            PlanCapability producer = producers.get(fact);
            if (producer != null) {
                visit(producer, producers, visiting, visited);
            }
        }
        visiting.remove(capability);
        visited.add(capability);
    }

    static List<CapabilitySpec> defaultSpecs() {
        return List.of(
                spec(PlanCapability.RESOLVE_INDICATOR, Set.of(), Set.of("rule_identity"),
                        "search_indicator_rules", CapabilitySpecRegistry::indicatorQuery, "rule_identity"),
                spec(PlanCapability.RESOLVE_EFFECTIVE_RULE, Set.of("rule_identity"),
                        Set.of("effective_rule", "definition", "formula"),
                        "get_effective_rule", CapabilitySpecRegistry::ruleReference, "effective_rule"),
                spec(PlanCapability.RESOLVE_TIME_RANGE, Set.of(), Set.of("stat_period"),
                        null, null, "stat_period", "agent.time.resolve", "evidence_only"),
                spec(PlanCapability.INSPECT_IMPLEMENTATION, Set.of("effective_rule"),
                        Set.of("implementation_status", "field_mapping"),
                        "inspect_indicator_implementation", CapabilitySpecRegistry::ruleReference,
                        "implementation_status"),
                spec(PlanCapability.PREPARE_VERIFIED_SQL, Set.of("effective_rule", "stat_period"),
                        Set.of("sql_validation"), "prepare_indicator_sql",
                        CapabilitySpecRegistry::sqlArguments, "sql_validation"),
                spec(PlanCapability.EXECUTE_TRIAL_RUN, Set.of("sql_validation"), Set.of("trial_run"),
                        "trial_run_indicator_sql", CapabilitySpecRegistry::trialArguments, "trial_run"),
                spec(PlanCapability.DIAGNOSE_INDICATOR, Set.of("effective_rule", "implementation_status"),
                        Set.of("diagnosis"), "diagnose_indicator_issue",
                        CapabilitySpecRegistry::diagnosisArguments, "diagnosis"),
                spec(PlanCapability.PREVIEW_RULE_CHANGE, Set.of("effective_rule"),
                        Set.of("rule_change_preview"), "preview_rule_change",
                        CapabilitySpecRegistry::changeArguments, "rule_change_preview"),
                spec(PlanCapability.ANALYZE_UPLOADED_FILE, Set.of(), Set.of("file_analysis"),
                        "analyze_uploaded_indicators", CapabilitySpecRegistry::uploadArguments, "file_analysis"),
                spec(PlanCapability.VALIDATE_IMPLEMENTATION,
                        Set.of("effective_rule", "implementation_status", "stat_period"),
                        Set.of("implementation_validation_report"), "validate_indicator_implementation",
                        CapabilitySpecRegistry::validationArguments, "implementation_validation_report",
                        "agent.implementation.validate", "validation_report"),
                spec(PlanCapability.COMPOSE_ANSWER, Set.of(), Set.of(), null, null, null,
                        "agent.answer.compose", "verified_evidence_only"));
    }

    private static CapabilitySpec spec(
            PlanCapability capability,
            Set<String> requires,
            Set<String> produces,
            String tool,
            CapabilitySpec.ArgumentCompiler compiler,
            String completionFact) {
        return spec(capability, requires, produces, tool, compiler, completionFact,
                "agent.tool.execute", "evidence_only");
    }

    private static CapabilitySpec spec(
            PlanCapability capability,
            Set<String> requires,
            Set<String> produces,
            String tool,
            CapabilitySpec.ArgumentCompiler compiler,
            String completionFact,
            String policyAction,
            String answerMode) {
        return new CapabilitySpec(
                capability,
                "1.0",
                requires,
                produces,
                tool,
                policyAction,
                compiler,
                "fact_present",
                (facts, value) -> value.completionFact() == null || facts.contains(value.completionFact()),
                "none",
                answerMode,
                completionFact);
    }

    private static Map<String, Object> indicatorQuery(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        String query = execution.requestPlan().targetIndicator().rawName();
        if (query.isBlank()) {
            query = userMessage == null ? "" : userMessage.strip();
        }
        if (query.isBlank()) {
            throw new CapabilityDispatchException(
                    "INDICATOR_QUERY_MISSING", "请提供需要查询的指标名称。", true);
        }
        return Map.of("query", query, "limit", 5);
    }

    private static Map<String, Object> ruleReference(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        return Map.of("rule_id", resolveRuleId(execution, state));
    }

    private static Map<String, Object> sqlArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        PlanValidation.ResolvedTimeRange period = execution.validation().resolvedTime();
        if (period == null) {
            throw new CapabilityDispatchException(
                    "STAT_PERIOD_MISSING", "请明确需要统计的开始时间和结束时间。", true);
        }
        return Map.of(
                "rule_id", resolveRuleId(execution, state),
                "stat_start_time", period.startTime().toString(),
                "stat_end_time", period.endTime().toString());
    }

    private static Map<String, Object> trialArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        if (state.validatedSqlIds().isEmpty()) {
            throw new CapabilityDispatchException(
                    "VALIDATED_SQL_ID_MISSING", "当前没有可试运行的已校验 SQL，请重新准备 SQL。");
        }
        return Map.of("sql_id", state.validatedSqlIds().get(state.validatedSqlIds().size() - 1));
    }

    private static Map<String, Object> diagnosisArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        return Map.of(
                "rule_id", resolveRuleId(execution, state),
                "issue_description", userMessage == null || userMessage.isBlank()
                        ? "请排查当前指标异常。"
                        : userMessage.strip());
    }

    private static Map<String, Object> changeArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new CapabilityDispatchException(
                    "CHANGE_DESCRIPTION_MISSING", "请说明希望调整的本院指标口径。", true);
        }
        return Map.of(
                "rule_id", resolveRuleId(execution, state),
                "change_description", userMessage.strip());
    }

    private static Map<String, Object> uploadArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        if (state.currentUploadFileKey() == null) {
            throw new CapabilityDispatchException(
                    "UPLOAD_FILE_KEY_MISSING", "请先上传需要分析的 Excel 文件。", true);
        }
        return Map.of("file_key", state.currentUploadFileKey());
    }

    private static Map<String, Object> validationArguments(
            PlanningExecution execution, AgentRunState state, String userMessage) {
        Map<String, Object> values = new LinkedHashMap<>(sqlArguments(execution, state, userMessage));
        if (state.currentUploadFileKey() != null) {
            values.put("file_key", state.currentUploadFileKey());
        }
        return values;
    }

    private static String resolveRuleId(PlanningExecution execution, AgentRunState state) {
        if (state.currentRuleId() != null) {
            return state.currentRuleId();
        }
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            Object value = result.data().get("resolved_rule_id");
            if (result.ok() && value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        for (int index = state.evidence().size() - 1; index >= 0; index--) {
            EvidenceFact evidence = state.evidence().get(index);
            if (evidence.factTypes().contains("rule_identity")
                    && evidence.sourceId() != null
                    && !evidence.sourceId().isBlank()) {
                return evidence.sourceId();
            }
        }
        String planned = execution.requestPlan().targetIndicator().ruleId();
        if (planned != null) {
            return planned;
        }
        throw new CapabilityDispatchException(
                "RULE_ID_MISSING", "当前尚未确认唯一指标，请先明确指标名称。", true);
    }

    private static Map<PlanCapability, Integer> phaseOrder() {
        Map<PlanCapability, Integer> result = new EnumMap<>(PlanCapability.class);
        PlanCapability[] values = {
                PlanCapability.RESOLVE_INDICATOR,
                PlanCapability.RESOLVE_EFFECTIVE_RULE,
                PlanCapability.RESOLVE_TIME_RANGE,
                PlanCapability.INSPECT_IMPLEMENTATION,
                PlanCapability.PREPARE_VERIFIED_SQL,
                PlanCapability.EXECUTE_TRIAL_RUN,
                PlanCapability.DIAGNOSE_INDICATOR,
                PlanCapability.PREVIEW_RULE_CHANGE,
                PlanCapability.ANALYZE_UPLOADED_FILE,
                PlanCapability.VALIDATE_IMPLEMENTATION
        };
        for (int index = 0; index < values.length; index++) {
            result.put(values[index], index);
        }
        return Map.copyOf(result);
    }
}
