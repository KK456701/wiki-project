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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 定义或实现 {@code DbHubMcpClient} 对外部服务的受控访问边界。
 *
 * <p>客户端统一处理连接、超时和协议错误，并向上层返回稳定领域异常。认证信息、SQL 明文和患者数据不得出现在普通日志中。</p>
 */
@Component
public class DbHubMcpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        } catch (RestClientException | JsonProcessingException exception) {
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
        } catch (RestClientException | JsonProcessingException exception) {
            throw new DbHubMcpException("无法访问 DBHub MCP。", exception);
        }
    }

    static JsonNode parseJsonOrSse(ObjectMapper objectMapper, String body) throws JsonProcessingException {
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
                    List<Map<String, Object>> rows = extractRows(MAPPER.readTree(text.asText()));
                    if (rows != null) {
                        return rows;
                    }
                } catch (JsonProcessingException ignored) {
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
                String nested = extractError(MAPPER.readTree(text.asText()));
                if (!nested.isBlank()) {
                    return nested;
                }
            } catch (JsonProcessingException ignored) {
                return text.asText().strip();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return MAPPER.convertValue(node, LinkedHashMap.class);
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceFirst("/+$", "");
    }


    /**
     * Call MCP tools/call for business-domain query tools.
     *
     * <p>Request body example:
     * {@code {"jsonrpc":"2.0","method":"tools/call","params":{"name":"...","arguments":{"domainNo":"...","params":{...},"hospitalSOID":"..."}}}}
     *
     * @param name         MCP tool name, e.g. getPatientTreatment
     * @param domainNo     business domain number, e.g. Encounter
     * @param params       tool business params, e.g. {@code {"encounterId":"..."}}
     * @param hospitalSOID hospital SOID
     * @return MCP result node (JSON-RPC wrapper removed and errors validated)
     */
    public JsonNode callTool(String name, String domainNo, Map<String, Object> params, Long hospitalSOID) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("domainNo", domainNo);
        if (params == null) {
            arguments.put("params", "");
        } else {
            try {
                arguments.put("params", objectMapper.writeValueAsString(params));
            } catch (JsonProcessingException e) {
                throw new DbHubMcpException("Failed to serialize params", e);
            }
        }
        arguments.put("hospitalSOID", hospitalSOID);

        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "method", "tools/call",
                "params", Map.of(
                        "name", name,
                        "arguments", arguments));
        try {
            String body = restClient.post()
                    .uri(properties.getBizMcpUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode response = parseJsonOrSse(objectMapper, body == null ? "" : body);
            if (response.has("error")) {
                throw new DbHubMcpException("DBHub MCP call failed: " + response.get("error"));
            }
            JsonNode result = response.has("result") ? response.get("result") : response;
            if (result.path("isError").asBoolean(false)) {
                String error = extractError(result);
                throw new DbHubMcpException(
                        "DBHub MCP execution failed: " + (error.isBlank() ? "tool returned error" : error));
            }
            return result;
        } catch (DbHubMcpException exception) {
            throw exception;
        } catch (RestClientException | JsonProcessingException exception) {
            throw new DbHubMcpException("Unable to access DBHub MCP.", exception);
        }
    }


    public static List<Map<String, Object>> extractRowsV2(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        // Prefer JSON-RPC result node when the full response is passed in.
        if (payload.isObject()
                && payload.has("result")
                && !payload.has("content")
                && !payload.has("rows")
                && !payload.has("data")
                && !payload.has("structuredContent")) {
            List<Map<String, Object>> fromResult = extractRowsV2(payload.get("result"));
            if (fromResult != null) {
                return fromResult;
            }
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
            if (value == null || value.isNull()) {
                continue;
            }
            // Wrap single-object data/rows as a one-element list.
            if (value.isObject() && ("rows".equals(key) || "data".equals(key))) {
                return List.of(toMap(value));
            }
            List<Map<String, Object>> rows = extractRowsV2(value);
            if (rows != null) {
                return rows;
            }
        }
        JsonNode content = payload.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                JsonNode textNode = item.get("text");
                if (textNode == null || !textNode.isTextual()) {
                    continue;
                }
                try {
                    // Common MCP shape: content[].text = {"success":true,"data":[...]}
                    JsonNode textJson = MAPPER.readTree(textNode.asText());
                    // Business error shape: {"success":false,"errorDetail":{"code":"...","message":"..."}}
                    if (textJson.has("success") && !textJson.get("success").asBoolean()) {
                        JsonNode errorDetail = textJson.get("errorDetail");
                        String errorMsg = (errorDetail != null && errorDetail.isObject() && errorDetail.has("message"))
                                ? errorDetail.get("message").asText()
                                : textNode.asText().strip();
                        throw new DbHubMcpException("DBHub MCP business error: " + errorMsg);
                    }
                    List<Map<String, Object>> rows = extractRowsV2(textJson);
                    if (rows != null) {
                        return rows;
                    }
                } catch (JsonProcessingException ignored) {
                    // Non-JSON text is handled by extractError.
                }
            }
        }
        return null;
    }
}
