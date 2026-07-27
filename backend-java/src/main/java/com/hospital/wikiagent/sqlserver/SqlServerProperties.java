package com.hospital.wikiagent.sqlserver;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 SQL Server 受控 JDBC 写入的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；默认关闭，只有显式启用后才会创建第二数据源与
 * JDBC 写入 Bean。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.sqlserver")
public class SqlServerProperties {

    /**
     * 是否启用 SQL Server 受控 JDBC 第二数据源。
     */
    private boolean enabled = false;

    private String url = "";

    private String username = "";

    private String password = "";

    private String driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

    private String schema = "dbo";

    private String expectedDatabase = "winex_aima";

    private Hikari hikari = new Hikari();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getExpectedDatabase() {
        return expectedDatabase;
    }

    public void setExpectedDatabase(String expectedDatabase) {
        this.expectedDatabase = expectedDatabase;
    }

    /**
     * 在创建第二数据源前拒绝危险的账号和不完整连接配置。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (url == null || url.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException("启用真实库写入时必须通过环境变量提供完整连接配置。");
        }
        if (!"dbo".equalsIgnoreCase(schema)
                || !"winex_aima".equalsIgnoreCase(expectedDatabase)) {
            throw new IllegalStateException("真实库写入仅允许 winex_aima.dbo。");
        }
        String normalized = url.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (!java.util.regex.Pattern.compile(
                "(?:^|;)databasename=winex_aima(?:;|$)")
                .matcher(normalized)
                .find()) {
            throw new IllegalStateException("SQL Server URL 必须显式绑定 databaseName=winex_aima。");
        }
    }

    public Hikari getHikari() {
        return hikari;
    }

    public void setHikari(Hikari hikari) {
        this.hikari = hikari;
    }

    /**
     * SQL Server 连接池参数。
     */
    public static class Hikari {
        private int maximumPoolSize = 2;
        private int minimumIdle = 1;
        private long connectionTimeout = 5000;

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
    }

}
