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
