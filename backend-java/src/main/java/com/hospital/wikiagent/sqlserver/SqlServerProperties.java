package com.hospital.wikiagent.sqlserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 SQL Server 受控写入数据源的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；默认关闭，只有显式启用后才会创建第二数据源与
 * JDBC Bean。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.sqlserver")
public class SqlServerProperties {

    /**
     * 是否启用 SQL Server + JPA 第二数据源。
     */
    private boolean enabled = false;

    private String url = "";

    private String username = "";

    private String password = "";

    private String driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver";

    private String schema = "dbo";

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
