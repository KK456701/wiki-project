package com.hospital.wikiagent.dbhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class DbHubMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesSseWrappedJsonRpcResponse() throws Exception {
        JsonNode response = DbHubMcpClient.parseJsonOrSse(
                objectMapper,
                "event: message\ndata: {\"result\":{\"rows\":[{\"count\":2}]}}\n\n");

        List<Map<String, Object>> rows = DbHubMcpClient.extractRows(response.get("result"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("count").toString()).isEqualTo("2");
    }

    @Test
    void extractsRowsFromMcpTextContent() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "content": [
                    {"type": "text", "text": "{\\\"data\\\":[{\\\"rule_id\\\":\\\"MQSI2025_001\\\"}]}"}
                  ]
                }
                """);

        List<Map<String, Object>> rows = DbHubMcpClient.extractRows(response);

        assertThat(rows).containsExactly(Map.of("rule_id", "MQSI2025_001"));
    }
}
