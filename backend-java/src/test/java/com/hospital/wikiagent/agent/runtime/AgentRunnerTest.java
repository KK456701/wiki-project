package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.FinalAnswerComposer;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.agent.planning.DeterministicDispatch;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidator;
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
import com.hospital.wikiagent.agent.validation.ImplementationValidationTools;
import com.hospital.wikiagent.agent.validation.ImplementationValidationWorkflow;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.mock;

class AgentRunnerTest {
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
                  "schema_version": "request-plan-v1",
                  "intent": "rule_explanation",
                  "goal": "解释急会诊及时到位率",
                  "target_indicator": {"raw_name": "transfer_within_48h_ratio"},
                  "time_expression": {
                    "raw_text": "2026年1月至3月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-04-01T00:00:00"
                  },
                  "requested_outputs": ["definition", "formula"],
                  "constraints": [],
                  "semantic_ambiguities": []
                }
                """,
                "急会诊及时到位率 = 分子 ÷ 分母 × 100%。");
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
                        "急会诊及时到位率", "急会诊及时到位率", "MQSI2025_005",
                        "RULE:MQSI2025_005", "rule", 1.0, 0, 9));

        assertThat(result.stopReason()).as(result.answer() + " " + events).isEqualTo("final_answer");
        assertThat(result.answer()).contains("分子", "分母");
        assertThat(result.requestPlan().targetIndicator().rawName()).isEqualTo("急会诊及时到位率");
        assertThat(result.requestPlan().targetIndicator().ruleId()).isEqualTo("MQSI2025_005");
        assertThat(result.stepCount()).isEqualTo(1);
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly("get_effective_rule");
        assertThat(store.evidence).hasSize(3);
        assertThat(store.verifications.values())
                .allMatch(value -> "verified".equals(value.status()));
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
                        assertThat(sql).contains("'hospital_001'", "'2026-01-01 00:00:00'", "'2026-04-01 00:00:00'");
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
                  "schema_version": "request-plan-v1",
                  "intent": "indicator_trial_run",
                  "goal": "计算急会诊及时到位率",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {
                    "raw_text": "2026年1月至3月",
                    "start_time": "2026-01-01T00:00:00",
                    "end_time": "2026-04-01T00:00:00"
                  },
                  "requested_outputs": ["trial_result"],
                  "constraints": [],
                  "semantic_ambiguities": []
                }
                """,
                "急会诊及时到位率为 25.0%（1 ÷ 4 × 100%）。");
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
                "计算2026年1月至3月急会诊及时到位率", "session_001", "ollama-test", null,
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
    void normalizesExplicitValidationRequestAndUsesFixedWorkflowWithoutFinalAnswerLlm() {
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
                }, objectMapper);
        UploadedIndicatorTools uploads = mock(UploadedIndicatorTools.class);
        ImplementationValidationTools validationTools = new ImplementationValidationTools(
                new ImplementationValidationWorkflow(sqlTools, uploads));
        ToolRegistry tools = new ToolRegistry(
                fixture.rules(), sqlTools, null, uploads, validationTools);
        CapabilitySpecRegistry capabilities = new CapabilitySpecRegistry(tools);
        MemoryEvidenceStore store = new MemoryEvidenceStore();
        AgentModelProperties properties = modelProperties();
        EvidenceLedger ledger = new EvidenceLedger(store, objectMapper, properties);
        EvidenceVerifier verifier = new EvidenceVerifier(store, ledger);
        gateway = new ToolGateway(tools, new PolicyDecisionService(), objectMapper, ledger);
        QueueInvoker models = new QueueInvoker(
                """
                {
                  "schema_version": "request-plan-v1",
                  "intent": "rule_explanation",
                  "goal": "解释急会诊及时到位率",
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {},
                  "requested_outputs": ["definition", "formula"],
                  "constraints": [],
                  "semantic_ambiguities": []
                }
                """);
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
        assertThat(result.answer()).contains("指标全面实施验收报告", "L1", "L4", "L5", "L6", "总体结论");
        assertThat(result.requestPlan().intent().name()).isEqualTo("IMPLEMENTATION_VALIDATION");
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly(
                        "search_indicator_rules", "get_effective_rule",
                        "inspect_indicator_implementation", "validate_indicator_implementation");
        assertThat(events).filteredOn(event -> "trace_node".equals(event.get("event")))
                .extracting(event -> event.get("node_name"))
                .contains("implementation_validation_l1", "implementation_validation_l4",
                        "implementation_validation_l5", "implementation_validation_l6",
                        "implementation_validation_answer")
                .doesNotContain("final_answer_llm");
        assertThat(models.calls).isEqualTo(1);
    }

    private static RuleReadRepository ruleRepository(ObjectMapper objectMapper) {
        var database = new EmbeddedDatabaseBuilder()
                .setName("runner_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO med_index_standard "
                        + "(index_code,index_name,index_type,index_desc,stat_cycle,numerator_rule,denominator_rule,"
                        + "filter_rule,exclude_rule,rely_table_field,calculation_definition,standard_sql,rule_params,"
                        + "source_path,version,status,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "MQSI2025_005", "急会诊及时到位率", "会诊制度", "急会诊及时到位次数占总次数的比例。", "month",
                "20分钟内到位次数", "急会诊总次数", "", "", "{}", "{}", "SELECT 1", "{}",
                "rules/source.yml", "2025", 1, now, now);
        return new RuleReadRepository(jdbc, objectMapper);
    }

    private static SqlFixture sqlFixture(ObjectMapper objectMapper) {
        var database = new EmbeddedDatabaseBuilder()
                .setName("runner_sql_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        LocalDateTime now = LocalDateTime.now();
        String fieldContract = """
                {"business_fields":{"hospital_id":{"required":true},"request_time":{"required":true},
                "arrive_time":{"required":true},"consult_type":{"required":true}}}
                """;
        String sql = """
                SELECT CASE WHEN COUNT(*)=0 THEN 0 ELSE 25.0 END AS index_value,
                       1 AS numerator_count,4 AS denominator_count,4 AS sample_count
                FROM consult_record
                WHERE hospital_id=:hospital_id AND consult_type=:consult_type_value
                  AND request_time>=:start_time AND request_time<:end_time
                """;
        jdbc.update(
                "INSERT INTO med_index_standard "
                        + "(index_code,index_name,index_type,index_desc,stat_cycle,numerator_rule,denominator_rule,"
                        + "filter_rule,exclude_rule,rely_table_field,calculation_definition,standard_sql,rule_params,"
                        + "source_path,version,status,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "MQSI2025_005", "急会诊及时到位率", "会诊制度", "及时到位次数占总次数比例。", "month",
                "及时到位次数", "急会诊总次数", "", "", fieldContract, "{}", sql,
                "{\"consult_type_value\":\"急会诊\"}", "rules/source.yml", "2025", 1, now, now);
        for (String field : List.of("hospital_id", "request_time", "arrive_time", "consult_type")) {
            jdbc.update(
                    "INSERT INTO med_field_mapping "
                            + "(hospital_id,rule_id,business_field,db_name,table_name,column_name,data_type,status) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    "hospital_001", "MQSI2025_005", field, "business_test", "consult_record", field,
                    "varchar", "confirmed");
            jdbc.update(
                    "INSERT INTO med_metadata_column "
                            + "(hospital_id,db_name,table_name,column_name,data_type,sync_batch_id,sync_time) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    "hospital_001", "business_test", "consult_record", field,
                    field.endsWith("time") ? "datetime" : "varchar", "batch-1", now);
        }
        return new SqlFixture(new RuleReadRepository(jdbc, objectMapper), jdbc);
    }

    private record SqlFixture(RuleReadRepository rules, JdbcTemplate jdbc) {}

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
