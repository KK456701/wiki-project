# 核心制度指标 Agent

> 更新日期：2026-07-22。当前生产对话入口为“业务语义规划 + 服务端计划编译 + 确定性执行 + 证据验证”的工具调用型 Agent，旧稳定流程和 Shadow 分流已经删除。

本项目是一个面向医院核心制度指标的本地化 Agent。已迁移指标以 MySQL 保存国标口径、医院定制口径及不可变版本，Markdown Wiki 作为规则导入来源和数据库故障时的只读兜底；系统同时提供 AI 问答、指标实施、审批发布、SQL 生成与试运行、明细审阅、异常诊断、运算监控和医院知识回收能力，前端以 AI 指标助手为主入口，并提供可直接操作的业务工作台。

项目默认适配本地部署场景：知识库文件保存在仓库内，模型通过 Ollama 调用本机模型，规则与运行状态保存在 MySQL，患者业务数据通过 DBHub 只读访问公司 SQL Server，适合医院内网或单机验证环境。

## 核心能力

- **MySQL 规则主存储**：四个首批指标的国标口径、医院定制口径、SQL 模板、字段映射和版本历史统一存入运行数据库；审批后的结构化规则可以立即生效、查询和回退，不依赖本地模型实时生成或重建 Wiki。
- **Wiki 导入与故障兜底**：`core-rules-wiki/` 保留原始规则、SQL 规格和字段映射；MySQL 暂时不可用或指标尚未迁移时只读回退，不接受兜底写入。
- **确定性工具直调 Agent 对话**：Planner 只生成不含工具名的业务语义计划，服务端编译并校验计划，由 StateController 与确定性参数编译器直接调用当前能力对应的受控工具；Verifier 校验规则、SQL、统计周期和数值链路后，最终回答模型只负责组织中文答案。
- **合成本院生效口径**：以国标为基础，查询时只合入本院已审批且处于生效期的差异项，不修改国标原始记录；没有有效本院差异时直接使用国标。
- **反馈与审批**：医院用户反馈口径不一致时先生成差异预览，用户确认后进入 Pending，管理员审批通过后才生效。
- **版本化医院口径**：医院 override 采用追加版本方式保存，支持历史口径对比和一键恢复历史版本。
- **SQL 生成与试运行分离**：根据指标 SQL 规格、字段要求和医院字段映射生成只读 SQL；“SQL 怎么写/先写出来”只返回经过校验的 SQL 预览，不访问业务数据，只有明确索要实际结果时才进入 DBHub 只读试运行。SQL 准备完成后由服务端直接使用校验证据生成最终回答，不再让 Final Answer 模型补充工具调用；临时连接中断会自动重试一次，并返回安全、可追踪的失败原因。
- **统计周期与结果意图确定性解析**：用户本轮明确说“从一月份到三月份”等时间范围时，由服务端统一解析为左闭右开区间（2026-01-01 至 2026-04-01）；本轮未提供时间时优先复用上一轮已确认的结构化周期。对于“怎么算，从一月份到现在”这类同时包含可解析周期和实际计算诉求、且没有异常诊断措辞的问法，即使 Planner 误判为公式解释或异常诊断，服务端也会纠偏为指标试运行；明确出现“异常、排查、原因、不一致”等措辞时仍保留诊断意图。Planner 自行生成的起止日期不能覆盖用户原文或已确认状态，因此 4B、8B 与 DeepSeek 使用同一统计边界。
- **规则 + 语义 + LLM 指标识别与复合查询**：服务端在用户整段原文中先扫描正式名称、简称和已审核别名，再对未命中的疑似指标片段进行本地字符语义召回；只有候选接近且无法唯一确认时，才让当前模型在服务端给定的 `rule_id` 候选内消歧，模型不能创造指标。识别到 2 至 3 个指标后，即使用户只用逗号、顿号或省略连接词，也会按原顺序拆成相互隔离的子任务并只解析一次公共统计周期。明确的公式、结果、SQL 和诊断请求由规则与语义分类生成各子任务业务计划，无法高置信分类时仍交给 Planner。完成后保存本轮全部 `rule_id`，后续“这两个的 SQL”“它们/他们的结果”“两者分别解释公式”“都计算一下”等复数指代继续按原周期独立执行并合并。
- **Agent 结果明细与 Excel 导出**：本轮只读试运行成功后，服务端根据经过校验的 `RUN_ID` 确定性追加“查看明细并导出 Excel”入口，不依赖模型生成特殊按钮。授权用户可查看统计范围（分母）、达到要求（分子）和未达到要求三组明细，并导出使用同一规则、医院、统计周期和快照的三工作表 Excel；明细边界同时兼容旧版平铺 `RunContext` 与新版 Agent 嵌套 SQL 快照，跨院访问、数量不一致、上下文不完整、过期或无权限时均以中文提示拒绝，不暴露内部校验信息。
- **Excel 上传分析与本院结果对比**：上传成功后，前端通过独立 `file_key` 字段把最近上传文件绑定到当前 Agent 会话，服务端保存安全文件引用；用户只说“分析刚上传的文件”也能确定性调用文件分析工具。用户要求与本院指标对比时，计划会先按明确统计周期取得本院试运行结果，再分析 Excel 并核对分子、分母和指标率；缺少指标名称或统计周期时直接澄清。当前存在上传文件时，“为什么我们的结果不一样”“哪些数据不一致”等追问由服务端确定性归一化为上传对比，即使 Planner 误判为普通诊断也不会调用错误工具；Runner 的请求类型始终服从已校验计划。对比回答不再附加普通患者明细导出，而是确定性提供“导出文件与系统差异表”，生成“对比摘要”“一致项”“不一致项”三张工作表，差值统一为上传文件值减系统值。只有汇总值的文件会明确标注无法判断患者级交集与差集，不会编造逐条差异。分析成功统一产生 `file_analysis` 完成证据，控制器不会再次调用同一工具，同时兼容历史会话中的 `upload_analysis` 旧证据。上传新文件会替换当前引用，新建会话会清除前端引用，工具仍按 `hospital_id` 阻止跨院访问。
- **指标实施控制台**：支持从自然语言创建“本院新增指标”，或把已有公司/国标指标适配到本院；实施任务依次完成取数要求确认、医院数据映射、确定性 SQL 生成、DBHub 试运行、审批和版本化发布，未发布任务不参与正式查询。
- **全面实施验收 MVP**：用户明确提出“全面实施验收、上线验收、迁移核对或全链路验收”并给出指标与统计周期时，Planner 只输出 `implementation_validation` 业务意图，服务端 IR 只开放一个 `validate_implementation` 专用能力。该能力内部固定执行 L1 字段映射与来源检查、L4 生效规则对齐、L5 受控 SQL 安全校验与只读试运行；当前会话存在上传文件时追加 L6 报表数据核对。各阶段失败形成结构化“未通过”报告，不触发自由 Replan 或重复工具循环；回答由服务端模板生成，并在 Trace 中显示四个独立阶段。正式 Excel/PDF 报告、L2/L3 和扩展 L5 仍属于后续批次。
- **三层异常诊断**：支持直接粘贴用户 SQL、参数和聚合结果；系统先做只读安全检查，再核对表字段，对比用户 SQL、国标与本院生效口径，并检查数据质量，输出医生可读结论和实施排障依据。
- **DBHub MCP 数据库接入**：通过本地 DBHub sidecar 读取业务库元数据、同步字段快照，并执行只读 SQL 试运行。
- **公司业务库真实取数**：`hospital_001` 默认连接 `WIN60_QA_991827`；已核验的急会诊指标从 `WINDBA.INPATIENT_CONSULT_APPLY` 与 `WINDBA.INP_CONSULT_INVITATION` 取数，入院 48 小时转科指标从 `WINDBA.INPATIENT_ENCOUNTER` 与 `WINDBA.INPAT_TRANSFER` 取数。两个指标的分子、分母、明细预览和 Excel 导出都共用各自同一份统计范围与字段映射。
- **计划编译与确定性状态控制**：当前生产链路为 `Planner → PlanCompiler/PlanValidator → StateController → DeterministicDispatch → ToolGateway → PlanVerifier → FinalAnswerLLM`；默认最多重规划一次，且只响应明确的语义计划错误、任务类型错误、用户目标改变或合法替代方向。数据库、权限和普通工具失败不触发 Replanner；诊断工具返回失败后立即停止，不再重复调用。工具选择和参数组装不再消耗本地模型推理。
- **版本化 CompiledPlan IR**：`CapabilitySpecRegistry` 是 Compiler、Controller、Dispatch 与 Verifier 的唯一能力定义源，固定声明依赖事实、产出事实、工具、参数编译器、策略动作、验证器、重试和回答模式。启动时检查能力环、重复 Fact Producer、未知工具及未知 Verifier，计划同时携带 IR、RequestPlan、Capability Registry、Prompt、模型适配器和 Verifier 版本。
- **轻量 Evidence Ledger**：工具成功结果先生成未验证 `EvidenceEnvelope`，只记录允许列表字段和 SQL/运行对象引用；Verifier 独立写入验证记录。最终回答只能消费 `VerifiedEvidence`。优先复用现有 MySQL，数据库不可用时自动降级到 `runtime/agent_evidence.jsonl`，不保存 SQL 原文或患者行级数据。
- **类型化策略执行边界**：不可变 `ToolExecutionContext` 显式携带登录主体、Agent 上下文、子任务、状态和策略决定；`PolicyDecisionService` 负责 allow/deny 与原因码，`ToolGateway` 继续作为真正阻止调用的 PEP，不引入 PydanticAI、OPA 或 Casbin Runtime。
- **全阶段运行 Trace 与运行观察**：成功和失败消息都可打开“查看链路”；认证后的 `/api/agent/runs/{trace_id}` 返回父子关系、子任务泳道、真实时间偏移、节点类型、模型与 Token、缓存、重试、FailureClass、版本和 Evidence 来源。规则命中与本地语义唯一命中时不会额外调用指标消歧 LLM；复合请求可确定性生成子计划时也会跳过 Planner，但每个实际调用的 `final_answer_llm` 都独立落在对应子任务泳道。页面提供瀑布图、调用树、筛选、最慢节点和证据定位；“Agent 运行观察”通过 `/api/agent/runs` 与 `/api/agent/runs/metrics` 展示当前医院的请求量、成功率、p50/p95/p99、工具/模型性能和轻量阈值提示。全部使用现有 MySQL/JSONL 与原生 HTML/CSS/JavaScript。
- **自适应复合任务并行**：不同指标使用独立 child state、Evidence namespace、Trace 泳道和 `subtask_id`。OpenAI 兼容 API 默认并发 2，本地 Ollama 默认并发 1，DBHub 只读工具默认并发 2；上传对比、规则变更、发布审批等任务保持串行，最终按用户输入顺序合并并允许局部失败。
- **轻量离线 Eval**：`evals/` 使用现有 PyYAML、Pydantic 和 pytest 覆盖别名/错别字、时间、跨轮、多指标、SQL 与试运行、文件对比、诊断边界和 Prompt Injection；模型矩阵只在用户显式运行脚本时调用 4B、8B 或 DeepSeek。
- **最终回答协议防护**：Final Answer 模型没有工具权限；如果模型仍把 DSML、`tool_calls`、`invoke` 或其他内部工具协议写入正文，ResponseGuard 会阻止展示，且在本轮已有完整已验证规则或试运行证据时直接使用确定性模板回答，不再重复请求模型输出同一种非法协议。没有足够证据时才返回安全错误，任何模型虚构工具都不会被执行。
- **提示词集中管理**：所有仍在使用的生产 LLM 提示词统一位于 `app/prompts/`；[`app/prompts/README.md`](app/prompts/README.md) 按 Planner、Final Answer、指标草稿和诊断列明每个文件的角色、调用者和触发时机。旧聊天意图识别与答案生成提示词及其加载分支已删除，Trace 配置显示当前 Agent 实际使用的提示词文件和短版本号。
- **恢复中心**：关键任务会写入恢复记录，服务异常中断后可在管理界面查看上次中断、可重试或已完成的任务。
- **指标监控工作台**：管理员可在前端新建、编辑、启停运行计划，手工运算指标，查看聚合结果和执行链路，并确认、关闭或重新诊断预警。
- **数据库与元数据工作台**：医院人员可在前端同步业务库结构，查看最近同步、表字段数量、结构变化和受影响指标；连接与只读工具信息集中在折叠详情中。
- **医学术语工作台**：维护 35 个核心制度指标涉及的标准概念、同义词、本院编码映射、审核状态和术语版本，并明确区分“可检索”和“可进 SQL”。
- **签名离线包交换**：医院按字段白名单生成 Ed25519 签名反馈包，公司验签后回收为候选；公司发布包在医院端先验签并进入隔离区，不会绕过本院适配、试运行和审批直接生效。
- **会话记忆**：用户问题和系统回答完整写入 SQLite 与 JSONL；结构化状态保存当前指标、医院和统计区间并保持最高优先级。最近 8 轮经过压缩后用于 Planner 理解“这个、后者、按你说的算”等指代，也用于最终回答模型组织连续对话，不会无限扩张上下文。
- **业务服务复用**：元数据解析、指标生成、口径适配和诊断等既有领域服务继续被工具及业务 API 复用；生产对话不再经过旧 `CoreIndicatorOrchestrator` 聊天流程。
- **类型化 Agent 契约**：Agent 之间通过 `app/agents/contracts.py` 中的 Pydantic 模型校验意图、规则检索、口径、字段映射、SQL、元数据预检查和诊断结果；API 与 SSE 边界继续输出兼容的 JSON 字典。
- **元数据预检查边界**：SQL 生成前由元数据解析 Agent 校验字段映射和运行库元数据，未通过时停止流程；指标生成 Agent 只消费已校验结果，不直接读取元数据。
- **业务工作台前端**：单页 HTML 前端以 AI 指标助手为主入口，并提供指标实施、指标监控、数据库与元数据、医学术语、审批和离线包交换等业务操作入口。
- **Java / Vue 渐进迁移前两批**：已在 `contracts/migration/v1/` 冻结 Agent REST、SSE、DBHub MCP、医院认证与规则只读契约；`backend-java/` 现已提供 Java 17 + Spring Boot 4.1 影子服务、DBHub 客户端、与 Python 兼容的 PBKDF2 登录会话，以及由登录主体强制注入医院范围的规则搜索和生效口径接口。`frontend-vue/` 提供 Vue 3 + TypeScript 登录、模型选择、SSE 对话、Excel 上传、证据轨道和 Trace 外壳。当前 FastAPI 仍是权威运行时，旧页面不删除，规则写入与 Agent 执行尚未切流；后续按单接口双跑、验收、切流和可回退方式迁移。完整计划见 [`docs/migration/java-vue-migration.md`](docs/migration/java-vue-migration.md)。

## 技术栈

- 后端：FastAPI、Pydantic、SQLAlchemy、PyMySQL；医院业务源为 SQL Server，只通过 DBHub 只读访问
- Agent：`/api/agent/chat` 提供非流式调用，主前端使用 `/api/agent/chat/stream` 接收 SSE 事件
- 编排：`app/agent_planning` 编译业务计划，`app/agent_runtime` 执行状态循环，`app/agent_tools` 提供受控工具、权限网关和 SQL 对象
- LLM：支持本地 Ollama 与 OpenAI 兼容 API；页面可选择 Qwen3 4B、Qwen3 8B 思考模式、DeepSeek V4 Flash 或 DeepSeek V4 Pro
- MCP：DBHub HTTP sidecar，用于数据库工具、元数据同步和只读 SQL 试运行
- SQL 模板：Jinja2
- 知识库：MySQL 保存已审核术语与版本，Wiki/YAML 保存公司语料来源和只读兜底，Markdown、YAML、JSON 索引服务制度文档检索
- 前端：原生 HTML/CSS/JavaScript，SSE 流式输出
- 迁移技术栈：Java 17、Spring Boot 4.1、Spring AI 2.0 BOM；Vue 3、TypeScript、Vite、Vue Router、Pinia。迁移版 Vue 当前代理现有 FastAPI，生产切换后静态资源进入 Spring Boot JAR
- 测试：Python `unittest` 测试套件，兼容使用 `pytest` 执行

## 运行架构与数据边界

主请求链路如下：

```text
浏览器模型选择器 -> POST /api/agent/chat/stream -> 登录与医院权限
       -> AgentRuntimeService -> HybridIndicatorResolver（规则精确匹配 -> 本地语义召回 -> 候选内 LLM 消歧）
       -> CompoundRequestSplitter（单指标注入已确认 rule_id；复合请求拆为 2 至 3 个隔离子任务）
       -> 每个子任务：确定性结果计划（带统一周期）或 LLM Planner（其他语义计划）
       -> CapabilitySpecRegistry -> PlanCompiler + PlanValidator -> StateController（查找缺失事实）
       -> DeterministicDispatch（从 CapabilitySpec 编译工具与参数）
       -> PolicyDecisionService -> ToolGateway（PEP）-> 规则 / SQL / 诊断 / 文件工具
       -> 明确全面实施验收：validate_indicator_implementation -> 固定 L1 / L4 / L5 / 可选 L6
       -> Evidence Ledger（未验证）-> PlanVerifier（验证记录）
       -> Final Answer LLM（仅 VerifiedEvidence）-> ResponseGuard
       -> CompoundResultMerge（自适应并发、按输入顺序合并）-> Trace + 会话记忆
```

| 数据或组件 | 当前职责 | 边界 |
|---|---|---|
| `wiki_agent_runtime` | 规则版本、医院口径、字段映射、元数据快照、审批、Trace、诊断、监控和导出审计 | 系统权威运行库 |
| 医院业务库 | 患者业务记录和指标计算原始数据 | 主服务通过 DBHub 只读访问；数据不出院 |
| `wiki_company_kb` | 公司候选、标准版本和发布包 | 与医院运行库分离，不保存医院患者明细 |
| `core-rules-wiki/` | 制度文档、规则与术语的可审阅导入来源 | MySQL 异常时只读兜底，不承接审批写入 |
| SQLite / JSONL | 会话记忆与请求事件辅助记录 | 不作为指标规则事实来源 |
| MySQL Evidence 表 / JSONL | Evidence Envelope 与独立验证记录 | 只保存安全允许列表和对象引用；MySQL 不可用时 JSONL 兜底 |
| `runtime/exports/` | 经授权生成的短期指标明细和 Excel | 默认 24 小时过期，目录不进入 Git |
| `tools/wxp-mcp` | 公司侧查询标准表模型的实施工具 | 不进入医院生产链路，不替代院内 DBHub |

完整运行细节见 [`docs/architecture/agent-runtime-current.md`](docs/architecture/agent-runtime-current.md)；精简流程图、PDF 核对结论以及 Spring AI/Java 迁移指南见 [`docs/architecture/agent-runtime-summary-and-spring-ai.md`](docs/architecture/agent-runtime-summary-and-spring-ai.md)。

## 目录结构

```text
.
+-- app/
|   +-- agent_planning/
|   +-- agent_understanding/
|   +-- agent_evidence/
|   +-- agent_runtime/
|   +-- agent_tools/
|   +-- implementation_validation/
|   +-- agents/
|   +-- api/
|   +-- db/
|   +-- db_access/
|   +-- diagnose/
|   +-- hospital_auth/
|   +-- indicator_details/
|   +-- indicators/
|   +-- kb/
|   +-- llm/
|   +-- memory/
|   +-- metadata/
|   +-- monitoring/
|   +-- observability/
|   +-- prompts/
|   +-- rules/
|   +-- sqlgen/
|   +-- tasks/
|   +-- terminology/
|   +-- workflows/
+-- core-rules-wiki/
|   +-- indexes/
|   +-- wiki/
|   +-- sql-specs/
|   +-- hospital-mappings/
|   +-- terminology/
|   +-- review/
|   +-- merge-reports/
+-- scripts/
|   +-- build_core_rules_wiki.py
|   +-- rebuild_runtime_indexes.py
|   +-- init_runtime_db.sql
|   +-- init_demo_hospital_db.sql
|   +-- migrate_runtime_schema.py
|   +-- seed_demo_hospital_data.py
|   +-- seed_demo_hospital_user.py
|   +-- compare_java_python_read_api.py
|   +-- simulate_metadata_drift.py
|   +-- seed_monitoring_baseline.py
|   +-- import_four_indicator_rules.py
|   +-- import_core_indicator_terms.py
|   +-- generate_package_keys.py
|   +-- kb_agent_demo.py
+-- tests/
+-- contracts/
|   +-- migration/
+-- backend-java/
+-- frontend-vue/
+-- evals/
+-- tools/
|   +-- dbhub/
|   +-- wxp-mcp/
+-- web/
|   +-- index.html
|   +-- workbench.css
|   +-- workbench.js
|   +-- indicator-console.css
|   +-- monitoring.css
|   +-- monitoring.js
|   +-- metadata.css
|   +-- metadata.js
|   +-- terminology.css
|   +-- terminology.js
|   +-- package-exchange.css
|   +-- package-exchange.js
+-- config.example.yaml
+-- requirements.txt
```

## 快速启动

### 1. 安装依赖

```powershell
cd F:\A-wiki-project
python -m pip install -r requirements.txt
Copy-Item config.example.yaml config.yaml
```

启动前编辑本机 `config.yaml`，填写本地数据库连接、管理员密码和 Ollama 模型。该文件已被 Git 忽略，不要提交真实账号、密码或令牌。

### 2. 启动 Ollama 模型

使用本地模型时，确保 Ollama 已运行，并拉取 4B 默认模型与可选 8B 思考模型：

```powershell
ollama pull qwen3:4B-instruct
ollama pull qwen3:8b
ollama serve
```

对话页会从 `config.yaml` 的 `models` 注册表读取模型，`default_model` 决定默认选择。DeepSeek 密钥只通过环境变量提供，不写入配置明文：

```yaml
agent_enabled: true
agent_planning_enabled: true
default_model: "ollama-qwen3"

models:
  - id: "ollama-qwen3"
    name: "Qwen3 4B（本地 Ollama）"
    provider: "ollama"
    model: "qwen3:4B-instruct"
    base_url: "http://127.0.0.1:11434"
  - id: "ollama-qwen3-8b-thinking"
    name: "Qwen3 8B 思考模式（本地 Ollama）"
    provider: "ollama"
    model: "qwen3:8b"
    base_url: "http://127.0.0.1:11434"
    thinking: true
    planner_thinking: false
    call_timeout_seconds: 120
    request_timeout_seconds: 300
  - id: "deepseek-v4-flash"
    name: "DeepSeek V4 Flash（API）"
    provider: "openai"
    model: "deepseek-v4-flash"
    base_url: "https://api.deepseek.com"
    api_key: "${DEEPSEEK_API_KEY}"
  - id: "deepseek-v4-pro"
    name: "DeepSeek V4 Pro（API）"
    provider: "openai"
    model: "deepseek-v4-pro"
    base_url: "https://api.deepseek.com"
    api_key: "${DEEPSEEK_API_KEY}"
```

轻量 Trace、性能提示和复合任务并发使用以下配置；这些设置不要求部署额外服务：

```yaml
agent_trace_retention_days: 30
agent_trace_slow_request_ms: 120000
agent_trace_slow_llm_ms: 60000
agent_trace_tool_failure_warning_rate: 0.05
agent_trace_timeout_warning_rate: 0.05

compound_api_concurrency: 2
compound_ollama_concurrency: 1
compound_db_concurrency: 2
```

完整升级说明、数据库迁移和分批验收命令见 [`docs/operations/2026-07-20-lightweight-agent-runtime-upgrade.md`](docs/operations/2026-07-20-lightweight-agent-runtime-upgrade.md)。

本项目不会把完整聊天记录无限塞给本地模型。每次请求由两部分组成：

- **结构化会话状态**：当前指标、统计区间和本次会话临时口径，是后续生成 SQL 与试运行的权威依据。
- **最近 8 轮原始消息**：经过压缩后提供给 Planner 和 Final Answer，帮助理解“这个指标”“后者”“按入区算”等自然语言追问；长 SQL 和长工具结果会自动压缩，历史原文与结构化状态冲突时以后者为准。

`ollama_num_ctx: 16384` 是当前 4B 本地模型兼顾速度、内存占用和稳定性的默认值。修改这些参数后需要重启 FastAPI 后端；系统会在每次 Ollama 请求中显式传入上下文窗口，不依赖 Ollama 的 4096 默认加载值。

### 3. 初始化数据库

项目包含两类数据库，连接信息在 `config.yaml`：

```yaml
runtime_db_url: "mysql+pymysql://USER:PASSWORD@127.0.0.1:3306/wiki_agent_runtime?charset=utf8mb4"
business_db_source_id: "win60_qa_991827"
business_db_dialect: "sqlserver"
business_db_database: "WIN60_QA_991827"
business_db_schema: "WINDBA"
```

医院端必须初始化 MySQL 运行库：

```powershell
mysql -uroot -p -e "SOURCE F:/A-wiki-project/scripts/init_runtime_db.sql"
```

只有需要保留旧版演示环境时，才初始化虚构业务库：

```powershell
mysql -uroot -p -e "SOURCE F:/A-wiki-project/scripts/init_demo_hospital_db.sql"
```

`runtime_db_url` 供系统读写运行数据；`business_db_url` 只供演示造数和结构漂移脚本直接连接本地演示库。FastAPI 正式指标查询、元数据同步和试运行仍通过 DBHub 使用医院只读账号访问业务库。

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

该命令可以重复执行：国标和医院映射按唯一键更新，不会重复创建初始医院口径版本。正式导入会读取 `config.yaml` 中的公司业务源配置。当前落地状态为：

| 指标编码 | 指标名称 | 公司库适配状态 |
|---|---|---|
| `MQSI2025_001` | 患者入院 48 小时内转科比例 | 已核验并启用 SQL Server 聚合与明细取数 |
| `MQSI2025_005` | 急会诊及时到位率 | 已核验并启用 SQL Server 聚合与明细取数 |
| `MQSI2025_014` | 急危重症患者抢救成功率 | 待 WxP 与实际库核验字段，禁止生成正式 SQL |
| `MQSI2025_035` | 术中自体血回输率 | 待 WxP 与实际库核验字段，禁止生成正式 SQL |

`MQSI2025_001` 的正式医院配置还必须填写跨科转科代码、病区转移代码和本院 ICU 科室/病区组织 ID。系统以 `INPATIENT_ENCOUNTER.ADMITTED_AT` 作为入院时间，统计区间采用左闭右开；每次住院只取按实际转科时间、转科记录 ID 排序后的最早一条有效非 ICU 转科。48 小时按分钟计算为 `0 <= 入院至转科分钟数 <= 2880`，边界记录计入分子。任一医院参数为空时导入失败关闭，不生成看似可用但口径不完整的正式 SQL。

也可以在服务启动后使用管理员令牌触发同一导入流程：

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8765/api/rules/import-four `
  -Headers @{ Authorization = "Bearer <admin_token>" }
```

公司回收与发布服务使用独立的 `wiki_company_kb`，不得与任一医院运行库共用 schema。公司端在 `config.yaml` 增加：

```yaml
company_db_url: "mysql+pymysql://USER:PASSWORD@127.0.0.1:3306/wiki_company_kb?charset=utf8mb4"
```

初始化公司知识中心并写入首批四指标标准版本 1：

```powershell
mysql -uroot -p -e "SOURCE F:/A-wiki-project/scripts/init_company_kb_db.sql"
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
Copy-Item dbhub.toml.example dbhub.local.toml
# 编辑 dbhub.local.toml，填写医院本地只读数据库账号
npm install
.\start-dbhub.ps1
```

默认 Workbench：

```text
http://127.0.0.1:8080/
```

项目默认期望 DBHub MCP HTTP 地址为：

```yaml
dbhub_mcp_url: "http://127.0.0.1:8080/mcp"
dbhub_source_id_win60_qa_991827: "win60_qa_991827"
dbhub_execute_tool_win60_qa_991827: "execute_sql_win60_qa_991827"
```

在 `tools/dbhub/dbhub.local.toml` 配置公司 SQL Server 只读账号；不得提交该本地文件。公司业务工具的 `max_rows` 设为 `20001`，与系统“最多导出 20000 条，额外 1 条用于溢出判断”的安全边界一致。

### 公司表模型工具（仅公司侧实施）

`tools/wxp-mcp` 用于查询公司 WxP 标准表模型、字段、索引和数据血缘，帮助实施人员确认指标设计稿中的字段映射。它不进入医院生产环境，安装与验证见 [`tools/wxp-mcp/README.md`](tools/wxp-mcp/README.md)。

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

### 业务工作台

当前前端采用“AI 负责理解和发起任务、业务页面负责审核和执行”的工作台结构。医院人员登录后默认进入 `#/assistant` AI 指标助手首页，普通问答不需要管理员权限。

- 左侧第一项固定为“AI 指标助手”，业务导航只展示已经完成并可正常操作的专业页面，当前包括“指标实施控制台”“指标运算监控”和“数据库与元数据”。
- 桌面端 AI 首页使用沉浸式工具轨道，传统品牌顶栏在该页面隐藏；医院、用户和系统工具固定在右上角，对话区域从窗口顶部开始。
- 进入指标运算监控等专业业务页后，完整顶栏和文字侧栏自动恢复；移动端继续使用横向导航，不采用窄工具轨道。
- 从左侧进入“指标运算监控”时才验证管理员权限，验证成功后仍停留在 `#/monitoring`，不会跳转到审批功能。
- 专业页面右上角可打开 AI 助手抽屉；返回 AI 首页后继续同一会话，关闭抽屉不会清空消息、输入内容或业务页面状态。
- “系统自检”和“恢复中心”位于顶部“系统工具”菜单。
- 智能异常排查和口径规则管理会在后续子批次逐页迁移；页面完成前不显示不可操作的空白导航项。
- “指标设计稿”已迁移为正式“指标实施控制台”；审批与版本仍保留在现有工具区。

### 数据库与元数据工作台

医院人员从左侧点击“数据库与元数据”进入正式工作台，不需要使用 DBHub 测试页面或命令行：

1. 确认顶部医院编号和“医院业务库”选择正确。
2. 点击“同步数据库结构”，页面会读取该库的数据库对象清单，并完整读取当前已实施指标所依赖表的字段定义；处理期间禁止重复提交。
3. 同步完成后查看最近同步时间、数据库对象数量、指标依赖字段数量和本批结构变化。
4. “受影响指标”会根据本院字段映射列出可能受表字段变化影响的指标；没有命中时明确显示本次无影响。
5. 需要实施排障时展开“连接详情”，查看医院业务库、系统管理库、DBHub 和 MCP 只读工具状态；系统管理库不会成为医院业务元数据的默认同步目标。

元数据同步只读取 `INFORMATION_SCHEMA.TABLES` 与 `INFORMATION_SCHEMA.COLUMNS`，不读取患者业务数据，不修改医院业务库，也不展示数据库密码。对于公司 SQL Server 这类超大模型库，系统同步全部数据库对象名称，但字段只同步当前指标映射实际依赖的表，避免 DBHub 行数上限造成“同步成功但关键字段缺失”，也避免一次交互写入十几万无关字段。新增指标实施时，先确认候选表并建立字段映射，再次同步即可把对应表的完整字段纳入运行元数据。页面重新打开后会从运行库读取最近一次成功快照；同步失败时保留上一次成功结果。

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

当前工具调用型 Agent 会执行：

1. 从结构化状态与最近 8 轮对话理解指标指代和统计时间。
2. 服务端识别明确的复合指标连接词；单指标直接继续，复合请求确定性拆成 2 至 3 个隔离子任务并统一统计周期。
3. 带统一统计周期的结果子任务由服务端生成严格业务计划；其他子任务由 Planner 输出不含工具名的计划 JSON。
4. 服务端编译、校验计划并确定下一项业务能力。
5. StateController 确定下一项能力，确定性参数编译器根据计划、状态和已验证证据生成唯一受控工具调用，不再请求模型选择工具。
6. 工具从 MySQL 读取本院生效口径，必要时经 DBHub 准备并试运行只读 SQL。
7. Verifier 校验 `rule_id`、规则版本、统计区间、`sql_id`、结果 ID 和分子分母一致性。
8. 模型只根据当前子任务证据组织中文回答；服务端合并全部子任务结果，并通过 SSE 输出工具摘要、答案和 Trace ID，完整安全参数只通过登录后的“查看链路”读取。

Planner JSON 形状错误时最多修复一次；执行方向错误时最多重规划一次。缺少时间、指标候选不唯一或业务确认时返回明确澄清，不再切换到旧聊天流程。生效口径接口和执行链路会返回 `rule_source`：正常为 `mysql`，数据库异常或指标未迁移时为 `wiki_fallback`，同时附带只读兜底警告。

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
- 对话正文只展示聚合数量、统计区间、口径版本和数据源；“查看链路”可展示本轮实际模型上下文、计划、工具参数、聚合结果和 SQL 相关安全参数，但不记录患者姓名、病历号、患者行级业务明细、凭据或连接串。

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
2. 继续输入“生成 SQL”。默认区域应显示数据来自 `WIN60_QA_991827`，并列出 `WINDBA.INPATIENT_CONSULT_APPLY`、`WINDBA.INP_CONSULT_INVITATION` 以及医院、急会诊级别、申请时间和签收时间对应的真实字段。
3. “分子与分母怎么计算”应说明分母按医院、会诊类型和统计时间筛选后计数；分子用到位时间减申请时间，在本院20分钟阈值内记为达到要求；最终用分子除以分母并乘以100。
4. 默认区域应显示本院采用20分钟、标准值为10分钟，并注明“只影响分子，不改变分母”；完整 SQL 仍不可见。点击“查看技术详情（供信息科和实施人员）”后，应显示完整字段血缘与 SQL。
5. 输入“试运行”。回答应使用业务语言显示公司库当前统计周期内的统计范围数量、达到要求数量、未达到要求数量及实际计算式；数据会随公司测试库变化，不使用固定 demo 数字验收。
6. 点击“查看链路”，确认能看到中英文节点名、节点类型、耗时、完整安全输入输出、数据处理和配置；不得出现患者明细、凭据、连接串或隐藏思维链。

验证入院 48 小时转科指标时，在新会话输入“患者入院48小时内转科的比例怎么算？”，再输入“生成 SQL”和“试运行”：

1. 数据来源应只显示 `WIN60_QA_991827` 的 `WINDBA.INPATIENT_ENCOUNTER`、`WINDBA.INPAT_TRANSFER`，不应引用本地 Demo 表或隐含第三张业务表。
2. 口径说明应明确起算时间为办理住院时间，48 小时边界包含第 2880 分钟，只取最早有效转科，并排除本院配置的 ICU 科室/病区。
3. 点击统计范围、达到要求或未达到要求的“查看详情”，明细总数应与汇总分母一致，明细中“达到要求”的条数应与汇总分子一致；Excel 导出沿用同一快照。
4. 当前公司 QA 库在 `2026-06-01 00:00:00` 至 `2026-08-01 00:00:00` 的只读冒烟结果为分子 2、分母 158、比例 1.27%。该数字只用于当前测试库连通性与一致性核对，后续测试数据变化时应以实时结果为准。

### 指标实施控制台

医院人员从左侧点击“指标实施控制台”，按五个业务步骤完成指标落地：

```text
定义指标 -> 确认取数要求 -> 映射医院数据 -> 生成和验证 -> 审批和发布
```

1. 选择“已有指标医院适配”或“本院新增指标”，用自然语言说明指标和本院差异。
2. 先确认分母从哪些业务记录得到、分子如何从分母中筛选，以及最终计算方式；此时不会生成或执行 SQL。
3. 系统根据 `med_metadata_column` 最近一次元数据快照推荐本院字段。页面使用“申请时间”“到位时间”等业务名称，并同时显示实际数据库、表和字段；缺少字段时先到“数据库与元数据”同步结构。
4. 字段确认后，系统根据强类型计算计划确定性渲染参数化只读 SQL，再选择统计区间通过 DBHub 在院内业务库试运行。
5. 只有当前版本试运行成功后才能提交审批；审批发布后才进入本院生效规则，未发布实施任务不会影响正式查询。

- **实施任务**：尚未进入正式规则库的工作副本，保留状态、当前版本和不可变版本快照。
- **本院新增指标**：没有对应公司或国标指标，由本院自行定义；审批后写入 `med_index_hospital_defined`。
- **已有指标医院适配**：以公司或国标规则为业务基础，确认本院实际字段和必要的本院口径差异；审批后写入 `med_index_hospital_custom`，不修改 `med_index_standard`。
- SQL 不由 LLM 无约束直接编写。LLM 只生成强类型计算计划，系统固定 `hospital_id` 租户范围并确定性渲染参数化 `SELECT`；当前支持单表比例/计数及两个时间字段的分钟差条件。
- 任何口径编辑都会使旧 SQL 和试运行证据失效，必须重新生成和试运行。

该闭环的 Dify-lite 节点清单可通过 `/api/workflows/indicator_generation_closed_loop` 查看，并通过 `/api/workflows/indicator_generation_closed_loop/validate` 校验。

### 执行链路与故障定位

工具调用型 Agent 对话、元数据同步、诊断、变更提交和审批都会生成 `trace_id`。前端消息下方的“查看链路”按钮会打开 Trace 弹窗：

- 默认只展示业务摘要：执行步骤、节点标题、处理结果、中文状态和该阶段耗时，不展示节点 ID、工具名等开发字段。
- 顶部按 LLM、工具、代码和存储汇总耗时；横向瀑布图以真实开始偏移对齐节点，复合指标按 `subtask_id` 展开泳道，同时提供父子调用树、状态/类型筛选、最慢节点、Token、缓存、重试、版本及 Evidence 来源定位。历史 Trace 缺少新字段时按顺序降级展示。
- Agent 对话链路来自认证后的 `/api/agent/runs/{trace_id}`，展示真实业务计划、完整安全模型上下文、实际工具参数与结果、校验状态和结束原因；“完整”不包括模型隐藏思维过程、密钥、连接串和患者行级明细。
- 节点同时显示中文名与英文 ID，并用紫色区分 LLM、蓝色区分确定性代码、橙色区分工具/数据库、绿色区分记忆/存储；失败状态额外使用红色。
- 其他业务工作流仍可在“详情”中查看结构化处理结果、节点配置和必要的问题码，定位建议只在失败、回退或字段检查异常时显示。
- 正常节点默认折叠；异常节点自动展开，避免重要故障被隐藏。
- 如果节点没有真实计时，前端显示“未计时”，不会再把未测量的节点误展示为 `0ms`。
- Agent 对话运行数据由 `TraceRecorder` 写入运行库和 JSONL，节点元数据来自 `app/workflows/agent_runtime.yaml`；指标实施等非对话业务流程继续使用各自的工作流 manifest。
- 失败步骤会保留稳定的结束原因或问题码，例如计划无效、工具失败、SQL 校验失败或需要用户澄清。

左侧“Agent 运行观察”调用当前医院授权接口 `/api/agent/runs` 与 `/api/agent/runs/metrics`，支持按时间、模型、状态、工具和 FailureClass 过滤。聚合接口只返回安全摘要和性能数据，不返回完整上下文、SQL 或患者数据，也不依赖 Prometheus、Grafana、Tempo 或其他服务。

Agent 对话的典型阶段包括：

```text
memory_load -> planner -> plan_compile_and_validate -> state_controller
-> deterministic_tool_dispatch -> tool_gateway -> tool_result -> plan_verify
-> final_answer_llm -> response_guard -> memory_save
```

查询实际指标结果时，受控能力链会继续展开：

```text
resolve_indicator -> resolve_caliber -> inspect_implementation
-> prepare_sql -> trial_run -> verify_result -> explain_result
```

明确要求排查异常时，才会进入诊断能力：

```text
resolve_indicator -> resolve_caliber -> inspect_implementation
-> diagnose_indicator -> verify_diagnosis -> explain_result
```

用户粘贴 SQL、参数或执行结果时，会显示更细的七个业务阶段：

```text
识别排查材料 -> 检查 SQL 安全 -> 试运行用户 SQL -> 核对表字段
-> 比较计算口径 -> 检查数据质量 -> 生成诊断结论
```

默认摘要只显示每个阶段的状态、耗时和一句话结论；点击“详情”后可查看参数名、表字段、差异代码和用户/国标/本院三方聚合值。执行链路不保存用户原始 SQL、绑定后的 SQL 或患者明细。写操作、动态 SQL、临时表、跨库查询、多语句和未赋值变量不会在医院业务库执行，页面会显示“未执行，已完成静态分析”。

其中 `diagnose_rule_check` 不再只是静态公式检查。对于已迁移且存在有效医院定制口径的指标，它会在相同医院、字段映射和统计周期下，通过 DBHub 分别执行纯国标口径和本院生效口径。节点摘要显示对比结论；展开详情后可查看两侧版本、执行状态、聚合结果、样本量、耗时、差值和运行 ID，不展示绑定后的 SQL 或患者明细。

### 粘贴 SQL 异常诊断

医生或实施人员不需要使用命令行。在 AI 指标助手中先询问指标，或沿用当前会话已经命中的指标，然后直接粘贴以下内容：

1. 当前执行的 SQL；SQL Server 脚本可以带 `USE` 和已赋值的 `DECLARE` 参数。
2. 本次统计区间和必要参数；如果已写在 `DECLARE` 中不需要重复填写。
3. 已有的聚合结果，例如“分子 2，分母 158，指标结果 1.27%”。不要粘贴患者姓名、病历号或完整患者明细。
4. 最后说明问题，例如“为什么我们算得和系统不一样”。

系统会在医院只读业务库中自动试运行通过安全检查的 SQL，并用相同医院和统计区间分别运行国标口径与本院生效口径。两段 SQL 都能执行但结果不同的场景，会优先解释统计时间字段、计时起点、时间边界、ICU 范围、事件选择、空值处理和分子分母差异，不会笼统归因于数据库故障。本地模型不可用或输出不符合要求时，系统会使用确定性中文模板完成说明。

前端验收步骤：

1. 输入“患者入院 48 小时内转科的比例怎么算？”。
2. 粘贴 SQL、参数和聚合结果，并询问“为什么我们算得不一样”。
3. 回答应先给出结论，再分别说明“不一致原因、对结果的影响、建议怎么处理”；普通回答不会展示“第一层、第二层、第三层”等内部结构。
4. 点击“查看链路”，确认七个新节点均有独立状态；详情只包含参数名、表字段、差异项、聚合值和运行 ID。
5. 再粘贴一条 `UPDATE` 验证安全边界；系统不得执行该 SQL，但仍应给出阻止原因和静态分析建议。

诊断只生成证据和建议，不会自动修改 SQL、发布口径或执行修复语句。需要调整本院口径时，仍须进入现有指标实施与审批流程，完成字段确认、只读试运行和人工批准后才会生效。

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
2. 输入“统计患者入院 48 小时内转科的比例，从 2026 年一月份到三月份”。
3. 在聚合结果回答末尾点击“查看明细并导出 Excel”，再切换“统计范围（分母）”“达到要求（分子）”“未达到要求”三个标签。
4. 核对三个标签的数量与本次计算结果一致；页面预览脱敏，Excel 保留授权完整值。
5. 点击“生成并下载 Excel”，阅读患者明细使用提示并确认。文件应包含“统计范围”“达到要求”“未达到要求”三个工作表，表头应写明本院口径版本和统计区间。

上传汇总指标文件并要求“和本院系统结果对比”时，回答末尾应显示“导出文件与系统差异表”，而不是普通明细按钮。下载文件包含“对比摘要”“一致项_N”“不一致项_N”三张工作表，并逐项列出系统值、上传文件值、差异、单位和结论。若上传文件只有分子、分母、指标率一行汇总值，工作簿会明确说明只能做汇总级对比；如需定位具体哪些患者记录相同或不同，上传文件必须提供入院流水号等可核对的逐条标识。

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

常见定位：没有“查看明细并导出 Excel”按钮时需确认本轮是否成功完成只读试运行；提示缺少口径快照时需重新试运行；提示业务数据变化时需重新试运行后再查看；提示权限不足时检查账号权限；文件过期后需重新生成。相关状态记录在 `med_indicator_detail_snapshot`、`med_indicator_export` 和 `med_data_access_audit`，排障时无需打开患者明细文件。

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

在公司测试库中，第 2 层会分别执行国标 10 分钟口径和本院 20 分钟口径；只有两者实际结果不同时才显示 `caliber_result_diff`，不再使用固定 demo 百分比验收。也可以在前端输入“急会诊及时到位率结果异常，帮我诊断一下”，再点击消息下方“执行链路”，展开“诊断口径规则”节点查看；对话入口未指定周期时默认使用当前自然月。

输出会区分：

- 通过
- 通过但有风险
- 不通过

### 签名离线包交换

医院和公司之间不需要网络互通，使用双向签名包交换指标知识。医院私钥只用于证明“反馈确实来自本院”，公司私钥只用于证明“发布确实来自公司”；私钥不得随包传输，也不得由对方保存。

| 部署端 | 本端保管的私钥 | 本端信任的公钥 |
|---|---|---|
| 医院端 | `hospital-private.pem` | `trusted-companies/company_main.pem` |
| 公司端 | `company-private.pem` | `trusted-hospitals/{hospital_id}.pem` |

本地演示可一次性生成两组 Ed25519 密钥：

```powershell
python -B scripts\generate_package_keys.py `
  --output runtime/package-keys `
  --hospital-id hospital_001 `
  --company-id company_main
```

生成目录已被 Git 忽略。正式部署时应在医院端和公司端分别生成私钥，只交换公钥，并通过操作系统权限或密钥管理服务限制私钥读取。对应路径在 `config.yaml` 中配置：

```yaml
hospital_package_signing_key_path: "runtime/package-keys/hospital-private.pem"
hospital_package_signing_key_id: "hospital_001"
trusted_hospital_keys_dir: "runtime/package-keys/trusted-hospitals"
company_package_signing_key_path: "runtime/package-keys/company-private.pem"
company_package_signing_key_id: "company_main"
trusted_company_keys_dir: "runtime/package-keys/trusted-companies"
```

医院日常操作全部在前端完成：

1. 进入“数据库与元数据”，切换到“离线包交换”。
2. 在“医院反馈包”中按表勾选允许带出院区的字段并保存。只有已同步元数据中的字段可以进入白名单。
3. 点击“检查包内容”，核对表数、字段数和明确排除项，再由管理员点击“生成并下载反馈包”。
4. `kb-exchange-v4` 反馈包包含白名单内的表字段定义、已确认字段映射、当前已审批且生效的本院口径和最近一次成功的聚合验证结果；不包含患者明细、样例值、字段默认值、数据库地址、账号密码、绑定 SQL 或其他医院数据。
5. 公司验签后将内容写入公司暂存区并生成差异项。重复上传完全相同的包不会重复创建数据；相同包编号但内容不同会被拒绝。

公司管理员逐项审核反馈候选后创建并发布公司版本：

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

发布后通过 `GET /api/kb/company/releases/{release_id}/export` 下载签名的 `company-release-v3`。医院管理员仍在“离线包交换”中选择该文件并点击“验签并导入隔离区”：签名有效且版本兼容时状态为“待本院适配”，签名不可信或旧版未签名包只能隔离查看，绝不写入当前生效规则。对包内指标点击“进入本院适配”后，系统创建实施任务并自动打开指标实施控制台；后续仍需完成本院字段映射、SQL 生成、院内试运行和审批。

`INFORMATION_SCHEMA` 与反馈包中的结构可以证明“表和字段存在”，不能证明“指标业务含义映射正确”或“真实数据计算结果正确”。因此，结构校验不能替代院内真实数据试运行：任何新增或调整指标都必须在医院只读业务库中核对分子、分母、边界记录和聚合结果后才能生效。

## API 概览

| 接口 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 前端页面 |
| `/api/health` | GET | 健康检查 |
| `/api/health/summary` | GET | 系统自检摘要，返回中文状态、处理建议和问题码 |
| `/api/health/dependencies` | GET | 依赖原始检查，返回 runtime DB、DBHub、业务库 MCP 等状态和问题码 |
| `/api/agent/capabilities` | GET | 获取 Agent 状态和可选模型列表；不返回密钥或医院数据 |
| `/api/agent/chat` | POST | 需要医院登录的非流式工具调用型 Agent 对话，支持可选 `model_id` 和 `file_key` |
| `/api/agent/chat/stream` | POST | 需要医院登录的 SSE 流式工具调用型 Agent 对话，支持可选 `model_id` 和 `file_key` |
| `/api/agent/upload` | POST | 上传不超过 10MB 的 Excel，供受控文件分析工具使用 |
| `/api/agent/runs/{trace_id}` | GET | 按当前医院权限查看 Agent 计划与工具证据链 |
| `/api/agent/runs` | GET | 按时间、模型、状态、工具和 FailureClass 查询当前医院安全运行摘要 |
| `/api/agent/runs/metrics` | GET | 查询当前医院请求量、成功率、分位耗时、工具与模型聚合性能 |
| `/api/traces/{trace_id}` | GET | 查看执行链路 Trace |
| `/api/workflows/{workflow_id}` | GET | 查看工作流 manifest |
| `/api/workflows/{workflow_id}/validate` | GET | 校验工作流 manifest 节点和边 |
| `/api/indicator-drafts/generate` | POST | 从自然语言生成并保存指标设计稿 |
| `/api/indicator-drafts/from-release` | POST | 从已验签且兼容的公司发布包规则创建本院适配任务 |
| `/api/indicator-drafts` | GET | 按医院和状态查看设计稿 |
| `/api/indicator-drafts/{draft_id}` | GET、PUT | 查看或创建设计稿新版本 |
| `/api/indicator-drafts/{draft_id}/requirements-confirm` | POST | 确认当前版本分子、分母和所需数据项 |
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
| `/api/sql-runs/{run_id}/upload-comparison-exports` | POST | 汇总文件生成一致项/不一致项；同指标明细文件生成双方都有、仅系统有、仅上传文件有的逐条差异表 |

上传文件只有分子、分母、指标率等汇总值时，系统只报告可验证的数值差异，并明确标记无法诊断具体原因；不会把重复记录、统计周期、ICU 排除或字段映射写成推测性结论。若要定位具体差异记录，上传明细至少应包含 `admission_id`、`admit_time`、`transfer_time`、`from_dept_id`、`to_dept_id`。聊天中的“导出文件与系统差异表”按钮会直接生成受控汇总差异 Excel，仍受 `indicator_detail_export` 权限、医院隔离、审计与 24 小时清理约束。

系统生成的指标明细 Excel 会在每个工作表头部写入指标编号。再次上传这类文件时，分析工具先校验指标编号与当前试运行 `rule_id`：不同指标直接拒绝比较，避免把其他指标的编号、科室代码或时间值误识别为指标率；同一指标则以患者/业务标识和关键事件时间组成匹配键，按重复次数执行多重集合核对。逐条差异 Excel 固定包含“对比摘要”“双方都有_N”“仅系统有_N”“仅上传文件有_N”，其中“双方都有”还会列出同一记录的字段差异。患者级原始值不进入模型上下文，模型只接收统计区间、三组计数、匹配字段和已确认差异证据。
| `/api/indicator-exports` | GET | 查看当前医院仍有效的明细导出记录 |
| `/api/indicator-exports/{export_id}/download` | GET | 下载经过权限、医院范围、期限和哈希校验的 Excel |
| `/api/diagnose/run` | POST | 执行异常诊断 |
| `/api/kb/export/scope` | GET、PUT | 查看或保存医院反馈包元数据字段白名单 |
| `/api/kb/export/preview` | GET | 预览反馈包将包含和排除的结构信息 |
| `/api/kb/export` | GET | 生成医院签名反馈包 |
| `/api/kb/merge/upload` | POST | 公司验签并回收医院反馈包 |
| `/api/kb/merge/reports` | GET | 查看合并报告列表 |
| `/api/kb/merge/report/{report_id}` | GET | 查看合并报告详情 |
| `/api/kb/merge/report/{report_id}/items/{item_id}/approve` | POST | 将回收项采纳为候选或保留在医院本地 |
| `/api/kb/merge/report/{report_id}/items/{item_id}/reject` | POST | 拒绝回收项 |
| `/api/kb/company/candidates` | GET | 查询待发布或指定状态的公司候选 |
| `/api/kb/company/releases` | GET、POST | 查看公司发布版本或从候选创建草稿 |
| `/api/kb/company/releases/{release_id}` | GET | 查看公司发布版本详情 |
| `/api/kb/company/releases/{release_id}/publish` | POST | 发布公司知识版本并追加标准历史 |
| `/api/kb/company/releases/{release_id}/export` | GET | 下载固定内容的公司发布包 |
| `/api/kb/hospital/releases/imports` | GET、POST | 查看记录或验签导入公司发布包到隔离区 |
| `/api/kb/hospital/releases/imports/{import_id}` | GET | 查看院内隔离包的签名、兼容性和项目详情 |

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

### 为什么选择 MySQL 作为规则主存储

指标口径、分子分母、字段映射、SQL 模板、医院差异和生效版本属于**结构化业务事实**。这类数据需要唯一约束、事务、审批状态、有效期、医院隔离、版本追加和历史回退，MySQL 比直接修改 Markdown/YAML 文件更适合作为正式运行依据：

- **一致性明确**：一次审批可以在同一事务中写入口径、版本和审计记录，失败时整体回滚，不会出现多个 Wiki 文件只更新了一部分的情况。
- **生效及时**：本院口径审批通过后，下一次查询即可按 `hospital_id`、状态和有效期读取新版本，不需要等待模型重新整理文档或重建全文索引。
- **查询稳定**：系统可以确定性检索指定指标、医院和版本，不依赖本地模型从长文档中再次理解分子、分母或猜测当前生效版本。
- **审计和回退可靠**：历史版本以追加方式保存，能够回答谁在什么时间修改了什么，并恢复到指定版本；文件覆盖难以提供同等级别的并发控制和审计能力。
- **医院隔离清晰**：医院部署时规则和运行数据保存在本院 MySQL；同一医院实例内再用 `hospital_id` 限定本院口径。公司知识中心使用独立的 `wiki_company_kb`，不与医院运行库共用事实表。
- **SQL 生成可控**：生成链路读取已确认的结构化计算定义、字段映射和元数据快照，再确定性渲染只读 SQL。LLM 只辅助理解用户问法和组织说明，不直接决定正式分子、分母或写入生效规则。

这项选择也考虑了医院使用本地模型时的资源限制。本地小模型通常需要在有限 CPU、内存或显存下运行；如果让模型同时负责生成和持续维护整套 Wiki，每次口径变化都可能涉及长文档读取、跨文件改写、格式校验、重复内容消解和索引重建。该过程耗时更长，而且模型输出存在非确定性，容易出现字段遗漏、Markdown/YAML 格式漂移、同一规则多种表述或文档版本与实际 SQL 不一致。文件更新本身也缺少数据库事务，服务中断时更难保证所有关联文件处于同一版本。

因此当前采用以下职责划分：

| 能力 | MySQL | Wiki / YAML | 本地模型 |
|---|---|---|---|
| 已审批口径、医院差异、版本和有效期 | 权威存储 | 保留来源或导出说明 | 不直接写入 |
| 字段映射、结构化计算定义和 SQL 模板 | 权威存储 | 可审阅导入来源 | 可辅助生成草稿 |
| 制度原文、长篇说明和实施知识 | 保存必要的结构化索引 | 主要载体 | 负责理解、摘要和问答 |
| 同义词和本院编码映射 | 保存审核后生效版本 | 保存公司语料来源 | 负责识别候选，不越过审批 |
| 数据库异常时继续查询 | 不可用时停止写入 | 提供只读兜底 | 根据兜底内容组织回答 |

这不是用 MySQL 完全替代知识库，而是把“事实记录”和“知识文档”分开：MySQL 管理需要实时生效、严格审计的规则事实，Wiki 管理适合人工阅读和持续补充的制度语义。本地模型可以协助把自然语言整理成候选规则或 Wiki 草稿，但候选内容必须经过结构化校验、院内试运行和审批后才能写入 MySQL 并参与正式计算。

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

- 五类 Agent 编排、类型化输入输出、多轮对话和意图识别
- 本院差异项与国标基础字段的生效口径合成、版本追加和恢复
- 四指标幂等导入、SQL 语义结果、无样本状态和 Wiki 只读故障回退
- 指标实施任务的取数要求、字段确认、SQL、试运行、审批、发布与恢复闭环
- SQL 安全校验、医生可读解释、分子分母字段血缘、明细快照和 Excel 导出
- 三层异常诊断、国标与本院口径双执行对比
- DBHub MCP 元数据同步、字段预检查和医院业务库只读调用
- 医学术语识别、医院值映射、审批、发布和版本回退
- 指标运行计划、调度租约、聚合结果、波动预警和自动诊断
- 执行链路 Trace、三个 workflow manifest、恢复任务和前端节点详情
- 医院登录与明细权限、签名离线包、公司候选与医院隔离导入
- AI 助手、指标实施、监控、数据与术语等工作台前端及 API 基础流程

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

指标实施与监控工作流校验接口：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/indicator_monitoring/validate
Invoke-RestMethod -Uri http://127.0.0.1:8765/api/workflows/indicator_generation_closed_loop/validate
```

如果 `ok=false`，先处理 `issues` 中的节点缺字段、重复节点或边引用不存在节点问题，再继续排查对应业务链路。Agent 对话不依赖工作流 manifest，可通过 `/api/agent/capabilities` 检查启用状态和模型注册结果。

## 开发备注

本项目强调“结构化规则事实优先，LLM 辅助表达”。已迁移指标以 MySQL 生效规则为准，Wiki 负责来源审阅与故障兜底。任何涉及口径、公式、SQL 或医院覆盖规则的变更，都应通过工具链和测试验证后再提交。
