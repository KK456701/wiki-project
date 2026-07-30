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
import com.hospital.wikiagent.agent.diagnosis.IndicatorDifferenceDiagnosisWorkflow;
import com.hospital.wikiagent.agent.sql.IndicatorCaliberTools;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.rules.RuleReadRepository;
import com.hospital.wikiagent.rules.RuleNotFoundException;

/**
 * 注册运行时允许调用的工具及其输入类型、风险等级、权限前置条件和超时。
 *
 * <p>注册内容在启动阶段完成校验并在运行期只读使用，重复 ID、未知实现或不完整配置会快速失败。调用方不得根据模型文本动态注册新的生产能力。</p>
 */
@Component
public class ToolRegistry {
    /*
     * 计算工具包含业务库抽取、真实库事务替换和概览查询。它不是普通 30 秒
     * 只读工具；若外层先超时，底层 JDBC/MCP 任务仍可能继续持有全局快照锁，
     * 后续 Profile 就会被连锁误判超时。该上限应大于 DBHub 单次 120 秒上限，
     * 批量总时限仍由上层 compound-timeout / 客户端控制。
     */
    private static final Duration INDICATOR_EXECUTION_TIMEOUT =
            Duration.ofSeconds(180);
    private final Map<String, AgentTool> tools;

    @Autowired
    public ToolRegistry(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorCaliberTools caliberTools,
            IndicatorDiagnosisTools diagnosisTools,
            IndicatorDifferenceDiagnosisWorkflow differenceDiagnosisWorkflow,
            UploadedIndicatorTools uploadTools) {
        this(rules, sqlTools, caliberTools, diagnosisTools,
                differenceDiagnosisWorkflow, uploadTools, true);
    }

    public ToolRegistry(RuleReadRepository rules, IndicatorSqlTools sqlTools) {
        this(rules, sqlTools, null, null, null, null, true);
    }

    /**
     * 保留既有测试和嵌入式调用的构造契约；生产 Spring 注入使用包含差异 Workflow 的
     * 完整构造器。
     */
    public ToolRegistry(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorDiagnosisTools diagnosisTools,
            UploadedIndicatorTools uploadTools) {
        this(rules, sqlTools, null, diagnosisTools, null, uploadTools, true);
    }

    public ToolRegistry(RuleReadRepository rules) {
        this(rules, null, null, null, null, null, false);
    }

    private ToolRegistry(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            IndicatorCaliberTools caliberTools,
            IndicatorDiagnosisTools diagnosisTools,
            IndicatorDifferenceDiagnosisWorkflow differenceDiagnosisWorkflow,
            UploadedIndicatorTools uploadTools,
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
                    Map<String, Object> data;
                    try {
                        data = arguments.profileId() == null
                                ? rules.effectiveRule(
                                        arguments.ruleId(),
                                        context.agentContext().hospitalId())
                                : rules.effectiveRule(
                                        arguments.ruleId(),
                                        context.agentContext().hospitalId(),
                                        arguments.profileId());
                    } catch (RuleNotFoundException exception) {
                        // 该指标在本医院没有可读取的生效规则（或规则未入库）。
                        // 返回结构化失败而非抛异常，避免复合请求中单个指标失败拖垮整体回答。
                        return ToolResult.failure(
                                "failed", "EFFECTIVE_RULE_NOT_FOUND",
                                "未找到指标 " + arguments.ruleId() + " 在本院的生效规则，可能尚未配置本院口径或未入库。",
                                false);
                    }
                    return ToolResult.success(
                            "EFFECTIVE_RULE_FOUND",
                            "已读取 " + data.getOrDefault("ruleName", arguments.ruleId()) + " 的生效规则。",
                            data);
                }));
        register(values, new AgentTool(
                "list_indicator_calibers",
                RuleReferenceInput.class,
                Set.of(),
                Duration.ofSeconds(10),
                AgentTool.RiskLevel.READ_ONLY,
                true,
                null,
                (input, context) -> {
                    RuleReferenceInput arguments = (RuleReferenceInput) input;
                    Map<String, Object> effective = rules.effectiveRule(
                            arguments.ruleId(), context.agentContext().hospitalId());
                    List<Map<String, Object>> options = rules.caliberCatalog(
                            arguments.ruleId(), context.agentContext().hospitalId());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("ruleId", arguments.ruleId());
                    data.put("ruleName", effective.get("ruleName"));
                    data.put("currentProfileId", effective.get("profileId"));
                    data.put("currentProfileName", effective.get("profileName"));
                    data.put("caliberOptions", options);
                    return ToolResult.success(
                            "CALIBER_OPTIONS_FOUND",
                            options.size() <= 1
                                    ? "当前指标只有一种已发布口径。"
                                    : "已读取当前指标的口径选项。",
                            data);
                }));
        register(values, new AgentTool(
                "preview_rule_change",
                PreviewRuleChangeInput.class,
                Set.of(),
                Duration.ofSeconds(30),
                AgentTool.RiskLevel.READ_ONLY,
                true,
                (context, state) -> state.currentRuleId() != null,
                (input, context) -> {
                    PreviewRuleChangeInput arguments = (PreviewRuleChangeInput) input;
                    if (!arguments.ruleId().equals(context.runState().currentRuleId())) {
                        return ToolResult.failure(
                                "validation_failed", "RULE_NOT_VERIFIED",
                                "该指标尚未经过规则搜索或读取，不能预览口径变更。", false);
                    }
                    Map<String, Object> data = rules.previewChange(
                            arguments.ruleId(), context.agentContext().hospitalId(),
                            arguments.changeDescription());
                    return ToolResult.success(
                            "RULE_CHANGE_PREVIEWED",
                            "本院口径变更预览已生成，尚未提交审批或发布。", data);
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
                    INDICATOR_EXECUTION_TIMEOUT,
                    AgentTool.RiskLevel.CONTROLLED_EXECUTION,
                    true,
                    (context, state) -> !state.validatedSqlIds().isEmpty(),
                    (input, context) -> sqlTools.trial((IndicatorSqlTools.TrialInput) input, context)));
        }
        if (caliberTools != null) {
            register(values, new AgentTool(
                    "resolve_indicator_caliber",
                    IndicatorCaliberTools.ResolveInput.class,
                    Set.of(),
                    Duration.ofSeconds(10),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    (context, state) -> state.currentRuleId() != null,
                    (input, context) -> caliberTools.resolve(
                            (IndicatorCaliberTools.ResolveInput) input, context)));
            register(values, new AgentTool(
                    "prepare_indicator_caliber_sql",
                    IndicatorCaliberTools.PrepareInput.class,
                    Set.of(),
                    Duration.ofSeconds(30),
                    AgentTool.RiskLevel.READ_ONLY,
                    true,
                    (context, state) -> state.currentCaliberProfileId() != null,
                    (input, context) -> caliberTools.prepare(
                            (IndicatorCaliberTools.PrepareInput) input, context)));
            register(values, new AgentTool(
                    "trial_run_indicator_caliber_sql",
                    IndicatorCaliberTools.TrialInput.class,
                    Set.of(),
                    INDICATOR_EXECUTION_TIMEOUT,
                    AgentTool.RiskLevel.CONTROLLED_EXECUTION,
                    true,
                    (context, state) -> state.currentCaliberProfileId() != null
                            && !state.validatedSqlIds().isEmpty(),
                    (input, context) -> caliberTools.trial(
                            (IndicatorCaliberTools.TrialInput) input, context)));
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
        if (differenceDiagnosisWorkflow != null) {
            register(values, new AgentTool(
                    "diagnose_indicator_difference",
                    IndicatorDifferenceDiagnosisWorkflow.Input.class,
                    Set.of(),
                    Duration.ofSeconds(150),
                    AgentTool.RiskLevel.CONTROLLED_EXECUTION,
                    true,
                    (context, state) -> state.currentRuleId() != null,
                    (input, context) -> differenceDiagnosisWorkflow.diagnose(
                            (IndicatorDifferenceDiagnosisWorkflow.Input) input, context)));
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
        for (String name : List.of(
                "inspect_indicator_implementation",
                "prepare_indicator_sql",
                "trial_run_indicator_sql",
                "list_indicator_calibers",
                "resolve_indicator_caliber",
                "prepare_indicator_caliber_sql",
                "trial_run_indicator_caliber_sql",
                "diagnose_indicator_issue",
                "diagnose_indicator_difference",
                "analyze_uploaded_indicators")) {
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
                        "TOOL_DEPENDENCY_UNAVAILABLE",
                        "当前 Java 运行时未配置该工具所需的领域依赖。",
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

    public record RuleReferenceInput(String ruleId, String profileId) {
        public RuleReferenceInput(String ruleId) {
            this(ruleId, null);
        }

        public RuleReferenceInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            profileId = profileId == null || profileId.isBlank()
                    ? null : profileId.strip();
            if (ruleId.isEmpty()) {
                throw new IllegalArgumentException("规则编号不能为空");
            }
        }
    }

    public record PreviewRuleChangeInput(String ruleId, String changeDescription) {
        public PreviewRuleChangeInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            changeDescription = changeDescription == null ? "" : changeDescription.strip();
            if (ruleId.isEmpty()) {
                throw new IllegalArgumentException("规则编号不能为空");
            }
            if (changeDescription.length() < 2 || changeDescription.length() > 5000) {
                throw new IllegalArgumentException("口径调整说明必须为 2 至 5000 个字符");
            }
        }
    }
}
