package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;

import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;

class AgentModelRegistryTest {
    @Test
    void buildsOllamaLazilyAndHidesApiKeyFromModelInfo() {
        AgentModelProperties properties = properties();
        AgentModelRegistry registry = new AgentModelRegistry(properties);

        assertThat(registry.defaultModelId()).isEqualTo("ollama-test");
        assertThat(registry.listModels()).extracting(AgentModelInfo::id)
                .containsExactly("ollama-test", "deepseek-test");
        assertThat(registry.requireModel("ollama-test")).isInstanceOf(OllamaChatModel.class);
        assertThat(registry.requireInfo("deepseek-test").available()).isFalse();
        assertThatThrownBy(() -> registry.requireModel("deepseek-test"))
                .isInstanceOf(AgentModelUnavailableException.class)
                .hasMessageContaining("缺少 API 密钥");
    }

    static AgentModelProperties properties() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setDefaultModel("ollama-test");
        ModelDefinition ollama = definition(
                "ollama-test", "Ollama Test", "ollama", "qwen3:4b", "http://localhost:11434", null);
        ModelDefinition deepseek = definition(
                "deepseek-test", "DeepSeek Test", "openai-compatible", "deepseek-v4-flash",
                "https://api.deepseek.com", "");
        properties.setModels(List.of(ollama, deepseek));
        return properties;
    }

    private static ModelDefinition definition(
            String id, String name, String provider, String model, String baseUrl, String apiKey) {
        ModelDefinition value = new ModelDefinition();
        value.setId(id);
        value.setName(name);
        value.setProvider(provider);
        value.setModel(model);
        value.setBaseUrl(baseUrl);
        value.setApiKey(apiKey);
        return value;
    }
}
