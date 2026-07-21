package com.hospital.wikiagent.metadata;

import java.util.List;
import java.util.Map;

public interface MetadataCatalogClient {
    List<Map<String, Object>> listTables(String databaseName, String schemaName);

    List<Map<String, Object>> listColumns(String databaseName, String schemaName, String tableName);

    String sourceName();
}
