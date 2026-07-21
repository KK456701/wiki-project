# DBHub MCP 兼容约定 v1

Java 主服务继续复用现有 DBHub sidecar，不直连医院 SQL Server。

## 数据源

- 请求：`GET {api_url}/api/sources`
- 响应：数组，或包含 `sources` / `value` 数组的对象。

## 只读 SQL 工具

- 请求：`POST {mcp_url}`
- `Content-Type: application/json`
- `Accept: application/json, text/event-stream`

```json
{
  "jsonrpc": "2.0",
  "id": "随机请求 ID",
  "method": "tools/call",
  "params": {
    "name": "execute_sql_<source>",
    "arguments": {"sql": "经过服务端校验的只读 SQL"}
  }
}
```

客户端兼容 JSON 或 SSE 包装的 JSON-RPC 响应，并从 `rows`、`data`、`structuredContent` 或 `content[].text` 中递归读取行数据。`isError=true`、JSON-RPC `error` 或缺少可解析行数据都必须作为失败处理。

DBHub 只是数据库能力边界；SQL 只读校验、医院权限、超时和 Evidence 仍由主服务控制。
