# Wiki Project 全量后端接口文档

> 基于当前 Java 17 / Spring Boot 3.5.16 源码生成，格式参照《质量风险预警 - 查询预警列表接口文档》。

## 文档基本信息

| 项目 | 说明 |
|------|------|
| 接口基线 | 当前 `backend-java` Controller 与请求 Record |
| 生成日期 | 2026-07-23（Asia/Shanghai） |
| 服务地址 | `http://127.0.0.1:8766`；同源部署时前端使用相对路径 |
| 接口数量 | 65 个 `/api` 操作 |
| JSON 命名 | 统一 `snake_case`；未知请求字段返回 422 |
| 统计区间 | 统一按左闭右开 `[start_time, end_time)` 解释 |

## 认证方式

| 标记 | 请求头 | 说明 |
|------|------|------|
| 公开 | 无需认证 | 登录与健康检查 |
| H | Authorization: Bearer <hospital_token> | 医院用户接口 |
| A | Authorization: Bearer <admin_token> | 管理员接口 |
| A+H | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> | 管理员操作指定医院数据，双令牌缺一不可 |
| 内部 | 当前代码未校验令牌；生产环境应由网关限制访问 | 基础设施探测 |

## Excel 上传接口特别说明

`POST /api/agent/upload` 的请求入参只有 multipart 字段 **`file`**。前端不需要额外传 `file_name` 或 `size_bytes`：

- `file_name` 来自 multipart 文件本身的原始文件名，由服务端读取后返回。
- `size_bytes` 由服务端根据实际收到的字节数计算后返回。
- 不要手工设置 `Content-Type`，浏览器会为 `FormData` 自动生成 multipart boundary。

```typescript
const formData = new FormData()
formData.append('file', file)
await fetch('/api/agent/upload', {
  method: 'POST',
  headers: { Authorization: `Bearer ${hospitalToken}` },
  body: formData
})
```

## 接口目录

1. `GET /api/health` - 系统状态 - 最小健康状态与编排模式
2. `GET /api/runtime/status` - 系统状态 - Java/Vue/Wiki/SQLite/DBHub 运行时摘要
3. `POST /api/auth/hospital/login` - 登录与会话 - 医院 token、主体、权限、到期时间
4. `POST /api/auth/hospital/change-password` - 登录与会话 - 换发后的登录响应
5. `POST /api/auth/hospital/logout` - 登录与会话 - 无响应体
6. `POST /api/admin/login` - 登录与会话 - 管理员 token、message
7. `POST /api/admin/logout` - 登录与会话 - message
8. `GET /api/agent/capabilities` - Agent 核心 - 模型、版本、流式与编排能力
9. `POST /api/agent/plan` - Agent 核心 - 只规划，不执行工具
10. `POST /api/agent/compile` - Agent 核心 - 校验、CompiledPlan IR、首个决策
11. `POST /api/agent/chat` - Agent 核心 - 同步 AgentChatResponse
12. `POST /api/agent/chat/stream` - Agent 核心 - SSE 事件流
13. `POST /api/agent/upload` - Agent 核心 - file_key、原文件名、大小
14. `GET /api/agent/runs` - Agent 运行链路 - 本院运行摘要列表
15. `GET /api/agent/runs/metrics` - Agent 运行链路 - 延迟、成功率、工具和模型指标
16. `GET /api/agent/runs/{traceId}` - Agent 运行链路 - 完整安全链路、节点、Evidence、耗时
17. `GET /api/mcp/dbhub/sources` - 知识库、DBHub 与元数据 - DBHub 来源；400：sidecar 失败
18. `GET /api/kb/rules/search` - 知识库、DBHub 与元数据 - Wiki 指标匹配列表
19. `GET /api/kb/rules/{ruleId}/effective` - 知识库、DBHub 与元数据 - 本院生效口径；404：未找到
20. `GET /api/metadata/overview` - 知识库、DBHub 与元数据 - 元数据快照、变化和受影响规则
21. `POST /api/metadata/sync` - 知识库、DBHub 与元数据 - 同步结果；502：DBHub 失败
22. `POST /api/sql-runs/{run_id}/details` - 指标明细与 Excel 导出 - 明细快照
23. `GET /api/sql-runs/{run_id}/details/{group}` - 指标明细与 Excel 导出 - 明细分页
24. `POST /api/sql-runs/{run_id}/exports` - 指标明细与 Excel 导出 - 分子/分母明细导出对象
25. `POST /api/sql-runs/{run_id}/upload-comparison-exports` - 指标明细与 Excel 导出 - 系统与上传文件差异导出对象
26. `GET /api/indicator-exports` - 指标明细与 Excel 导出 - 当前医院导出对象数组
27. `GET /api/indicator-exports/{export_id}/download` - 指标明细与 Excel 导出 - XLSX 二进制附件
28. `GET /api/indicator-drafts` - 指标实施草稿 - 草稿数组
29. `GET /api/indicator-drafts/{draftId}` - 指标实施草稿 - 草稿；404：不存在
30. `GET /api/indicator-drafts/{draftId}/versions` - 指标实施草稿 - {items: 版本数组}
31. `PUT /api/indicator-drafts/{draftId}` - 指标实施草稿 - 更新后的草稿
32. `POST /api/indicator-drafts/{draftId}/requirements-confirm` - 指标实施草稿 - 需求确认后的草稿
33. `POST /api/indicator-drafts/{draftId}/submit` - 指标实施草稿 - 提交后的草稿
34. `GET /api/indicator-drafts/{draftId}/metadata-suggestions` - 指标实施草稿 - 字段候选、缺失和歧义
35. `POST /api/indicator-drafts/{draftId}/metadata-confirm` - 指标实施草稿 - 确认映射后的草稿
36. `POST /api/indicator-drafts/{draftId}/sql-generate` - 指标实施草稿 - 含 SQL 对象的草稿
37. `POST /api/indicator-drafts/{draftId}/trial-run` - 指标实施草稿 - 含试运行结果的草稿
38. `POST /api/indicator-drafts/{draftId}/approve` - 指标审批与版本治理 - 审批发布结果
39. `POST /api/indicator-drafts/{draftId}/reject` - 指标审批与版本治理 - 驳回结果
40. `GET /api/hospital-defined/{hospitalId}/{indexCode}/versions` - 指标审批与版本治理 - 活动版本和历史版本
41. `POST /api/hospital-defined/{hospitalId}/{indexCode}/restore` - 指标审批与版本治理 - 恢复结果
42. `GET /api/terminology/concepts` - 术语读取与治理 - {items, total}
43. `GET /api/terminology/concepts/{conceptCode}` - 术语读取与治理 - 概念详情、别名、映射和发布版本
44. `POST /api/terminology/test` - 术语读取与治理 - 归一化与歧义结果
45. `GET /api/terminology/releases` - 术语读取与治理 - {items: 发布记录}
46. `POST /api/terminology/aliases` - 术语读取与治理 - 新建别名
47. `POST /api/terminology/aliases/{aliasId}/approve` - 术语读取与治理 - 审核后的别名
48. `POST /api/terminology/hospital-mappings` - 术语读取与治理 - 新建医院映射
49. `POST /api/terminology/hospital-mappings/{mappingId}/approve` - 术语读取与治理 - 审核后的映射
50. `POST /api/terminology/releases/publish` - 术语读取与治理 - 新发布版本
51. `POST /api/terminology/releases/{releaseId}/restore` - 术语读取与治理 - 恢复结果
52. `POST /api/monitoring/plans` - 指标监控与告警 - 新建计划
53. `GET /api/monitoring/plans` - 指标监控与告警 - {items: 计划数组}
54. `PUT /api/monitoring/plans/{planId}` - 指标监控与告警 - 更新后的计划
55. `POST /api/monitoring/plans/{planId}/enable` - 指标监控与告警 - 启用后的计划
56. `POST /api/monitoring/plans/{planId}/disable` - 指标监控与告警 - 停用后的计划
57. `POST /api/monitoring/plans/{planId}/run` - 指标监控与告警 - 手工执行结果
58. `GET /api/monitoring/results` - 指标监控与告警 - {items: 结果数组}
59. `GET /api/monitoring/results/{resultId}` - 指标监控与告警 - 单次结果
60. `GET /api/monitoring/alerts` - 指标监控与告警 - {items: 告警数组}
61. `POST /api/monitoring/alerts/{alertId}/acknowledge` - 指标监控与告警 - 已确认告警
62. `POST /api/monitoring/alerts/{alertId}/close` - 指标监控与告警 - 已关闭告警
63. `POST /api/monitoring/alerts/{alertId}/diagnose` - 指标监控与告警 - 诊断后的告警
64. `GET /api/monitoring/scheduler/status` - 指标监控与告警 - 调度器状态
65. `POST /api/monitoring/scheduler/scan` - 指标监控与告警 - 本次扫描结果

## 1. 系统状态 - 最小健康状态与编排模式

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/health` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | 无需认证 |
| API ID | wiki-agent-system-0001 |
| 版本 | v1 |
| 用途 | 200：最小健康状态与编排模式 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "status": "ok",
  "service": "wiki-agent-java"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 是 | 状态 |
| service | String | 是 | service 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/health'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 2. 系统状态 - Java/Vue/Wiki/SQLite/DBHub 运行时摘要

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/runtime/status` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | 无需认证 |
| API ID | wiki-agent-system-0002 |
| 版本 | v1 |
| 用途 | 200：Java/Vue/Wiki/SQLite/DBHub 运行时摘要 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "status": "ok",
  "runtime": "java",
  "rule_source": "wiki",
  "database": "sqlite",
  "dbhub": "available"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 是 | 状态 |
| runtime | String | 是 | runtime 字段，含义见业务对象 |
| rule_source | String | 是 | rule_source 字段，含义见业务对象 |
| database | String | 是 | database 字段，含义见业务对象 |
| dbhub | String | 是 | dbhub 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/runtime/status'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 3. 登录与会话 - 医院 token、主体、权限、到期时间

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/auth/hospital/login` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | 无需认证 |
| API ID | wiki-agent-auth-0003 |
| 版本 | v1 |
| 用途 | 200：医院 token、主体、权限、到期时间 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| account_id | String | 是 | RequestBody | 医院登录账号，最长 64 字符 |
| password | String | 是 | RequestBody | 医院登录密码，最长 256 字符 |

#### RequestBody 示例

```json
{
  "account_id": "hospital_user",
  "password": "<password>"
}
```

### 响应参数

```json
{
  "token": "<hospital_token>",
  "token_type": "bearer",
  "expires_at": "2026-07-23T18:00:00",
  "user_id": "user_001",
  "account_id": "hospital_user",
  "hospital_id": "hospital_001",
  "permissions": [
    "indicator_read"
  ],
  "must_change_password": false
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| token | String | 是 | 访问令牌 |
| token_type | String | 是 | 令牌类型 |
| expires_at | String | 是 | 过期时间 |
| user_id | String | 是 | 用户编号 |
| account_id | String | 是 | 登录账号 |
| hospital_id | String | 是 | 医院编号 |
| permissions | Array<Object> | 是 | 权限集合 |
| must_change_password | Boolean | 是 | 是否必须修改密码 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/auth/hospital/login' \
  -H 'Content-Type: application/json' \
  -d '{"account_id":"hospital_user","password":"<password>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 4. 登录与会话 - 换发后的登录响应

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/auth/hospital/change-password` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-auth-0004 |
| 版本 | v1 |
| 用途 | 200：换发后的登录响应 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| current_password | String | 是 | RequestBody | 当前密码 |
| new_password | String | 是 | RequestBody | 新密码，8～256 字符 |

#### RequestBody 示例

```json
{
  "current_password": "<current_password>",
  "new_password": "<new_password>"
}
```

### 响应参数

```json
{
  "token": "<hospital_token>",
  "token_type": "bearer",
  "expires_at": "2026-07-23T18:00:00",
  "user_id": "user_001",
  "account_id": "hospital_user",
  "hospital_id": "hospital_001",
  "permissions": [
    "indicator_read"
  ],
  "must_change_password": false
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| token | String | 是 | 访问令牌 |
| token_type | String | 是 | 令牌类型 |
| expires_at | String | 是 | 过期时间 |
| user_id | String | 是 | 用户编号 |
| account_id | String | 是 | 登录账号 |
| hospital_id | String | 是 | 医院编号 |
| permissions | Array<Object> | 是 | 权限集合 |
| must_change_password | Boolean | 是 | 是否必须修改密码 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/auth/hospital/change-password' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"current_password":"<current_password>","new_password":"<new_password>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 5. 登录与会话 - 无响应体

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/auth/hospital/logout` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-auth-0005 |
| 版本 | v1 |
| 用途 | 204：无响应体 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```text
HTTP 204 No Content
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| — | — | — | HTTP 204，无响应体 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/auth/hospital/logout' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 6. 登录与会话 - 管理员 token、message

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/admin/login` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | 无需认证 |
| API ID | wiki-agent-auth-0006 |
| 版本 | v1 |
| 用途 | 200：管理员 token、message |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| password | String | 是 | RequestBody | 管理员密码 |

#### RequestBody 示例

```json
{
  "password": "<admin_password>"
}
```

### 响应参数

```json
{
  "token": "<admin_token>",
  "message": "登录成功"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| token | String | 是 | 访问令牌 |
| message | String | 是 | 提示信息 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/admin/login' \
  -H 'Content-Type: application/json' \
  -d '{"password":"<admin_password>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 7. 登录与会话 - message

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/admin/logout` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token> |
| API ID | wiki-agent-auth-0007 |
| 版本 | v1 |
| 用途 | 200：message |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "message": "已退出"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | String | 是 | 提示信息 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/admin/logout' \
  -H 'Authorization: Bearer <admin_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 8. Agent 核心 - 模型、版本、流式与编排能力

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/agent/capabilities` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0008 |
| 版本 | v1 |
| 用途 | 200：模型、版本、流式与编排能力 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "enabled": true,
  "default_model": "deepseek-v4-flash",
  "models": [
    {
      "id": "deepseek-v4-flash",
      "name": "DeepSeek V4 Flash",
      "provider": "openai",
      "available": true
    }
  ],
  "streaming": true,
  "max_steps": 12,
  "orchestration": "compiled_plan_deterministic"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| enabled | Boolean | 是 | enabled 字段，含义见业务对象 |
| default_model | String | 是 | default_model 字段，含义见业务对象 |
| models | Array<Object> | 是 | models 字段，含义见业务对象 |
| streaming | Boolean | 是 | streaming 字段，含义见业务对象 |
| max_steps | Long | 是 | max_steps 字段，含义见业务对象 |
| orchestration | String | 是 | orchestration 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/agent/capabilities' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 9. Agent 核心 - 只规划，不执行工具

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/plan` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0009 |
| 版本 | v1 |
| 用途 | 200：只规划，不执行工具 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| query | String | 是 | RequestBody | 待规划的用户问题，最长 4000 字符 |
| model_id | String | 否 | RequestBody | Planner 使用的模型编号 |
| structured_state | String | 否 | RequestBody | 结构化会话状态，最长 16000 字符 |
| recent_history | String | 否 | RequestBody | 最近对话，最长 32000 字符 |

#### RequestBody 示例

```json
{
  "query": "查询急会诊及时到位率",
  "model_id": "deepseek-v4-flash",
  "structured_state": "rule_id=MQSI2025_005",
  "recent_history": ""
}
```

### 响应参数

```json
{
  "status": "shadow_only",
  "model_id": "deepseek-v4-flash",
  "repaired": false,
  "request_plan": {
    "intent": "rule_explanation"
  },
  "validation": {
    "ok": true
  },
  "compiled_plan": {
    "plan_id": "PLAN_demo"
  },
  "tools_executed": false
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 是 | 状态 |
| model_id | String | 是 | model_id 字段，含义见业务对象 |
| repaired | Boolean | 是 | repaired 字段，含义见业务对象 |
| request_plan | Object | 是 | request_plan 字段，含义见业务对象 |
| validation | Object | 是 | validation 字段，含义见业务对象 |
| compiled_plan | Object | 是 | compiled_plan 字段，含义见业务对象 |
| tools_executed | Boolean | 是 | tools_executed 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/agent/plan' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"query":"查询急会诊及时到位率","model_id":"deepseek-v4-flash","structured_state":"rule_id=MQSI2025_005","recent_history":""}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 10. Agent 核心 - 校验、CompiledPlan IR、首个决策

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/compile` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0010 |
| 版本 | v1 |
| 用途 | 200：校验、CompiledPlan IR、首个决策 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| schema_version | String | 是 | RequestBody | 固定为 request-plan-v1 |
| intent | String | 是 | RequestBody | 业务意图枚举 |
| goal | String | 是 | RequestBody | 业务目标 |
| target_indicator | Object | 是 | RequestBody | 指标原文与可选 rule_id |
| time_expression | Object | 是 | RequestBody | 时间原文与可选绝对边界 |
| requested_outputs | String[] | 是 | RequestBody | 期望输出事实集合 |
| constraints | String[] | 否 | RequestBody | 执行约束 |
| semantic_ambiguities | Object[] | 否 | RequestBody | 语义歧义 |

#### RequestBody 示例

```json
{
  "schema_version": "request-plan-v1",
  "intent": "indicator_trial_run",
  "goal": "查询急会诊及时到位率实际结果",
  "target_indicator": {
    "raw_name": "急会诊及时到位率",
    "rule_id": "MQSI2025_005"
  },
  "time_expression": {
    "raw_text": "从1月到现在"
  },
  "requested_outputs": [
    "trial_result"
  ],
  "constraints": [],
  "semantic_ambiguities": []
}
```

### 响应参数

```json
{
  "validation": {
    "ok": true
  },
  "compiled_plan": {
    "plan_id": "PLAN_demo",
    "nodes": []
  },
  "first_decision": {
    "action": "EXECUTE_TOOL"
  }
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| validation | Object | 是 | validation 字段，含义见业务对象 |
| compiled_plan | Object | 是 | compiled_plan 字段，含义见业务对象 |
| first_decision | Object | 是 | first_decision 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/agent/compile' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"schema_version":"request-plan-v1","intent":"indicator_trial_run","goal":"查询急会诊及时到位率实际结果","target_indicator":{"raw_name":"急会诊及时到位率","rule_id":"MQSI2025_005"},"time_expression":{"raw_text":"从1月到现在"},"requested_outputs":["trial_result"],"constraints":[],"semantic_ambiguities":[]}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 11. Agent 核心 - 同步 AgentChatResponse

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/chat` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0011 |
| 版本 | v1 |
| 用途 | 200：同步 AgentChatResponse |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| query | String | 是 | RequestBody | 用户问题，1～5000 字符 |
| session_id | String | 否 | RequestBody | 会话编号；跨轮对话必须复用 |
| model_id | String | 否 | RequestBody | 模型编号；不传使用默认模型 |
| file_key | String | 否 | RequestBody | 上传接口返回的文件编号；不是原始文件名 |
| X-Request-ID | String | 否 | Header | 幂等和排障关联编号 |

#### RequestBody 示例

```json
{
  "query": "患者入院48小时内转科的比例从1月到现在是多少？",
  "session_id": "session_demo_001",
  "model_id": "deepseek-v4-flash",
  "file_key": "hospital_001_demo_指标.xlsx"
}
```

### 响应参数

```json
{
  "answer": "指标结果为 2.81%。",
  "stop_reason": "final_answer",
  "trace_id": "TRACE_demo",
  "session_id": "session_demo_001",
  "step_count": 3
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| answer | String | 是 | 最终回答 |
| stop_reason | String | 是 | 停止原因 |
| trace_id | String | 是 | 链路编号 |
| session_id | String | 是 | 会话编号 |
| step_count | Long | 是 | 工具步骤数 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/agent/chat' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"query":"患者入院48小时内转科的比例从1月到现在是多少？","session_id":"session_demo_001","model_id":"deepseek-v4-flash","file_key":"hospital_001_demo_指标.xlsx"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 12. Agent 核心 - SSE 事件流

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/chat/stream` |
| Content-Type | application/json |
| 响应类型 | text/event-stream |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0012 |
| 版本 | v1 |
| 用途 | 200 text/event-stream：SSE 事件流 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| query | String | 是 | RequestBody | 用户问题，1～5000 字符 |
| session_id | String | 否 | RequestBody | 会话编号；跨轮对话必须复用 |
| model_id | String | 否 | RequestBody | 模型编号；不传使用默认模型 |
| file_key | String | 否 | RequestBody | 上传接口返回的文件编号；不是原始文件名 |
| X-Request-ID | String | 否 | Header | 幂等和排障关联编号 |

#### RequestBody 示例

```json
{
  "query": "患者入院48小时内转科的比例从1月到现在是多少？",
  "session_id": "session_demo_001",
  "model_id": "deepseek-v4-flash",
  "file_key": "hospital_001_demo_指标.xlsx"
}
```

### 响应参数

```text
event: agent_start
data: {"trace_id":"TRACE_demo","status":"running"}

event: assistant_message
data: {"trace_id":"TRACE_demo","message":"回答内容"}

event: agent_done
data: {"trace_id":"TRACE_demo","status":"completed"}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| body | text/event-stream | 是 | 流式事件或文件二进制内容 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/agent/chat/stream' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"query":"患者入院48小时内转科的比例从1月到现在是多少？","session_id":"session_demo_001","model_id":"deepseek-v4-flash","file_key":"hospital_001_demo_指标.xlsx"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 13. Agent 核心 - file_key、原文件名、大小

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/upload` |
| Content-Type | multipart/form-data（浏览器自动生成 boundary） |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-agent-0013 |
| 版本 | v1 |
| 用途 | 200：file_key、原文件名、大小 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| file | File(.xlsx) | 是 | multipart/form-data | Excel 文件本体；字段名固定为 file，最大 10 MB |

> **注意**：请求表单只有 `file`。`file_name`、`size_bytes` 不属于请求参数。

### 响应参数

```json
{
  "file_key": "hospital_001_demo_指标数据.xlsx",
  "file_name": "指标数据.xlsx",
  "size_bytes": 2351
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file_key | String | 是 | 服务端文件编号，后续分析时传入 |
| file_name | String | 是 | 服务端读取的原始文件名，仅为响应字段 |
| size_bytes | Long | 是 | 服务端统计的文件字节数，仅为响应字段 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/agent/upload' \
  -H 'Authorization: Bearer <hospital_token>' \
  -F 'file=@指标数据.xlsx'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 14. Agent 运行链路 - 本院运行摘要列表

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/agent/runs` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-trace-0014 |
| 版本 | v1 |
| 用途 | 200：本院运行摘要列表 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| started_after | DateTime | 否 | Query | 运行开始时间下界（ISO-8601） |
| started_before | DateTime | 否 | Query | 运行开始时间上界（ISO-8601） |
| status | String | 否 | Query | 运行状态筛选 |
| model_id | String | 否 | Query | 模型编号筛选 |
| tool_name | String | 否 | Query | 工具名称筛选 |
| failure_class | String | 否 | Query | 统一失败分类筛选 |
| limit | Integer | 否 | Query | 返回条数上限，默认 100 |

### 响应参数

```json
{
  "items": [
    {
      "trace_id": "TRACE_demo",
      "status": "success",
      "model_id": "deepseek-v4-flash",
      "duration_ms": 1234
    }
  ],
  "total": 1
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |
| total | Long | 是 | 总数 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/agent/runs' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 15. Agent 运行链路 - 延迟、成功率、工具和模型指标

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/agent/runs/metrics` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-trace-0015 |
| 版本 | v1 |
| 用途 | 200：延迟、成功率、工具和模型指标 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| started_after | DateTime | 否 | Query | 运行开始时间下界（ISO-8601） |
| started_before | DateTime | 否 | Query | 运行开始时间上界（ISO-8601） |
| status | String | 否 | Query | 运行状态筛选 |
| model_id | String | 否 | Query | 模型编号筛选 |
| tool_name | String | 否 | Query | 工具名称筛选 |
| failure_class | String | 否 | Query | 统一失败分类筛选 |

### 响应参数

```json
{
  "request_count": 10,
  "success_rate": 0.9,
  "p50_ms": 1200,
  "p95_ms": 4500,
  "models": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| request_count | Long | 是 | request_count 字段，含义见业务对象 |
| success_rate | Number | 是 | success_rate 字段，含义见业务对象 |
| p50_ms | Long | 是 | p50_ms 字段，含义见业务对象 |
| p95_ms | Long | 是 | p95_ms 字段，含义见业务对象 |
| models | Array<Object> | 是 | models 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/agent/runs/metrics' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 16. Agent 运行链路 - 完整安全链路、节点、Evidence、耗时

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/agent/runs/{traceId}` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-trace-0016 |
| 版本 | v1 |
| 用途 | 200：完整安全链路、节点、Evidence、耗时 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| traceId | String | 是 | Path | 路径参数 traceId |

### 响应参数

```json
{
  "trace_id": "TRACE_demo",
  "nodes": [],
  "evidence": [],
  "timing_summary": {
    "llm_ms": 800,
    "tool_ms": 200
  },
  "final_status": "success"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| nodes | Array<Object> | 是 | nodes 字段，含义见业务对象 |
| evidence | Array<Object> | 是 | evidence 字段，含义见业务对象 |
| timing_summary | Object | 是 | timing_summary 字段，含义见业务对象 |
| final_status | String | 是 | final_status 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/agent/runs/<traceId>' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 17. 知识库、DBHub 与元数据 - DBHub 来源；400：sidecar 失败

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/mcp/dbhub/sources` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | 当前代码未校验令牌；生产环境应由网关限制访问 |
| API ID | wiki-agent-knowledge-0017 |
| 版本 | v1 |
| 用途 | 200：DBHub 来源；400：sidecar 失败 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "sources": [
    {
      "id": "dbhub",
      "status": "available"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sources | Array<Object> | 是 | sources 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/mcp/dbhub/sources'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 18. 知识库、DBHub 与元数据 - Wiki 指标匹配列表

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/kb/rules/search` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-knowledge-0018 |
| 版本 | v1 |
| 用途 | 200：Wiki 指标匹配列表 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| query | String | 是 | Query | 查询文本 |
| limit | Integer | 否 | Query | 返回条数上限 |

### 响应参数

```json
{
  "matches": [
    {
      "rule_id": "MQSI2025_005",
      "rule_name": "急会诊及时到位率",
      "category": "会诊制度"
    }
  ],
  "resolved_rule_id": "MQSI2025_005"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| matches | Array<Object> | 是 | 匹配结果 |
| resolved_rule_id | String | 是 | 唯一匹配时的规则编号 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/kb/rules/search?query=急会诊及时到位率' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 19. 知识库、DBHub 与元数据 - 本院生效口径；404：未找到

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/kb/rules/{ruleId}/effective` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-knowledge-0019 |
| 版本 | v1 |
| 用途 | 200：本院生效口径；404：未找到 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| ruleId | String | 是 | Path | 路径参数 ruleId |
| hospital_id | String | 否 | Query | 医院编号 |

### 响应参数

```json
{
  "rule_id": "MQSI2025_005",
  "rule_name": "急会诊及时到位率",
  "definition": "急会诊请求发出后及时到位的比例",
  "formula": "分子 ÷ 分母 × 100%",
  "numerator_rule": "20分钟内到位次数",
  "denominator_rule": "同期急会诊总次数",
  "effective_level": "hospital",
  "rule_source": "wiki"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| rule_name | String | 是 | rule_name 字段，含义见业务对象 |
| definition | String | 是 | definition 字段，含义见业务对象 |
| formula | String | 是 | formula 字段，含义见业务对象 |
| numerator_rule | String | 是 | numerator_rule 字段，含义见业务对象 |
| denominator_rule | String | 是 | denominator_rule 字段，含义见业务对象 |
| effective_level | String | 是 | effective_level 字段，含义见业务对象 |
| rule_source | String | 是 | rule_source 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/kb/rules/<ruleId>/effective' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 20. 知识库、DBHub 与元数据 - 元数据快照、变化和受影响规则

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/metadata/overview` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-knowledge-0020 |
| 版本 | v1 |
| 用途 | 200：元数据快照、变化和受影响规则 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 否 | Query | 医院编号 |
| db_name | String | 否 | Query | 数据库名 |

### 响应参数

```json
{
  "hospital_id": "hospital_001",
  "db_name": "win60_qa_991827",
  "source_id": "dbhub",
  "has_snapshot": true,
  "table_count": 10,
  "column_count": 120
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hospital_id | String | 是 | 医院编号 |
| db_name | String | 是 | db_name 字段，含义见业务对象 |
| source_id | String | 是 | source_id 字段，含义见业务对象 |
| has_snapshot | Boolean | 是 | has_snapshot 字段，含义见业务对象 |
| table_count | Long | 是 | table_count 字段，含义见业务对象 |
| column_count | Long | 是 | column_count 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/metadata/overview' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 21. 知识库、DBHub 与元数据 - 同步结果；502：DBHub 失败

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/metadata/sync` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-knowledge-0021 |
| 版本 | v1 |
| 用途 | 200：同步结果；502：DBHub 失败 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | RequestBody | 医院编号，必须与医院令牌一致 |
| db_name | String | 否 | RequestBody | 业务数据库名；不传使用配置 |
| source | String | 否 | RequestBody | 元数据来源，当前使用 dbhub |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "db_name": "win60_qa_991827",
  "source": "dbhub"
}
```

### 响应参数

```json
{
  "hospital_id": "hospital_001",
  "batch_id": "META_demo",
  "synced_at": "2026-07-23T10:00:00",
  "changes": [],
  "affected_rules": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hospital_id | String | 是 | 医院编号 |
| batch_id | String | 是 | batch_id 字段，含义见业务对象 |
| synced_at | String | 是 | synced_at 字段，含义见业务对象 |
| changes | Array<Object> | 是 | changes 字段，含义见业务对象 |
| affected_rules | Array<Object> | 是 | affected_rules 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/metadata/sync' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","db_name":"win60_qa_991827","source":"dbhub"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 22. 指标明细与 Excel 导出 - 明细快照

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/sql-runs/{run_id}/details` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0022 |
| 版本 | v1 |
| 用途 | 200 复用/201 新建：明细快照 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| run_id | String | 是 | Path | 路径参数 run_id |

### 响应参数

```json
{
  "snapshot_id": "SNAP_demo",
  "run_id": "RUN_demo",
  "rule_id": "MQSI2025_005",
  "rule_name": "急会诊及时到位率",
  "stat_start": "2026-01-01 00:00:00",
  "stat_end": "2026-07-23 00:00:00",
  "denominator_count": 68,
  "numerator_count": 3,
  "unmatched_count": 0,
  "columns": [],
  "reused": false
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| snapshot_id | String | 是 | snapshot_id 字段，含义见业务对象 |
| run_id | String | 是 | run_id 字段，含义见业务对象 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| rule_name | String | 是 | rule_name 字段，含义见业务对象 |
| stat_start | String | 是 | stat_start 字段，含义见业务对象 |
| stat_end | String | 是 | stat_end 字段，含义见业务对象 |
| denominator_count | Long | 是 | denominator_count 字段，含义见业务对象 |
| numerator_count | Long | 是 | numerator_count 字段，含义见业务对象 |
| unmatched_count | Long | 是 | unmatched_count 字段，含义见业务对象 |
| columns | Array<Object> | 是 | columns 字段，含义见业务对象 |
| reused | Boolean | 是 | reused 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/sql-runs/<run_id>/details' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 23. 指标明细与 Excel 导出 - 明细分页

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/sql-runs/{run_id}/details/{group}` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0023 |
| 版本 | v1 |
| 用途 | 200：明细分页 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| run_id | String | 是 | Path | 路径参数 run_id |
| group | String | 是 | Path | 路径参数 group |
| page | Integer | 是 | Query | 页码，从 1 开始 |
| page_size | Integer | 是 | Query | 每页条数，只允许 20、50、100 |

### 响应参数

```json
{
  "snapshot_id": "SNAP_demo",
  "run_id": "RUN_demo",
  "group": "numerator",
  "page": 1,
  "page_size": 50,
  "total": 3,
  "items": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| snapshot_id | String | 是 | snapshot_id 字段，含义见业务对象 |
| run_id | String | 是 | run_id 字段，含义见业务对象 |
| group | String | 是 | group 字段，含义见业务对象 |
| page | Long | 是 | page 字段，含义见业务对象 |
| page_size | Long | 是 | page_size 字段，含义见业务对象 |
| total | Long | 是 | 总数 |
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/sql-runs/<run_id>/details/numerator?page=1&page_size=50' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 24. 指标明细与 Excel 导出 - 分子/分母明细导出对象

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/sql-runs/{run_id}/exports` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0024 |
| 版本 | v1 |
| 用途 | 201：分子/分母明细导出对象 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| run_id | String | 是 | Path | 路径参数 run_id |
| confirmed | Boolean | 是 | RequestBody | 用户是否确认导出；必须为 true |

#### RequestBody 示例

```json
{
  "confirmed": true
}
```

### 响应参数

```json
{
  "export_id": "EXP_demo",
  "run_id": "RUN_demo",
  "file_name": "MQSI2025_005_20260101_20260723.xlsx",
  "row_count": 71,
  "status": "ready",
  "expires_at": "2026-07-24T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| export_id | String | 是 | export_id 字段，含义见业务对象 |
| run_id | String | 是 | run_id 字段，含义见业务对象 |
| file_name | String | 是 | 服务端读取的原始文件名，仅为响应字段 |
| row_count | Long | 是 | row_count 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| expires_at | String | 是 | 过期时间 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/sql-runs/<run_id>/exports' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"confirmed":true}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 25. 指标明细与 Excel 导出 - 系统与上传文件差异导出对象

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/sql-runs/{run_id}/upload-comparison-exports` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0025 |
| 版本 | v1 |
| 用途 | 201：系统与上传文件差异导出对象 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| run_id | String | 是 | Path | 路径参数 run_id |
| confirmed | Boolean | 是 | RequestBody | 用户是否确认导出；必须为 true |
| file_token | String | 是 | RequestBody | 上传文件安全令牌 |

#### RequestBody 示例

```json
{
  "confirmed": true,
  "file_token": "<file_token>"
}
```

### 响应参数

```json
{
  "status": "success",
  "data": {}
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 是 | 状态 |
| data | Object | 是 | 业务数据 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/sql-runs/<run_id>/upload-comparison-exports' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"confirmed":true,"file_token":"<file_token>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 26. 指标明细与 Excel 导出 - 当前医院导出对象数组

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-exports` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0026 |
| 版本 | v1 |
| 用途 | 200：当前医院导出对象数组 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
[
  {
    "export_id": "EXP_demo",
    "run_id": "RUN_demo",
    "file_name": "指标明细.xlsx",
    "status": "ready"
  }
]
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| [] | Array<Object> | 是 | 响应对象数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-exports' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 27. 指标明细与 Excel 导出 - XLSX 二进制附件

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-exports/{export_id}/download` |
| Content-Type | 无请求体 |
| 响应类型 | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-detail-0027 |
| 版本 | v1 |
| 用途 | 200：XLSX 二进制附件 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| export_id | String | 是 | Path | 路径参数 export_id |

### 响应参数

```text
<XLSX binary>
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| body | binary | 是 | 流式事件或文件二进制内容 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-exports/<export_id>/download' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 28. 指标实施草稿 - 草稿数组

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-drafts` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0028 |
| 版本 | v1 |
| 用途 | 200：草稿数组 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | Query | 医院编号 |
| status | String | 否 | Query | 状态筛选 |

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-drafts?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 29. 指标实施草稿 - 草稿；404：不存在

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-drafts/{draftId}` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0029 |
| 版本 | v1 |
| 用途 | 200：草稿；404：不存在 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 30. 指标实施草稿 - {items: 版本数组}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-drafts/{draftId}/versions` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0030 |
| 版本 | v1 |
| 用途 | 200：{items: 版本数组} |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |

### 响应参数

```json
{
  "items": [
    {
      "version": 1,
      "status": "draft",
      "updated_at": "2026-07-23T10:00:00"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/versions' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 31. 指标实施草稿 - 更新后的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `PUT /api/indicator-drafts/{draftId}` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0031 |
| 版本 | v1 |
| 用途 | 200：更新后的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |
| changes | Object | 是 | RequestBody | 允许修改的草稿字段 |
| actor_id | String | 否 | RequestBody | 兼容字段；审计主体以令牌为准 |

#### RequestBody 示例

```json
{
  "expected_version": 1,
  "changes": {
    "index_name": "示例指标"
  },
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X PUT 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1,"changes":{"index_name":"示例指标"},"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 32. 指标实施草稿 - 需求确认后的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/requirements-confirm` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0032 |
| 版本 | v1 |
| 用途 | 200：需求确认后的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |
| actor_id | String | 否 | RequestBody | 兼容字段；审计主体以令牌为准 |

#### RequestBody 示例

```json
{
  "expected_version": 1,
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/requirements-confirm' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1,"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 33. 指标实施草稿 - 提交后的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/submit` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0033 |
| 版本 | v1 |
| 用途 | 200：提交后的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |
| actor_id | String | 否 | RequestBody | 兼容字段；审计主体以令牌为准 |

#### RequestBody 示例

```json
{
  "expected_version": 1,
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/submit' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1,"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 34. 指标实施草稿 - 字段候选、缺失和歧义

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/indicator-drafts/{draftId}/metadata-suggestions` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0034 |
| 版本 | v1 |
| 用途 | 200：字段候选、缺失和歧义 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "suggestions": [],
  "missing_fields": [],
  "ambiguities": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| suggestions | Array<Object> | 是 | suggestions 字段，含义见业务对象 |
| missing_fields | Array<Object> | 是 | missing_fields 字段，含义见业务对象 |
| ambiguities | Array<Object> | 是 | ambiguities 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/metadata-suggestions' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 35. 指标实施草稿 - 确认映射后的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/metadata-confirm` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0035 |
| 版本 | v1 |
| 用途 | 200：确认映射后的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |
| mappings | Object | 是 | RequestBody | 字段到候选元数据的映射 |

#### RequestBody 示例

```json
{
  "expected_version": 1,
  "mappings": {
    "admission_id": {
      "table_name": "INPATIENT",
      "column_name": "ADMISSION_ID"
    }
  }
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/metadata-confirm' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1,"mappings":{"admission_id":{"table_name":"INPATIENT","column_name":"ADMISSION_ID"}}}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 36. 指标实施草稿 - 含 SQL 对象的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/sql-generate` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0036 |
| 版本 | v1 |
| 用途 | 200：含 SQL 对象的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |

#### RequestBody 示例

```json
{
  "expected_version": 1
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/sql-generate' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 37. 指标实施草稿 - 含试运行结果的草稿

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/trial-run` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-draft-0037 |
| 版本 | v1 |
| 用途 | 200：含试运行结果的草稿 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| expected_version | Integer | 是 | RequestBody | 乐观锁版本 |
| stat_start_time | DateTime | 是 | RequestBody | 统计开始时间，含 |
| stat_end_time | DateTime | 是 | RequestBody | 统计结束时间，不含 |

#### RequestBody 示例

```json
{
  "expected_version": 1,
  "stat_start_time": "2026-01-01 00:00:00",
  "stat_end_time": "2026-07-23 00:00:00"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/trial-run' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"expected_version":1,"stat_start_time":"2026-01-01 00:00:00","stat_end_time":"2026-07-23 00:00:00"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 38. 指标审批与版本治理 - 审批发布结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/approve` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-governance-0038 |
| 版本 | v1 |
| 用途 | 200：审批发布结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| hospital_id | String | 是 | RequestBody | 医院编号，必须与医院令牌一致 |
| expected_version | Integer | 是 | RequestBody | 待审批草稿版本 |
| reason | String | 否 | RequestBody | 审批或驳回原因；驳回时应填写 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "expected_version": 1,
  "reason": "核对通过"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/approve' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","expected_version":1,"reason":"核对通过"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 39. 指标审批与版本治理 - 驳回结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/indicator-drafts/{draftId}/reject` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-governance-0039 |
| 版本 | v1 |
| 用途 | 200：驳回结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| draftId | String | 是 | Path | 路径参数 draftId |
| hospital_id | String | 是 | RequestBody | 医院编号，必须与医院令牌一致 |
| expected_version | Integer | 是 | RequestBody | 待审批草稿版本 |
| reason | String | 否 | RequestBody | 审批或驳回原因；驳回时应填写 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "expected_version": 1,
  "reason": "核对通过"
}
```

### 响应参数

```json
{
  "draft_id": "DRAFT_demo",
  "hospital_id": "hospital_001",
  "index_name": "示例指标",
  "status": "draft",
  "current_version": 1,
  "field_mapping": {},
  "sql_id": null,
  "trial_result": null
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| draft_id | String | 是 | draft_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| index_name | String | 是 | index_name 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| current_version | Long | 是 | current_version 字段，含义见业务对象 |
| field_mapping | Object | 是 | field_mapping 字段，含义见业务对象 |
| sql_id | Object/null | 是 | sql_id 字段，含义见业务对象 |
| trial_result | Object/null | 是 | trial_result 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/indicator-drafts/<draftId>/reject' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","expected_version":1,"reason":"核对通过"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 40. 指标审批与版本治理 - 活动版本和历史版本

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/hospital-defined/{hospitalId}/{indexCode}/versions` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-governance-0040 |
| 版本 | v1 |
| 用途 | 200：活动版本和历史版本 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospitalId | String | 是 | Path | 路径参数 hospitalId |
| indexCode | String | 是 | Path | 路径参数 indexCode |

### 响应参数

```json
{
  "hospital_id": "hospital_001",
  "index_code": "MQSI2025_005",
  "active_version": 1,
  "versions": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hospital_id | String | 是 | 医院编号 |
| index_code | String | 是 | index_code 字段，含义见业务对象 |
| active_version | Long | 是 | active_version 字段，含义见业务对象 |
| versions | Array<Object> | 是 | versions 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/hospital-defined/<hospitalId>/<indexCode>/versions' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 41. 指标审批与版本治理 - 恢复结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/hospital-defined/{hospitalId}/{indexCode}/restore` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-governance-0041 |
| 版本 | v1 |
| 用途 | 200：恢复结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospitalId | String | 是 | Path | 路径参数 hospitalId |
| indexCode | String | 是 | Path | 路径参数 indexCode |
| version | Integer | 是 | RequestBody | 目标版本号 |

#### RequestBody 示例

```json
{
  "version": 1
}
```

### 响应参数

```json
{
  "hospital_id": "hospital_001",
  "index_code": "MQSI2025_005",
  "active_version": 1,
  "versions": []
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hospital_id | String | 是 | 医院编号 |
| index_code | String | 是 | index_code 字段，含义见业务对象 |
| active_version | Long | 是 | active_version 字段，含义见业务对象 |
| versions | Array<Object> | 是 | versions 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/hospital-defined/<hospitalId>/<indexCode>/restore' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"version":1}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 42. 术语读取与治理 - {items, total}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/terminology/concepts` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0042 |
| 版本 | v1 |
| 用途 | 200：{items, total} |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| query | String | 否 | Query | 查询文本 |
| concept_type | String | 否 | Query | 概念类型 |
| rule_id | String | 否 | Query | 指标规则编号 |

### 响应参数

```json
{
  "items": [
    {
      "concept_code": "INDICATOR_URGENT_CONSULT",
      "canonical_name": "急会诊及时到位率",
      "concept_type": "indicator"
    }
  ],
  "total": 1
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |
| total | Long | 是 | 总数 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/terminology/concepts' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 43. 术语读取与治理 - 概念详情、别名、映射和发布版本

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/terminology/concepts/{conceptCode}` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0043 |
| 版本 | v1 |
| 用途 | 200：概念详情、别名、映射和发布版本 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| conceptCode | String | 是 | Path | 路径参数 conceptCode |
| hospital_id | String | 否 | Query | 医院编号 |

### 响应参数

```json
{
  "concept_code": "INDICATOR_URGENT_CONSULT",
  "canonical_name": "急会诊及时到位率",
  "aliases": [],
  "hospital_mappings": [],
  "release_version": "v1"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| concept_code | String | 是 | concept_code 字段，含义见业务对象 |
| canonical_name | String | 是 | canonical_name 字段，含义见业务对象 |
| aliases | Array<Object> | 是 | aliases 字段，含义见业务对象 |
| hospital_mappings | Array<Object> | 是 | hospital_mappings 字段，含义见业务对象 |
| release_version | String | 是 | release_version 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/terminology/concepts/<conceptCode>' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 44. 术语读取与治理 - 归一化与歧义结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/test` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0044 |
| 版本 | v1 |
| 用途 | 200：归一化与歧义结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | RequestBody | 医院编号 |
| text（1~1000 字） | String | 是 | RequestBody | text（1~1000 字） 参数 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "text（1~1000 字）": "<text（1~1000 字）>"
}
```

### 响应参数

```json
{
  "original_text": "急会诊到位率",
  "normalized_text": "急会诊及时到位率",
  "matches": [],
  "ambiguities": [],
  "release_version": "v1",
  "sql_eligible": true
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| original_text | String | 是 | original_text 字段，含义见业务对象 |
| normalized_text | String | 是 | normalized_text 字段，含义见业务对象 |
| matches | Array<Object> | 是 | 匹配结果 |
| ambiguities | Array<Object> | 是 | ambiguities 字段，含义见业务对象 |
| release_version | String | 是 | release_version 字段，含义见业务对象 |
| sql_eligible | Boolean | 是 | sql_eligible 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/test' \
  -H 'Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","text（1~1000 字）":"<text（1~1000 字）>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 45. 术语读取与治理 - {items: 发布记录}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/terminology/releases` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0045 |
| 版本 | v1 |
| 用途 | 200：{items: 发布记录} |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "items": [
    {
      "release_id": "REL_demo",
      "version": "v1",
      "status": "published"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/terminology/releases' \
  -H 'Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 46. 术语读取与治理 - 新建别名

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/aliases` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0046 |
| 版本 | v1 |
| 用途 | 200：新建别名 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 否 | RequestBody | 空值表示全局别名；非空需与医院令牌一致 |
| concept_code | String | 是 | RequestBody | 标准概念编码 |
| alias_text | String | 是 | RequestBody | 别名文本 |
| relation_type | String | 是 | RequestBody | 别名关系类型 |
| retrieval_enabled | Boolean | 否 | RequestBody | 是否参与检索，默认 true |
| sql_safe | Boolean | 否 | RequestBody | 是否允许用于 SQL 安全映射，默认 false |
| ambiguity_group | String | 否 | RequestBody | 歧义分组 |
| source_reference | String | 否 | RequestBody | 来源说明 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "concept_code": "INDICATOR_URGENT_CONSULT",
  "alias_text": "急会诊到位率",
  "relation_type": "synonym",
  "retrieval_enabled": true,
  "sql_safe": false,
  "ambiguity_group": "urgent_consult",
  "source_reference": "本院术语确认"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/aliases' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","concept_code":"INDICATOR_URGENT_CONSULT","alias_text":"急会诊到位率","relation_type":"synonym","retrieval_enabled":true,"sql_safe":false,"ambiguity_group":"urgent_consult","source_reference":"本院术语确认"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 47. 术语读取与治理 - 审核后的别名

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/aliases/{aliasId}/approve` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0047 |
| 版本 | v1 |
| 用途 | 200：审核后的别名 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| aliasId | Long | 是 | Path | 路径参数 aliasId |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/aliases/1/approve' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 48. 术语读取与治理 - 新建医院映射

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/hospital-mappings` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0048 |
| 版本 | v1 |
| 用途 | 200：新建医院映射 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | RequestBody | 医院编号 |
| concept_code | String | 是 | RequestBody | 标准概念编码 |
| code_system | String | 是 | RequestBody | 本地编码体系 |
| local_code | String | 是 | RequestBody | 本地编码 |
| local_name | String | 是 | RequestBody | 本地名称 |
| local_value | String | 是 | RequestBody | 本地值 |
| effective_from | String | 否 | RequestBody | 生效时间 |
| effective_to | String | 否 | RequestBody | 失效时间 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "concept_code": "DEPT_ICU",
  "code_system": "HIS_DEPT",
  "local_code": "ICU001",
  "local_name": "重症医学科",
  "local_value": "ICU",
  "effective_from": "2026-01-01",
  "effective_to": "<effective_to>"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/hospital-mappings' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","concept_code":"DEPT_ICU","code_system":"HIS_DEPT","local_code":"ICU001","local_name":"重症医学科","local_value":"ICU","effective_from":"2026-01-01","effective_to":"<effective_to>"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 49. 术语读取与治理 - 审核后的映射

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/hospital-mappings/{mappingId}/approve` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-terminology-0049 |
| 版本 | v1 |
| 用途 | 200：审核后的映射 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| mappingId | Long | 是 | Path | 路径参数 mappingId |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/hospital-mappings/1/approve' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 50. 术语读取与治理 - 新发布版本

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/releases/publish` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token> |
| API ID | wiki-agent-terminology-0050 |
| 版本 | v1 |
| 用途 | 200：新发布版本 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/releases/publish' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'Content-Type: application/json' \
  -d '{"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 51. 术语读取与治理 - 恢复结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/terminology/releases/{releaseId}/restore` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token> |
| API ID | wiki-agent-terminology-0051 |
| 版本 | v1 |
| 用途 | 200：恢复结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| releaseId | String | 是 | Path | 路径参数 releaseId |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "id": "TERM_demo",
  "status": "pending",
  "created_at": "2026-07-23T10:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| created_at | String | 是 | created_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/terminology/releases/<releaseId>/restore' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'Content-Type: application/json' \
  -d '{"actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 52. 指标监控与告警 - 新建计划

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/plans` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0052 |
| 版本 | v1 |
| 用途 | 200：新建计划 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| plan_id | String | 更新时是 | RequestBody | 计划编号；创建时可空 |
| hospital_id | String | 是 | RequestBody | 医院编号 |
| rule_id | String | 是 | RequestBody | 指标规则编号 |
| plan_name | String | 是 | RequestBody | 监控计划名称 |
| frequency | String | 是 | RequestBody | 执行频率：daily/monthly |
| run_time | String | 是 | RequestBody | 执行时间 HH:mm |
| day_of_month | Integer | 月度时是 | RequestBody | 每月执行日 |
| timezone | String | 否 | RequestBody | 时区，默认 Asia/Shanghai |
| mom_enabled | Boolean | 否 | RequestBody | 是否启用环比告警 |
| mom_threshold_pct | Number | 环比开启时是 | RequestBody | 环比变化阈值 |
| yoy_enabled | Boolean | 否 | RequestBody | 是否启用同比告警 |
| yoy_threshold_pct | Number | 同比开启时是 | RequestBody | 同比变化阈值 |
| created_by | String | 否 | RequestBody | 兼容字段；审计主体以令牌为准 |

#### RequestBody 示例

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "plan_name": "急会诊及时到位率月度监控",
  "frequency": "monthly",
  "run_time": "02:00",
  "day_of_month": 1,
  "timezone": "Asia/Shanghai",
  "mom_enabled": true,
  "mom_threshold_pct": 10.0,
  "yoy_enabled": false,
  "yoy_threshold_pct": 20.0,
  "created_by": "admin"
}
```

### 响应参数

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "frequency": "monthly",
  "status": "enabled",
  "next_run_at": "2026-08-01T02:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| plan_id | String | 是 | plan_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| frequency | String | 是 | frequency 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| next_run_at | String | 是 | next_run_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/plans' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"plan_id":"PLAN_MONITOR_001","hospital_id":"hospital_001","rule_id":"MQSI2025_005","plan_name":"急会诊及时到位率月度监控","frequency":"monthly","run_time":"02:00","day_of_month":1,"timezone":"Asia/Shanghai","mom_enabled":true,"mom_threshold_pct":10.0,"yoy_enabled":false,"yoy_threshold_pct":20.0,"created_by":"admin"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 53. 指标监控与告警 - {items: 计划数组}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/monitoring/plans` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0053 |
| 版本 | v1 |
| 用途 | 200：{items: 计划数组} |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | Query | 医院编号 |

### 响应参数

```json
{
  "items": [
    {
      "plan_id": "PLAN_MONITOR_001",
      "hospital_id": "hospital_001",
      "rule_id": "MQSI2025_005",
      "status": "enabled"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/monitoring/plans?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 54. 指标监控与告警 - 更新后的计划

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `PUT /api/monitoring/plans/{planId}` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0054 |
| 版本 | v1 |
| 用途 | 200：更新后的计划 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| planId | String | 是 | Path | 路径参数 planId |

### 响应参数

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "frequency": "monthly",
  "status": "enabled",
  "next_run_at": "2026-08-01T02:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| plan_id | String | 是 | plan_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| frequency | String | 是 | frequency 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| next_run_at | String | 是 | next_run_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X PUT 'http://127.0.0.1:8766/api/monitoring/plans/<planId>' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 55. 指标监控与告警 - 启用后的计划

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/plans/{planId}/enable` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0055 |
| 版本 | v1 |
| 用途 | 200：启用后的计划 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| planId | String | 是 | Path | 路径参数 planId |
| hospital_id | String | 是 | Query | 医院编号 |

### 响应参数

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "frequency": "monthly",
  "status": "enabled",
  "next_run_at": "2026-08-01T02:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| plan_id | String | 是 | plan_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| frequency | String | 是 | frequency 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| next_run_at | String | 是 | next_run_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/plans/<planId>/enable?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 56. 指标监控与告警 - 停用后的计划

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/plans/{planId}/disable` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0056 |
| 版本 | v1 |
| 用途 | 200：停用后的计划 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| planId | String | 是 | Path | 路径参数 planId |
| hospital_id | String | 是 | Query | 医院编号 |

### 响应参数

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "frequency": "monthly",
  "status": "enabled",
  "next_run_at": "2026-08-01T02:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| plan_id | String | 是 | plan_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| frequency | String | 是 | frequency 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| next_run_at | String | 是 | next_run_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/plans/<planId>/disable?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 57. 指标监控与告警 - 手工执行结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/plans/{planId}/run` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0057 |
| 版本 | v1 |
| 用途 | 200：手工执行结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| planId | String | 是 | Path | 路径参数 planId |
| hospital_id | String | 是 | RequestBody | 医院编号 |
| stat_period | String | 否 | RequestBody | 统计周期 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "stat_period": "2026-07"
}
```

### 响应参数

```json
{
  "plan_id": "PLAN_MONITOR_001",
  "hospital_id": "hospital_001",
  "rule_id": "MQSI2025_005",
  "frequency": "monthly",
  "status": "enabled",
  "next_run_at": "2026-08-01T02:00:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| plan_id | String | 是 | plan_id 字段，含义见业务对象 |
| hospital_id | String | 是 | 医院编号 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| frequency | String | 是 | frequency 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| next_run_at | String | 是 | next_run_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/plans/<planId>/run' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","stat_period":"2026-07"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 58. 指标监控与告警 - {items: 结果数组}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/monitoring/results` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0058 |
| 版本 | v1 |
| 用途 | 200：{items: 结果数组} |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | Query | 医院编号 |
| rule_id | String | 否 | Query | 指标规则编号 |
| limit | Integer | 否 | Query | 返回条数上限 |

### 响应参数

```json
{
  "items": [
    {
      "id": 1,
      "rule_id": "MQSI2025_005",
      "stat_period": "2026-07",
      "result_value": 4.41,
      "run_status": "success"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/monitoring/results?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 59. 指标监控与告警 - 单次结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/monitoring/results/{resultId}` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0059 |
| 版本 | v1 |
| 用途 | 200：单次结果 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| resultId | Long | 是 | Path | 路径参数 resultId |
| hospital_id | String | 是 | Query | 医院编号 |

### 响应参数

```json
{
  "id": 1,
  "rule_id": "MQSI2025_005",
  "stat_period": "2026-07",
  "result_value": 4.41,
  "run_status": "success"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | Long | 是 | id 字段，含义见业务对象 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| stat_period | String | 是 | stat_period 字段，含义见业务对象 |
| result_value | Number | 是 | result_value 字段，含义见业务对象 |
| run_status | String | 是 | run_status 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/monitoring/results/1?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 60. 指标监控与告警 - {items: 告警数组}

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/monitoring/alerts` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0060 |
| 版本 | v1 |
| 用途 | 200：{items: 告警数组} |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| hospital_id | String | 是 | Query | 医院编号 |
| status | String | 否 | Query | 状态筛选 |
| limit | Integer | 否 | Query | 返回条数上限 |

### 响应参数

```json
{
  "items": [
    {
      "alert_id": "ALERT_demo",
      "rule_id": "MQSI2025_005",
      "alert_level": "warning",
      "status": "open"
    }
  ]
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Array<Object> | 是 | 结果数组 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/monitoring/alerts?hospital_id=hospital_001' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 61. 指标监控与告警 - 已确认告警

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/alerts/{alertId}/acknowledge` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0061 |
| 版本 | v1 |
| 用途 | 200：已确认告警 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| alertId | String | 是 | Path | 路径参数 alertId |
| hospital_id | String | 是 | RequestBody | 医院编号 |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "alert_id": "ALERT_demo",
  "rule_id": "MQSI2025_005",
  "status": "acknowledged",
  "diagnose_status": "completed"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| alert_id | String | 是 | alert_id 字段，含义见业务对象 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| diagnose_status | String | 是 | diagnose_status 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/alerts/<alertId>/acknowledge' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 62. 指标监控与告警 - 已关闭告警

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/alerts/{alertId}/close` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0062 |
| 版本 | v1 |
| 用途 | 200：已关闭告警 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| alertId | String | 是 | Path | 路径参数 alertId |
| hospital_id | String | 是 | RequestBody | 医院编号 |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "alert_id": "ALERT_demo",
  "rule_id": "MQSI2025_005",
  "status": "acknowledged",
  "diagnose_status": "completed"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| alert_id | String | 是 | alert_id 字段，含义见业务对象 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| diagnose_status | String | 是 | diagnose_status 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/alerts/<alertId>/close' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 63. 指标监控与告警 - 诊断后的告警

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/alerts/{alertId}/diagnose` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token>；X-Hospital-Authorization: Bearer <hospital_token> |
| API ID | wiki-agent-monitoring-0063 |
| 版本 | v1 |
| 用途 | 200：诊断后的告警 |

### 请求参数

| 参数名 | 类型 | 必填 | 位置 | 说明 |
|------|------|------|------|------|
| alertId | String | 是 | Path | 路径参数 alertId |
| hospital_id | String | 是 | RequestBody | 医院编号 |
| actor_id | String | 否 | RequestBody | 操作人兼容字段；以令牌主体为准 |

#### RequestBody 示例

```json
{
  "hospital_id": "hospital_001",
  "actor_id": "user_001"
}
```

### 响应参数

```json
{
  "alert_id": "ALERT_demo",
  "rule_id": "MQSI2025_005",
  "status": "acknowledged",
  "diagnose_status": "completed"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| alert_id | String | 是 | alert_id 字段，含义见业务对象 |
| rule_id | String | 是 | rule_id 字段，含义见业务对象 |
| status | String | 是 | 状态 |
| diagnose_status | String | 是 | diagnose_status 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/alerts/<alertId>/diagnose' \
  -H 'Authorization: Bearer <admin_token>' \
  -H 'X-Hospital-Authorization: Bearer <hospital_token>' \
  -H 'Content-Type: application/json' \
  -d '{"hospital_id":"hospital_001","actor_id":"user_001"}'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 64. 指标监控与告警 - 调度器状态

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `GET /api/monitoring/scheduler/status` |
| Content-Type | 无请求体 |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token> |
| API ID | wiki-agent-monitoring-0064 |
| 版本 | v1 |
| 用途 | 200：调度器状态 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "enabled": true,
  "running": true,
  "next_scan_at": "2026-07-23T10:05:00"
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| enabled | Boolean | 是 | enabled 字段，含义见业务对象 |
| running | Boolean | 是 | running 字段，含义见业务对象 |
| next_scan_at | String | 是 | next_scan_at 字段，含义见业务对象 |

### 请求示例

```bash
curl -X GET 'http://127.0.0.1:8766/api/monitoring/scheduler/status' \
  -H 'Authorization: Bearer <admin_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 65. 指标监控与告警 - 本次扫描结果

### 基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/monitoring/scheduler/scan` |
| Content-Type | application/json |
| 响应类型 | application/json |
| 认证方式 | Authorization: Bearer <admin_token> |
| API ID | wiki-agent-monitoring-0065 |
| 版本 | v1 |
| 用途 | 200：本次扫描结果 |

### 请求参数

该接口无业务请求参数。

### 响应参数

```json
{
  "scanned": 3,
  "executed": 1,
  "failed": 0
}
```

| 字段名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| scanned | Long | 是 | scanned 字段，含义见业务对象 |
| executed | Long | 是 | executed 字段，含义见业务对象 |
| failed | Long | 是 | failed 字段，含义见业务对象 |

### 请求示例

```bash
curl -X POST 'http://127.0.0.1:8766/api/monitoring/scheduler/scan' \
  -H 'Authorization: Bearer <admin_token>'
```

### 常见错误

| HTTP 状态 | 说明 |
|------|------|
| 400 / 422 | 参数格式、必填字段或业务校验失败 |
| 401 | 令牌缺失、失效或登录状态已过期 |
| 403 | 医院范围或权限不足 |
| 404 | 当前医院范围内对象不存在 |
| 409 / 410 | 版本冲突或临时对象已过期 |
| 500 / 502 / 503 | 服务端、DBHub 或模型依赖异常 |

## 前端通用错误读取建议

```typescript
const data = await response.json().catch(() => ({}))
if (!response.ok) {
  const detail = data.detail
  const message = typeof detail === 'string' ? detail : detail?.message
  throw new Error(message || data.message || `请求失败（HTTP ${response.status}）`)
}
```

> 响应新增字段时前端应安全忽略；请求端只能发送文档列出的字段，因为后端拒绝未知 JSON 属性。
