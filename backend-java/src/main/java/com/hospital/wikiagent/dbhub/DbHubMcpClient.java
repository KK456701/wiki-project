package com.hospital.wikiagent.dbhub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DbHubMcpClient {

    private final DbHubProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DbHubMcpClient(DbHubProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public JsonNode sources() {
        try {
            String body = restClient.get()
                    .uri(stripTrailingSlash(properties.getApiUrl()) + "/api/sources")
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientException | JacksonException exception) {
            throw new DbHubMcpException("无法访问 DBHub API。", exception);
        }
    }

    public List<Map<String, Object>> executeSql(String executeTool, String sql) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "method", "tools/call",
                "params", Map.of(
                        "name", executeTool,
                        "arguments", Map.of("sql", sql)));
        try {
            String body = restClient.post()
                    .uri(properties.getMcpUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode response = parseJsonOrSse(objectMapper, body == null ? "" : body);
            if (response.has("error")) {
                throw new DbHubMcpException("DBHub MCP 调用失败: " + response.get("error"));
            }
            JsonNode result = response.has("result") ? response.get("result") : response;
            if (result.path("isError").asBoolean(false)) {
                String error = extractError(result);
                throw new DbHubMcpException("DBHub MCP 执行失败: " + (error.isBlank() ? "工具返回错误" : error));
            }
            List<Map<String, Object>> rows = extractRows(result);
            if (rows == null) {
                String error = extractError(result);
                throw new DbHubMcpException("DBHub MCP 返回格式中没有可解析的 rows"
                        + (error.isBlank() ? "" : ": " + error));
            }
            return rows;
        } catch (DbHubMcpException exception) {
            throw exception;
        } catch (RestClientException | JacksonException exception) {
            throw new DbHubMcpException("无法访问 DBHub MCP。", exception);
        }
    }

    static JsonNode parseJsonOrSse(ObjectMapper objectMapper, String body) throws JacksonException {
        String stripped = body.strip();
        if (stripped.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        if (stripped.startsWith("{")) {
            return objectMapper.readTree(stripped);
        }
        StringBuilder data = new StringBuilder();
        for (String line : stripped.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(line.substring("data:".length()).strip());
        }
        return objectMapper.readTree(data.isEmpty() ? stripped : data.toString());
    }

    static List<Map<String, Object>> extractRows(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        if (payload.isArray()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : payload) {
                if (row.isObject()) {
                    rows.add(toMap(row));
                }
            }
            return rows;
        }
        if (!payload.isObject()) {
            return null;
        }
        for (String key : List.of("rows", "data", "structuredContent")) {
            JsonNode value = payload.get(key);
            if (value == null) {
                continue;
            }
            List<Map<String, Object>> rows = extractRows(value);
            if (rows != null) {
                return rows;
            }
        }
        JsonNode content = payload.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                JsonNode text = item.get("text");
                if (text == null || !text.isTextual()) {
                    continue;
                }
                try {
                    List<Map<String, Object>> rows = extractRows(new ObjectMapper().readTree(text.asText()));
                    if (rows != null) {
                        return rows;
                    }
                } catch (JacksonException ignored) {
                    // 非 JSON 文本由 extractError 负责生成安全错误说明。
                }
            }
        }
        return null;
    }

    static String extractError(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return "";
        }
        JsonNode error = payload.get("error");
        if (error != null && !error.isNull()) {
            return error.isTextual() ? error.asText() : error.toString();
        }
        JsonNode content = payload.get("content");
        if (content == null || !content.isArray()) {
            return "";
        }
        for (JsonNode item : content) {
            JsonNode text = item.get("text");
            if (text == null || !text.isTextual() || text.asText().isBlank()) {
                continue;
            }
            try {
                String nested = extractError(new ObjectMapper().readTree(text.asText()));
                if (!nested.isBlank()) {
                    return nested;
                }
            } catch (JacksonException ignored) {
                return text.asText().strip();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return new ObjectMapper().convertValue(node, LinkedHashMap.class);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceFirst("/+$", "");
    }
}
