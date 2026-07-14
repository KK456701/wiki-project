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
- **指标设计稿闭环**：支持从自然语言创建“本院新增指标”，或为已有国标指标创建“本院口径差异”；设计稿依次完成字段确认、确定性 SQL 生成、DBHub 试运行、提交审批和版本化发布，未发布设计稿不参与正式查询。
- **三层异常诊断**：系统结构校验、口径规则校验、数据质量校验，输出中文诊断结果和风险提示。
- **DBHub MCP 数据库接入**：通过本地 DBHub sidecar 读取业务库元数据、同步字段快照，并执行只读 SQL 试运行。
- **Dify-lite 执行链路**：使用 `app/workflows/core_indicator_chat.yaml` 描述节点清单，运行时 Trace 会展示节点职责、状态、耗时、实际入参/出参、配置和故障定位建议；前端默认展示节点摘要，点击“详情”后展开完整信息。
- **节点输入输出检查**：关键节点在 manifest 中声明必要输入、必要输出、出错处理和默认问题码，Trace 会标记缺少内容，便于定位节点接线或数据传递问题。
- **恢复中心**：关键任务会写入恢复记录，服务异常中断后可在管理界面查看上次中断、可重试或已完成的任务。
- **指标监控工作台**：管理员可在前端新建、编辑、启停运行计划，手工运算指标，查看聚合结果和执行链路，并确认、关闭或重新诊断预警。
- **数据库与元数据工作台**：医院人员可在前端同步业务库结构，查看最近同步、表字段数量、结构变化和受影响指标；连接与只读工具信息集中在折叠详情中。
- **医学术语工作台**：维护 35 个核心制度指标涉及的标准概念、同义词、本院编码映射、审核状态和术语版本，并明确区分“可检索”和“可进 SQL”。
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
- 知识库：MySQL 保存已审核术语与版本，Wiki/YAML 保存公司语料来源和只读兜底，Markdown、YAML、JSON 索引服务制度文档检索
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
|   +-- indicators/
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
|   +-- seed_demo_hospital_data.py
|   +-- simulate_metadata_drift.py
|   +-- seed_monitoring_baseline.py
|   +-- import_four_indicator_rules.py
|   +-- kb_agent_demo.py
+-- tests/
+-- tools/
|   +-- dbhub/
+-- web/
|   +-- index.html
|   +-- monitoring.css
|   +-- monitoring.js
|   +-- metadata.css
|   +-- metadata.js
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

医院端初始化运行库和演示业务库：

```powershell
mysql -uroot -p123456 < scripts\init_runtime_db.sql
mysql -uroot -p123456 < scripts\init_demo_hospital_db.sql
```

`init_demo_hospital_db.sql` 只包含用于快速冒烟测试的少量记录。需要验证同比、环比、边界值和数据质量诊断时，先预览真实规模模拟数据：

```powershell
python scripts\seed_demo_hospital_data.py
```

默认生成 2025 年 1 月至 2026 年 7 月共 19 个月、约 3.6 万条虚构业务记录，包含四个指标的临界值、2026 年 6 月波动和可插入的数据质量异常。确认预览后执行：

```powershell
python scripts\seed_demo_hospital_data.py --profile realistic --apply
```

`--apply` 会在一个事务内清空并重建四张演示业务表，只允许连接名称以 `_demo_data` 结尾的数据库。使用 `--profile baseline` 可生成不含质量异常的正常基线；同一随机种子重复执行会得到相同数据，便于复现问题。

如果运行库来自旧版本，建表命令不会自动给已存在表补列。拉取新版本后再执行一次幂等迁移：

```powershell
python -B scripts\migrate_runtime_schema.py
```

该命令只补齐缺失字段和表，可重复执行；当前会同时迁移诊断报告、指标运行计划、运行审计字段和指标预警表。

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

公司回收与发布服务使用独立的 `wiki_company_kb`，不得与任一医院运行库共用 schema。公司端在 `config.yaml` 增加：

```yaml
company_db_url: "mysql+pymysql://root:123456@127.0.0.1:3306/wiki_company_kb?charset=utf8mb4"
```

初始化公司知识中心并写入首批四指标标准版本 1：

```powershell
mysql -uroot -p123456 < scripts\init_company_kb_db.sql
python -B scripts\import_company_standard_rules.py
```

初始化脚本只补齐公司库中缺失的指标，不覆盖已发布标准。后续公司标准变化必须经过“医院回收包、候选审核、公司版本发布”流程。

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

指标调度器默认随 FastAPI 启停，在 `config.yaml` 中配置：

```yaml
monitoring_scheduler_enabled: true
monitoring_scheduler_timezone: "Asia/Shanghai"
monitoring_scheduler_lease_seconds: 600
```

多进程或开发模式重载时，每个进程都可以恢复启用计划；数据库租约和稳定运行键会阻止同一计划、同一统计周期重复执行。

## 常用工作流

### 五页面业务工作台

第六批前端改造采用“AI 负责理解和发起任务、业务页面负责审核和执行”的工作台结构。医院人员登录后默认进入 `#/assistant` AI 指标助手首页，普通问答不需要管理员权限。

- 左侧第一项固定为“AI 指标助手”，业务导航只展示已经完成并可正常操作的专业页面，当前包括“指标运算监控”和“数据库与元数据”。
- 桌面端 AI 首页使用沉浸式工具轨道，传统品牌顶栏在该页面隐藏；医院、用户和系统工具固定在右上角，对话区域从窗口顶部开始。
- 进入指标运算监控等专业业务页后，完整顶栏和文字侧栏自动恢复；移动端继续使用横向导航，不采用窄工具轨道。
- 从左侧进入“指标运算监控”时才验证管理员权限，验证成功后仍停留在 `#/monitoring`，不会跳转到审批功能。
- 专业页面右上角可打开 AI 助手抽屉；返回 AI 首页后继续同一会话，关闭抽屉不会清空消息、输入内容或业务页面状态。
- “系统自检”和“恢复中心”位于顶部“系统工具”菜单。
- AI 指标自助配置、智能异常排查和口径规则管理会在第六批后续子批次逐页迁移；页面完成前不显示不可操作的空白导航项。

现有“指标设计稿”和“审批与版本”入口暂时位于兼容工具区，后续会迁入对应的正式业务页面。

### 数据库与元数据工作台

医院人员从左侧点击“数据库与元数据”进入正式工作台，不需要使用 DBHub 测试页面或命令行：

1. 确认顶部医院编号和“医院业务库”选择正确。
2. 点击“同步数据库结构”，页面会读取该库的表和字段定义，并在处理期间禁止重复提交。
3. 同步完成后查看最近同步时间、数据表数量、字段数量和本批结构变化。
4. “受影响指标”会根据本院字段映射列出可能受表字段变化影响的指标；没有命中时明确显示本次无影响。
5. 需要实施排障时展开“连接详情”，查看医院业务库、系统管理库、DBHub 和 MCP 只读工具状态；系统管理库不会成为医院业务元数据的默认同步目标。

元数据同步只读取 `INFORMATION_SCHEMA.TABLES` 与 `INFORMATION_SCHEMA.COLUMNS`，不读取患者业务数据，不修改医院业务库，也不展示数据库密码。页面重新打开后会从运行库读取最近一次成功快照；同步失败时保留上一次成功结果。

本地演示环境可以使用可选字段 `consult_priority` 验证新增、类型修改和删除三类结构变化。命令默认只预览，增加 `--apply` 才会修改名称以 `_demo_data` 结尾的演示库：

```powershell
python scripts\simulate_metadata_drift.py add --apply
# 到前端“数据库与元数据”点击“同步数据库结构”，应看到新增字段

python scripts\simulate_metadata_drift.py modify --apply
# 再次同步，应看到字段类型变化

python scripts\simulate_metadata_drift.py remove --apply
# 再次同步，应看到字段删除
```

`consult_priority` 不参与现有四个指标计算。需要确保环境恢复到初始结构时执行 `python scripts\simulate_metadata_drift.py restore --apply`；命令可重复执行，不会因为字段已存在或已删除而失败。

### 医学术语工作台

医院人员从左侧进入“数据与术语基础”，切换到“医学术语库”即可完成日常操作：

1. 搜索标准概念、指标名称或同义词，也可以按指标编码筛选。
2. 查看每个词的来源、关联指标和安全范围。“可检索”表示可帮助理解问法；“可进 SQL”表示在本院已审批值映射存在时，才可作为参数化统计条件。
3. 医院管理员新增本院候选词或本院编码映射，审核通过后才进入当前医院的运行链路；其他医院不可见。
4. 在识别测试中输入“统计上感患者”或指标别名，查看标准化结果、歧义和 SQL 可用结论。
5. 公司管理员发布术语版本或回退到历史版本。发布和回退均保留审计记录，不删除旧版本。

首次部署或升级已有数据库时执行：

```powershell
python scripts\migrate_runtime_schema.py
python scripts\import_core_indicator_terms.py --apply
```

语料当前覆盖 35 个核心制度指标及共用诊断、科室、人员角色、时间范围和数据值概念。MySQL 是审核后生效和版本回退的主存储；`core-rules-wiki/terminology/core_indicator_terms.yaml` 是公司语料的可审阅导入来源，运行库异常时 Wiki 只提供只读兜底，不接受审批写入。

前端“识别测试”可使用以下问法验收安全边界：

- “急会诊响应率怎么算？”应唯一定位 `MQSI2025_005`，并标准化为“急会诊及时到位率”。
- “统计上感患者”应识别“急性上呼吸道感染”，但因为没有本院已审批诊断值映射而不能直接进入 SQL。
- “查房率”应返回多个可能指标并要求确认，不能擅自选择。
- “抢救成功患者”必须保持“抢救成功”；“治愈”是禁止替换词，不能改写成抢救成功。

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

### 生成 SQL 与试运行说明

生成 SQL 后，回答默认面向医生解释业务计算，并直接列出本次取数使用的数据库、表和必要字段。完整字段血缘、安全校验和 SQL 仍放入默认关闭的“查看技术详情（供信息科和实施人员）”：

```text
当前规则 -> 数据来源 -> 一句话说明 -> 分子分母拆解 -> 执行步骤 -> 折叠技术详情
```

- “当前采用什么规则”会说明指标名称、统计医院、统计时间以及采用标准口径还是本院生效口径。
- “数据从哪里来”会列出真实数据库、医院数据表以及每张表提供的业务数据，不推测未配置的表间关联。
- “分子与分母怎么计算”使用“中文含义 + 医院数据库字段”说明涉及字段，并展开筛选、时间相减、按行计数或去重计数、相除和乘以100的运算关系。
- “系统实际执行的步骤”按顺序串起统计范围、派生值、分母、分子和最终比例；没有时间差的指标不会显示无关步骤。
- 本院修改过阈值时，默认区域同时说明本院值、标准值以及它影响分子还是分母。例如本院20分钟只影响分子，不改变同期急会诊总次数。
- 展开技术详情后，信息科或实施人员仍可查看“系统统一名称”“本院数据库位置”“系统如何判断”“规则来源”和完整 SQL。
- SQL 试运行一次返回 `index_value`、`numerator_count`、`denominator_count` 和兼容字段 `sample_count`，不会额外执行分子、分母查询。
- 例如统计范围为10次、达到要求为8次时，回答会明确说明另有2次未达到要求，以及 `8 / 10 x 100% = 80%`。
- 分母为0时显示“本期没有符合统计范围的数据，指标暂不可计算”，不把0%误解为真实业务水平。
- 对话和执行链路只展示聚合数量、统计区间、口径版本和数据源，不展示患者姓名、病历号、业务明细或绑定参数后的 SQL。

现有旧 SQL 如果尚未返回分子、分母列，系统仍可显示指标值，但会提示重新生成新版 SQL 后再查看完整计算过程。旧规则没有结构化字段关系时，回答明确显示“当前指标的取数关系尚未配置完整，请联系信息科或实施人员完善后再生成”，不会根据表名或字段名猜测分子、分母关系。

### 指标字段为什么可信

正确 SQL 不是只靠 `INFORMATION_SCHEMA` 或字段名模糊匹配生成的，而是由以下五层确定性信息共同约束：

1. `med_index_standard.calculation_definition` 保存结构化分母、分子、统计范围、聚合方式和派生字段关系；本院已审批差异通过 `custom_calculation_patch` 合成生效定义，不修改国标原始记录。
2. 业务字段字典说明 `request_time`、`arrive_time` 等字段在指标中的业务含义和期望类型，由 Wiki/YAML 审阅后导入 MySQL。
3. `med_field_mapping` 保存每家医院的业务字段到实际数据库表字段映射，只有状态为 `confirmed` 的映射可以生成可执行 SQL。
4. DBHub 元数据同步把医院业务库的真实表、字段和类型写入 `med_metadata_column`；预检查会确认映射字段仍然存在且类型兼容。
5. 多表指标必须在 `med_table_relation` 中存在已确认关联关系，系统不会自行猜测 JOIN 条件。

SQL 生成前只检查当前指标结构化定义真正依赖的字段，不会因未参与计算的可选字段缺失而误阻断。预检查和 SQL 生成共用同一份本院生效定义与字段映射，生成阶段不会再次查询另一份映射，从而避免同一次请求前后口径漂移。

### 前端验收医生说明与字段关系

1. 打开 [http://127.0.0.1:8765/](http://127.0.0.1:8765/)，在“AI 指标助手”输入“急会诊及时到位率怎么算？”。
2. 继续输入“生成 SQL”。默认区域应显示数据来自 `hospital_demo_data` 数据库的 `consult_record` 表，并列出医院、会诊类型、申请时间和到位时间对应的真实字段。
3. “分子与分母怎么计算”应说明分母按医院、会诊类型和统计时间筛选后计数；分子用到位时间减申请时间，在本院20分钟阈值内记为达到要求；最终用分子除以分母并乘以100。
4. 默认区域应显示本院采用20分钟、标准值为10分钟，并注明“只影响分子，不改变分母”；完整 SQL 仍不可见。点击“查看技术详情（供信息科和实施人员）”后，应显示完整字段血缘与 SQL。
5. 输入“试运行”。回答应使用业务语言显示统计范围数量、达到要求数量、未达到要求数量及计算式，例如 `488 / 576 x 100% = 84.72%`。
6. 点击“查看链路”，试运行节点只能看到聚合结果、统计区间、数据源、耗时和运行 ID，不应出现患者明细或绑定后的 SQL。

### 指标设计稿闭环

前端顶部点击“指标设计稿”，输入业务描述后可完成：

```text
待确认字段 -> 字段已确认 -> SQL 已生成 -> 试运行通过 -> 待审批 -> 已发布
```

- **指标设计稿**：尚未进入正式规则库的工作副本，可修改并保留不可变版本快照。
- **本院新增指标**：没有对应国标指标，由本院自行定义；审批后写入 `med_index_hospital_defined`。
- **本院口径差异**：基于已有国标指标，仅保存本院需要调整的口径；审批后写入 `med_index_hospital_custom`，不修改 `med_index_standard`。
- 字段从 `med_metadata_column` 最近一次元数据快照中推荐，缺少字段时先到“数据库与元数据”工作台同步业务库结构。
- SQL 不由 LLM 直接编写。LLM 只生成强类型计算计划，系统固定 `hospital_id` 租户范围并确定性渲染参数化 SELECT；当前支持单表比例/计数及两个时间字段的分钟差条件。
- 任何编辑都会使旧 SQL 和试运行证据失效；只有当前版本试运行成功后才能提交审批。

该闭环的 Dify-lite 节点清单可通过 `/api/workflows/indicator_generation_closed_loop` 查看，并通过 `/api/workflows/indicator_generation_closed_loop/validate` 校验。

### 执行链路与故障定位

每次对话、元数据同步、诊断、变更提交和审批都会生成 `trace_id`。前端消息下方的“执行链路”按钮会打开 Trace 弹窗：

- 默认只展示业务摘要：执行步骤、节点标题、处理结果、中文状态和该阶段耗时，不展示节点 ID、工具名等开发字段。
- 顶部“执行耗时”使用请求的端到端总耗时；“阶段时间轴”展示各已计时节点的耗时占比，并标出最慢节点。网络发送、事件组装等未单独记录的开销可能导致阶段占比之和小于 100%。
- 点击具体节点的“详情”后先看到结构化“处理结果”；继续展开“原始输入输出”才显示 JSON 入参、出参和节点配置。
- “开发与排障”包含节点 ID、负责 Agent、类型、工具、输入输出检查、必要输入/输出、出错处理和问题码；定位建议仅在失败、回退或字段检查异常时显示。
- 正常节点默认折叠；异常节点自动展开，避免重要故障被隐藏。
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

其中 `diagnose_rule_check` 不再只是静态公式检查。对于已迁移且存在有效医院定制口径的指标，它会在相同医院、字段映射和统计周期下，通过 DBHub 分别执行纯国标口径和本院生效口径。节点摘要显示对比结论；展开详情后可查看两侧版本、执行状态、聚合结果、样本量、耗时、差值和运行 ID，不展示绑定后的 SQL 或患者明细。

### 指标运算监控与预警

首批四指标支持持久化日/月运行计划、手工重算、波动预警和自动三层诊断。日常操作已接入前端“指标运算监控”正式页面，不需要使用 PowerShell。

- 日计划计算最近一个完整自然日，月计划计算最近一个完整自然月，统计区间统一为 `[start_time, end_time)`。
- 环比默认启用、阈值 `20%`；同比默认启用、阈值 `30%`，两者可独立关闭或调整。
- 只有绝对变化率严格大于阈值才预警；等于阈值不预警。
- 缺少上期或去年同期结果时标记 `baseline_insufficient`，不会误报；分母为零时标记 `no_sample`。
- 波动预警会自动触发现有结构、口径、数据三层诊断，并关联诊断报告。
- 执行失败会在恢复中心创建“指标重新运算”任务，重试时保留原失败记录并建立重试关联。

生成真实规模业务数据后，可以预览四指标、19个月的历史运算任务：

```powershell
python scripts\seed_monitoring_baseline.py
```

确认 DBHub、FastAPI 和 MySQL 均已启动后执行：

```powershell
python scripts\seed_monitoring_baseline.py --apply
```

脚本会创建四条固定编号的月计划，并按月份顺序调用正式监控服务执行 76 次指标运算。运行结果、环比/同比基线、预警、自动诊断报告和 Trace 均由系统正常生成；相同运行键再次执行会复用已有结果，不会重复造数。模拟数据中 2026 年 3 月抢救指标为无样本，2026 年 4 月术中自体血指标为低样本，2026 年 6 月包含明显波动。

前端验证和日常使用：

1. 使用医院人员身份进入系统，页面会直接进入 `#/assistant`；确认顶部医院编号正确后即可询问指标口径或发起任务。
2. 从左侧点击“指标运算监控”，完成管理员登录后会自动返回 `#/monitoring`。
3. 在“运行计划”中新建计划，选择日/月频率、运行时间和环比/同比阈值。
4. 选择计划后可编辑、启停或点击“立即运行”；统计范围留空时自动计算最近完整周期。
5. 运行完成后可直接查看本次聚合结果和执行链路，也可在“运行结果”中按指标编码筛选历史结果。
6. 在“预警处理”中按状态查看预警，并执行确认、关闭或重新诊断。
7. 运行失败时通过顶部“系统工具”进入“恢复中心”重试。
8. 需要继续询问当前业务时点击顶部“AI 助手”；关闭抽屉后可以继续处理原监控页面，返回 AI 首页仍保留同一会话。

监控链路依次展示计划读取、运行锁、统计周期、DBHub 运算、波动判断，以及触发异常后的预警和自动诊断。节点详情只保存聚合结果、口径版本、数据源、耗时、预警和报告 ID，不保存绑定后的 SQL 或患者明细。

#### 实施排障

开发、部署或故障定位时，可以使用以下命令校验工作流清单：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/indicator_monitoring/validate
```

如前端不可用，可通过管理 API 定位计划创建问题；该方式不是普通用户的日常入口：

```powershell
$headers = @{ Authorization = "Bearer <admin_token>" }
$plan = @{
  hospital_id = "hospital_001"
  rule_id = "MQSI2025_005"
  plan_name = "急会诊月报"
  frequency = "monthly"
  run_time = "02:00"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/monitoring/plans `
  -Headers $headers -ContentType "application/json" -Body $plan
```

### 恢复中心

管理员可在前端顶部点击“恢复中心”，查看需要补救的任务。第一批恢复中心覆盖：

- 元数据同步
- 审批并应用医院口径
- 恢复医院历史口径
- 索引重建类任务
- 指标运算失败后的原周期重算

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

### 指标明细预览与短期导出验收

首次安装或拉取本功能后，依次执行：

```powershell
python -B scripts\migrate_runtime_schema.py
python -B scripts\import_four_indicator_rules.py
python scripts\seed_demo_hospital_user.py
```

最后一条命令只用于本地演示，会创建或重置 `user_001`，所属医院为 `hospital_001`，并授予 `indicator_detail_view` 和 `indicator_detail_export`。初始密码为 `123456`，首次登录必须改成至少 8 位且同时包含字母和数字的新密码。生产环境不得执行该演示账号脚本，应通过医院管理员初始化正式账号。

医生或实施人员在前端按以下步骤验收：

1. 使用演示账号登录并完成首次改密。
2. 输入“急会诊及时到位率怎么算”，再输入“生成 SQL”和“试运行”。
3. 在“统计范围（分母）”“达到要求（分子）”“未达到要求”三行分别点击“查看详情”。
4. 核对三个标签的数量与本次计算结果一致；页面预览脱敏，Excel 保留授权完整值。
5. 点击“生成并下载 Excel”，阅读患者明细使用提示并确认。文件应包含“统计范围”“达到要求”“未达到要求”三个工作表，表头应写明本院口径版本和统计区间。

明细窗口会直接显示“来源数据库”和“取数表”；展开“查看字段来源”可核对每个中文业务列对应的医院表字段。直接字段显示完整的 `表名.字段名`，派生列会说明由哪些原始字段计算，例如“到位耗时（分钟）由申请时间、到位时间计算”。Excel 三个工作表顶部保存同一份来源说明，但不包含数据库连接串、账号密码或 SQL。

明细采用短期快照，不是长期患者业务库。首次查看时，系统按本次试运行固化的本院口径、字段映射和统计区间重新读取明细，并先核对分子、分母数量；如果业务数据已经变化，会要求重新试运行，不会展示数量不一致的旧结果。默认最多生成 20,000 条分母明细，超限时应缩小统计区间。

短期快照和 Excel 默认保存在 `runtime/exports/{hospital_id}/{run_id}/`，由 Git 忽略，并在 24 小时后由启动清理、按需清理和每小时调度清理删除。`indicator_detail_view` 只允许查看脱敏预览，`indicator_detail_export` 才允许生成和下载完整 Excel；跨医院访问统一按资源不存在处理。查看、导出、下载、拒绝和过期清理只把人员、医院、指标、数量与结果写入 `med_data_access_audit`，不会写入患者字段值。

部署参数位于 `config.yaml`：

```yaml
hospital_auth_session_hours: 8
indicator_detail_export_root: "runtime/exports"
indicator_detail_expire_hours: 24
indicator_detail_max_rows: 20000
indicator_detail_default_page_size: 50
```

常见定位：没有详情按钮时需重新生成并试运行 SQL；提示缺少口径快照时需重新试运行；提示业务数据变化时需重新试运行后再查看；提示权限不足时检查账号权限；文件过期后需重新生成。相关状态记录在 `med_indicator_detail_snapshot`、`med_indicator_export` 和 `med_data_access_audit`，排障时无需打开患者明细文件。

### 异常诊断

用户可输入：

```text
急会诊及时到位率结果异常，帮我诊断一下
```

诊断 Agent 会执行三层检查：

- 第 1 层：系统结构校验
- 第 2 层：静态口径规则校验，以及国标口径与本院生效口径双执行对比
- 第 3 层：数据质量校验

双口径结果不同表示本院定制项确实改变了统计结果，系统会给出风险警告并继续第 3 层，不会自动判定哪一侧错误；任一侧执行失败时，才会在第 2 层阻断并给出对应定位建议。无有效医院定制、本院新增指标、MySQL 规则库不可用等场景会明确标记为“不适用”，不会使用 Wiki 伪造医院口径。

可用指标五验证首批双口径诊断。先调用：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/diagnose/run `
  -ContentType "application/json" `
  -Body '{"hospital_id":"hospital_001","rule_id":"MQSI2025_005","trigger":"manual","stat_period":"2026-07-01~2026-07-31"}'
```

在演示数据与默认口径下，第 2 层应显示国标 10 分钟口径约 `33.33`、本院 20 分钟口径约 `66.67`，结论为 `caliber_result_diff`，随后继续执行第 3 层。也可以在前端输入“急会诊及时到位率结果异常，帮我诊断一下”，再点击消息下方“执行链路”，展开“诊断口径规则”节点查看；对话入口未指定周期时默认使用当前自然月。

输出会区分：

- 通过
- 通过但有风险
- 不通过

### 知识库导出与回收合并

医院侧从本院 MySQL 当前生效投影导出 `kb-exchange-v3` 知识包：

```http
GET /api/kb/export?hospital_id=hospital_001
```

知识包包含已审批且处于生效期的医院口径差异、已确认字段映射、已审批本院术语值映射、待公司复核的本院术语候选、版本号和逐文件 SHA-256。它不包含患者记录、数据库密码、会话、运行日志，也不会导出其他医院数据。历史版本继续保存在医院 MySQL；恢复历史版本会创建一个新版本，下一次导出只携带新的当前版本。公司端继续接受旧的 `kb-exchange-v2` 包。

公司管理员上传 ZIP，内容先校验后写入公司 MySQL 暂存区：

```http
POST /api/kb/merge/upload
Authorization: Bearer <admin_token>
Content-Type: application/zip
```

系统生成合并报告后，管理员可以逐项拒绝、仅保留医院本地或采纳为公司候选。术语条目会标记为术语候选、术语冲突、术语歧义或 SQL 安全映射变更，并进入独立的公司术语候选表。采纳候选不会立即改变公司标准，也不会直接进入公司术语发布版本；规则候选仍需创建并发布公司版本：

```http
POST /api/kb/company/releases
Authorization: Bearer <admin_token>
Content-Type: application/json

{
  "candidate_ids": ["CAND_xxx"],
  "created_by": "publisher",
  "notes": "首批医院经验"
}
```

```http
POST /api/kb/company/releases/{release_id}/publish
Authorization: Bearer <admin_token>
Content-Type: application/json

{
  "approver_id": "approver"
}
```

发布后通过 `GET /api/kb/company/releases/{release_id}/export` 下载 `company-release-v2`。包内除规则外还包含公司已审核术语快照 `terminology/release.json`、`terminology/concepts.json` 和 `terminology/aliases.json`，不会混入仅被采纳但尚未发布的医院术语候选。本批不自动在医院侧应用公司发布包，防止绕过医院审批；后续医院导入时应先比较基础标准版本，再由管理员确认冲突。

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
| `/api/indicator-drafts/generate` | POST | 从自然语言生成并保存指标设计稿 |
| `/api/indicator-drafts` | GET | 按医院和状态查看设计稿 |
| `/api/indicator-drafts/{draft_id}` | GET、PUT | 查看或创建设计稿新版本 |
| `/api/indicator-drafts/{draft_id}/metadata-suggestions` | GET | 推荐业务字段对应数据库列 |
| `/api/indicator-drafts/{draft_id}/metadata-confirm` | POST | 确认当前版本字段映射 |
| `/api/indicator-drafts/{draft_id}/sql-generate` | POST | 确定性生成并校验当前版本 SQL |
| `/api/indicator-drafts/{draft_id}/trial-run` | POST | 通过 DBHub 试运行当前版本 SQL |
| `/api/indicator-drafts/{draft_id}/submit` | POST | 提交已试运行版本等待审批 |
| `/api/indicator-drafts/{draft_id}/approve` | POST | 管理员批准并发布设计稿 |
| `/api/indicator-drafts/{draft_id}/reject` | POST | 管理员拒绝设计稿 |
| `/api/hospital-defined/{hospital_id}/{index_code}/versions` | GET | 查看本院新增指标正式版本 |
| `/api/hospital-defined/{hospital_id}/{index_code}/versions/{version}/restore` | POST | 恢复本院新增指标历史版本 |
| `/api/rules/import-four` | POST | 管理员幂等导入首批四指标到 MySQL |
| `/api/recovery/tasks` | GET | 查看恢复中心任务 |
| `/api/recovery/tasks/{task_id}/retry` | POST | 重试可恢复任务 |
| `/api/recovery/tasks/{task_id}/ignore` | POST | 忽略恢复任务 |
| `/api/monitoring/plans` | GET、POST | 按医院查看或创建指标运行计划 |
| `/api/monitoring/plans/{plan_id}` | PUT | 修改运行频率、时间和波动阈值 |
| `/api/monitoring/plans/{plan_id}/enable` | POST | 启用计划并同步到调度器 |
| `/api/monitoring/plans/{plan_id}/disable` | POST | 停用计划并移除调度任务 |
| `/api/monitoring/plans/{plan_id}/run` | POST | 按指定或默认完整周期手工重算 |
| `/api/monitoring/results` | GET | 按医院和指标查看运行审计结果 |
| `/api/monitoring/alerts` | GET | 按医院和状态查看指标预警 |
| `/api/monitoring/alerts/{alert_id}/acknowledge` | POST | 确认预警并记录操作人和时间 |
| `/api/monitoring/alerts/{alert_id}/close` | POST | 关闭预警并记录关闭时间 |
| `/api/monitoring/alerts/{alert_id}/diagnose` | POST | 手工重新执行预警诊断 |
| `/api/monitoring/scheduler/scan` | POST | 扫描到期计划，供运维验收使用 |
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
| `/api/metadata/overview` | GET | 查看当前医院最近一次元数据同步概览和影响范围 |
| `/api/metadata/sync` | POST | 同步业务库元数据 |
| `/api/terminology/concepts` | GET | 搜索标准概念、同义词和指标关联 |
| `/api/terminology/concepts/{concept_code}` | GET | 查看概念详情和当前医院映射 |
| `/api/terminology/test` | POST | 测试自然语言术语识别和 SQL 可用性 |
| `/api/terminology/aliases` | POST | 新建公司或医院术语候选 |
| `/api/terminology/aliases/{alias_id}/approve` | POST | 审批术语候选 |
| `/api/terminology/hospital-mappings` | POST | 新建本院编码和值映射 |
| `/api/terminology/hospital-mappings/{mapping_id}/approve` | POST | 审批本院编码和值映射 |
| `/api/terminology/releases` | GET | 查看术语版本 |
| `/api/terminology/releases/publish` | POST | 发布当前已审核术语版本 |
| `/api/terminology/releases/{release_id}/restore` | POST | 回退到历史术语版本 |
| `/api/auth/hospital/login` | POST | 医院账号登录并返回默认 8 小时会话令牌 |
| `/api/auth/hospital/change-password` | POST | 首次登录或日常修改医院账号密码 |
| `/api/auth/hospital/logout` | POST | 注销当前医院账号会话 |
| `/api/sql/generate` | POST | 生成或试运行 SQL |
| `/api/sql-runs/{run_id}/details` | POST | 生成或复用本次试运行的短期明细快照 |
| `/api/sql-runs/{run_id}/details/{group}` | GET | 分页查看统计范围、达到要求或未达到要求的脱敏明细 |
| `/api/sql-runs/{run_id}/exports` | POST | 二次确认后生成三工作表 Excel |
| `/api/indicator-exports` | GET | 查看当前医院仍有效的明细导出记录 |
| `/api/indicator-exports/{export_id}/download` | GET | 下载经过权限、医院范围、期限和哈希校验的 Excel |
| `/api/diagnose/run` | POST | 执行异常诊断 |
| `/api/kb/export` | GET | 导出医院知识库 zip |
| `/api/kb/merge/upload` | POST | 上传医院知识库 zip |
| `/api/kb/merge/reports` | GET | 查看合并报告列表 |
| `/api/kb/merge/report/{report_id}` | GET | 查看合并报告详情 |
| `/api/kb/merge/report/{report_id}/items/{item_id}/approve` | POST | 将回收项采纳为候选或保留在医院本地 |
| `/api/kb/merge/report/{report_id}/items/{item_id}/reject` | POST | 拒绝回收项 |
| `/api/kb/company/candidates` | GET | 查询待发布或指定状态的公司候选 |
| `/api/kb/company/releases` | GET、POST | 查看公司发布版本或从候选创建草稿 |
| `/api/kb/company/releases/{release_id}` | GET | 查看公司发布版本详情 |
| `/api/kb/company/releases/{release_id}/publish` | POST | 发布公司知识版本并追加标准历史 |
| `/api/kb/company/releases/{release_id}/export` | GET | 下载固定内容的公司发布包 |

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
- 从原始制度文档重新生成 Wiki 后
- 人工合并需要继续保存在 Wiki 中的说明文档后

医院口径审批、版本恢复、医院知识包导出、公司回收和公司版本发布均直接读写各自 MySQL，不依赖 Wiki 索引重建。Wiki 索引只服务于制度文档检索和 MySQL 故障时的只读兜底。

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
- `items` 包含后端服务、运行数据库、DBHub 服务、业务数据库、指标调度器和流程引擎。
- 指标调度器项展示启用计划数、已注册任务数和最近扫描时间。
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
MONITORING_SCHEDULER_UNAVAILABLE
```

工作流校验接口：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/core_indicator_chat/validate
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/indicator_monitoring/validate
```

如果 `ok=false`，先处理 `issues` 中的节点缺字段、重复节点或边引用不存在节点问题，再继续排查业务链路。

## 开发备注

本项目强调“结构化规则事实优先，LLM 辅助表达”。已迁移指标以 MySQL 生效规则为准，Wiki 负责来源审阅与故障兜底。任何涉及口径、公式、SQL 或医院覆盖规则的变更，都应通过工具链和测试验证后再提交。
