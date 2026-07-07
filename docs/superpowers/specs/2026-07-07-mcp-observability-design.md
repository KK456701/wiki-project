# 数据库 MCP 化与全链路可观测性设计

日期：2026-07-07

## 目标

本设计用于把项目中的业务数据库访问统一收敛到 DBHub MCP，并补齐类似 Dify 工作流的执行链路可观测能力。完成后，系统应能回答两个核心问题：

1. 某次回答质量不好时，能定位是意图识别、知识库检索、口径解析、SQL 生成、SQL 执行、LLM 生成还是事实校验出了问题。
2. 某次执行故障时，能看到故障节点、错误原因、耗时、工具调用、SQL 标识和兜底路径，而不是只看到最终失败。

## 边界决策

采用方案 A：

- 业务库 `hospital_demo_data` 全部通过 DBHub MCP 只读访问。
- 运行库 `wiki_agent_runtime` 保留项目内部 Repository 写入。
- 不暴露运行库通用可写 SQL MCP 工具。

这个边界的原因是：业务库访问需要受控、只读、可审计；运行库是系统自身状态、日志、Trace、诊断报告和审批记录的持久化位置，应由项目内部代码按固定仓储接口写入，避免把核心审计链路交给通用 SQL 工具。

## 当前状态

当前已经接入 DBHub MCP 的链路：

- `/api/metadata/sync` 在 `source=dbhub` 时通过 DBHub MCP 查询 `INFORMATION_SCHEMA`。
- 前端 MCP 面板可以查看 DBHub 数据源和工具。
- 前端 MCP 面板可以触发 DBHub 元数据同步。

当前仍然直连业务库的链路：

- SQL 生成 Agent 的试运行。
- 诊断 Agent 的第三层数据质量检查。
- 诊断 Agent 默认构造时未注入 DBHub 实时元数据 Provider。
- 聊天中的 SQL 生成、试运行和诊断分支仍使用 `create_business_engine()`。

当前保留项目内部运行库连接的链路：

- 元数据缓存写入。
- SQL 生成记录写入。
- SQL 运行日志写入。
- 指标运行结果写入。
- 诊断报告写入。
- 会话记忆和 JSONL 事件。

## 目标架构

### 数据库访问分层

新增或强化以下边界：

```text
app/db_access/
  dbhub_mcp.py              # DBHub MCP HTTP 客户端
  business_db.py            # 业务库只读访问接口，统一走 MCP
  metadata_provider.py      # SQLAlchemy / DBHub 元数据 Provider
  query_result.py           # 统一 SQL 执行结果结构

app/observability/
  trace.py                  # Trace 上下文和节点记录
  events.py                 # JSONL 事件写入
  metrics.py                # MVP 级指标聚合
```

业务库访问统一变为：

```text
SQL 生成试运行
诊断数据质量查询
实时元数据查询
字段预校验
表结构查看
    -> BusinessDBClient
    -> DBHubMCPClient
    -> execute_sql_hospital_demo_data
```

运行库写入保留：

```text
Repository
    -> SQLAlchemy runtime_engine
    -> wiki_agent_runtime
```

### 禁止项

第一阶段不做以下事情：

- 不暴露运行库通用可写 SQL MCP。
- 不让 LLM 直接决定执行任意 SQL。
- 不在 MCP 失败时绕过 MCP 直连业务库。
- 不把 DBHub 的 `node_modules` 提交到仓库。

## 短期 MVP 范围

第一阶段只完成以下能力：

1. 新增 `BusinessDBClient`，封装业务库只读 SQL 执行。
2. SQL 试运行改为通过 `BusinessDBClient` 执行，不再使用 `business_engine.connect()`。
3. 诊断 Agent 第三层数据质量检查改为通过 `BusinessDBClient` 执行。
4. 诊断 Agent 第一层结构校验优先使用 DBHub 实时元数据 Provider，失败后回退运行库元数据缓存。
5. `/api/metadata/sync` 默认或推荐使用 `source=dbhub`，保留 `sqlalchemy` 仅作为开发调试入口。
6. 新增 Trace 表和 Trace 节点表。
7. 聊天、SQL 生成、试运行、诊断、元数据同步都生成 `trace_id`。
8. 前端每条 Agent 回复显示“查看链路”入口。
9. MCP 调用失败时降级返回，不绕过 MCP 直连业务库。
10. 新增健康检查信息：FastAPI、DBHub、Ollama、业务库 MCP、运行库。

## Trace 数据模型

新增运行库表：

```sql
CREATE TABLE IF NOT EXISTS med_agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(128),
  hospital_id VARCHAR(64),
  user_id VARCHAR(128),
  user_query TEXT,
  intent VARCHAR(64),
  final_status VARCHAR(32),
  final_answer_summary TEXT,
  error_count INT DEFAULT 0,
  fallback_count INT DEFAULT 0,
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS med_agent_trace_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  node_name VARCHAR(128) NOT NULL,
  node_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_summary TEXT,
  output_summary TEXT,
  error_code VARCHAR(128),
  error_message TEXT,
  tool_name VARCHAR(128),
  db_source VARCHAR(128),
  sql_id VARCHAR(64),
  run_id VARCHAR(64),
  rule_id VARCHAR(64),
  llm_model VARCHAR(128),
  started_at DATETIME NOT NULL,
  ended_at DATETIME,
  duration_ms INT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_trace_node_trace_id (trace_id),
  INDEX idx_trace_node_status (status),
  INDEX idx_trace_node_rule_id (rule_id)
);
```

节点状态：

- `success`：节点成功。
- `failed`：节点失败且影响主流程。
- `skipped`：条件不满足，跳过执行。
- `fallback`：节点失败或校验不通过后使用兜底路径。
- `warning`：节点完成但存在风险或注意项。

## 标准执行链路

一次聊天请求的典型 Trace：

```text
request_received
  -> memory_load
  -> intent_detect
  -> rule_search
  -> effective_rule_resolve
  -> metadata_check_mcp
  -> sql_generate
  -> sql_validate
  -> sql_trial_mcp
  -> llm_answer
  -> fact_guard
  -> final_response
```

诊断请求的典型 Trace：

```text
request_received
  -> intent_detect
  -> rule_search
  -> effective_rule_resolve
  -> diagnose_structure_mcp
  -> diagnose_rule_check
  -> diagnose_data_check_mcp
  -> diagnose_report_save
  -> final_response
```

元数据同步的典型 Trace：

```text
request_received
  -> dbhub_sources_check
  -> metadata_tables_query_mcp
  -> metadata_columns_query_mcp
  -> metadata_diff
  -> runtime_cache_write
  -> affected_rules_detect
  -> final_response
```

## 故障兜底策略

### MCP 连接失败

处理方式：

- SQL 生成：仍可生成 SQL，但标记“未试运行”，并记录 `mcp_unavailable`。
- SQL 试运行：返回失败状态，不执行直连兜底。
- 诊断结构校验：优先回退运行库缓存；没有缓存则该层返回 `warning` 或 `failed`。
- 诊断数据质量：返回 `skipped`，说明业务库 MCP 不可用。
- 普通问答：回退知识库模板答案。

### LLM 失败

处理方式：

- 意图识别失败：回退关键词规则。
- 答案生成失败：回退知识库工具答案。
- 事实校验失败：回退知识库模板答案，并记录 `LLM_ANSWER_FAILED_FACT_GUARD`。

### SQL 校验失败

处理方式：

- 不执行 SQL。
- 返回校验失败原因。
- Trace 节点记录 SQL 文本摘要、错误码和安全校验结果。

### 运行库写入失败

处理方式：

- 主流程尽量继续返回用户可读结果。
- 同步写 JSONL 文件事件，作为运行库不可用时的最低审计兜底。
- 健康检查暴露运行库异常状态。

## 可观测性能力

### 前端

新增“查看链路”入口：

- 每条 Agent 回复旁边显示 `trace_id` 或“查看链路”按钮。
- 弹窗展示节点列表、状态、耗时、错误原因和兜底说明。
- 失败节点高亮。
- 支持复制 Trace JSON。

### API

新增接口：

```http
GET /api/traces/{trace_id}
GET /api/traces?session_id=...&limit=20
GET /api/health/dependencies
```

`/api/health/dependencies` 返回：

- FastAPI 状态。
- LangGraph 是否可用。
- Ollama 是否可用。
- DBHub HTTP 是否可用。
- DBHub MCP 是否可用。
- `hospital_demo_data` 业务库 MCP 查询是否可用。
- `wiki_agent_runtime` 运行库连接是否可用。

### 日志

MVP 同时写入：

- 运行库 Trace 表。
- 本地 JSONL 文件：`runtime/trace_events.jsonl`。

JSONL 作为兜底审计，不依赖运行库写入成功。

### 指标

MVP 先在运行库聚合，不引入 Prometheus。后续生产版再扩展：

- 请求总数。
- 节点失败率。
- MCP 调用耗时。
- LLM 调用耗时。
- SQL 试运行耗时。
- 兜底次数。
- 事实校验失败次数。

## 修改点

### 后端

- `app/db_access/business_db.py`：新增业务库 MCP 查询接口。
- `app/sqlgen/runner.py`：试运行改用 `BusinessDBClient`。
- `app/diagnose/data_check.py`：数据质量检查改用 `BusinessDBClient`。
- `app/diagnose/agent.py`：默认接入 DBHub 元数据 Provider。
- `app/api/main.py`：构造 Agent 时注入业务库 MCP 客户端和 Trace 上下文。
- `app/agent/graph.py`：聊天流式链路记录 Trace 节点。
- `app/db/repositories.py`：新增 Trace 写入方法。
- `scripts/init_runtime_db.sql`：新增 Trace 表。

### 前端

- 聊天回复显示 Trace 入口。
- 新增 Trace 弹窗。
- MCP 面板保留，用于手动验证 DBHub 数据源和同步。

### 测试

新增测试：

- SQL 试运行使用 MCP，不调用 `business_engine.connect()`。
- 诊断数据质量检查使用 MCP。
- MCP 失败时不直连业务库，返回降级结果。
- Trace 节点按顺序写入。
- 前端 Trace API 返回节点详情。
- 运行库写入失败时 JSONL 兜底。

## 成功标准

第一阶段完成后应满足：

1. 搜索代码，业务库查询不再通过 `create_business_engine()` 主流程执行。
2. SQL 试运行通过 `execute_sql_hospital_demo_data` 完成。
3. 诊断第三层数据质量检查通过 `execute_sql_hospital_demo_data` 完成。
4. MCP 停止时，系统不会瘫痪，而是返回明确降级信息。
5. 每次聊天、SQL 生成、试运行、诊断和元数据同步都有 `trace_id`。
6. 前端可以查看一次执行的节点链路。
7. 单测覆盖 MCP 成功、MCP 失败、LLM 失败、事实校验失败和运行库写入失败。

## 后续生产增强

第二阶段再考虑：

- OpenTelemetry 跨服务 Trace。
- Prometheus 指标导出。
- Grafana 仪表盘。
- Trace 采样策略。
- 慢 SQL 分析。
- 异常告警。
- 审计报表导出。

