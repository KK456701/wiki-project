package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.evidence.EvidenceEnvelope;
import com.hospital.wikiagent.agent.evidence.EvidenceLedger;
import com.hospital.wikiagent.agent.evidence.EvidenceStore;
import com.hospital.wikiagent.agent.evidence.EvidenceVerification;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.ir.ExplanationFocus;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.AgentFailureRouter;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanGoalAlignmentValidator;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.planning.ReplanPolicy;
import com.hospital.wikiagent.agent.planning.TimeRangeResolver;
import com.hospital.wikiagent.agent.tools.PolicyDecisionService;
import com.hospital.wikiagent.agent.tools.ToolGateway;
import com.hospital.wikiagent.agent.tools.ToolRegistry;
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlObjectRepository;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.agent.sql.SqlTemplateRenderer;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
class AgentRunnerTest {
    private static final String RULE_TEMPLATE_ANSWER = """
            # 急会诊及时到位率

            > **口径速览**
            > 当前结果依据本院生效规则。

            ## 口径摘要

            用于衡量急会诊是否及时到位。

            ## 计算口径

            指标率 = 分子 ÷ 分母 × 100%。

            ## 实施信息

            当前规则已生效，分子分母口径以本轮证据为准。
            """;
    private static final String TRIAL_TEMPLATE_ANSWER = """
            # 急会诊及时到位率 · 统计结果

            > **结论速览**
            > 指标率为 **25.0%**。

            ## 结果速览

            | 统计项 | 结果 |
            |---|---:|
            | 分子 | 1 |
            | 分母 | 4 |
            | 指标率 | **25.0%** |

            ## 计算口径

            指标率 = 1 ÷ 4 × 100%。

            ## 数据依据

            数据来自本轮已验证试运行。
            """;
    private ToolGateway gateway;

    @AfterEach
    void closeGateway() {
        if (gateway != null) {
            gateway.close();
        }
    }

    @Test
    void runsRuleExplanationThroughDeterministicToolsAndVerifiedEvidence() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        ToolRegistry tools = new ToolRegistry(ruleRepository(objectMapper));
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "rule_explanation",
                  "goal": "解释急会诊及时到位率",
                  "target_indicator": {"raw_name": "transfer_within_48h_ratio"},
                  "time_expression": {
                    "raw_text": "2026年1月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-02-01T00:00:00"
                  },
                  "requested_outputs": ["definition", "formula"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """,
                RULE_TEMPLATE_ANSWER);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "急会诊及时到位率怎么算？", "session_001", "ollama-test", null,
                "request_001", "trace_001", null, "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001")),
                events::add,
                new HybridIndicatorResolver.ResolvedIndicator(
                        "急会诊及时到位率", "急会诊及时到位率", "HXZD-003-001",
                        "RULE:HXZD-003-001", "rule", 1.0, 0, 9));

        assertThat(result.stopReason()).as(result.answer() + " " + events).isEqualTo("final_answer");
        assertThat(result.answer()).contains("分子", "分母");
        assertThat(result.requestPlan().targetIndicator().rawName()).isEqualTo("急会诊及时到位率");
        assertThat(result.requestPlan().targetIndicator().ruleId()).isEqualTo("HXZD-003-001");
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly("get_effective_rule");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("followup_plan_resolve", "rule_explanation_answer")
                .doesNotContain("planner_llm", "final_answer_llm");
        assertThat(store.evidence).hasSize(3);
        assertThat(store.verifications.values())
                .allMatch(value -> "verified".equals(value.status()));
        assertThat(models.calls).isZero();

        AgentRunResult definition = runner.run(new AgentRunRequest(
                "急会诊及时到位率是什么？", "session_definition", "ollama-test", null,
                "request_definition", "trace_definition", null, "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001",
                        Set.of(), false, "auth_session_definition")),
                AgentRunObserver.noop(),
                new HybridIndicatorResolver.ResolvedIndicator(
                        "急会诊及时到位率", "急会诊及时到位率", "HXZD-003-001",
                        "RULE:HXZD-003-001", "rule", 1.0, 0, 9));

        assertThat(definition.stopReason()).isEqualTo("final_answer");
        assertThat(definition.answer()).contains("指标定义", "急会诊及时到位率");
        assertThat(models.calls).isZero();
    }

    @Test
    void resolvesCurrentCaliberFollowupDeterministicallyBeforePlanner() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        RuleReadRepository rules = ruleRepository(objectMapper);
        ToolRegistry tools = new ToolRegistry(rules);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(RULE_TEMPLATE_ANSWER);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentConversationMemory conversations = mock(AgentConversationMemory.class);
        org.mockito.Mockito.when(conversations.open(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("session_caliber_current")))
                .thenReturn(new AgentConversationMemory.ConversationSnapshot(
                        "hospital_001:user_001:session_caliber_current",
                        "session_caliber_current",
                        "用户：急会诊及时到位率从一月到现在是多少？",
                        "{\"active_rule_id\":\"HXZD-003-001\"}",
                        "HXZD-003-001",
                        "急会诊及时到位率",
                        null,
                        null,
                        "2026-01-01T00:00:00",
                        "2026-02-01T00:00:00",
                        "RUN_001",
                        null,
                        java.util.List.of(),
                        ""));
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(
                        models, modelRegistry, properties,
                        new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(
                        models, modelRegistry, properties,
                        new PromptCatalog(), objectMapper),
                conversations,
                new AgentFailureRouter(new ReplanPolicy()),
                null,
                new PlanGoalAlignmentValidator(rules));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "根据什么口径算的", "session_caliber_current", "ollama-test", null,
                "request_caliber_current", "trace_caliber_current", null, "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001",
                        Set.of(), false, "auth_session_caliber_current")),
                events::add,
                null);

        assertThat(result.stopReason()).as(result.answer() + " " + events)
                .isEqualTo("final_answer");
        assertThat(result.requestPlan().intent()).isEqualTo(
                com.hospital.wikiagent.agent.ir.PlanIntent.RULE_EXPLANATION);
        assertThat(result.requestPlan().targetCaliber().profileId()).isNull();
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly("get_effective_rule");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("followup_plan_resolve", "plan_goal_alignment",
                        "rule_explanation_answer")
                .doesNotContain(
                        "planner_llm", "final_answer_llm", "plan_replan",
                        "plan_alignment_deterministic_fallback",
                        "plan_alignment_review_llm");
        assertThat(models.calls).isZero();
    }

    @Test
    void replansSemanticValidationFailureBeforeCallingTools() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        ToolRegistry tools = new ToolRegistry(ruleRepository(objectMapper));
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "indicator_sql_prepare",
                  "goal": "错误理解的初始目标",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {"raw_text": ""},
                  "requested_outputs": ["trial_result"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """,
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "rule_explanation",
                  "goal": "纠正后解释指标定义和公式",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {"raw_text": ""},
                  "requested_outputs": ["definition", "formula"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """,
                RULE_TEMPLATE_ANSWER);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "请介绍急会诊及时到位率", "session_001", "ollama-test", null,
                "request_001", "trace_001", null, "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001")),
                events::add,
                new HybridIndicatorResolver.ResolvedIndicator(
                        "急会诊及时到位率", "急会诊及时到位率", "HXZD-003-001",
                        "RULE:HXZD-003-001", "rule", 1.0, 0, 9));

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.requestPlan().goal()).isEqualTo("纠正后解释指标定义和公式");
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly("get_effective_rule");
        assertThat(models.calls).isEqualTo(2);
    }

    @Test
    void runsTrialResultThroughPreparedSqlObjectDbHubBoundaryAndVerifiedEvidence() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                fixture.rules(), new SqlObjectRepository(fixture.jdbc(), objectMapper),
                new SqlTemplateRenderer(), new ReadOnlySqlValidator(), new SqlParameterBinder(),
                new IndicatorBusinessQueryClient() {
                    @Override
                    public List<Map<String, Object>> execute(String sql) {
                        assertThat(sql).contains("'hospital_001'", "'2026-01-01 00:00:00'", "'2026-02-01 00:00:00'");
                        return List.of(Map.of(
                                "index_value", 25.0,
                                "numerator_count", 1,
                                "denominator_count", 4));
                    }

                    @Override
                    public String sourceId() {
                        return "business_test";
                    }
                },
                objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules(), sqlTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "indicator_trial_run",
                  "goal": "计算急会诊及时到位率",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {
                    "raw_text": "2026年1月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-02-01T00:00:00"
                  },
                  "requested_outputs": ["trial_result"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """,
                TRIAL_TEMPLATE_ANSWER);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "计算2026年1月急会诊及时到位率", "session_001", "ollama-test", null,
                "request_001", "trace_001", "business_test", "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of("indicator_detail_view"),
                        false, "auth_session_001")),
                events::add);

        assertThat(result.stopReason()).as(result.answer() + " " + events).isEqualTo("final_answer");
        assertThat(result.answer()).contains("25.0%", "1", "4");
        assertThat(result.answer()).contains("{{detail_export:RUN_");
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly(
                        "search_indicator_rules", "get_effective_rule",
                        "prepare_indicator_sql", "trial_run_indicator_sql");
        assertThat(store.evidence.values())
                .filteredOn(value -> "trial_run".equals(value.factType()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.safePayload()).containsEntry("numerator_count", 1L)
                            .containsEntry("denominator_count", 4L)
                            .containsEntry("result_value", 25.0);
                    assertThat(value.sourceObjectId()).startsWith("RUN_");
                });
        assertThat(store.verifications.values()).allMatch(value -> "verified".equals(value.status()));
    }

    @Test
    void upgradesRuleExplanationToTrialRunWhenQueryContainsTimeRange() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                fixture.rules(), new SqlObjectRepository(fixture.jdbc(), objectMapper),
                new SqlTemplateRenderer(), new ReadOnlySqlValidator(), new SqlParameterBinder(),
                new IndicatorBusinessQueryClient() {
                    @Override
                    public List<Map<String, Object>> execute(String sql) {
                        return List.of(Map.of(
                                "index_value", 25.0,
                                "numerator_count", 1,
                                "denominator_count", 4));
                    }

                    @Override
                    public String sourceId() {
                        return "business_test";
                    }
                },
                objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules(), sqlTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        // Planner 返回 RULE_EXPLANATION，但用户查询中包含显式时间范围
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "rule_explanation",
                  "goal": "解释急会诊及时到位率",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {
                    "raw_text": "2026年1月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-02-01T00:00:00"
                  },
                  "requested_outputs": ["definition", "formula"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """,
                TRIAL_TEMPLATE_ANSWER);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "计算2026-01-01到2026-02-01急会诊及时到位率", "session_001", "ollama-test", null,
                "request_001", "trace_001", "business_test", "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of("indicator_detail_view"),
                        false, "auth_session_001")),
                events::add);

        // 意图应被升级为 TRIAL_RUN，系统应执行计算并返回结果
        assertThat(result.stopReason()).as(result.answer() + " " + events).isEqualTo("final_answer");
        assertThat(result.requestPlan().intent()).isEqualTo(
                com.hospital.wikiagent.agent.ir.PlanIntent.INDICATOR_TRIAL_RUN);
        // 应执行 SQL 工具
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .contains("prepare_indicator_sql", "trial_run_indicator_sql");
    }

    @Test
    void preparedSqlAnswerUsesCompactSqlFirstTemplate() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                fixture.rules(), new SqlObjectRepository(fixture.jdbc(), objectMapper),
                new SqlTemplateRenderer(), new ReadOnlySqlValidator(), new SqlParameterBinder(),
                new IndicatorBusinessQueryClient() {
                    @Override
                    public List<Map<String, Object>> execute(String sql) {
                        throw new AssertionError("SQL prepare must not execute the business database");
                    }

                    @Override
                    public String sourceId() {
                        return "business_test";
                    }
                }, objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules(), sqlTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v2",
                  "intent": "indicator_sql_prepare",
                  "goal": "解释 SQL 以及分子分母口径",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {
                    "raw_text": "沿用2026年1月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-02-01T00:00:00"
                  },
                  "requested_outputs": ["prepared_sql_handle"],
                  "constraints": [],
                  "semantic_ambiguities": [],
                  "confidence": 0.9
                }
                """);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper), capabilities,
                new AgentStateController(capabilities), new DeterministicDispatch(), gateway, verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));

        AgentRunResult result = runner.run(new AgentRunRequest(
                "这个 SQL 是怎么写的，分子分母具体是什么口径",
                "session_001", "ollama-test", null, "request_sql", "trace_sql",
                "business_test", "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001")));

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer())
                .contains("急会诊及时到位率 · 概览 SQL", "当前 Profile")
                .contains("2026-01-01 00:00:00", "2026-02-01 00:00:00")
                .contains("本轮只展示知识库中的概览 SQL", "未访问数据库")
                .doesNotContain("分子口径：", "分母口径：", "实施信息");
        assertThat(models.calls).isEqualTo(1);
    }

    @Test
    void resolvesSqlFollowupFromStructuredContextWithoutCallingUnreliableLocalPlanner() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                fixture.rules(), new SqlObjectRepository(fixture.jdbc(), objectMapper),
                new SqlTemplateRenderer(), new ReadOnlySqlValidator(), new SqlParameterBinder(),
                new IndicatorBusinessQueryClient() {
                    @Override
                    public List<Map<String, Object>> execute(String sql) {
                        throw new AssertionError("SQL follow-up must only prepare SQL");
                    }

                    @Override
                    public String sourceId() {
                        return "business_test";
                    }
                }, objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules(), sqlTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker();
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentConversationMemory memory = AgentConversationMemory.noop();
        HospitalPrincipal principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001");
        var conversation = memory.open(principal, "session_followup");
        AgentRunState previousState = new AgentRunState();
        previousState.currentRuleId("HXZD-003-001");
        previousState.statPeriod("2026-01-01 00:00:00", "2026-02-01 00:00:00");
        memory.appendAssistant(conversation, principal,
                "急会诊及时到位率统计区间为2026-01-01 00:00:00至2026-02-01 00:00:00。",
                previousState);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper), capabilities,
                new AgentStateController(capabilities), new DeterministicDispatch(), gateway, verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                memory);
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "这个SQL是怎么写的，分子分母具体是什么口径？",
                "session_followup", "ollama-test", null, "request_followup", "trace_followup",
                "business_test", "{}", "", principal), events::add);

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer())
                .contains("急会诊及时到位率 · 概览 SQL", "当前 Profile")
                .contains("2026-01-01 00:00:00", "2026-02-01 00:00:00")
                .doesNotContain("分子口径：", "分母口径：");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("followup_plan_resolve")
                .doesNotContain("planner_llm");
        assertThat(models.calls).isZero();
    }

    @Test
    void resolvesSqlFollowupWithoutPreviousPeriodAndUsesNonExecutingDefaultRange() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        IndicatorSqlTools sqlTools = new IndicatorSqlTools(
                fixture.rules(), new SqlObjectRepository(fixture.jdbc(), objectMapper),
                new SqlTemplateRenderer(), new ReadOnlySqlValidator(), new SqlParameterBinder(),
                new IndicatorBusinessQueryClient() {
                    @Override
                    public List<Map<String, Object>> execute(String sql) {
                        throw new AssertionError("SQL follow-up must not execute the database");
                    }

                    @Override
                    public String sourceId() {
                        return "business_test";
                    }
                }, objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules(), sqlTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker();
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentConversationMemory memory = AgentConversationMemory.noop();
        HospitalPrincipal principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001");
        var conversation = memory.open(principal, "session_sql_without_period");
        AgentRunState previousState = new AgentRunState();
        previousState.currentRuleId("HXZD-003-001");
        previousState.lastRuleName("急会诊及时到位率");
        previousState.lastIntent(PlanIntent.RULE_EXPLANATION.value());
        memory.appendAssistant(
                conversation,
                principal,
                "急会诊及时到位率的定义和公式如下。",
                previousState);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper), capabilities,
                new AgentStateController(capabilities), new DeterministicDispatch(), gateway, verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                memory);
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "sql怎么写",
                "session_sql_without_period", "ollama-test", null,
                "request_sql_without_period", "trace_sql_without_period",
                "business_test", "{}", "", principal), events::add);

        assertThat(result.stopReason()).as(result.answer()).isEqualTo("final_answer");
        assertThat(result.requestPlan().intent()).isEqualTo(PlanIntent.INDICATOR_SQL_PREPARE);
        assertThat(result.answer())
                .contains("概览 SQL", "未指定统计时间", "未访问数据库");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("followup_plan_resolve")
                .doesNotContain("planner_llm");
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .contains("prepare_indicator_sql")
                .doesNotContain("trial_run_indicator_sql");
        assertThat(models.calls).isZero();
    }

    @Test
    void resolvesNumeratorFollowupWithoutPlannerAndUsesFocusedTemplate() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        SqlFixture fixture = sqlFixture(objectMapper);
        ToolRegistry tools = new ToolRegistry(fixture.rules());
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker("""
                # 急会诊及时到位率

                ## 分子口径

                及时到位次数。
                """);
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentConversationMemory memory = AgentConversationMemory.noop();
        HospitalPrincipal principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001");
        var conversation = memory.open(principal, "session_numerator_followup");
        AgentRunState previousState = new AgentRunState();
        previousState.currentRuleId("HXZD-003-001");
        previousState.lastRuleName("急会诊及时到位率");
        previousState.lastIntent(PlanIntent.RULE_EXPLANATION.value());
        memory.appendAssistant(
                conversation,
                principal,
                "急会诊及时到位率的完整口径如下。",
                previousState);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper), capabilities,
                new AgentStateController(capabilities), new DeterministicDispatch(), gateway, verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                memory);
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "分子是什么口径",
                "session_numerator_followup", "ollama-test", null,
                "request_numerator_followup", "trace_numerator_followup",
                "business_test", "{}", "", principal), events::add);

        assertThat(result.stopReason()).as(result.answer()).isEqualTo("final_answer");
        assertThat(result.requestPlan().intent()).isEqualTo(PlanIntent.RULE_EXPLANATION);
        assertThat(result.requestPlan().explanationFocuses())
                .containsExactly(ExplanationFocus.NUMERATOR);
        assertThat(result.answer())
                .contains("## 分子口径", "及时到位次数")
                .doesNotContain("## 分母口径", "## 口径摘要");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("followup_plan_resolve", "rule_explanation_answer")
                .doesNotContain("planner_llm", "final_answer_llm");
        assertThat(models.calls).isZero();
    }

    @Test
    void rejectsRemovedImplementationValidationBeforePlannerOrTools() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        ToolRegistry tools = new ToolRegistry(ruleRepository(objectMapper));
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker();
        AgentModelRegistry modelRegistry = new AgentModelRegistry(properties);
        AgentRunner runner = new AgentRunner(
                new ModelRequestPlanner(models, modelRegistry, properties, new PromptCatalog(), objectMapper),
                new PlanValidator(new TimeRangeResolver()),
                new PlanCompiler(capabilities, objectMapper),
                capabilities,
                new AgentStateController(capabilities),
                new DeterministicDispatch(),
                gateway,
                verifier,
                new FinalAnswerComposer(models, modelRegistry, properties, new PromptCatalog(), objectMapper));
        List<Map<String, Object>> events = new ArrayList<>();

        AgentRunResult result = runner.run(new AgentRunRequest(
                "对2026年1月至3月急会诊及时到位率做全面实施验收", "session_001",
                "ollama-test", null, "request_001", "trace_001", "business_test", "{}", "",
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001", Set.of(), false, "auth_session_001")),
                events::add);

        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.answer()).contains("当前系统不提供", "全面实施验收");
        assertThat(result.requestPlan().intent()).isEqualTo(PlanIntent.UNKNOWN);
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .isEmpty();
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("unsupported_feature_guard")
                .doesNotContain("planner_llm", "final_answer_llm");
        assertThat(models.calls).isZero();
    }

    private static RuleReadRepository ruleRepository(ObjectMapper objectMapper) {
        var database = new EmbeddedDatabaseBuilder()
                .setName("runner_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        return new RuleReadRepository(new JdbcTemplate(database), objectMapper);
    }

    private static SqlFixture sqlFixture(ObjectMapper objectMapper) {
        var database = new EmbeddedDatabaseBuilder()
                .setName("runner_sql_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        RuleReadRepository rules = mock(RuleReadRepository.class);
        Map<String, Object> rule = executableRule();
        Map<String, Object> mapping = confirmedMapping();
        Map<String, Object> search = Map.of(
                "query", "急会诊及时到位率",
                "hospital_id", "hospital_001",
                "resolved_rule_id", "HXZD-003-001",
                "matches", List.of(Map.of(
                        "rule_id", "HXZD-003-001",
                        "rule_name", "急会诊及时到位率",
                        "category", "会诊制度")),
                "rule_source", "wiki");
        when(rules.searchForHospital(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(search);
        when(rules.effectiveRule(anyString(), anyString())).thenReturn(rule);
        when(rules.effectiveRule(anyString(), anyString(), isNull())).thenReturn(rule);
        when(rules.effectiveRule(anyString(), anyString(), anyString())).thenReturn(rule);
        when(rules.fieldMapping(anyString(), anyString())).thenReturn(mapping);
        when(rules.fieldMapping(anyString(), anyString(), isNull())).thenReturn(mapping);
        when(rules.fieldMapping(anyString(), anyString(), anyString())).thenReturn(mapping);
        return new SqlFixture(rules, jdbc);
    }

    private record SqlFixture(RuleReadRepository rules, JdbcTemplate jdbc) {}

    /**
     * SQL 运行链测试使用显式的可执行 Profile 夹具。
     *
     * <p>真实 Wiki 中未完成字段和结果映射验证的 Profile 必须保持
     * documentation_only；测试若要覆盖 SQL 运行链，应自行构造已验证契约，
     * 不能把生产知识库中的未验证方案临时提升为 executable。</p>
     */
    private static Map<String, Object> executableRule() {
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value,
                       1 AS numerator_count,4 AS denominator_count,4 AS sample_count
                FROM consult_record
                WHERE hospital_id=:hospital_id AND consult_type=:consult_type_value
                  AND request_time>=:start_time AND request_time<:end_time
                """;
        return Map.ofEntries(
                Map.entry("rule_id", "HXZD-003-001"),
                Map.entry("rule_name", "急会诊及时到位率"),
                Map.entry("category", "会诊制度"),
                Map.entry("profile_id", "HXZD-003-001-company-default"),
                Map.entry("execution_status", "executable"),
                Map.entry("definition", "急会诊及时到位次数占总次数的比例。"),
                Map.entry("formula", "及时到位次数 ÷ 急会诊总次数 × 100%"),
                Map.entry("numerator_rule", "及时到位次数"),
                Map.entry("denominator_rule", "急会诊总次数"),
                Map.entry("standard_sql", sql),
                Map.entry("field_contract", Map.of("business_fields", Map.of(
                        "hospital_id", Map.of("type", "string"),
                        "request_time", Map.of("type", "datetime"),
                        "arrive_time", Map.of("type", "datetime"),
                        "consult_type", Map.of("type", "string")))),
                Map.entry("effective_params", Map.of("consult_type_value", "急会诊")),
                Map.entry("hospital_version", 1),
                Map.entry("national_version", "2025"));
    }

    private static Map<String, Object> confirmedMapping() {
        List<Map<String, Object>> items = List.of(
                mappingItem("hospital_id", "varchar"),
                mappingItem("request_time", "datetime"),
                mappingItem("arrive_time", "datetime"),
                mappingItem("consult_type", "varchar"));
        return Map.ofEntries(
                Map.entry("rule_id", "HXZD-003-001"),
                Map.entry("profile_id", "HXZD-003-001-company-default"),
                Map.entry("dialect", "sqlserver"),
                Map.entry("db_name", "business_test"),
                Map.entry("schema", "dbo"),
                Map.entry("main_table", "consult_record"),
                Map.entry("status", "confirmed"),
                Map.entry("fields", Map.of(
                        "hospital_id", "consult_record.hospital_id",
                        "request_time", "consult_record.request_time",
                        "arrive_time", "consult_record.arrive_time",
                        "consult_type", "consult_record.consult_type")),
                Map.entry("items", items),
                Map.entry("metadata_items", items),
                Map.entry("relations", List.of()));
    }

    private static Map<String, Object> mappingItem(String field, String dataType) {
        return Map.of(
                "business_field", field,
                "table_name", "consult_record",
                "column_name", field,
                "data_type", dataType,
                "mapping_data_type", dataType,
                "metadata_data_type", dataType,
                "status", "confirmed");
    }

    private static AgentModelProperties modelProperties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setDefaultModel("ollama-test");
        ModelDefinition model = new ModelDefinition();
        model.setId("ollama-test");
        model.setName("Ollama Test");
        model.setProvider("ollama");
        model.setModel("qwen3:4b");
        model.setBaseUrl("http://127.0.0.1:11434");
        properties.setModels(List.of(model));
        return properties;
    }

    private static class QueueInvoker implements AgentModelInvoker {
        private final Queue<String> values = new ArrayDeque<>();
        private int calls;

        QueueInvoker(String... values) {
            this.values.addAll(List.of(values));
        }

        @Override
        public ModelCompletion complete(
                String modelId, String systemPrompt, String userPrompt, java.time.Duration timeout) {
            calls++;
            return new ModelCompletion(modelId, values.remove());
        }
    }

    private static class MemoryEvidenceStore implements EvidenceStore {
        private final Map<String, EvidenceEnvelope> evidence = new LinkedHashMap<>();
        private final Map<String, EvidenceVerification> verifications = new LinkedHashMap<>();

        @Override
        public void saveEvidence(EvidenceEnvelope value) { evidence.put(value.evidenceId(), value); }
        @Override
        public void saveVerification(EvidenceVerification value) {
            verifications.put(value.evidenceId(), value);
        }
        @Override
        public Optional<EvidenceEnvelope> loadEvidence(String evidenceId) {
            return Optional.ofNullable(evidence.get(evidenceId));
        }
        @Override
        public Optional<EvidenceVerification> loadVerified(String evidenceId) {
            return Optional.ofNullable(verifications.get(evidenceId))
                    .filter(value -> "verified".equals(value.status()));
        }
    }
}
