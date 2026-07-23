package com.hospital.wikiagent.agent.model;

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
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.model.AgentModelProperties.ModelDefinition;

/**
 * 集中注册和查询 {@code AgentModelRegistry} 管理的类型化能力。
 *
 * <p>注册内容在启动阶段完成校验并在运行期只读使用，重复 ID、未知实现或不完整配置会快速失败。调用方不得根据模型文本动态注册新的生产能力。</p>
 */
@Component
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

    /** 返回可展示给前端的模型元数据，不包含 API Key。 */
    public List<AgentModelInfo> listModels() {
        return definitions.values().stream().map(this::toInfo).toList();
    }

    /** 查找模型元数据；未知模型 ID 会返回稳定领域异常。 */
    public AgentModelInfo requireInfo(String modelId) {
        return toInfo(requireDefinition(normalizeId(modelId)));
    }

    /**
     * 延迟创建并缓存 ChatModel。外部 API 模型缺少密钥时不会构造客户端。
     */
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
        return OllamaChatModel.builder().ollamaApi(api).defaultOptions(options).build();
    }

    private ChatModel buildOpenAiCompatible(ModelDefinition definition) {
        // Spring AI 1.1.x 把地址和密钥放在 OpenAiApi，ChatOptions 只保存模型推理参数。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(stripTrailingSlash(definition.getBaseUrl()))
                .apiKey(definition.getApiKey())
                .build();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(definition.getModel())
                .temperature(0.0);
        if (definition.getEnableThinking() != null) {
            // 百炼的 Qwen3 默认会进入思考模式。Planner 需要稳定、短延迟的结构化输出，
            // 因此该模型默认显式关闭思考；此参数仅对声明它的模型发送。
            optionsBuilder.extraBody(Map.of("enable_thinking", definition.getEnableThinking()));
        }
        OpenAiChatOptions options = optionsBuilder.build();
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();
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
