package com.hospital.wikiagent.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
    void semanticLayerReturnsCandidateWhenModelCannotConfirm() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        var result = fixture.resolver().resolve(
                "帮我看看患者入院48小时转科比例怎么算",
                "hospital_001", "ollama-test", "trace-2", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.ambiguities().get(0).candidates()).extracting("ruleId")
                .contains("MQSI2025_001");
    }

    @Test
    void systemAliasResolvesItsSingleIndicatorWithoutLlm() {
        Fixture fixture = fixture(List.of(rule(
                "HXZD-001-001", "患者入院48小时内转科的比例", "首诊负责制度")), null);

        var result = fixture.resolver().resolve(
                "首诊责任制指标怎么计算",
                "hospital_001", "ollama-test", "trace-system-single", "root",
                AgentRunObserver.noop());

        assertThat(result.indicators()).singleElement().satisfies(value -> {
            assertThat(value.ruleId()).isEqualTo("HXZD-001-001");
            assertThat(value.canonicalName()).isEqualTo("患者入院48小时内转科的比例");
            assertThat(value.source()).isEqualTo("system");
        });
        assertThat(result.needsClarification()).isFalse();
        verifyNoInteractions(fixture.models());
    }

    @Test
    void oneSystemNameResolvesAllIndicatorsInThatSystem() {
        Fixture fixture = fixture(List.of(
                rule("HXZD-002-001", "三级查房制度知晓率", "三级查房制度"),
                rule("HXZD-002-002", "患者入院8小时内查房完成率", "三级查房制度"),
                rule("HXZD-002-003", "三级查房记录完整率", "三级查房制度")), null);

        var result = fixture.resolver().resolve(
                "三级查房制度有哪些指标，分别怎么计算",
                "hospital_001", "ollama-test", "trace-system-multiple", "root",
                AgentRunObserver.noop());

        assertThat(result.indicators()).extracting("ruleId").containsExactly(
                "HXZD-002-001", "HXZD-002-002", "HXZD-002-003");
        assertThat(result.indicators()).allMatch(value -> "system".equals(value.source()));
        assertThat(result.needsClarification()).isFalse();
        verifyNoInteractions(fixture.models());
    }

    @Test
    void oneSystemNameWithSeveralIndicatorsRequiresSelectionWithoutPluralRequest() {
        Fixture fixture = fixture(List.of(
                rule("HXZD-002-001", "三级查房制度知晓率", "三级查房制度"),
                rule("HXZD-002-002", "患者入院8小时内查房完成率", "三级查房制度")), null);

        var result = fixture.resolver().resolve(
                "三级查房制度怎么计算", "hospital_001", "ollama-test",
                "trace-system-choice", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.ambiguities().get(0).candidates()).hasSize(2);
        assertThat(result.usedLlm()).isFalse();
        verifyNoInteractions(fixture.models());
    }

    @Test
    void semanticLayerDoesNotDirectlyBindTransferIndicatorWithoutRatioSuffix() {
        Fixture fixture = fixture(List.of(
                rule("HXZD-001-001", "患者入院48小时内转科的比例")), null);

        var result = fixture.resolver().resolve(
                "患者入院 48小时内转科",
                "hospital_001", "ollama-test", "trace-short-name", "root",
                AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    void semanticLayerSplitsTwoImpreciseIndicatorMentions() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        var result = fixture.resolver().resolve(
                "急会诊到位率和48小时转科比例怎么算",
                "hospital_001", "ollama-test", "trace-3", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.ambiguities()).hasSize(2);
    }

    @Test
    void leadingActionAndTimeWordsAreStrippedBeforeSemanticRecall() {
        Fixture fixture = fixture(List.of(
                rule("MQSI2025_001", TRANSFER), rule("MQSI2025_005", CONSULT)), null);

        // 句首“算”与时间词“去年”不属于指标名，剥离后模糊名称应能语义命中或给出候选，
        // 绝不能落入“请补充指标”的自由文本澄清
        var result = fixture.resolver().resolve(
                "算去年患者入院内转科的比例",
                "hospital_001", "ollama-test", "trace-time", "root", AgentRunObserver.noop());

        boolean resolvedDirectly = result.indicators().stream()
                .anyMatch(value -> "MQSI2025_001".equals(value.ruleId()));
        boolean offeredAsCandidate = result.ambiguities().stream()
                .flatMap(value -> value.candidates().stream())
                .anyMatch(value -> "MQSI2025_001".equals(value.ruleId()));
        assertThat(resolvedDirectly || offeredAsCandidate).isTrue();
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
                        "matchedText", "患者入院48小时内转科的比例",
                        "canonicalName", "患者入院48小时内转科的比例",
                        "conceptCode", "IND_MQSI2025_001",
                        "linkedRuleIds", List.of("MQSI2025_001"))),
                "releaseVersion", "stale-term-v1"));

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
    void lowConfidenceCandidatesFallBackToUserChoiceWhenLlmFails() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        // LLM 消歧超时/失败时安全降级为“把候选交给用户确认”
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("timeout"));
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-4", "root", AgentRunObserver.noop());

        assertThat(result.usedLlm()).isTrue();
        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.ambiguities().get(0).candidates()).hasSize(2);
    }

    @Test
    void llmDisambiguationResolvesAmbiguousCandidates() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new AgentModelInvoker.ModelCompletion("ollama-test",
                        "{\"selections\":[{\"group_id\":\"candidate_1\",\"rule_id\":\"RULE_1\",\"confidence\":0.9}]}"));
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-4b", "root", AgentRunObserver.noop());

        assertThat(result.usedLlm()).isTrue();
        assertThat(result.indicators()).hasSize(1);
        assertThat(result.indicators().get(0).ruleId()).isEqualTo("RULE_1");
        assertThat(result.needsClarification()).isFalse();
    }

    @Test
    void llmDisambiguationBelowThresholdRequiresUserSelection() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new AgentModelInvoker.ModelCompletion("ollama-test",
                        "{\"selections\":[{\"group_id\":\"candidate_1\",\"rule_id\":\"RULE_1\",\"confidence\":0.89}]}"));
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-low-confidence", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
    }

    @Test
    void inventedRuleIdIsRejectedAndLeavesClarification() {
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        // LLM 返回不存在的 rule_id，应被拒绝，仍留澄清
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new AgentModelInvoker.ModelCompletion("ollama-test",
                        "{\"selections\":[{\"group_id\":\"candidate_1\",\"rule_id\":\"FAKE_999\",\"confidence\":0.99}]}"));
        Fixture fixture = fixture(List.of(
                rule("RULE_1", "急会诊及时到位率"),
                rule("RULE_2", "急会诊及时到达率")), models);

        var result = fixture.resolver().resolve(
                "急会诊及时率怎么算", "hospital_001", "ollama-test",
                "trace-5", "root", AgentRunObserver.noop());

        assertThat(result.indicators()).isEmpty();
        assertThat(result.needsClarification()).isTrue();
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
                "matches", List.of(), "releaseVersion", "test-v1"));
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
        return Map.of("ruleId", id, "ruleName", name);
    }

    private static Map<String, String> rule(String id, String name, String system) {
        return Map.of("ruleId", id, "ruleName", name, "system", system);
    }

    private record Fixture(
            HybridIndicatorResolver resolver,
            AgentModelInvoker models,
            TerminologyRepository repository,
            TerminologyService terminology) { }
}
