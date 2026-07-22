package com.hospital.wikiagent.dbhub;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 {@code DbHubProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；缺失的安全关键值必须显式失败或保持安全默认值。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.dbhub")
public class DbHubProperties {

    private String apiUrl = "http://127.0.0.1:8080";
    private String mcpUrl = "http://127.0.0.1:8080/mcp";
    private String sourceId = "win60_qa_991827";
    private String executeTool = "execute_sql_win60_qa_991827";
    private String databaseName = "WIN60_QA_991827";
    private String schemaName = "WINDBA";
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

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
