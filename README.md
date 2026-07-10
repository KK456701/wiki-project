# 核心制度指标 Agent

本项目是一个面向医院核心制度指标的本地化 Agent。已迁移指标以 MySQL 保存国标口径、医院定制口径及不可变版本，Markdown Wiki 作为规则导入来源和数据库故障时的只读兜底；系统同时提供审批流、SQL 生成、异常诊断和医院知识库回收能力，前端采用类聊天式交互和审批管理界面。

项目默认适配本地部署场景：知识库文件保存在仓库内，模型通过 Ollama 调用本机模型，数据库通过 `config.yaml` 配置，适合医院内网或单机验证环境。

## 核心能力

- **MySQL 规则主存储**：四个首批指标的国标口径、医院定制口径、SQL 模板、字段映射和版本历史统一存入运行数据库。
- **Wiki 导入与故障兜底**：`core-rules-wiki/` 保留原始规则、SQL 规格和字段映射；MySQL 暂时不可用或指标尚未迁移时只读回退，不接受兜底写入。
- **Agent 对话问答**：基于规则检索、上下文记忆和 Ollama 生成最终回答，失败或事实校验不通过时自动回退到知识库模板答案。
- **合成本院生效口径**：以国标为基础，查询时只合入本院已审批且处于生效期的差异项，不修改国标原始记录；没有有效本院差异时直接使用国标。
- **反馈与审批**：医院用户反馈口径不一致时先生成差异预览，用户确认后进入 Pending，管理员审批通过后才生效。
- **版本化医院口径**：医院 override 采用追加版本方式保存，支持历史口径对比和一键恢复历史版本。
- **SQL 生成与试运行**：根据指标 SQL 规格、字段要求和医院字段映射生成只读 SQL，并支持二步确认试运行。
- **三层异常诊断**：系统结构校验、口径规则校验、数据质量校验，输出中文诊断结果和风险提示。
- **DBHub MCP 数据库接入**：通过本地 DBHub sidecar 读取业务库元数据、同步字段快照，并执行只读 SQL 试运行。
- **Dify-lite 执行链路**：使用 `app/workflows/core_indicator_chat.yaml` 描述节点清单，运行时 Trace 会展示节点职责、状态、耗时、实际入参/出参、配置和故障定位建议；前端默认展示节点摘要，点击“详情”后展开完整信息。
- **节点输入输出检查**：关键节点在 manifest 中声明必要输入、必要输出、出错处理和默认问题码，Trace 会标记缺少内容，便于定位节点接线或数据传递问题。
- **恢复中心**：关键任务会写入恢复记录，服务异常中断后可在管理界面查看上次中断、可重试或已完成的任务。
- **知识库导出与回收合并**：医院可导出本院知识库压缩包，公司管理员上传后生成合并报告，对候选项逐项处理。
- **会话记忆**：对话记忆写入 SQLite 与 JSONL，支持多轮追问和反馈上下文。
- **五类 Agent 统一编排**：元数据解析、指标生成、口径适配、故障根因排查和人机交互 Agent 由 `CoreIndicatorOrchestrator` 统一路由；HTTP、LangGraph 适配器和 SSE 统一调用“理解请求、检索规则、解析生效口径”三个编排阶段，不再绕过编排器直接访问专业 Agent。
- **类型化 Agent 契约**：Agent 之间通过 `app/agents/contracts.py` 中的 Pydantic 模型校验意图、规则检索、口径、字段映射、SQL、元数据预检查和诊断结果；API 与 SSE 边界继续输出兼容的 JSON 字典。
- **元数据预检查边界**：SQL 生成前由元数据解析 Agent 校验字段映射和运行库元数据，未通过时停止流程；指标生成 Agent 只消费已校验结果，不直接读取元数据。
- **高级前端界面**：单页 HTML 前端，包含流式输出、状态流转、审批、版本、合并上传等操作入口。

## 技术栈

- 后端：FastAPI、Pydantic、SQLAlchemy、PyMySQL
- Agent：非流式 `/api/chat` 支持可选 LangGraph StateGraph；主前端 `/api/chat/stream` 采用自定义 Python 流式工作流，并按 Dify/LangGraph 风格记录节点 Trace
- 编排：`CoreIndicatorOrchestrator` 负责五类 Agent 路由、请求准备、规则检索和口径解析；LangGraph 和 SSE 只负责执行、Trace 与流式适配，不承载领域逻辑
- LLM：Ollama，本地模型默认 `qwen3:4B-instruct`
- MCP：DBHub HTTP sidecar，用于数据库工具、元数据同步和只读 SQL 试运行
- SQL 模板：Jinja2
- 知识库：Markdown、YAML、JSON 索引
- 前端：原生 HTML/CSS/JavaScript，SSE 流式输出
- 测试：unittest

## 目录结构

```text
.
+-- app/
|   +-- agent/
|   +-- api/
|   +-- db/
|   +-- diagnose/
|   +-- kb/
|   +-- rules/
|   +-- llm/
|   +-- memory/
|   +-- metadata/
|   +-- observability/
|   +-- prompts/
|   +-- sqlgen/
|   +-- workflows/
+-- core-rules-wiki/
|   +-- indexes/
|   +-- wiki/
|   +-- sql-specs/
|   +-- hospital-mappings/
|   +-- review/
|   +-- merge-reports/
+-- scripts/
|   +-- build_core_rules_wiki.py
|   +-- rebuild_runtime_indexes.py
|   +-- init_runtime_db.sql
|   +-- init_demo_hospital_db.sql
|   +-- import_four_indicator_rules.py
|   +-- kb_agent_demo.py
+-- tests/
+-- tools/
|   +-- dbhub/
+-- web/
|   +-- index.html
+-- config.yaml
+-- requirements.txt
```

## 快速启动

### 1. 安装依赖

```powershell
cd F:\A-wiki-project
python -m pip install -r requirements.txt
Copy-Item config.example.yaml config.yaml
```

Edit `config.yaml` with your local database password, admin password and Ollama model before starting the service.

### 2. 启动 Ollama 模型

确保本机 Ollama 已运行，并已拉取配置中的模型：

```powershell
ollama pull qwen3:4B-instruct
ollama serve
```

如需换模型，修改 `config.yaml`：

```yaml
ollama_model: "qwen3:4B-instruct"
ollama_base_url: "http://127.0.0.1:11434"
```

### 3. 初始化数据库

项目默认使用 MySQL，连接信息在 `config.yaml`：

```yaml
runtime_db_url: "mysql+pymysql://root:123456@127.0.0.1:3306/wiki_agent_runtime?charset=utf8mb4"
business_db_url: "mysql+pymysql://root:123456@127.0.0.1:3306/hospital_demo_data?charset=utf8mb4"
business_db_dialect: "mysql"
```

初始化运行库和演示业务库：

```powershell
mysql -uroot -p123456 < scripts\init_runtime_db.sql
mysql -uroot -p123456 < scripts\init_demo_hospital_db.sql
```

将首批四个指标从 Wiki 导入 MySQL 规则库：

```powershell
python -B scripts\import_four_indicator_rules.py
```

该命令可以重复执行：国标和医院映射按唯一键更新，不会重复创建初始医院口径版本。当前迁移范围为：

| 指标编码 | 指标名称 | `hospital_001` 演示结果 |
|---|---|---:|
| `MQSI2025_001` | 患者入院 48 小时内转科比例 | `25.00` |
| `MQSI2025_005` | 急会诊及时到位率 | `66.67` |
| `MQSI2025_014` | 急危重症患者抢救成功率 | `75.00` |
| `MQSI2025_035` | 术中自体血回输率 | `50.00` |

也可以在服务启动后使用管理员令牌触发同一导入流程：

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8765/api/rules/import-four `
  -Headers @{ Authorization = "Bearer <admin_token>" }
```

### 4. 重建知识库索引

```powershell
python scripts\rebuild_runtime_indexes.py
```

### 5. 启动 DBHub MCP sidecar

如果需要使用元数据同步、数据库工具查看、SQL 试运行或诊断中的实时元数据能力，需要先启动 DBHub：

```powershell
cd F:\A-wiki-project\tools\dbhub
npm install
npx @bytebase/dbhub@latest --transport http --host 127.0.0.1 --port 8080 --config F:\A-wiki-project\tools\dbhub\dbhub.local.toml
```

默认 Workbench：

```text
http://127.0.0.1:8080/
```

项目默认期望 DBHub MCP HTTP 地址为：

```yaml
dbhub_mcp_url: "http://127.0.0.1:8080/mcp"
dbhub_source_hospital_demo_data: "hospital_demo_data"
dbhub_execute_tool_hospital_demo_data: "execute_sql_hospital_demo_data"
```

如果本地 MySQL 账号不同，请同步修改 `tools/dbhub/dbhub.local.toml` 中的连接串。

### 6. 启动服务

```powershell
python -B -m uvicorn app.api.main:app --host 127.0.0.1 --port 8765 --reload
```

打开：

```text
http://127.0.0.1:8765
```

健康检查：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/health
```

## 常用工作流

### 指标问答

用户在前端输入：

```text
急会诊及时到位率怎么算？
```

工作流会执行：

1. 意图识别
2. 多轮上下文改写
3. 规则检索
4. 从 MySQL 合并国标与医院生效口径
5. Ollama 生成最终回答
6. 事实校验
7. SSE 流式输出

如 LLM 输出未通过事实校验，系统会回退到规则模板答案，避免给出不可靠结论。生效口径接口和执行链路会返回 `rule_source`：正常为 `mysql`，数据库异常或指标未迁移时为 `wiki_fallback`，同时附带只读兜底警告。

### 执行链路与故障定位

每次对话、元数据同步、诊断、变更提交和审批都会生成 `trace_id`。前端消息下方的“执行链路”按钮会打开 Trace 弹窗：

- 默认只展示节点摘要：节点标题、节点 ID、状态、类型、耗时和职责说明。
- 点击具体节点的“详情”后，才展开本次入参、本次出参、节点配置、期望入参/出参和定位建议。
- 节点详情中的“负责 Agent”说明该阶段属于五类 Agent 中的哪一个，摘要仍保持精简。
- 详情中会展示输入输出检查、必要输入/输出、缺少内容、出错处理和问题码。
- 如果节点没有真实计时，前端显示“未计时”，不会再把未测量的节点误展示为 `0ms`。
- 节点说明来自 `app/workflows/core_indicator_chat.yaml`，运行数据来自 `TraceRecorder`。
- 失败节点如果没有显式问题码，会自动使用 manifest 中的默认问题码，例如 `SQL_VALIDATE_FAILED`。
- 工作流定义可通过 `/api/workflows/core_indicator_chat` 查看，并通过 `/api/workflows/core_indicator_chat/validate` 校验。

典型节点包括：

```text
memory_load -> intent_detect -> rule_search -> effective_rule_resolve -> final_response
```

SQL 链路会继续展开：

```text
field_mapping_precheck -> sql_generate -> sql_validate -> sql_trial_mcp
```

诊断链路会继续展开：

```text
diagnose_structure_mcp -> diagnose_rule_check -> diagnose_data_check_mcp
```

### 恢复中心

管理员可在前端顶部点击“恢复中心”，查看需要补救的任务。第一批恢复中心覆盖：

- 元数据同步
- 审批并应用医院口径
- 恢复医院历史口径
- 索引重建类任务

任务状态包括：

```text
执行中
上次中断
可重试
已完成
已忽略
```

如果服务崩溃或请求中断，遗留的 `running` 任务会在恢复中心中标记为“上次中断”。可安全自动重试的任务会显示“重试”或“继续重建索引”；审批类任务默认需要人工确认后重新发起，避免重复写入口径。

知识库关键文件写入采用临时文件加原子替换：先写 `.tmp`，校验写完后再替换正式文件。如果替换失败，原正式文件会保留，临时文件会清理，降低 Markdown/YAML 写坏风险。

### 医院口径反馈与审批

医院用户反馈：

```text
我们医院急会诊及时到位率按20分钟计算
```

系统不会立即修改生效口径，而是：

1. 识别为口径反馈
2. 命中对应指标
3. 展示用户反馈口径与当前医院口径差异
4. 用户点击提交后生成 Pending 变更
5. 管理员审批通过后写入 MySQL 医院定制口径新版本
6. 保留不可变版本记录，支持查看和恢复
7. 查询时立即优先采用新医院口径

### SQL 生成与试运行

用户可在对话中要求：

```text
生成急会诊及时到位率 SQL，统计 2026-01-01 到 2026-01-31
```

系统会先生成 SQL 并做安全校验。只有用户确认试运行后，才会访问业务库执行只读查询。

### 异常诊断

用户可输入：

```text
急会诊及时到位率结果异常，帮我诊断一下
```

诊断 Agent 会执行三层检查：

- 第 1 层：系统结构校验
- 第 2 层：口径规则校验
- 第 3 层：数据质量校验

输出会区分：

- 通过
- 通过但有风险
- 不通过

### 知识库导出与回收合并

医院侧导出：

```http
GET /api/kb/export?hospital_id=hospital_001
```

公司管理员上传 zip：

```http
POST /api/kb/merge/upload
Authorization: Bearer <admin_token>
Content-Type: application/zip
```

系统生成合并报告后，管理员可以逐项审批、拒绝或作为公司候选口径沉淀。

## API 概览

| 接口 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 前端页面 |
| `/api/health` | GET | 健康检查 |
| `/api/health/summary` | GET | 系统自检摘要，返回中文状态、处理建议和问题码 |
| `/api/health/dependencies` | GET | 依赖原始检查，返回 runtime DB、DBHub、业务库 MCP 等状态和问题码 |
| `/api/chat` | POST | 非流式对话 |
| `/api/chat/stream` | POST | SSE 流式对话 |
| `/api/traces/{trace_id}` | GET | 查看执行链路 Trace |
| `/api/workflows/{workflow_id}` | GET | 查看工作流 manifest |
| `/api/workflows/{workflow_id}/validate` | GET | 校验工作流 manifest 节点和边 |
| `/api/rules/import-four` | POST | 管理员幂等导入首批四指标到 MySQL |
| `/api/recovery/tasks` | GET | 查看恢复中心任务 |
| `/api/recovery/tasks/{task_id}/retry` | POST | 重试可恢复任务 |
| `/api/recovery/tasks/{task_id}/ignore` | POST | 忽略恢复任务 |
| `/api/kb/search` | POST | 知识库检索 |
| `/api/kb/rules/{rule_id}/effective` | GET | 查询医院生效口径 |
| `/api/review/change-requests` | POST | 创建口径变更申请 |
| `/api/review/pending` | GET | 查看待审批申请 |
| `/api/review/change-requests/{change_id}/approve` | POST | 审批通过 |
| `/api/review/change-requests/{change_id}/reject` | POST | 审批拒绝 |
| `/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions` | GET | 查看医院口径版本 |
| `/api/review/hospital-overrides/{hospital_id}/{rule_id}/versions/{version_id}/restore` | POST | 恢复历史版本 |
| `/api/admin/login` | POST | 管理员登录 |
| `/api/admin/logout` | POST | 管理员登出 |
| `/api/mcp/dbhub/sources` | GET | 查看 DBHub 数据源/工具 |
| `/api/metadata/sync` | POST | 同步业务库元数据 |
| `/api/sql/generate` | POST | 生成或试运行 SQL |
| `/api/diagnose/run` | POST | 执行异常诊断 |
| `/api/kb/export` | GET | 导出医院知识库 zip |
| `/api/kb/merge/upload` | POST | 上传医院知识库 zip |
| `/api/kb/merge/reports` | GET | 查看合并报告列表 |
| `/api/kb/merge/report/{report_id}` | GET | 查看合并报告详情 |

管理员接口需要请求头：

```http
Authorization: Bearer <token>
```

默认管理员密码在 `config.yaml`：

```yaml
admin_password: "admin123"
```

## 知识库维护

首批四指标运行时以 MySQL 为准。Wiki 中的相同内容是可审阅的导入来源和只读兜底，不再是审批后生效口径的最终存储。未迁移指标仍可通过 Wiki 读取，但会明确标记 `wiki_fallback`；兜底状态下所有审批、恢复等写操作都会失败关闭，防止形成两套事实来源。

### 全量建库

全量建库脚本：

```powershell
python scripts\build_core_rules_wiki.py
```

适用于从原始核心制度指标文档重新生成 Wiki 结构。一般在原始制度文档大版本变更时运行。

### 索引重建

索引重建脚本：

```powershell
python scripts\rebuild_runtime_indexes.py
```

适用于以下场景：

- 手工修改 Wiki Markdown / YAML 后
- 审批通过医院口径变更后
- 恢复医院历史版本后
- 合并回收知识库后

系统内置的审批、恢复和合并流程会在写入后触发索引维护，保证检索、相关性、医院 override 优先级和关系索引保持一致。

## 测试

运行全部测试：

```powershell
python -B -m unittest discover -s tests -v
```

当前测试覆盖：

- Agent 多轮对话和意图识别
- 本院差异项与国标基础字段的生效口径合成
- 四指标幂等导入和 Wiki 只读故障回退
- 四指标 SQL 语义结果及无样本状态
- 变更申请审批
- 医院口径版本追加与恢复
- 索引重建
- SQL 生成安全校验
- 诊断 Agent 三层检查
- DBHub MCP 元数据同步和业务库只读调用
- 执行链路 Trace、workflow manifest 注解和前端节点详情展示
- 知识库导出与合并
- API 基础流程

## 安全边界

- 医院用户只能提交医院口径变更，不能直接修改公司标准。
- 医院口径变更必须审批后生效。
- SQL 生成只允许只读 `SELECT`。
- SQL 试运行采用二步确认，避免误触发。
- LLM 只参与意图识别、回答组织和过滤条件提取，不直接无约束写入知识库。
- 知识库最终变更以结构化工具、审批结果和索引重建为准。

## 生产部署建议

MVP 当前适合单机或内网验证。进入生产前建议补充：

- 管理员账号密码持久化与权限分级
- HTTPS 和反向代理
- 审计日志独立存储
- 数据库连接密钥改为环境变量或密钥管理服务
- Ollama 模型服务监控
- 定时索引完整性检查
- 变更申请和合并报告的备份策略

## 系统自检

每个 HTTP 请求都会返回 `X-Request-ID` 和 `X-Process-Time-Ms` 响应头，并追加写入 `runtime/request_events.jsonl`。如果用户反馈“某次请求异常”，优先用 request_id 对齐后端日志和 Trace。

面向页面展示的自检接口：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/health/summary
```

返回结构中：

- `status_text` 为 `全部正常` 或 `部分异常`。
- `items` 包含后端服务、运行数据库、DBHub 服务、业务数据库和流程引擎。
- 每个项目都有中文状态、说明、处理建议和问题码。

原始依赖检查接口保留给开发和实施排障：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/health/dependencies
```

常见问题码：

```text
RUNTIME_DB_UNAVAILABLE
BUSINESS_DB_MCP_UNAVAILABLE
DBHUB_HTTP_UNAVAILABLE
LANGGRAPH_NOT_INSTALLED
```

工作流校验接口：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/core_indicator_chat/validate
```

如果 `ok=false`，先处理 `issues` 中的节点缺字段、重复节点或边引用不存在节点问题，再继续排查业务链路。

## 开发备注

本项目强调“结构化规则事实优先，LLM 辅助表达”。已迁移指标以 MySQL 生效规则为准，Wiki 负责来源审阅与故障兜底。任何涉及口径、公式、SQL 或医院覆盖规则的变更，都应通过工具链和测试验证后再提交。
