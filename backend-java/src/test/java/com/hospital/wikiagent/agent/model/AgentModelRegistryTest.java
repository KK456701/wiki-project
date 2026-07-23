package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;

class AgentModelRegistryTest {
    @Test
    void buildsOllamaLazilyAndHidesApiKeyFromModelInfo() {
        AgentModelProperties properties = properties();
        AgentModelRegistry registry = new AgentModelRegistry(properties);

        assertThat(registry.defaultModelId()).isEqualTo("ollama-test");
        assertThat(registry.listModels()).extracting(AgentModelInfo::id)
                .containsExactly("ollama-test", "dashscope-test", "deepseek-test");
        assertThat(registry.requireModel("ollama-test")).isInstanceOf(OllamaChatModel.class);
        assertThat(registry.requireModel("dashscope-test")).isInstanceOf(OpenAiChatModel.class);
        assertThat(registry.requireInfo("dashscope-test").available()).isTrue();
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
        ModelDefinition dashscope = definition(
                "dashscope-test", "DashScope Test", "openai-compatible", "qwen3-14b",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key");
        dashscope.setEnableThinking(false);
        ModelDefinition deepseek = definition(
                "deepseek-test", "DeepSeek Test", "openai-compatible", "deepseek-v4-flash",
                "https://api.deepseek.com", "");
        properties.setModels(List.of(ollama, dashscope, deepseek));
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
