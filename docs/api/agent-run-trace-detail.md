# 运行链路详情接口文档

> `GET /api/agent/runs/{traceId}`
>
> 按 `traceId` 返回单次 Agent 运行（一轮对话）的完整链路详情：运行汇总、逐节点执行明细、已核验证据（Evidence）与耗时汇总。供前端"查看链路 / 运行详情"页面使用。

---

## 1. 基本信息

| 项 | 值 |
|---|---|
| 方法 | `GET` |
| 路径 | `/api/agent/runs/{traceId}` |
| Content-Type（响应） | `application/json` |
| 字符编码 | UTF-8 |
| 鉴权 | 见下方"鉴权与医院隔离" |
| 后端实现 | `AgentTraceController#get` → `AgentTraceService#get` |

---

## 2. 鉴权与医院隔离

- 通过 HTTP 头 `Authorization: Bearer <token>` 传递登录令牌。
- **令牌缺失或无效不会直接返回 401**：服务端会退化为访客身份（`hospital_id = hospital_001`）。因此对该访客医院不可见的 `traceId` 会返回 **404**，而不是 401。
- **医院范围强制来自已认证主体，不能被客户端参数覆盖**。查询时始终附带 `hospital_id` 条件，跨院 `traceId` 一律视为"未找到"（404），以此保证医院数据隔离。

---

## 3. 请求参数

### 3.1 路径参数（Path Variable）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `traceId` | string | 是 | 运行链路唯一标识。来自发起对话时 SSE `agent_start` 事件返回的 `trace_id`，或列表接口 `GET /api/agent/runs` 返回项中的 `trace_id`。 |

### 3.2 请求头（Header）

| 头字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `Authorization` | string | 否 | 格式 `Bearer <token>`。缺失时按访客身份处理（见"鉴权与医院隔离"）。 |

### 3.3 查询参数（Query）

无。

### 3.4 请求示例

```http
GET /api/agent/runs/TRACE_5f3c9a1b2d7e4f80 HTTP/1.1
Host: 127.0.0.1:8765
Authorization: Bearer eyJhbGciOi...
```

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8765/api/agent/runs/TRACE_5f3c9a1b2d7e4f80" `
  -Headers @{ Authorization = "Bearer <token>" } | ConvertTo-Json -Depth 8
```

---

## 4. 响应结构

响应为一个 JSON 对象，字段命名统一为 **snake_case**。顶层结构如下：

```jsonc
{
  // —— 运行汇总（来自 med_agent_trace 表） ——
  "id": 1024,
  "trace_id": "TRACE_5f3c9a1b2d7e4f80",
  "session_id": "SESSION_ab12cd34",
  "hospital_id": "hospital_001",
  "user_id": "user_007",
  "user_query": "计算2026-01-01到2026-03-31急会诊及时到位率",
  "intent": "INDICATOR_TRIAL_RUN",
  "final_status": "success",
  "final_answer_summary": "急会诊及时到位率为 96.3% ……",
  "error_count": 0,
  "fallback_count": 0,
  "started_at": "2026-07-24T19:35:06",
  "ended_at": "2026-07-24T19:35:21",
  "duration_ms": 15234,
  "created_at": "2026-07-24T19:35:06",

  // —— 服务端追加的派生字段 ——
  "trace_version": "java-agent-trace-v1",
  "timing_summary": {
    "llm_ms": 8200,
    "tool_ms": 6100,
    "code_ms": 700,
    "storage_ms": 234
  },

  // —— 逐节点执行明细 ——
  "nodes": [ /* TraceNode[]，见 4.2 */ ],

  // —— 已核验证据 ——
  "evidence": [ /* Evidence[]，见 4.3 */ ]
}
```

### 4.1 顶层字段（运行汇总）

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | integer | 否 | 数据库自增主键，无业务含义。 |
| `trace_id` | string | 否 | 运行链路唯一标识，与请求路径一致。 |
| `session_id` | string | 是 | 所属会话 ID。同一会话的多轮对话共享该 ID。 |
| `hospital_id` | string | 否 | 医院隔离范围。恒等于当前登录主体所属医院。 |
| `user_id` | string | 是 | 发起本轮运行的用户 ID。 |
| `user_query` | string | 是 | 用户本轮原始提问文本。 |
| `intent` | string | 是 | 识别出的业务意图，运行结束后写入；运行中为 `null`。枚举见 4.4。 |
| `final_status` | string | 否 | 运行最终状态。枚举见 4.4。 |
| `final_answer_summary` | string | 是 | 最终回答摘要，最长 2000 字符（超出截断）。运行中为 `null`。 |
| `error_count` | integer | 否 | 错误计数。失败运行为 1，否则 0。 |
| `fallback_count` | integer | 否 | 兜底/澄清计数。`incomplete`（澄清）运行为 1，否则 0。 |
| `started_at` | string | 否 | 运行开始时间，本地时间（Asia/Shanghai），ISO 格式 `yyyy-MM-ddTHH:mm:ss`。 |
| `ended_at` | string | 是 | 运行结束时间，格式同上。运行中为 `null`。 |
| `duration_ms` | integer | 是 | 运行总耗时（毫秒）。运行中为 `null` 或 0。 |
| `created_at` | string | 否 | 记录创建时间，格式同 `started_at`。 |
| `trace_version` | string | 否 | 链路数据结构版本，当前固定为 `java-agent-trace-v1`。 |
| `timing_summary` | object | 否 | 按节点类型聚合的耗时汇总，见 4.5。 |
| `nodes` | array | 否 | 逐节点执行明细，按执行顺序排列，见 4.2。 |
| `evidence` | array | 否 | 本次运行沉淀的已核验证据列表，见 4.3。 |

### 4.2 `nodes[]` — 节点执行明细

数组元素按 `sequence`（缺失时按 `id`）升序排列。每个节点包含数据库原始字段与服务端追加的 4 个派生字段。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | integer | 否 | 节点记录自增主键。 |
| `trace_id` | string | 否 | 所属运行链路 ID。 |
| `node_id` | string | 否 | 节点唯一标识。 |
| `node_name` | string | 否 | 节点内部名称（英文键），如 `planner_llm`、`tool_result`。 |
| `node_type` | string | 否 | 节点类型，用于耗时归类。枚举见 4.4。 |
| `status` | string | 否 | 节点执行状态，如 `success` / `failed` / `error`。 |
| `input_summary` | string | 是 | **原始输入** JSON 字符串（已按敏感字段脱敏）。前端通常用派生字段 `input_data`。 |
| `output_summary` | string | 是 | **原始输出** JSON 字符串（已脱敏）。前端通常用派生字段 `output_data`。 |
| `error_code` | string | 是 | 错误码（如 `AGENT_REPEATED_TOOL_CALL`）；无错误为 `null`。 |
| `error_message` | string | 是 | 错误信息，最长 2000 字符。 |
| `tool_name` | string | 是 | 工具名（仅工具类节点）。 |
| `db_source` | string | 是 | 数据源标识（涉及数据库访问时）。 |
| `sql_id` | string | 是 | 受控 SQL 对象 ID。 |
| `run_id` | string | 是 | 试运行 ID。 |
| `rule_id` | string | 是 | 关联指标规则 ID。 |
| `llm_model` | string | 是 | LLM 模型名（历史字段，与 `model_id` 含义相近）。 |
| `started_at` | string | 否 | 节点开始时间（本地时间 ISO 格式）。 |
| `ended_at` | string | 是 | 节点结束时间。 |
| `duration_ms` | integer | 是 | 节点耗时（毫秒）。 |
| `parent_node_id` | string | 是 | 父节点 ID，用于构建树形链路。 |
| `subtask_id` | string | 是 | 子任务 ID；非复合请求为 `root`。 |
| `sequence` | integer | 是 | 节点在本链路内的执行序号（从 1 递增）。 |
| `started_offset_ms` | integer | 是 | 相对运行开始的偏移毫秒数，用于时间轴渲染。 |
| `exclusive_duration_ms` | integer | 是 | 节点独占耗时（毫秒）。 |
| `capability` | string | 是 | 业务能力标识。 |
| `model_id` | string | 是 | 模型 ID（LLM 类节点）。 |
| `failure_class` | string | 是 | 失败分类，枚举见 4.4。 |
| `input_tokens` | integer | 是 | 输入 token 数（LLM 类节点）。 |
| `output_tokens` | integer | 是 | 输出 token 数（LLM 类节点）。 |
| `cache_reused` | integer | 是 | 是否命中缓存：`1` 命中 / `0` 未命中。 |
| `retry_count` | integer | 是 | 重试次数。 |
| `created_at` | string | 否 | 节点记录创建时间。 |
| `node_title` | string | 否 | **【派生】** 节点中文标题（供 UI 展示），如"规划业务目标""执行并观察工具结果"。 |
| `processing_summary` | string | 否 | **【派生】** 该节点处理逻辑的中文一句话说明。 |
| `input_data` | object \| array \| string | 否 | **【派生】** 由 `input_summary` 解析得到的结构化对象；解析失败时回退为原始字符串，无内容时为 `{}`。 |
| `output_data` | object \| array \| string | 否 | **【派生】** 由 `output_summary` 解析得到的结构化对象；规则同上。 |

> 说明：`input_data` / `output_data` 是把 `input_summary` / `output_summary` 的 JSON 文本解析成对象后的结果，**前端优先使用这两个派生字段**渲染入参/出参；原始 `*_summary` 字段保留以兼容旧逻辑。

### 4.3 `evidence[]` — 已核验证据

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `evidence_id` | string | 否 | 证据唯一标识。 |
| `fact_type` | string | 否 | 事实类型（如指标口径、试运行结果等）。 |
| `rule_id` | string | 是 | 关联指标规则 ID。 |
| `rule_version` | string | 是 | 规则版本。 |
| `stat_start` | string | 是 | 统计周期开始日期。 |
| `stat_end` | string | 是 | 统计周期结束日期。 |
| `source_tool` | string | 否 | 生成该证据的工具名。 |
| `source_object_id` | string | 是 | 来源对象 ID（如 SQL 对象、试运行）。 |
| `created_at` | string | 否 | 证据创建时间。 |
| `expires_at` | string | 是 | 证据过期时间。 |

> 注意：证据列表仅返回**安全元数据**，不包含 SQL 正文、患者原始行等敏感明细（这些字段已在链路存储与返回中脱敏或剔除）。

### 4.4 枚举取值

**`final_status`（运行状态）**

| 值 | 含义 |
|---|---|
| `running` | 运行进行中（尚未结束）。 |
| `success` | 成功产出最终回答。 |
| `incomplete` | 需澄清/兜底，未完成（如低置信度触发意图澄清）。 |
| `failed` | 运行失败。 |

**`node_type`（节点类型）**

| 值 | 归入耗时项 | 含义 |
|---|---|---|
| `llm` | `llm_ms` | 大模型调用节点。 |
| `tool` | `tool_ms` | 工具调用节点。 |
| `database` | `tool_ms` | 数据库访问节点。 |
| `storage` | `storage_ms` | 存储读写节点。 |
| `code`（或其他） | `code_ms` | 服务端确定性代码节点。 |

**`failure_class`（失败分类）**

| 值 | 含义 |
|---|---|
| `TIMEOUT` | 超时。 |
| `PERMISSION` | 权限/禁止访问。 |
| `DATABASE` | 数据库或 SQL 相关失败。 |
| `CLARIFICATION` | 时间范围/歧义类，需澄清。 |
| `TOOL_OR_RUNTIME` | 其他工具或运行时错误。 |

**`intent`（业务意图，常见值）**：`INDICATOR_SQL_PREPARE`、`INDICATOR_TRIAL_RUN`、`RULE_EXPLANATION`、`INDICATOR_CALIBER_SIMULATION` 等；运行未结束时为 `null`。

### 4.5 `timing_summary` — 耗时汇总

| 字段 | 类型 | 说明 |
|---|---|---|
| `llm_ms` | integer | 所有 `llm` 类节点耗时之和（毫秒）。 |
| `tool_ms` | integer | 所有 `tool` + `database` 类节点耗时之和（毫秒）。 |
| `code_ms` | integer | 其他（代码类）节点耗时之和（毫秒）。 |
| `storage_ms` | integer | 所有 `storage` 类节点耗时之和（毫秒）。 |

---

## 5. 状态码与错误响应

| HTTP 状态码 | 触发场景 | 响应体（Spring 默认错误结构） |
|---|---|---|
| `200 OK` | 成功返回链路详情。 | 见第 4 节。 |
| `404 Not Found` | 当前医院范围内不存在该 `traceId`（含跨院访问、`traceId` 不存在）。 | 错误码 `TRACE_NOT_FOUND`，消息"未找到本院可访问的运行链路。" |

错误响应遵循 Spring Boot 默认结构，示例：

```json
{
  "timestamp": "2026-07-24T11:35:21.000+00:00",
  "status": 404,
  "error": "Not Found",
  "message": "未找到本院可访问的运行链路。",
  "path": "/api/agent/runs/TRACE_not_exist"
}
```

> 说明：由于鉴权已放宽（缺失/无效令牌退化为访客身份），本接口正常情况下**不返回 401**；权限外的链路统一表现为 404。

---

## 6. 完整响应示例

```json
{
  "id": 1024,
  "trace_id": "TRACE_5f3c9a1b2d7e4f80",
  "session_id": "SESSION_ab12cd34",
  "hospital_id": "hospital_001",
  "user_id": "user_007",
  "user_query": "计算2026-01-01到2026-03-31急会诊及时到位率",
  "intent": "INDICATOR_TRIAL_RUN",
  "final_status": "success",
  "final_answer_summary": "急会诊及时到位率为 96.3%，分子 258，分母 268 ……",
  "error_count": 0,
  "fallback_count": 0,
  "started_at": "2026-07-24T19:35:06",
  "ended_at": "2026-07-24T19:35:21",
  "duration_ms": 15234,
  "created_at": "2026-07-24T19:35:06",
  "trace_version": "java-agent-trace-v1",
  "timing_summary": { "llm_ms": 8200, "tool_ms": 6100, "code_ms": 700, "storage_ms": 234 },
  "nodes": [
    {
      "id": 5001,
      "trace_id": "TRACE_5f3c9a1b2d7e4f80",
      "node_id": "NODE_a1b2c3d4e5f60718",
      "node_name": "planner_llm",
      "node_type": "llm",
      "status": "success",
      "input_summary": "{\"user_query\":\"计算2026-01-01到2026-03-31急会诊及时到位率\"}",
      "output_summary": "{\"intent\":\"INDICATOR_TRIAL_RUN\",\"confidence\":0.94}",
      "error_code": null,
      "error_message": null,
      "tool_name": null,
      "db_source": null,
      "sql_id": null,
      "run_id": null,
      "rule_id": "MQSI2025_005",
      "llm_model": "qwen-plus",
      "started_at": "2026-07-24T19:35:06",
      "ended_at": "2026-07-24T19:35:10",
      "duration_ms": 4200,
      "parent_node_id": null,
      "subtask_id": "root",
      "sequence": 1,
      "started_offset_ms": 0,
      "exclusive_duration_ms": 4200,
      "capability": null,
      "model_id": "aliyun-qwen-plus",
      "failure_class": null,
      "input_tokens": 512,
      "output_tokens": 64,
      "cache_reused": 0,
      "retry_count": 0,
      "created_at": "2026-07-24T19:35:06",
      "node_title": "规划业务目标",
      "processing_summary": "LLM 只生成业务 RequestPlan，不选择工具。",
      "input_data": { "user_query": "计算2026-01-01到2026-03-31急会诊及时到位率" },
      "output_data": { "intent": "INDICATOR_TRIAL_RUN", "confidence": 0.94 }
    }
  ],
  "evidence": [
    {
      "evidence_id": "EV_20260724_0001",
      "fact_type": "trial_run_result",
      "rule_id": "MQSI2025_005",
      "rule_version": "2025.1",
      "stat_start": "2026-01-01",
      "stat_end": "2026-03-31",
      "source_tool": "indicator_trial_run",
      "source_object_id": "RUN_9a8b7c6d",
      "created_at": "2026-07-24T19:35:19",
      "expires_at": "2026-08-23T19:35:19"
    }
  ]
}
```

---

## 7. 前端对接要点

1. **入参/出参渲染**：优先使用节点的 `input_data` / `output_data`（已解析为对象），`*_summary` 仅作兼容备用。
2. **链路树**：用 `parent_node_id` + `subtask_id` 构建层级；同级按 `sequence` 排序。
3. **时间轴**：用 `started_offset_ms`（相对运行起点偏移）+ `duration_ms` 绘制甘特图，用 `exclusive_duration_ms` 展示独占耗时。
4. **中文展示**：`node_title` / `processing_summary` 已由后端提供中文文案，无需前端再做名称映射。
5. **状态判断**：运行是否结束看 `ended_at` / `final_status`；`running` 表示仍在进行。
6. **脱敏**：SQL 正文、token、患者原始行等敏感数据已在后端剔除或替换为 `[已脱敏]`，前端无需额外处理。
7. **数据来源链路**：`traceId` 来自 SSE `agent_start` 事件的 `trace_id`，或 `GET /api/agent/runs` 列表项。
