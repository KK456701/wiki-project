package com.hospital.wikiagent.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dbhub.DbHubProperties;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/mcp/dbhub")
/**
 * 提供 {@code DbHubController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 */
public class DbHubController {

    private final DbHubMcpClient client;
    private final DbHubProperties properties;

    public DbHubController(DbHubMcpClient client, DbHubProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        JsonNode payload = client.sources();
        JsonNode items = payload.isArray() ? payload : firstPresent(payload, "sources", "value");
        return Map.of(
                "status", "ok",
                "dbhub_http_url", properties.getApiUrl().replaceFirst("/+$", ""),
                "sources", items == null ? List.of() : items);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(DbHubMcpException.class)
    public Map<String, Object> dbHubError(DbHubMcpException exception) {
        return Map.of("detail", "DBHub sidecar 访问失败: " + exception.getMessage());
    }

    private static JsonNode firstPresent(JsonNode payload, String... fields) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = payload.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
