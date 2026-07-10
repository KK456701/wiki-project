# 五类 Agent 边界与统一编排设计

## 1. 背景与目标

需求规格要求核心引擎由五个职责明确、可独立迭代的专用 Agent 协同工作：元数据解析 Agent、指标生成 Agent、口径适配 Agent、故障根因排查 Agent、人机交互 Agent。

当前项目已经具备这些业务能力，但边界不清晰：`app/agent/graph.py` 同时承担意图识别、规则查询、口径合成、SQL 生成、诊断调用、流式输出、记忆和 Trace 编排；API 端点又会绕过对话链路直接构造 SQL、诊断和元数据组件。本批目标是建立真实的五 Agent 代码边界和一个统一编排器，同时保持现有 API、SSE、前端及四指标业务结果兼容。

## 2. 范围

本批实现：

- 五个专用 Agent 的独立模块、明确输入输出和依赖。
- `CoreIndicatorOrchestrator` 统一意图到 Agent 的路由。
- 对话非流式、SSE 流式以及 SQL/诊断/元数据 API 复用同一组 Agent 能力。
- Trace 节点增加 `agent_owner`，明确每个节点由哪个 Agent 负责。
- 保留现有四指标、MySQL 规则主存储、Wiki 只读兜底和审批版本逻辑。
- 保留执行链路默认折叠、点击“详情”展开的交互。

本批不实现：

- 不把全部链路一次性改写为 LangGraph。
- 不增加其余 31 个指标。
- 不实现下一批的任意自然语言新建指标和完整指标文档生成。
- 不实现国标与医院 SQL 双执行诊断、定时运算或五个业务工作台。

## 3. 五类 Agent 边界

### 3.1 `MetadataParsingAgent`

职责：

- 通过元数据 Provider 同步数据库表和字段快照。
- 计算结构变化及受影响指标。
- 对指标依赖字段进行生成前预校验。

不负责：规则审批、SQL 业务逻辑、回答组织。

### 3.2 `IndicatorGenerationAgent`

职责：

- 接收已解析的生效口径、字段映射和统计周期。
- 调用现有 `SQLGenerationAgent` 生成、安全校验和试运行 SQL。
- 返回结构化 SQL 结果，不直接组织聊天话术。

不负责：搜索指标、决定使用国标或本院口径、诊断根因。

### 3.3 `CaliberAdaptationAgent`

职责：

- 通过 `RuleRepository` 搜索指标。
- 以国标为基础合成本院生效口径。
- 读取字段映射、生成口径反馈预览、提交变更申请。

不负责：直接执行 SQL、直接修改国标、生成最终聊天回答。

### 3.4 `RootCauseDiagnosisAgent`

职责：

- 调用现有三层诊断实现。
- 固定按结构、口径、数据顺序执行。
- 返回标准化诊断结果。

不负责：意图识别、审批、最终页面渲染。

### 3.5 `HumanInteractionAgent`

职责：

- 关键词或可选 LLM 意图识别。
- 多轮上下文中的指标追问解析。
- 生成、校验和兜底最终回答。
- 把结构化 Agent 结果转换为用户可读文本。

不负责：直接访问业务数据库、直接写规则表。

## 4. 统一编排器

新增 `CoreIndicatorOrchestrator`，持有五类 Agent，并提供以下稳定能力：

- `understand(query, memory_context)`：调用人机交互 Agent 返回标准意图结果。
- `resolve_caliber(query, hospital_id, memory_context)`：搜索指标并合成本院生效口径。
- `generate_indicator(...)`：转发给指标生成 Agent。
- `diagnose(...)`：转发给根因排查 Agent。
- `sync_metadata(...)`：转发给元数据解析 Agent。
- `owner_for_intent(intent)`：返回负责该意图的专用 Agent ID。

编排器只负责顺序、路由和数据传递，不复制各 Agent 的业务逻辑。HTTP、SSE、LangGraph 和 Trace 都是编排器外部适配层。

## 5. 兼容策略

- `app/agent/graph.py` 暂时保留 `run_chat`、`run_chat_stream`、`detect_intent` 等公共入口。
- 公共入口内部逐步改为调用 `CoreIndicatorOrchestrator`，避免一次性破坏现有调用方。
- 非流式仍可使用现有 LangGraph 适配器；流式仍使用 SSE 事件适配器，但两者共享同一组 Agent 实例和路由规则。
- 原有 `SQLGenerationAgent` 和 `DiagnoseAgent` 作为领域执行器保留，由新的专用 Agent 包装，不重复实现 SQL 和诊断算法。
- API 响应字段保持兼容，只新增 `agent_owner`、`orchestrator` 等可选观测字段。

## 6. Trace 与 Manifest

节点清单继续保持当前折叠/展开体验。每个节点在 manifest 中增加 `agent_owner`：

- `memory_load`、`intent_detect`、`final_response` -> `human_interaction`
- `rule_search`、`effective_rule_resolve`、反馈与审批 -> `caliber_adaptation`
- `field_mapping_precheck`、`metadata_sync_mcp` -> `metadata_parsing`
- `sql_generate`、`sql_validate`、`sql_trial_mcp` -> `indicator_generation`
- 三层诊断节点 -> `root_cause_diagnosis`

Trace 详情中展示 `agent_owner`，但默认节点摘要不增加业务参数或 JSON。

## 7. 错误边界

- Agent 返回领域错误，编排器负责停止、回退或转交，不吞掉错误。
- MySQL 规则读取失败仍可由口径适配 Agent 触发 Wiki 只读兜底。
- 写操作失败关闭，不通过 Wiki 兜底。
- 指标生成和诊断失败不能影响规则查询与普通问答。
- 元数据同步失败保留现有恢复任务机制。

## 8. 验收标准

- 五类 Agent 均有独立模块和单元测试。
- 编排器可明确返回各意图的负责 Agent。
- 普通问答、口径反馈、SQL 生成、试运行、诊断和元数据同步继续工作。
- 四指标结果仍为 `25.00%`、`66.67%`、`75.00%`、`50.00%`。
- Trace 节点可以在详情中识别负责 Agent。
- 前端链路仍默认折叠，点击详情后展开。
- 全量自动测试通过。
