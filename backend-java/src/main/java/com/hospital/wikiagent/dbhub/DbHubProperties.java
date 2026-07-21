package com.hospital.wikiagent.dbhub;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.dbhub")
public class DbHubProperties {

    private String apiUrl = "http://127.0.0.1:8080";
    private String mcpUrl = "http://127.0.0.1:8080/mcp";
    private String sourceId = "win60_qa_991827";
    private String executeTool = "execute_sql_win60_qa_991827";
    private int timeoutSeconds = 10;

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getMcpUrl() {
        return mcpUrl;
    }

    public void setMcpUrl(String mcpUrl) {
        this.mcpUrl = mcpUrl;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getExecuteTool() {
        return executeTool;
    }

    public void setExecuteTool(String executeTool) {
        this.executeTool = executeTool;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
