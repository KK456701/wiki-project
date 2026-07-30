package com.hospital.wikiagent.sqlserver;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 暴露 SQL Server + JPA 第二数据源的启用状态与连通性探测能力。
 *
 * <p>该服务不替代 DBHub 业务只读网关，只负责在显式启用直连后检查 EntityManagerFactory 是否可用。
 * 默认关闭时返回 disabled 状态，避免启动或健康检查依赖医院 SQL Server。</p>
 */
@Service
public class SqlServerAccessService {

    private static final String JDBC_TEMPLATE_BEAN = "sqlServerJdbcTemplate";

    private final SqlServerProperties properties;
    private final ApplicationContext applicationContext;

    public SqlServerAccessService(SqlServerProperties properties, ApplicationContext applicationContext) {
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    /**
     * 返回当前 SQL Server JPA 接入状态，不强制发起真实查询。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("schema", properties.getSchema());
        status.put("jdbcReady", applicationContext.containsBean(JDBC_TEMPLATE_BEAN));
        status.put("database", properties.getExpectedDatabase());
        return status;
    }

    /**
     * 在已启用时执行一次 {@code SELECT 1} 探测；未启用时返回 empty。
     */
    public java.util.Optional<Map<String, Object>> probe() {
        if (!properties.isEnabled()) {
            return java.util.Optional.empty();
        }

        if (!applicationContext.containsBean(JDBC_TEMPLATE_BEAN)) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("message", "sqlServerJdbcTemplate is not available");
            return java.util.Optional.of(failed);
        }

        JdbcTemplate jdbc = applicationContext.getBean(JDBC_TEMPLATE_BEAN, JdbcTemplate.class);
        try {
            Object value = jdbc.queryForObject("SELECT 1", Object.class);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("result", value);
            return java.util.Optional.of(ok);
        } catch (RuntimeException exception) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("message", exception.getMessage());
            return java.util.Optional.of(failed);
        }
    }
}
