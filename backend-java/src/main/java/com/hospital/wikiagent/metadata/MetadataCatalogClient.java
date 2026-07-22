package com.hospital.wikiagent.metadata;

import java.util.List;
import java.util.Map;

/**
 * 定义或实现 {@code MetadataCatalogClient} 对外部服务的受控访问边界。
 */
public interface MetadataCatalogClient {
    List<Map<String, Object>> listTables(String databaseName, String schemaName);

    List<Map<String, Object>> listColumns(String databaseName, String schemaName, String tableName);

    String sourceName();
}
