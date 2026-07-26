# Wiki Project 后端接口对接文档

> 版本：2026-07-26
> 服务：Java 17 + Spring Boot 3.5
> 默认地址：`http://127.0.0.1:8765`

## 1. 通用约定

除登录和健康接口外，业务接口使用：

```http
Authorization: Bearer <hospital-token>
Content-Type: application/json
```

医院编号从登录令牌取得。前端不得通过请求体覆盖医院范围。错误响应使用 HTTP 状态码和安全错误说明；不得把数据库连接串、SQL 正文或患者原始行展示给未授权用户。

## 2. 接口清单

### 2.1 系统与认证

| 方法 | 路径 | 用途 | 主要入参 |
|---|---|---|---|
| GET | `/api/health` | 存活检查 | 无 |
| GET | `/api/runtime/status` | Java、SQLite、Wiki、DBHub 与模型状态 | 无 |
| POST | `/api/auth/hospital/login` | 医院人员登录 | `account_id`, `password` |
| POST | `/api/auth/hospital/change-password` | 修改密码 | `current_password`, `new_password` |
| POST | `/api/auth/hospital/logout` | 注销当前令牌 | 无 |
| POST | `/api/admin/login` | 管理员登录 | `password` |
| POST | `/api/admin/logout` | 注销管理员令牌 | 无 |

登录示例：

```json
{
  "account_id": "user_001",
  "password": "<password>"
}
```

### 2.2 Agent 对话与会话

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/agent/capabilities` | 模型列表、默认模型和能力版本 |
| POST | `/api/agent/chat` | 同步对话 |
| POST | `/api/agent/chat/stream` | SSE 流式对话 |
| POST | `/api/agent/sessions` | 创建新会话 |
| GET | `/api/agent/sessions` | 当前用户历史会话 |
| GET | `/api/agent/sessions/{sessionId}/messages` | 恢复会话消息 |
| DELETE | `/api/agent/sessions/{sessionId}` | 删除会话 |

对话请求：

```json
{
  "query": "患者入院48小时内转科的比例怎么算？",
  "session_id": "session_001",
  "model_id": "deepseek-v4-flash",
  "file_key": null
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `query` | 是 | 1～5000 字符 |
| `session_id` | 否 | 不传时可先创建会话 |
| `model_id` | 否 | 不传使用默认模型 |
| `file_key` | 否 | 上传接口返回的文件安全编号 |

同步响应包含 `answer`、`stop_reason`、`trace_id`、`session_id`、`step_count` 和可选 `clarification`。

### 2.3 Excel 上传

| 方法 | 路径 | Content-Type | 入参 |
|---|---|---|---|
| POST | `/api/agent/upload` | `multipart/form-data` | 仅一个文件字段 `file` |

前端不需要额外传文件名和文件大小。浏览器的 multipart 文件对象自带原始文件名和字节数，服务端会重新校验并返回：

```json
{
  "file_key": "hospital_001_xxx_指标明细.xlsx",
  "file_name": "指标明细.xlsx",
  "size_bytes": 12345
}
```

上传后，把返回的 `file_key` 放入后续对话请求。不得把本地绝对路径传给聊天接口。

### 2.4 规则与计划调试

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/kb/rules/search?query=...&limit=5` | 检索 35 项 HXZD 指标 |
| GET | `/api/kb/rules/{ruleId}/effective` | 读取默认 Profile、定义、公式和执行状态 |
| POST | `/api/agent/plan` | 调试 Planner 输出，不执行工具 |
| POST | `/api/agent/compile` | 调试 CompiledPlan IR，不执行数据库 |

规则响应必须同时关注：

- `rule_id`
- `profile_id`
- `execution_status`
- `execution_blockers`
- `definition`
- `formula`
- `numerator_rule`
- `denominator_rule`
- `sql_status`

只有 `execution_status=executable` 且 `sql_status=available` 的 Profile 才能进入试运行。

### 2.5 Trace 与运行观察

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/agent/runs/{traceId}` | 单轮完整安全链路 |
| GET | `/api/agent/runs` | 当前医院运行摘要 |
| GET | `/api/agent/runs/metrics` | 当前医院聚合性能 |

列表和指标支持 `started_after`、`started_before`、`status`、`model_id`、`tool_name`、`failure_class`；列表另支持 `limit`。

### 2.6 指标明细和导出

| 方法 | 路径 | 用途 | 请求体 |
|---|---|---|---|
| POST | `/api/sql-runs/{run_id}/details` | 生成或复用明细快照 | 无 |
| GET | `/api/sql-runs/{run_id}/details/{group}` | 分页查看明细 | 查询参数 `page`, `page_size` |
| POST | `/api/sql-runs/{run_id}/exports` | 导出分子/分母 Excel | `{"confirmed":true}` |
| POST | `/api/sql-runs/{run_id}/upload-comparison-exports` | 导出逐条集合差异 | `{"confirmed":true,"file_token":"..."}` |
| POST | `/api/diagnosis-reports/{report_id}/exports` | 导出分层诊断报告 | `{"confirmed":true}` |
| GET | `/api/indicator-exports` | 当前用户导出列表 | 无 |
| GET | `/api/indicator-exports/{export_id}/download` | 下载 Excel | 无 |

`group` 只允许服务端支持的分母、分子和差异分组。明细/导出入口只会在当前 `rule_id + profile_id` 声明对应明细 SQL 且 Profile 可执行时展示。

### 2.7 元数据与 DBHub

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/metadata/overview` | 当前医院元数据概览 |
| POST | `/api/metadata/sync` | 通过 DBHub 同步只读元数据 |
| GET | `/api/mcp/dbhub/sources` | DBHub 数据源状态 |

字段和元数据检查是 SQL 执行安全门禁，不属于已删除的指标实施工作台。

### 2.8 医学术语

| 方法 | 路径 |
|---|---|
| GET | `/api/terminology/concepts` |
| GET | `/api/terminology/concepts/{conceptCode}` |
| POST | `/api/terminology/test` |
| GET | `/api/terminology/releases` |
| POST | `/api/terminology/aliases` |
| POST | `/api/terminology/aliases/{aliasId}/approve` |
| POST | `/api/terminology/hospital-mappings` |
| POST | `/api/terminology/hospital-mappings/{mappingId}/approve` |
| POST | `/api/terminology/releases/publish` |
| POST | `/api/terminology/releases/{releaseId}/restore` |

涉及治理写操作的具体字段以控制器 DTO 校验结果为准，医院范围仍由登录主体约束。

## 3. SSE 事件

`POST /api/agent/chat/stream` 返回命名 SSE。常用事件：

| 事件 | 说明 |
|---|---|
| `agent_start` | 本轮开始 |
| `model_start` | Planner/Replanner/Final Answer 开始 |
| `trace_node` | LLM、代码、工具或存储节点完成 |
| `tool_call` | 受控工具开始 |
| `tool_result` | 工具结果或明确失败 |
| `answer` | 最终回答 |
| `agent_error` | 本轮失败 |
| `clarification` | 需要用户选择或补充 |

前端应按 `event` 字段驱动当前阶段槽，并用 `trace_id` 打开完整链路。

## 4. 已删除接口

以下接口和页面永久返回 404：

- `/api/monitoring/**`
- `/api/indicator-drafts/**`
- `/monitoring`
- `/implementation`

“全面实施验收”不是 HTTP 接口，也不再进入 Agent 工具链。
