package com.hospital.wikiagent.agent.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;

@Component
/**
 * 集中注册和查询 {@code AgentModelRegistry} 管理的类型化能力。
 */
public class AgentModelRegistry {
    public static final String VERSION = "model-registry-v1";

    private final AgentModelProperties properties;
    private final Map<String, ModelDefinition> definitions;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    public AgentModelRegistry(AgentModelProperties properties) {
        this.properties = properties;
        Map<String, ModelDefinition> values = new LinkedHashMap<>();
        for (ModelDefinition definition : properties.getModels()) {
            validate(definition);
            if (values.putIfAbsent(definition.getId(), definition) != null) {
                throw new IllegalArgumentException("模型 ID 重复: " + definition.getId());
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("至少需要配置一个 Agent 模型");
        }
        if (!values.containsKey(properties.getDefaultModel())) {
            throw new IllegalArgumentException("默认模型未注册: " + properties.getDefaultModel());
        }
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public String defaultModelId() { return properties.getDefaultModel(); }

    public List<AgentModelInfo> listModels() {
        return definitions.values().stream().map(this::toInfo).toList();
    }

    public AgentModelInfo requireInfo(String modelId) {
        return toInfo(requireDefinition(normalizeId(modelId)));
    }

    public ChatModel requireModel(String modelId) {
        String id = normalizeId(modelId);
        ModelDefinition definition = requireDefinition(id);
        if (!available(definition)) {
            throw new AgentModelUnavailableException(
                    "MODEL_CREDENTIAL_MISSING", "模型 " + id + " 缺少 API 密钥。");
        }
        return models.computeIfAbsent(id, ignored -> build(definition));
    }

    private ChatModel build(ModelDefinition definition) {
        return switch (definition.getProvider()) {
            case "ollama" -> buildOllama(definition);
            case "openai-compatible" -> buildOpenAiCompatible(definition);
            default -> throw new AgentModelUnavailableException(
                    "MODEL_PROVIDER_UNSUPPORTED", "不支持的模型提供方: " + definition.getProvider());
        };
    }

    private ChatModel buildOllama(ModelDefinition definition) {
        OllamaApi api = OllamaApi.builder().baseUrl(stripTrailingSlash(definition.getBaseUrl())).build();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(definition.getModel())
                .temperature(0.0)
                .build();
        return OllamaChatModel.builder().ollamaApi(api).options(options).build();
    }

    private ChatModel buildOpenAiCompatible(ModelDefinition definition) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(stripTrailingSlash(definition.getBaseUrl()))
                .apiKey(definition.getApiKey())
                .model(definition.getModel())
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }

    private AgentModelInfo toInfo(ModelDefinition definition) {
        return new AgentModelInfo(
                definition.getId(), definition.getName(), definition.getProvider(),
                definition.getModel(), definition.isThinking(), available(definition));
    }

    private boolean available(ModelDefinition definition) {
        return !"openai-compatible".equals(definition.getProvider())
                || (definition.getApiKey() != null && !definition.getApiKey().isBlank());
    }

    private ModelDefinition requireDefinition(String id) {
        ModelDefinition value = definitions.get(id);
        if (value == null) {
            throw new AgentModelUnavailableException("MODEL_NOT_FOUND", "模型不存在: " + id);
        }
        return value;
    }

    private String normalizeId(String modelId) {
        return modelId == null || modelId.isBlank() ? defaultModelId() : modelId.strip();
    }

    private static void validate(ModelDefinition definition) {
        if (definition.getId() == null || definition.getId().isBlank()
                || definition.getName() == null || definition.getName().isBlank()
                || definition.getModel() == null || definition.getModel().isBlank()
                || definition.getBaseUrl() == null || definition.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("模型配置缺少 id/name/model/base-url");
        }
        if (!List.of("ollama", "openai-compatible").contains(definition.getProvider())) {
            throw new IllegalArgumentException("不支持的模型提供方: " + definition.getProvider());
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
