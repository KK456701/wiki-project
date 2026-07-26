package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubProperties;

/**
 * 定义或实现 {@code DbHubIndicatorBusinessQueryClient} 对外部服务的受控访问边界。
 *
 * <p>客户端统一处理连接、超时和协议错误，并向上层返回稳定领域异常。认证信息、SQL 明文和患者数据不得出现在普通日志中。</p>
 */
@Component
public class DbHubIndicatorBusinessQueryClient
        implements IndicatorBusinessQueryClient, IndicatorDatabaseQueryClient {
    private final DbHubMcpClient client;
    private final DbHubProperties properties;

    public DbHubIndicatorBusinessQueryClient(DbHubMcpClient client, DbHubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<Map<String, Object>> execute(String sql) {
        return execute(DatabaseRole.BUSINESS, sql);
    }

    @Override
    public String sourceId() {
        return sourceId(DatabaseRole.BUSINESS);
    }

    @Override
    public List<Map<String, Object>> execute(DatabaseRole role, String sql) {
        DbHubProperties.Source source = source(role);
        if (source.getExecuteTool().isBlank() || source.getSourceId().isBlank()) {
            throw new IllegalStateException("DBHub " + role.value() + " 数据源配置不完整。");
        }
        return client.executeSql(source.getExecuteTool(), sql);
    }

    @Override
    public String sourceId(DatabaseRole role) {
        return source(role).getSourceId();
    }

    private DbHubProperties.Source source(DatabaseRole role) {
        return role == DatabaseRole.BUSINESS
                ? properties.businessSource()
                : properties.realSource();
    }
}
