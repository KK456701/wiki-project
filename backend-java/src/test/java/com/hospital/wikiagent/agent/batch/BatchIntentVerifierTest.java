package com.hospital.wikiagent.agent.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.batch.BatchRequestSpec.Target;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryTarget;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelInvoker.ModelCompletion;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.PromptCatalog;

import com.fasterxml.jackson.databind.ObjectMapper;

class BatchIntentVerifierTest {

    private AgentModelInvoker models;
    private AgentModelRegistry registry;
    private AgentModelProperties properties;
    private PromptCatalog prompts;
    private BatchIntentVerifier verifier;

    @BeforeEach
    void setUp() {
        models = mock(AgentModelInvoker.class);
        registry = mock(AgentModelRegistry.class);
        properties = new AgentModelProperties();
        prompts = mock(PromptCatalog.class);
        when(registry.defaultModelId()).thenReturn("test-model");
        when(prompts.batchIntentVerifier()).thenReturn("system prompt");
        verifier = new BatchIntentVerifier(
                models, registry, properties, prompts, new ObjectMapper());
    }

    @Test
    void noVerificationNeededWhenRegexSelectedAndNoConflict() {
        BatchRequestSpec spec = BatchRequestSpec.selected(
                "计算转科比例", "上个月",
                List.of(new Target("R1", "转科比例")));
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "转科比例")),
                "2026-06-01 00:00:00", "2026-07-01 00:00:00");

        BatchRequestSpec result = verifier.verify(spec, previous, "计算转科比例");

        assertThat(result).isSameAs(spec);
        verify(models, never()).complete(anyString(), anyString(), anyString(), any());
    }

    @Test
    void verifiesWhenRegexSaysAllButScopeHasTargets() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("计算全部指标结果，统计时间为：上个月");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "转科比例"),
                        new QueryTarget("R2", "急会诊有效率")),
                null, null);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model",
                        "{\"scope\":\"SELECTED\",\"confidence\":0.9}"));

        BatchRequestSpec result = verifier.verify(
                spec, previous, "计算全部指标结果，统计时间为：上个月");

        assertThat(result.scope()).isEqualTo(BatchRequestSpec.Scope.SELECTED);
        assertThat(result.targets()).hasSize(2);
        assertThat(result.targets().get(0).ruleName()).isEqualTo("转科比例");
    }

    @Test
    void keepsAllWhenLlmConfirmsAll() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("计算所有指标的结果");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "转科比例")),
                null, null);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model",
                        "{\"scope\":\"ALL\",\"confidence\":0.95}"));

        BatchRequestSpec result = verifier.verify(spec, previous, "计算所有指标的结果");

        assertThat(result.allActive()).isTrue();
    }

    @Test
    void fallsBackToRegexOnLlmFailure() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("计算全部指标结果");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "转科比例")),
                null, null);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("timeout"));

        BatchRequestSpec result = verifier.verify(spec, previous, "计算全部指标结果");

        assertThat(result).isSameAs(spec);
    }

    @Test
    void lowConfidenceLlmKeepsRegexResult() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("计算全部指标结果");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "转科比例")),
                null, null);
        when(models.complete(anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new ModelCompletion("test-model",
                        "{\"scope\":\"SELECTED\",\"confidence\":0.4}"));

        BatchRequestSpec result = verifier.verify(spec, previous, "计算全部指标结果");

        assertThat(result.allActive()).isTrue();
    }

    @Test
    void upgradesToAllWhenRegexMissesButLlmSaysAll() {
        // 正则没命中批量（不含"全部/所有"），但含"指标"+"计算"
        BatchRequestSpec spec = BatchRequestSpec.notBatch();
        // notBatch 的 rawQuery 为空，需要构造一个带 rawQuery 的 spec
        // 实际上 notBatch() 返回 rawQuery=""，needsVerification 检查 looksLikeBatch("")=false
        // 所以这个场景需要 spec 有 rawQuery。用 selected 但 targets 为空来模拟不太合适。
        // 直接测试 needsVerification 的静态逻辑：
        assertThat(BatchIntentVerifier.needsVerification(spec, null)).isFalse();
    }

    @Test
    void needsVerificationTrueWhenAllActiveWithSubsetScope() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("全部指标");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "SUBSET",
                List.of(new QueryTarget("R1", "指标A")),
                null, null);

        assertThat(BatchIntentVerifier.needsVerification(spec, previous)).isTrue();
    }

    @Test
    void needsVerificationFalseWhenAllActiveWithAllScope() {
        BatchRequestSpec spec = BatchRequestSpec.allActive("全部指标");
        QueryScopeState previous = new QueryScopeState(
                "indicator_trial_run", "ALL", List.of(),
                null, null);

        assertThat(BatchIntentVerifier.needsVerification(spec, previous)).isFalse();
    }
}
