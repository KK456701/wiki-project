package com.hospital.wikiagent.agent.tools;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.diagnosis.IndicatorDiagnosisTools;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.agent.validation.ImplementationValidationTools;
import com.hospital.wikiagent.rules.RuleReadRepository;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools;

    @Autowired
    public ToolRegistry(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorDiagnosisTools diagnosisTools,
            UploadedIndicatorTools uploadTools,
            ImplementationValidationTools validationTools) {
        this(rules, sqlTools, diagnosisTools, uploadTools, validationTools, true);
    }

    public ToolRegistry(RuleReadRepository rules, IndicatorSqlTools sqlTools) {
        this(rules, sqlTools, null, null, null, true);
    }

    public ToolRegistry(RuleReadRepository rules) {
        this(rules, null, null, null, null, false);
    }

    private ToolRegistry(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorDiagnosisTools diagnosisTools,
            UploadedIndicatorTools uploadTools,
            ImplementationValidationTools validationTools,
            boolean migrateSqlTools) {
        Map<String, AgentTool> values = new LinkedHashMap<>();
        register(values, new AgentTool(
                "search_indicator_rules",
                SearchRulesInput.class,
                Set.of(),
                Duration.ofSeconds(10),
                AgentTool.RiskLevel.READ_ONLY,
                true,
                null,
                (input, context) -> {
                    SearchRulesInput arguments = (SearchRulesInput) input;
                    Map<String, Object> data = rules.searchForHospital(
                            arguments.query(), context.agentContext().hospitalId(), arguments.limit());
                    int count = ((List<?>) data.getOrDefault("matches", List.of())).size();
                    return ToolResult.success("RULE_SEARCHED", "找到 " + count + " 个匹配指标。", data);
                }));
        register(values, new AgentTool(
                "get_effective_rule",
                RuleReferenceInput.class,
                Set.of(),
                Duration.ofSeconds(10),
                AgentTool.RiskLevel.READ_ONLY,
                true,
                null,
                (input, context) -> {
                    RuleReferenceInput arguments = (RuleReferenceInput) input;
                    Map<String, Object> data = rules.effectiveRule(
                            arguments.ruleId(), context.agentContext().hospitalId());
                    return ToolResult.success(
                            "EFFECTIVE_RULE_FOUND",
                            "已读取 " + data.getOrDefault("rule_name", arguments.ruleId()) + " 的生效规则。",
                            data);
                }));

        if (migrateSqlTools) {
            register(values, new AgentTool(
                    "inspect_indicator_implementation",
                    IndicatorSqlTools.InspectInput.class,
                    Set.of(),
                    Duration.ofSeconds(10),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    null,
                    (input, context) -> sqlTools.inspect((IndicatorSqlTools.InspectInput) input, context)));
            register(values, new AgentTool(
                    "prepare_indicator_sql",
                    IndicatorSqlTools.PrepareInput.class,
                    Set.of(),
                    Duration.ofSeconds(30),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    (context, state) -> state.currentRuleId() != null,
                    (input, context) -> sqlTools.prepare((IndicatorSqlTools.PrepareInput) input, context)));
            register(values, new AgentTool(
                    "trial_run_indicator_sql",
                    IndicatorSqlTools.TrialInput.class,
                    Set.of(),
                    Duration.ofSeconds(30),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    (context, state) -> !state.validatedSqlIds().isEmpty(),
                    (input, context) -> sqlTools.trial((IndicatorSqlTools.TrialInput) input, context)));
        }
        if (diagnosisTools != null) {
            register(values, new AgentTool(
                    "diagnose_indicator_issue",
                    IndicatorDiagnosisTools.Input.class,
                    Set.of(),
                    Duration.ofSeconds(60),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    (context, state) -> state.currentRuleId() != null,
                    (input, context) -> diagnosisTools.diagnose((IndicatorDiagnosisTools.Input) input, context)));
        }
        if (uploadTools != null) {
            register(values, new AgentTool(
                    "analyze_uploaded_indicators",
                    UploadedIndicatorTools.Input.class,
                    Set.of(),
                    Duration.ofSeconds(30),
                    AgentTool.RiskLevel.READ_ONLY,
                    false,
                    (context, state) -> state.currentUploadFileKey() != null,
                    (input, context) -> uploadTools.analyze(
                            (UploadedIndicatorTools.Input) input, context)));
        }
        if (validationTools != null) {
            register(values, new AgentTool(
                    "validate_indicator_implementation",
                    ImplementationValidationTools.Input.class,
                    Set.of(),
                    Duration.ofSeconds(150),
                    AgentTool.RiskLevel.CONTROLLED_EXECUTION,
                    true,
                    (context, state) -> state.currentRuleId() != null,
                    (input, context) -> validationTools.validate(
                            (ImplementationValidationTools.Input) input, context)));
        }

        for (String name : List.of(
                "inspect_indicator_implementation",
                "prepare_indicator_sql",
                "trial_run_indicator_sql",
                "diagnose_indicator_issue",
                "preview_rule_change",
                "analyze_uploaded_indicators",
                "validate_indicator_implementation")) {
            if (!values.containsKey(name)) {
                register(values, placeholder(name));
            }
        }
        tools = Map.copyOf(values);
    }

    ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> values = new LinkedHashMap<>();
        tools.forEach(tool -> register(values, tool));
        this.tools = Map.copyOf(values);
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        return tool;
    }

    public Set<String> names() {
        return tools.keySet();
    }

    private static AgentTool placeholder(String name) {
        return new AgentTool(
                name,
                Map.class,
                Set.of(),
                Duration.ofSeconds(30),
                AgentTool.RiskLevel.READ_ONLY,
                true,
                null,
                (input, context) -> ToolResult.failure(
                        "unavailable",
                        "TOOL_NOT_MIGRATED",
                        "该工具尚未迁移到 Java 影子运行时。",
                        false));
    }

    private static void register(Map<String, AgentTool> values, AgentTool tool) {
        if (values.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("重复工具: " + tool.name());
        }
    }

    public record SearchRulesInput(String query, int limit) {
        public SearchRulesInput {
            query = query == null ? "" : query.strip();
            if (query.isEmpty()) {
                throw new IllegalArgumentException("指标查询不能为空");
            }
            limit = Math.max(1, Math.min(limit, 20));
        }
    }

    public record RuleReferenceInput(String ruleId) {
        public RuleReferenceInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            if (ruleId.isEmpty()) {
                throw new IllegalArgumentException("规则编号不能为空");
            }
        }
    }
}
