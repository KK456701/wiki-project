package com.hospital.wikiagent.agent.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private List<ModelDefinition> models = new ArrayList<>();

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
        private String apiKey;
        private boolean thinking;

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
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public boolean isThinking() { return thinking; }
        public void setThinking(boolean value) { thinking = value; }
    }
}
