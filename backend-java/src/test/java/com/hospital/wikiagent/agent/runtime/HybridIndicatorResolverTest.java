package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.rules.RuleReadRepository;
import com.hospital.wikiagent.terminology.TerminologyRepository;
import com.hospital.wikiagent.terminology.TerminologyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

class HybridIndicatorResolverTest {
    private static final String TRANSFER = "患者入院 48 小时内转科的比例";
    private static final String CONSULT = "急会诊及时到位率";

    @Test
    void ruleLayerRecognizesTwoFormalIndicatorsWithoutPlannerHelp() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        var result = fixture.resolver().resolve(
                "急会诊及时到位率，患者入院 48 小时内转科的比例怎么算？",
                "hospital_001", "ollama-test", "trace-1", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).extracting("ruleId")
                .containsExactly("MQSI2025_005", "MQSI2025_001");
        assertThat(result.indicators()).allMatch(value -> "rule".equals(value.source()));
        assertThat(result.needsClarification()).isFalse();
        verifyNoInteractions(fixture.models());
    }

    @Test
    void semanticLayerResolvesImpreciseIndicatorNameWithoutLlm() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        var result = fixture.resolver().resolve(
                "帮我看看患者入院48小时转科比例怎么算",
                "hospital_001", "ollama-test", "trace-2", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).singleElement().satisfies(value -> {
            assertThat(value.ruleId()).isEqualTo("MQSI2025_001");
            assertThat(value.source()).isEqualTo("semantic");
        });
        verifyNoInteractions(fixture.models());
    }

    @Test
    void semanticLayerSplitsTwoImpreciseIndicatorMentions() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        var result = fixture.resolver().resolve(
                "急会诊到位率和48小时转科比例怎么算",
                "hospital_001", "ollama-test", "trace-3", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).extracting("ruleId")
                .containsExactly("MQSI2025_005", "MQSI2025_001");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void staleTerminologyRuleLinkCannotOverrideCurrentWikiRuleId() {
        Fixture fixture = fixture(List.of(
                rule("HXZD-001-001", "患者入院48小时内转科的比例")), null);
        when(fixture.repository().concepts()).thenReturn(List.of(Map.of(
                "concept_code", "IND_MQSI2025_001",
                "concept_type", "indicator",
                "canonical_name", "患者入院48小时内转科的比例")));
        when(fixture.repository().ruleLinks()).thenReturn(List.of(Map.of(
                "concept_code", "IND_MQSI2025_001",
                "index_code", "MQSI2025_001")));
        when(fixture.terminology().normalize(anyString(), anyString())).thenReturn(Map.of(
                "matches", List.of(Map.of(
                        "matched_text", "患者入院48小时内转科的比例",
                        "canonical_name", "患者入院48小时内转科的比例",
                        "concept_code", "IND_MQSI2025_001",
                        "linked_rule_ids", List.of("MQSI2025_001"))),
                "release_version", "stale-term-v1"));

        var result = fixture.resolver().resolve(
                "统计患者入院48小时内转科的比例，从2026年1月1日到2026年2月1日",
                "hospital_001", "ollama-test", "trace-stale", "root",
                AgentRunObserver.noop());

        assertThat(result.indicators()).singleElement().satisfies(value -> {
            assertThat(value.ruleId()).isEqualTo("HXZD-001-001");
            assertThat(value.canonicalName()).isEqualTo("患者入院48小时内转科的比例");
        });
    }

    @Test
    void lowConfidenceCandidatesRequireUserChoiceWithoutCallingLlm() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-4", "root", AgentRunObserver.noop());

        assertThat(result.usedLlm()).isFalse();
        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.ambiguities().get(0).candidates()).hasSize(2);
        verifyNoInteractions(models);
    }

    @Test
    void inventedRuleIdIsRejectedAndLeavesClarification() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-5", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        verifyNoInteractions(models);
    }

    private static Fixture fixture(
            List<Map<String, String>> activeRules,
            AgentModelInvoker configuredModels) {
        RuleReadRepository rules = mock(RuleReadRepository.class);
        when(rules.activeIndicatorNames("hospital_001", 500)).thenReturn(activeRules);
        TerminologyRepository repository = mock(TerminologyRepository.class);
        when(repository.concepts()).thenReturn(List.of());
        when(repository.aliases("approved")).thenReturn(List.of());
        when(repository.hospitalAliases("hospital_001")).thenReturn(List.of());
        when(repository.ruleLinks()).thenReturn(List.of());
        TerminologyService terminology = mock(TerminologyService.class);
        when(terminology.normalize(anyString(), anyString())).thenReturn(Map.of(
                "matches", List.of(), "release_version", "test-v1"));
        AgentModelInvoker models = configuredModels == null
                ? mock(AgentModelInvoker.class) : configuredModels;
        AgentModelProperties properties = new AgentModelProperties();
        properties.setDefaultModel("ollama-test");
        ModelDefinition model = new ModelDefinition();
        model.setId("ollama-test");
        model.setName("Ollama Test");
        model.setProvider("ollama");
        model.setModel("qwen3:8b");
        model.setBaseUrl("http://127.0.0.1:11434");
        properties.setModels(List.of(model));
        ObjectMapper mapper = JsonMapper.builder().build();
        return new Fixture(new HybridIndicatorResolver(
                terminology, repository, rules, models, new AgentModelRegistry(properties),
                properties, new PromptCatalog(), mapper), models, repository, terminology);
    }

    private static Map<String, String> rule(String id, String name) {
        return Map.of("rule_id", id, "rule_name", name);
    }

    private record Fixture(
            HybridIndicatorResolver resolver,
            AgentModelInvoker models,
            TerminologyRepository repository,
            TerminologyService terminology) { }
}
