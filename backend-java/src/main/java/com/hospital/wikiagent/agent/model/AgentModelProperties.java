package com.hospital.wikiagent.agent.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 {@code AgentModelProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；缺失的安全关键值必须显式失败或保持安全默认值。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties("wiki.agent")
public class AgentModelProperties {
    private String defaultModel = "ollama-qwen3";
    private Duration plannerTimeout = Duration.ofSeconds(90);
    private Duration finalAnswerTimeout = Duration.ofSeconds(120);
    private int evidenceTtlDays = 30;
    private int compoundApiConcurrency = 2;
    private int compoundOllamaConcurrency = 1;
    private int compoundDbConcurrency = 2;
    private Duration compoundTimeout = Duration.ofSeconds(300);
    private int traceRetentionDays = 30;
    private long traceSlowRequestMs = 120_000;
    private long traceSlowLlmMs = 60_000;
    private double traceToolFailureWarningRate = 0.05;
    private double traceTimeoutWarningRate = 0.05;
    private String evidenceJsonlPath = "runtime/agent_evidence_java.jsonl";
    private double confidenceThreshold = 0.9;
    private int batchMaxIndicators = 35;
    private int batchWorkerConcurrency = 4;
    /** 指标消歧 LLM 调用的独立超时，短于 Planner 以避免离线模型阻塞主链路。 */
    private Duration disambiguationTimeout = Duration.ofSeconds(20);
    /** 批量意图 LLM 校验的独立超时；超时或失败时回退正则结果。 */
    private Duration batchVerifyTimeout = Duration.ofSeconds(15);
    /**
     * 首次安装时在“系统设置”中显示的模型模板。模板只提供名称、协议和推荐地址，
     * 不包含任何 API Key；实施人员保存后，实际配置写入本机运行时数据库。
     */
    private List<ModelDefinition> models = defaultModels();

    private static List<ModelDefinition> defaultModels() {
        return List.of(
                model("ollama-qwen3", "Qwen3 4B（本地 Ollama）", "ollama", "qwen3:4B-instruct",
                        "http://127.0.0.1:11434", "", false, null),
                model("ollama-qwen3-8b-thinking", "Qwen3 8B 思考模式（本地 Ollama）", "ollama", "qwen3:8b",
                        "http://127.0.0.1:11434", "", true, null),
                model("aliyun-qwen-distill-7b", "DeepSeek R1 Distill Qwen 7B（阿里云百炼 API）",
                        "openai-compatible", "deepseek-r1-distill-qwen-7b",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions", false, false),
                model("aliyun-qwen3-14b", "Qwen3 14B（阿里云百炼 API）", "openai-compatible", "qwen3-14b",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1", "/chat/completions", true, false),
                model("deepseek-v4-flash", "DeepSeek V4 Flash（API）", "openai-compatible", "deepseek-v4-flash",
                        "https://api.deepseek.com", "", false, null),
                model("deepseek-v4-pro", "DeepSeek V4 Pro（API）", "openai-compatible", "deepseek-v4-pro",
                        "https://api.deepseek.com", "", true, null));
    }

    private static ModelDefinition model(
            String id, String name, String provider, String model, String baseUrl,
            String completionsPath, boolean thinking, Boolean enableThinking) {
        ModelDefinition value = new ModelDefinition();
        value.setId(id);
        value.setName(name);
        value.setProvider(provider);
        value.setModel(model);
        value.setBaseUrl(baseUrl);
        value.setCompletionsPath(completionsPath);
        value.setThinking(thinking);
        value.setEnableThinking(enableThinking);
        value.setContextWindowTokens("ollama".equals(provider) ? 16_384 : null);
        return value;
    }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String value) { defaultModel = value; }
    public Duration getPlannerTimeout() { return plannerTimeout; }
    public void setPlannerTimeout(Duration value) { plannerTimeout = value; }
    public Duration getFinalAnswerTimeout() { return finalAnswerTimeout; }
    public void setFinalAnswerTimeout(Duration value) { finalAnswerTimeout = value; }
    public int getEvidenceTtlDays() { return evidenceTtlDays; }
    public void setEvidenceTtlDays(int value) { evidenceTtlDays = value; }
    public int getCompoundApiConcurrency() { return compoundApiConcurrency; }
    public void setCompoundApiConcurrency(int value) { compoundApiConcurrency = value; }
    public int getCompoundOllamaConcurrency() { return compoundOllamaConcurrency; }
    public void setCompoundOllamaConcurrency(int value) { compoundOllamaConcurrency = value; }
    public int getCompoundDbConcurrency() { return compoundDbConcurrency; }
    public void setCompoundDbConcurrency(int value) { compoundDbConcurrency = value; }
    public Duration getCompoundTimeout() { return compoundTimeout; }
    public void setCompoundTimeout(Duration value) { compoundTimeout = value; }
    public int getTraceRetentionDays() { return traceRetentionDays; }
    public void setTraceRetentionDays(int value) { traceRetentionDays = value; }
    public long getTraceSlowRequestMs() { return traceSlowRequestMs; }
    public void setTraceSlowRequestMs(long value) { traceSlowRequestMs = value; }
    public long getTraceSlowLlmMs() { return traceSlowLlmMs; }
    public void setTraceSlowLlmMs(long value) { traceSlowLlmMs = value; }
    public double getTraceToolFailureWarningRate() { return traceToolFailureWarningRate; }
    public void setTraceToolFailureWarningRate(double value) { traceToolFailureWarningRate = value; }
    public double getTraceTimeoutWarningRate() { return traceTimeoutWarningRate; }
    public void setTraceTimeoutWarningRate(double value) { traceTimeoutWarningRate = value; }
    public String getEvidenceJsonlPath() { return evidenceJsonlPath; }
    public void setEvidenceJsonlPath(String value) { evidenceJsonlPath = value; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double value) { confidenceThreshold = value; }
    public int getBatchMaxIndicators() { return batchMaxIndicators; }
    public void setBatchMaxIndicators(int value) { batchMaxIndicators = value; }
    public int getBatchWorkerConcurrency() { return batchWorkerConcurrency; }
    public void setBatchWorkerConcurrency(int value) { batchWorkerConcurrency = value; }
    public Duration getDisambiguationTimeout() { return disambiguationTimeout; }
    public void setDisambiguationTimeout(Duration value) { disambiguationTimeout = value; }
    public Duration getBatchVerifyTimeout() { return batchVerifyTimeout; }
    public void setBatchVerifyTimeout(Duration value) { batchVerifyTimeout = value; }
    public List<ModelDefinition> getModels() { return models; }
    public void setModels(List<ModelDefinition> value) {
        models = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public static class ModelDefinition {
        private String id;
        private String name;
        private String provider;
        private String model;
        private String baseUrl;
        private String completionsPath;
        private String apiKey;
        private boolean thinking;
        private Boolean enableThinking;
        private Integer contextWindowTokens;

        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getProvider() { return provider; }
        public void setProvider(String value) { provider = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        /**
         * 聊天补全的相对路径。为空时沿用 Spring AI 默认的 {@code /v1/chat/completions}；
         * 百炼官方 Base URL 已包含 {@code /v1}，因此需要显式改为 {@code /chat/completions}。
         */
        public String getCompletionsPath() { return completionsPath; }
        public void setCompletionsPath(String value) { completionsPath = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public boolean isThinking() { return thinking; }
        public void setThinking(boolean value) { thinking = value; }
        /**
         * OpenAI 兼容接口的厂商扩展参数。保持 {@code null} 时不向请求体写入该字段，
         * 避免 DeepSeek 等不识别 {@code enable_thinking} 的服务拒绝请求。
         */
        public Boolean getEnableThinking() { return enableThinking; }
        public void setEnableThinking(Boolean value) { enableThinking = value; }
        public Integer getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(Integer value) { contextWindowTokens = value; }
    }
}
