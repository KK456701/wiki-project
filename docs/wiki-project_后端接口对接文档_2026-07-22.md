> BACKEND API · 对接基线

# Wiki Project 后端接口对接文档

*面向 Vue 3 前端开发、联调、测试与实施排障*

| 项目 | 内容 |
| --- | --- |
| 接口基线 | 当前 Java 17 / Spring Boot 3.5.16 后端源码 |
| 生成日期 | 2026-07-22（Asia/Shanghai） |
| 接口规模 | 65 个 /api 操作；另含 2 个 Actuator 接口与 6 个 SPA 前端路由 |
| 默认地址 | http://127.0.0.1:8766；前后端同源部署时统一使用相对路径 /api/... |
| 数据格式 | JSON 字段统一 snake_case；UTF-8；未知 JSON 字段会被拒绝 |
| 规则来源 | core-rules-wiki；运行数据 SQLite；医院业务数据经 DBHub 只读访问 |

**对接结论** 前端应优先走医院登录 → 能力加载 → SSE 对话 → trace_id 查链路；需要患者级明细时，再凭 run_id 创建快照和导出。hospital_id 不能作为越权依据，最终范围始终由医院令牌决定。

## 目录与阅读建议

- 1. 快速接入与通用约定
- 2. 完整接口总览（65 个 API）
- 3. 核心接口详细契约
- 4. 业务工作台接口契约
- 5. 通用响应模型与错误语义
- 6. 前端联调流程与验收清单
- 附录 A：基础设施与 SPA 路由

## 1. 快速接入与通用约定

### 1.1 推荐接入顺序

1. 调用 POST /api/auth/hospital/login，保存医院 token 和用户权限。
1. 调用 GET /api/agent/capabilities，渲染模型选择器并确认目标模型 available=true。
1. 调用 POST /api/agent/chat/stream；按 SSE 事件更新工具轨迹和最终回答。
1. 保存每轮 trace_id；用户点击“查看链路”时请求 GET /api/agent/runs/{traceId}。
1. 回答包含试运行结果时保存 run_id；用户查看或导出明细时按“创建快照 → 分页/导出 → 下载”调用。

### 1.2 鉴权模式

| 标记 | 请求头 | 使用场景 |
| --- | --- | --- |
| 公开 | 无需令牌 | 登录、最小健康状态 |
| H | 医院令牌：Authorization: Bearer <hospital_token> | 医院用户对话、规则、术语读取、明细、实施草稿 |
| A | 管理员令牌：Authorization: Bearer <admin_token> | 管理员登录后的全局治理或调度器操作 |
| A+H | 双令牌：Authorization 放管理员令牌；X-Hospital-Authorization 放医院令牌 | 管理员操作某医院数据；两个令牌缺一不可 |
| 内部 | 内部/实施排障接口；当前代码未要求登录令牌 | DBHub 来源探测；建议生产网关限制访问 |

```http
// 医院接口
Authorization: Bearer <hospital_token>
// 双令牌管理接口
Authorization: Bearer <admin_token>
X-Hospital-Authorization: Bearer <hospital_token>
```

### 1.3 HTTP 与 JSON 约定

- 普通 JSON 请求使用 Content-Type: application/json；上传接口不要手工设置 multipart boundary。
- 全局 Jackson 命名策略为 SNAKE_CASE。TypeScript 中的 sessionId、modelId 等字段发送时必须转成 session_id、model_id。
- 后端启用 fail-on-unknown-properties=true。请求体多发未定义字段会导致 422，不要把完整前端对象直接透传。
- 时间筛选优先使用 ISO 日期时间，例如 2026-07-22T14:30:00；业务统计区间按接口返回的左闭右开范围解释。
- 可选 X-Request-ID 用于幂等/排障关联；未传时服务端自动生成 REQ_ 前缀编号。
- 医院范围以 token 中的 hospital_id 为准；客户端传入 hospital_id 仅用于显式一致性检查，不能切换医院。
**注意** 当前默认配置端口为 8766。若启动脚本或反向代理对外暴露 8765，前端仍建议使用相对路径，避免在代码里写死端口。

### 1.4 SSE 事件消费

POST /api/agent/chat/stream 返回具名 SSE。每个事件块包含 event: <name> 和 data: <JSON>。前端必须以 event 行作为事件类型，不要只依赖 JSON 内字段。

| 事件 | 关键字段 | 前端动作 |
| --- | --- | --- |
| agent_start | trace_id, step | 创建运行态消息，保存 trace_id |
| model_start | message | 可显示“正在规划/生成回答” |
| tool_call | tool_name, step | 新增进行中的工具节点 |
| tool_result | tool_name, status, code, message, duration_ms, reused | 更新工具节点；识别复用和失败 |
| assistant_message | message, trace_id | 写入最终回答；同轮后续事件不得重复追加正文 |
| clarification_required | message, stop_reason | 显示需要用户补充的信息 |
| agent_error | message, stop_reason, status | 将消息标记为失败 |
| agent_done | status, stop_reason, step_count | 结束 loading；连接可关闭 |

```javascript
const response = await fetch('/api/agent/chat/stream', {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ query, session_id, model_id, file_key })
})
// 使用 ReadableStream 按空行拆分 SSE block；不要用 response.json()。
```

## 2. 完整接口总览

以下表格覆盖当前 Controller 中全部 65 个 /api 操作。状态码为正常路径的主要返回；领域失败请同时参考第 5 节。

### 系统状态

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/health | 公开 | 无 | 200：最小健康状态与编排模式 |
| GET | /api/runtime/status | 公开 | 无 | 200：Java/Vue/Wiki/SQLite/DBHub 运行时摘要 |

### 登录与会话

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| POST | /api/auth/hospital/login | 公开 | JSON：account_id, password | 200：医院 token、主体、权限、到期时间 |
| POST | /api/auth/hospital/change-password | H | JSON：current_password, new_password | 200：换发后的登录响应 |
| POST | /api/auth/hospital/logout | H | 无 | 204：无响应体 |
| POST | /api/admin/login | 公开 | JSON：password | 200：管理员 token、message |
| POST | /api/admin/logout | A | 无 | 200：message |

### Agent 核心

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/agent/capabilities | H | 无 | 200：模型、版本、流式与编排能力 |
| POST | /api/agent/plan | H | JSON：PlanRequest | 200：只规划，不执行工具 |
| POST | /api/agent/compile | H | JSON：RequestPlan | 200：校验、CompiledPlan IR、首个决策 |
| POST | /api/agent/chat | H | JSON：AgentChatRequest；可带 X-Request-ID | 200：同步 AgentChatResponse |
| POST | /api/agent/chat/stream | H | JSON：AgentChatRequest；可带 X-Request-ID | 200 text/event-stream：SSE 事件流 |
| POST | /api/agent/upload | H | multipart/form-data：file | 200：file_key、原文件名、大小 |

### Agent 运行链路

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/agent/runs | H | Query：时间、状态、模型、工具、失败类、limit | 200：本院运行摘要列表 |
| GET | /api/agent/runs/metrics | H | Query：与列表相同（无 limit） | 200：延迟、成功率、工具和模型指标 |
| GET | /api/agent/runs/{traceId} | H | Path：traceId | 200：完整安全链路、节点、Evidence、耗时 |

### 知识库、DBHub 与元数据

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/mcp/dbhub/sources | 内部 | 无 | 200：DBHub 来源；400：sidecar 失败 |
| GET | /api/kb/rules/search | H | Query：query, limit=5（1~20） | 200：Wiki 指标匹配列表 |
| GET | /api/kb/rules/{ruleId}/effective | H | Path：ruleId；Query：hospital_id? | 200：本院生效口径；404：未找到 |
| GET | /api/metadata/overview | H | Query：hospital_id?, db_name? | 200：元数据快照、变化和受影响规则 |
| POST | /api/metadata/sync | H | JSON：hospital_id, db_name, source | 200：同步结果；502：DBHub 失败 |

### 指标明细与 Excel 导出

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| POST | /api/sql-runs/{run_id}/details | H | Path：run_id | 200 复用/201 新建：明细快照 |
| GET | /api/sql-runs/{run_id}/details/{group} | H | Path：group；Query：page, page_size | 200：明细分页 |
| POST | /api/sql-runs/{run_id}/exports | H | JSON：confirmed | 201：分子/分母明细导出对象 |
| POST | /api/sql-runs/{run_id}/upload-comparison-exports | H | JSON：confirmed, file_token | 201：系统与上传文件差异导出对象 |
| GET | /api/indicator-exports | H | 无 | 200：当前医院导出对象数组 |
| GET | /api/indicator-exports/{export_id}/download | H | Path：export_id | 200：XLSX 二进制附件 |

### 指标实施草稿

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/indicator-drafts | H | Query：hospital_id, status? | 200：草稿数组 |
| GET | /api/indicator-drafts/{draftId} | H | Path：draftId | 200：草稿；404：不存在 |
| GET | /api/indicator-drafts/{draftId}/versions | H | Path：draftId | 200：{items: 版本数组} |
| PUT | /api/indicator-drafts/{draftId} | H | JSON：expected_version, changes, actor_id? | 200：更新后的草稿 |
| POST | /api/indicator-drafts/{draftId}/requirements-confirm | H | JSON：expected_version, actor_id? | 200：需求确认后的草稿 |
| POST | /api/indicator-drafts/{draftId}/submit | H | JSON：expected_version, actor_id? | 200：提交后的草稿 |
| GET | /api/indicator-drafts/{draftId}/metadata-suggestions | H | Path：draftId | 200：字段候选、缺失和歧义 |
| POST | /api/indicator-drafts/{draftId}/metadata-confirm | H | JSON：expected_version, mappings | 200：确认映射后的草稿 |
| POST | /api/indicator-drafts/{draftId}/sql-generate | H | JSON：expected_version | 200：含 SQL 对象的草稿 |
| POST | /api/indicator-drafts/{draftId}/trial-run | H | JSON：expected_version, stat_start_time, stat_end_time | 200：含试运行结果的草稿 |

### 指标审批与版本治理

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| POST | /api/indicator-drafts/{draftId}/approve | A+H | JSON：hospital_id, expected_version, reason? | 200：审批发布结果 |
| POST | /api/indicator-drafts/{draftId}/reject | A+H | JSON：hospital_id, expected_version, reason | 200：驳回结果 |
| GET | /api/hospital-defined/{hospitalId}/{indexCode}/versions | A+H | Path：hospitalId, indexCode | 200：活动版本和历史版本 |
| POST | /api/hospital-defined/{hospitalId}/{indexCode}/restore | A+H | JSON：version | 200：恢复结果 |

### 术语读取与治理

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| GET | /api/terminology/concepts | H | Query：query?, concept_type?, rule_id? | 200：{items, total} |
| GET | /api/terminology/concepts/{conceptCode} | H | Path：conceptCode；Query：hospital_id? | 200：概念详情、别名、映射和发布版本 |
| POST | /api/terminology/test | H | JSON：hospital_id, text（1~1000 字） | 200：归一化与歧义结果 |
| GET | /api/terminology/releases | H | 无 | 200：{items: 发布记录} |
| POST | /api/terminology/aliases | A+H | JSON：AliasRequest | 200：新建别名 |
| POST | /api/terminology/aliases/{aliasId}/approve | A+H | JSON：actor_id? | 200：审核后的别名 |
| POST | /api/terminology/hospital-mappings | A+H | JSON：MappingRequest | 200：新建医院映射 |
| POST | /api/terminology/hospital-mappings/{mappingId}/approve | A+H | JSON：actor_id? | 200：审核后的映射 |
| POST | /api/terminology/releases/publish | A | JSON：actor_id? | 200：新发布版本 |
| POST | /api/terminology/releases/{releaseId}/restore | A | JSON：actor_id? | 200：恢复结果 |

### 指标监控与告警

| 方法 | 路径 | 鉴权 | 请求 | 成功响应/用途 |
| --- | --- | --- | --- | --- |
| POST | /api/monitoring/plans | A+H | JSON：PlanRequest | 200：新建计划 |
| GET | /api/monitoring/plans | A+H | Query：hospital_id | 200：{items: 计划数组} |
| PUT | /api/monitoring/plans/{planId} | A+H | JSON：PlanRequest | 200：更新后的计划 |
| POST | /api/monitoring/plans/{planId}/enable | A+H | Query：hospital_id | 200：启用后的计划 |
| POST | /api/monitoring/plans/{planId}/disable | A+H | Query：hospital_id | 200：停用后的计划 |
| POST | /api/monitoring/plans/{planId}/run | A+H | JSON：hospital_id, stat_period? | 200：手工执行结果 |
| GET | /api/monitoring/results | A+H | Query：hospital_id, rule_id?, limit=100 | 200：{items: 结果数组} |
| GET | /api/monitoring/results/{resultId} | A+H | Path：resultId；Query：hospital_id | 200：单次结果 |
| GET | /api/monitoring/alerts | A+H | Query：hospital_id, status?, limit=100 | 200：{items: 告警数组} |
| POST | /api/monitoring/alerts/{alertId}/acknowledge | A+H | JSON：hospital_id, actor_id? | 200：已确认告警 |
| POST | /api/monitoring/alerts/{alertId}/close | A+H | JSON：hospital_id, actor_id? | 200：已关闭告警 |
| POST | /api/monitoring/alerts/{alertId}/diagnose | A+H | JSON：hospital_id, actor_id? | 200：诊断后的告警 |
| GET | /api/monitoring/scheduler/status | A | 无 | 200：调度器状态 |
| POST | /api/monitoring/scheduler/scan | A | 无 | 200：本次扫描结果 |

## 3. 核心接口详细契约

### 3.1 医院登录

**POST /api/auth/hospital/login**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| account_id | string | 是 | 医院账号，最长 64 字符 |
| password | string | 是 | 密码，最长 256 字符；不得记录日志 |

**成功响应 LoginResponse：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| token | string | 是 | Bearer token；仅保存在会话存储或安全内存 |
| token_type | string | 是 | 固定为 bearer |
| expires_at | datetime | 是 | 会话到期时间；默认 8 小时 |
| user_id | string | 是 | 业务用户编号 |
| account_id | string | 是 | 登录账号 |
| hospital_id | string | 是 | 令牌绑定的医院范围 |
| permissions | string[] | 是 | 权限集合，例如明细查看/导出权限 |
| must_change_password | boolean | 是 | 为 true 时必须先进入修改密码流程 |

**前端处理** 收到 401 时清理医院 token 并回到登录页；must_change_password=true 时不要进入主工作台。

### 3.2 AgentChatRequest

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| query | string | 是 | 用户问题，去除首尾空白后 1~5000 字符 |
| session_id | string | 否 | 会话编号，1~128 字符；跨轮必须稳定复用 |
| model_id | string | 否 | 来自 capabilities.models[].id；空值使用默认模型 |
| file_key | string | 否 | 上传接口返回的 file_key；不得包含 / 或 \ |

```json
{
  "query": "患者入院48小时内转科的比例从一月份到现在是多少？",
  "session_id": "session_6e0a3d1b",
  "model_id": "deepseek-v4-flash",
  "file_key": null
}
```

### 3.3 同步对话

**POST /api/agent/chat**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| answer | string | 是 | 最终业务回答 |
| stop_reason | string | 是 | final_answer / clarification / runtime_error 等 |
| trace_id | string | 是 | 查看链路的主键 |
| session_id | string | 是 | 实际会话编号 |
| step_count | integer | 是 | 本轮执行步骤数 |

同步接口适合自动化测试和低频管理调用；聊天页面应优先使用 SSE 流式接口。

### 3.4 能力与模型列表

**GET /api/agent/capabilities**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| enabled | boolean | 是 | Agent 是否启用 |
| model / default_model | string | 是 | 默认模型 ID |
| models | AgentModel[] | 是 | 每项含 id、name、provider、model、thinking、available |
| streaming | boolean | 是 | 是否支持 SSE |
| max_steps | integer | 是 | 单子任务最大受控步骤 |
| orchestration | string | 是 | 当前为 compiled_plan_deterministic |
| versions | object | 是 | RequestPlan、IR、Prompt、Evidence、Verifier 等版本 |
| automatic_tool_loop | boolean | 是 | 当前为 false；工具由服务端计划控制 |

**模型选择器** 只展示 available=true 的模型；刷新 capabilities 时不要无条件覆盖用户已经选择的 model_id。

### 3.5 文件上传

**POST /api/agent/upload**

请求：multipart/form-data，字段名固定为 file。当前仅支持真实 XLSX（ZIP 签名）文件。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file_key | string | 是 | 医院隔离的文件引用；后续放入 AgentChatRequest.file_key |
| file_name | string | 是 | 清洗后的原始文件名 |
| size_bytes | long | 是 | 上传大小 |

- 最大上传大小：10 MB。
- 解析安全限制：解压后 50 MB、最多 2000 个 ZIP 条目、每工作表最多 5001 行、最多 100 列。
- file_key 与医院绑定；不要让用户手工编辑，也不要复用其他医院返回的 file_key。

### 3.6 运行链路

GET /api/agent/runs/{traceId} 返回当前医院可访问的安全链路。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| nodes | object[] | 是 | 节点关系、类型、状态、耗时、工具、模型、安全输入输出 |
| evidence | object[] | 是 | 与结论关联的 Evidence 来源列表 |
| trace_version | string | 是 | 链路结构版本 |
| timing_summary | object | 是 | llm_ms、tool_ms、code_ms、storage_ms |
| final_status | string | 是 | success / incomplete / failed |

**列表与指标接口筛选参数：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| started_after | datetime | 否 | ISO 本地日期时间 |
| started_before | datetime | 否 | ISO 本地日期时间 |
| status | string | 否 | 按运行最终状态筛选 |
| model_id | string | 否 | 按模型筛选 |
| tool_name | string | 否 | 按工具筛选 |
| failure_class | string | 否 | 按失败类别筛选 |
| limit | integer | 否 | 仅列表接口；默认 100 |

### 3.7 指标明细与导出

**完整前端调用顺序：**

| 步骤 | 前端动作 |
| --- | --- |
| 1 | 从 Agent 回答/事件中取得 run_id。 |
| 2 | POST /api/sql-runs/{run_id}/details 创建或复用明细快照。 |
| 3 | GET .../details/denominator\|numerator\|unmatched 分页查看。 |
| 4 | 用户确认使用范围后，POST .../exports，body 传 {"confirmed": true}。 |
| 5 | GET /api/indicator-exports/{export_id}/download，以 Blob 下载 XLSX。 |

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| group | enum | 是 | denominator / numerator / unmatched |
| page | integer | 否 | 从 1 开始，默认 1 |
| page_size | integer | 否 | 只允许 20、50、100；默认 50 |
| confirmed | boolean | 导出时是 | 必须为 true，否则 400 |
| file_token | string | 对比导出时是 | 上传文件的 file_key |

**明细快照 SnapshotSummary 关键字段：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| snapshot_id / run_id | string | 是 | 快照和试运行对象编号 |
| rule_id / rule_name | string | 是 | 指标身份 |
| stat_start / stat_end | string | 是 | 统计区间 |
| denominator_count / numerator_count / unmatched_count | integer | 是 | 三类数量 |
| columns | DetailColumn[] | 是 | field、label、sensitivity |
| created_at / expires_at | instant | 是 | 快照创建和过期时间 |
| reused | boolean | 是 | 是否复用既有快照 |
| source_database / source_tables | string / string[] | 是 | 安全来源摘要 |

**权限** 分页查看需要 indicator_detail_view；创建、列举和下载导出需要 indicator_detail_export。导出文件默认 24 小时过期，过期返回 410。

## 4. 业务工作台接口契约

### 4.1 Planner 与 Plan Compiler（调试接口）

这两个接口均返回 status=shadow_only，禁止执行工具；适合提示词、计划和 IR 调试，不应代替正式聊天接口。

**POST /api/agent/plan 请求字段：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| query | string | 是 | 1~4000 字符 |
| model_id | string | 否 | Planner 使用的模型 |
| structured_state | string | 否 | 最多 16000 字符的结构化状态 |
| recent_history | string | 否 | 最多 32000 字符的近期对话 |

**POST /api/agent/compile 直接接收 RequestPlan：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| schema_version | string | 否 | 默认 request-plan-v1 |
| intent | enum | 是 | general_chat、rule_explanation、indicator_sql_prepare、indicator_trial_run、indicator_diagnosis、rule_change_preview、upload_analysis、implementation_validation、unknown |
| goal | string | 是 | 1~500 字符 |
| target_indicator | object | 是 | raw_name、rule_id |
| time_expression | object | 是 | raw_text、start_time、end_time |
| requested_outputs | enum[] | 是 | definition、formula、implementation_status、prepared_sql_handle、trial_result、diagnosis、change_preview、file_analysis、implementation_validation_report、explanation |
| constraints | string[] | 否 | 执行约束 |
| semantic_ambiguities | object[] | 否 | field、description |

### 4.2 Wiki 规则读取

GET /api/kb/rules/search 的 matches[] 每项包含 rule_id、rule_name、category、content、type=wiki_rule、path。resolved_rule_id 是首个候选。

GET /api/kb/rules/{ruleId}/effective 返回字段较多，前端建议只依赖下列稳定业务字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| rule_id / rule_name / category | string | 是 | 指标身份 |
| effective_level | string | 是 | hospital / company 等 |
| definition / formula | string | 是 | 本院生效定义与公式 |
| numerator_rule / denominator_rule | string | 是 | 分子分母口径 |
| filter_rule / exclude_rule | string | 是 | 纳入与排除规则 |
| field_status / sql_status | string | 是 | 字段映射和 SQL 可用状态 |
| effective_params | object | 是 | 本院生效参数 |
| national_version / hospital_version | string/integer | 否 | 规则版本 |
| rule_source | string | 是 | 当前为 wiki |
| warnings / relations | array/object | 是 | 口径警告与关联关系 |

### 4.3 元数据工作台

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| hospital_id | string | 是 | 必须与医院令牌一致 |
| db_name | string | 否 | 业务数据库名；空值使用配置 |
| source | string | 否 | 当前前端传 dbhub |

MetadataOverview 返回 hospital_id、db_name、source_id、has_snapshot、metadata_source、batch_id、synced_at、table_count、column_count、changes、affected_rules、trace_id。

**依赖** POST /api/metadata/sync 需要 DBHub sidecar 可用；不可用时返回 502，前端应提示检查 DBHub，而不是提示用户重新登录。

### 4.4 指标实施草稿

草稿接口使用乐观锁 expected_version。前端每次成功后必须用响应中的 current_version 替换本地版本，冲突时刷新草稿再重试。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| draft_id | string | 是 | 草稿编号 |
| hospital_id | string | 是 | 医院范围 |
| proposed_index_code / index_name | string | 是 | 拟发布指标编号与名称 |
| index_type / index_desc / stat_cycle | string | 是 | 基础定义 |
| numerator_rule / denominator_rule | string | 是 | 分子分母口径 |
| filter_rule / exclude_rule | string | 否 | 纳入与排除 |
| metric_type | enum | 是 | ratio / count |
| metadata_requirements | string[] | 是 | 所需业务字段 |
| field_mapping / sql_plan / sql_params | object | 是 | 实施配置 |
| current_sql / sql_id | string | 否 | 受控 SQL 及安全对象编号 |
| trial_result | object | 是 | 试运行结果 |
| status / current_version | string/integer | 是 | 状态与乐观锁版本 |
| formal_index_code / updated_at | string | 否 | 发布后编号和更新时间 |

MetadataConfirmRequest.mappings 的值是候选对象，常用字段为 db_name、table_name、column_name、data_type、confidence、reason。

### 4.5 指标审批与版本治理

审批、驳回和医院自定义版本恢复均使用 A+H 双令牌。request.hospital_id 或路径 hospitalId 必须与医院 token 一致。

**actor_id** 若请求体包含 actor_id，当前控制器仍以已认证主体或固定 admin 作为审计操作者；前端不要依赖自行提交的 actor_id 改变审计身份。

### 4.6 术语工作台

只读接口使用医院令牌；别名、医院映射需要双令牌；发布/恢复词表只需要管理员令牌。

**AliasRequest：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| hospital_id | string | 否 | 空表示全局别名；非空必须与医院令牌一致 |
| concept_code | string | 是 | 标准概念编码 |
| alias_text | string | 是 | 别名文本 |
| relation_type | string | 是 | 别名关系类型 |
| retrieval_enabled | boolean | 否 | 默认 true |
| sql_safe | boolean | 否 | 是否允许进入 SQL 安全映射；默认 false |
| ambiguity_group | string | 否 | 歧义分组 |
| source_reference | string | 否 | 来源说明 |

**MappingRequest：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| hospital_id | string | 是 | 医院范围 |
| concept_code | string | 是 | 标准概念编码 |
| code_system | string | 是 | 本地编码体系 |
| local_code / local_name / local_value | string | 是 | 本地值 |
| effective_from / effective_to | string | 否 | 有效期 |

术语识别响应 TerminologyNormalization 包含 original_text、normalized_text、matches、ambiguities、release_version、duration_ms、sql_eligible。

### 4.7 监控计划与告警

除调度器 status/scan 为管理员令牌外，其余监控接口均使用 A+H 双令牌。列表 limit 会限制在 1~500。

**PlanRequest：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| plan_id | string | 更新时 | 计划编号 |
| hospital_id / rule_id / plan_name | string | 是 | 医院、指标与计划名 |
| frequency | enum | 是 | daily / monthly |
| run_time | string | 是 | 每日执行时间 |
| day_of_month | integer | 月度时 | 月度执行日 |
| timezone | string | 否 | 建议 Asia/Shanghai |
| mom_enabled / yoy_enabled | boolean | 否 | 环比/同比告警开关 |
| mom_threshold_pct / yoy_threshold_pct | number | 开关开启时 | 变化阈值百分比 |
| created_by | string | 否 | 当前审计身份仍以认证主体为准 |

MonitoringPlan 常用响应字段：plan_id、hospital_id、rule_id、plan_name、frequency、run_time、day_of_month、timezone、mom_enabled、mom_threshold_pct、yoy_enabled、yoy_threshold_pct、status、next_run_at、last_run_at。

MonitoringResult 常用字段：id、rule_id、stat_period、result_value、run_status、trigger_type、duration_ms、is_abnormal、created_at、error_message。

MonitoringAlert 常用字段：alert_id、rule_id、alert_type、alert_level、conclusion_code、current_value、mom_change_rate、yoy_change_rate、diagnose_status、status、created_at。

## 5. 通用响应模型与错误语义

### 5.1 错误响应形态

| 来源 | JSON 形态 | 前端取值顺序 |
| --- | --- | --- |
| 通用参数/鉴权 | {"detail":"中文说明"} | detail |
| Planner/模型/实施/监控/术语治理 | {"detail":"中文说明","code":"稳定错误码"} | detail → code |
| 指标明细 | {"detail":{"code":"...","message":"..."}} | detail.message → detail.code |
| Spring 默认错误 | 可能含 status、error、path、message | detail → message → error → HTTP 状态 |

```javascript
async function readJson(response) {
  const data = await response.json().catch(() => ({}))
  if (response.ok) return data
  const detail = data.detail
  const message = typeof detail === 'string' ? detail : detail?.message
  throw new Error(message || data.message || `请求失败（HTTP ${response.status}）`)
}
```

### 5.2 常见状态码

| 状态码 | 含义 | 前端处理 |
| --- | --- | --- |
| 200 | 查询、更新或执行成功 | 按 JSON/二进制契约处理 |
| 201 | 新建明细快照或导出对象 | 保存新对象编号 |
| 204 | 医院登出成功 | 不要调用 response.json() |
| 400 | 业务参数错误、未确认导出、DBHub 来源失败 | 显示 detail；保留用户输入 |
| 401 | 未登录、token 缺失或失效 | 清理对应 token；回到登录 |
| 403 | 跨医院或权限不足 | 禁止重试；提示联系管理员 |
| 404 | 本院范围内对象不存在 | 显示空状态或要求重新生成 |
| 409 | 对象尚未就绪、校验冲突或版本冲突 | 刷新对象/版本后再操作 |
| 410 | 导出文件过期 | 重新创建导出 |
| 422 | 请求结构、字段长度、枚举或页大小不符合约束 | 定位表单字段；不要原样重试 |
| 502 | DBHub/业务库侧依赖失败 | 提示检查 sidecar/连接；保留前端状态 |
| 503 | 模型不可用、明细生成依赖不可用 | 允许稍后重试或切换可用模型 |
| 500 | 未预期服务端错误 | 展示通用提示并保留 trace/request id |

### 5.3 前端类型定义建议

```typescript
interface AgentEvent {
  event: string
  trace_id: string
  step?: number
  step_count?: number
  tool_name?: string
  status?: string
  code?: string
  message?: string
  stop_reason?: string
  duration_ms?: number
  reused?: boolean
  retryable?: boolean
}
```

**兼容策略** 响应新增字段时前端应安全忽略；请求端则必须只发送当前契约字段，因为后端拒绝未知 JSON 属性。

## 6. 前端联调流程与验收清单

### 6.1 会话与模型

- 登录后刷新 capabilities；模型不可用时禁用该选项。
- session_id 在“新会话”之前保持不变；新会话时同时清空 latestFileKey。
- 发送请求前不得再次刷新 capabilities 并覆盖用户模型选择。
- SSE 连接结束但未收到正文时，显示明确空结果提示；收到 agent_error 时标记失败。

### 6.2 明细与文件

- 上传成功后保存 file_key，不从聊天气泡文本反向解析文件编号。
- 明细按钮必须绑定具体 run_id；多指标回答要区分每个子任务的 run_id。
- 导出前展示患者明细用途确认；confirmed=false 不应发送创建导出请求。
- 下载使用 response.blob()，文件名优先取创建导出响应的 file_name。
- 对比导出必须同时传 run_id 和上传 file_token。

### 6.3 权限与错误

- 医院 token 与管理员 token 分开保存，双令牌接口不得颠倒请求头。
- 403 不做自动重试；401 只清理对应类型的 token。
- DBHub 502、模型 503、业务对象 404/410 使用不同的下一步提示。
- 所有失败保留用户表单、当前会话和 trace_id，禁止直接清空页面。
- 前端日志不得记录 password、Authorization、X-Hospital-Authorization、患者明细行或 SQL 正文。

### 6.4 最小联调验收

- □ 医院登录、强制改密、登出与 token 失效处理正常。
- □ 能力接口能稳定渲染 4B、8B 和 DeepSeek 等可用模型。
- □ SSE 能显示 tool_call/tool_result，并只渲染一次最终回答。
- □ 多轮 session_id 保持一致；上传文件 file_key 能进入下一轮。
- □ 查看链路只读取当前医院 trace，历史字段缺失时页面可降级。
- □ 指标明细三种 group 分页、创建导出和 Blob 下载正常。
- □ A+H 接口在缺少任一 token、交换 token、跨医院时均被拒绝。
- □ 422/502/503/410 等状态均有明确中文下一步。

## 附录 A：基础设施与 SPA 路由

### A.1 Actuator

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /actuator/health | Spring Boot 健康端点；部署探针使用 |
| GET | /actuator/info | Spring Boot 信息端点；是否有内容取决于部署配置 |

### A.2 Vue 3 SPA 回退路由

下列 GET 路径由 SpaForwardController 转发到 index.html，不是 JSON API：

| 路径 | 前端页面 |
| --- | --- |
| / | Agent 当前会话 |
| /runs | Agent 运行观察 |
| /metadata | 数据库元数据 |
| /terminology | 医学术语 |
| /monitoring | 指标监控 |
| /implementation | 指标实施 |

### A.3 源码核对范围

本文件基于以下当前源码入口核对：

- backend-java/src/main/java/com/hospital/wikiagent/api/*Controller.java
- backend-java/src/main/java/com/hospital/wikiagent/contract/*.java
- backend-java/src/main/java/com/hospital/wikiagent/details/DetailContracts.java
- backend-java/src/main/resources/application.yml
- frontend-vue/src/api/agent.ts
**维护要求** Controller、请求 Record、全局命名策略或状态码发生变化时，应同步更新本文件；前端以实际运行环境的 /api/agent/capabilities 和错误响应为动态能力来源。
