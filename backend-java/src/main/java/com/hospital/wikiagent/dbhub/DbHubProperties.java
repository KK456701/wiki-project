package com.hospital.wikiagent.dbhub;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 {@code DbHubProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；缺失的安全关键值必须显式失败或保持安全默认值。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.dbhub")
public class DbHubProperties {

    public static final String BUSINESS_SOURCE_ID = "winex_all_dev";
    public static final String REAL_SOURCE_ID = "winex_aima";

    private String apiUrl = "http://127.0.0.1:8080";
    private String mcpUrl = "http://127.0.0.1:8080/mcp";
    private String bizMcpUrl = "http://127.0.0.1:8080/mcp";
    private int timeoutSeconds = 10;
    private Sources sources = new Sources();

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

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Sources getSources() {
        return sources;
    }

    public void setSources(Sources sources) {
        this.sources = sources == null ? new Sources() : sources;
    }

    public Source businessSource() {
        return sources.getBusiness();
    }

    public Source realSource() {
        return sources.getReal();
    }

    /**
     * 启动时锁定两个允许的数据源，避免旧环境变量或遗留配置把普通查询重新导向
     * 已退役数据库。数据库凭据仍只存在于 DBHub，本检查只校验逻辑编号和工具名。
     */
    @PostConstruct
    public void validateSources() {
        validateSource("business", businessSource(), BUSINESS_SOURCE_ID);
        validateSource("real", realSource(), REAL_SOURCE_ID);
        if (businessSource().getExecuteTool().equals(realSource().getExecuteTool())) {
            throw new IllegalStateException("业务库和真实库必须配置不同的 DBHub 执行工具。");
        }
    }

    private static void validateSource(String role, Source source, String expectedId) {
        if (source == null || source.getSourceId().isBlank() || source.getExecuteTool().isBlank()
                || source.getDatabaseName().isBlank() || source.getSchemaName().isBlank()) {
            throw new IllegalStateException("DBHub " + role + " 数据源配置不完整。");
        }
        if (!expectedId.equalsIgnoreCase(source.getSourceId())) {
            throw new IllegalStateException(
                    "DBHub " + role + " 数据源只能配置为 " + expectedId + "。");
        }
    }

    /**
     * 双库数据源只保存 DBHub 的逻辑编号和工具名称，不包含数据库凭据。
     */
    public static class Source {
        private String sourceId = "";
        private String executeTool = "";
        private String databaseName = "";
        private String schemaName = "dbo";

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId == null ? "" : sourceId;
        }

        public String getExecuteTool() {
            return executeTool;
        }

        public void setExecuteTool(String executeTool) {
            this.executeTool = executeTool == null ? "" : executeTool;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName == null ? "" : databaseName;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName == null ? "" : schemaName;
        }
    }

    public static class Sources {
        private Source business = source(
                BUSINESS_SOURCE_ID, "execute_sql_winex_all_dev", "WiNEX_All_DEV", "WINDBA_GN");
        private Source real = source(
                REAL_SOURCE_ID, "execute_sql_winex_aima", "winex_aima", "dbo");

        public Source getBusiness() {
            return business;
        }

        public void setBusiness(Source business) {
            this.business = business == null ? new Source() : business;
        }

        public Source getReal() {
            return real;
        }

        public void setReal(Source real) {
            this.real = real == null ? new Source() : real;
        }

        private static Source source(
                String sourceId, String tool, String databaseName, String schemaName) {
            Source value = new Source();
            value.setSourceId(sourceId);
            value.setExecuteTool(tool);
            value.setDatabaseName(databaseName);
            value.setSchemaName(schemaName);
            return value;
        }
    }

    public String getBizMcpUrl() {
        return bizMcpUrl;
    }

    public void setBizMcpUrl(String bizMcpUrl) {
        this.bizMcpUrl = bizMcpUrl;
    }
}
