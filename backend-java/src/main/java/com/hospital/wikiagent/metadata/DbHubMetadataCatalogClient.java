package com.hospital.wikiagent.metadata;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubProperties;

/**
 * 定义或实现 {@code DbHubMetadataCatalogClient} 对外部服务的受控访问边界。
 *
 * <p>客户端统一处理连接、超时和协议错误，并向上层返回稳定领域异常。认证信息、SQL 明文和患者数据不得出现在普通日志中。</p>
 */
@Component
public class DbHubMetadataCatalogClient implements MetadataCatalogClient {
    private final DbHubMcpClient client;
    private final DbHubProperties properties;

    public DbHubMetadataCatalogClient(DbHubMcpClient client, DbHubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<Map<String, Object>> listTables(String databaseName, String schemaName) {
        String sql = "SELECT TABLE_NAME, '' AS TABLE_COMMENT, TABLE_TYPE "
                + "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_CATALOG = '"
                + literal(databaseName) + "' AND TABLE_SCHEMA = '" + literal(schemaName)
                + "' ORDER BY TABLE_NAME";
        return client.executeSql(properties.getExecuteTool(), sql);
    }

    @Override
    public List<Map<String, Object>> listColumns(
            String databaseName, String schemaName, String tableName) {
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_TYPE AS COLUMN_TYPE, "
                + "IS_NULLABLE, '' AS COLUMN_KEY, COLUMN_DEFAULT, '' AS COLUMN_COMMENT "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_CATALOG = '"
                + literal(databaseName) + "' AND TABLE_SCHEMA = '" + literal(schemaName)
                + "' AND TABLE_NAME = '" + literal(tableName)
                + "' ORDER BY TABLE_NAME, ORDINAL_POSITION";
        return client.executeSql(properties.getExecuteTool(), sql);
    }

    @Override
    public String sourceName() {
        return "dbhub";
    }

    private static String literal(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
