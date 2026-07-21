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
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

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
                  "target_indicator": {"raw_name": "急会诊及时到位率"},
                  "time_expression": {},
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
                events::add);

        assertThat(result.stopReason()).as(result.answer() + " " + events).isEqualTo("final_answer");
        assertThat(result.answer()).contains("分子", "分母");
        assertThat(result.stepCount()).isEqualTo(2);
        assertThat(events).filteredOn(event -> "tool_call".equals(event.get("event")))
                .extracting(event -> event.get("tool_name"))
                .containsExactly("search_indicator_rules", "get_effective_rule");
        assertThat(store.evidence).hasSize(4);
        assertThat(store.verifications.values())
                .allMatch(value -> "verified".equals(value.status()));
        assertThat(models.calls).isEqualTo(2);
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
