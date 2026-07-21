package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubProperties;

@Component
public class DbHubIndicatorBusinessQueryClient implements IndicatorBusinessQueryClient {
    private final DbHubMcpClient client;
    private final DbHubProperties properties;

    public DbHubIndicatorBusinessQueryClient(DbHubMcpClient client, DbHubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<Map<String, Object>> execute(String sql) {
        return client.executeSql(properties.getExecuteTool(), sql);
    }

    @Override
    public String sourceId() {
        return properties.getSourceId();
    }
}
