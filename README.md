# 核心制度指标 Agent

本项目是一个面向医院核心制度指标的本地化 Wiki 知识库 Agent。它把 Markdown Wiki、医院口径覆盖、审批流、SQL 生成、异常诊断和医院知识库回收合并整合到一个可本地部署的 Python 服务中，前端提供类聊天式交互和审批管理界面。

项目默认适配本地部署场景：知识库文件保存在仓库内，模型通过 Ollama 调用本机模型，数据库通过 `config.yaml` 配置，适合医院内网或单机验证环境。

## 核心能力

- **本地 Markdown Wiki 知识库**：国家标准、公司标准、医院口径、字段映射、SQL 规格、索引文件统一存放在 `core-rules-wiki/`。
- **Agent 对话问答**：基于规则检索、上下文记忆和 Ollama 生成最终回答，失败或事实校验不通过时自动回退到知识库模板答案。
- **医院口径优先**：查询时优先读取本院已生效口径，其次回退公司标准，再回退国标。
- **反馈与审批**：医院用户反馈口径不一致时先生成差异预览，用户确认后进入 Pending，管理员审批通过后才生效。
- **版本化医院口径**：医院 override 采用追加版本方式保存，支持历史口径对比和一键恢复历史版本。
- **SQL 生成与试运行**：根据指标 SQL 规格、字段契约和医院字段映射生成只读 SQL，并支持二步确认试运行。
- **三层异常诊断**：系统结构校验、口径规则校验、数据质量校验，输出中文诊断结果和风险提示。
- **知识库导出与回收合并**：医院可导出本院知识库压缩包，公司管理员上传后生成合并报告，对候选项逐项处理。
- **会话记忆**：对话记忆写入 SQLite 与 JSONL，支持多轮追问和反馈上下文。
- **高级前端界面**：单页 HTML 前端，包含流式输出、状态流转、审批、版本、合并上传等操作入口。

## 技术栈

- 后端：FastAPI、Pydantic、SQLAlchemy、PyMySQL
- Agent：自定义轻量工作流，预留 LangGraph 风格节点拆分
- LLM：Ollama，本地模型默认 `qwen3:4B-instruct`
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
|   +-- llm/
|   +-- memory/
|   +-- metadata/
|   +-- prompts/
|   +-- sqlgen/
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
|   +-- kb_agent_demo.py
+-- tests/
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

### 4. 重建知识库索引

```powershell
python scripts\rebuild_runtime_indexes.py
```

### 5. 启动服务

```powershell
python -B -m uvicorn app.api.main:app --host 127.0.0.1 --port 8765
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
3. 知识库检索
4. 医院口径优先解析
5. Ollama 生成最终回答
6. 事实校验
7. SSE 流式输出

如 LLM 输出未通过事实校验，系统会回退到知识库模板答案，避免给出不可靠结论。

### 医院口径反馈与审批

医院用户反馈：

```text
我们医院急会诊及时到位率按20分钟计算
```

系统不会立即改 Wiki，而是：

1. 识别为口径反馈
2. 命中对应指标
3. 展示用户反馈口径与当前医院口径差异
4. 用户点击提交后生成 Pending 变更
5. 管理员审批通过后写入医院 override 新版本
6. 自动重建索引
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
| `/api/chat` | POST | 非流式对话 |
| `/api/chat/stream` | POST | SSE 流式对话 |
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
- 医院口径优先级
- 变更申请审批
- 医院口径版本追加与恢复
- 索引重建
- SQL 生成安全校验
- 诊断 Agent 三层检查
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

## 开发备注

本项目强调“知识库事实优先，LLM 辅助表达”。任何涉及口径、公式、SQL 或医院覆盖规则的变更，都应通过工具链和测试验证后再提交。
