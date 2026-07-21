# 2026-07-21 规则、语义与 LLM 混合指标识别

## 背景

原复合请求拆分依赖“还有、以及、另外”等显式连接词，`RequestPlan` 又只保存一个 `target_indicator`。因此用户只用逗号并列两个指标时，单值 Planner 可能只保留第一个指标。

## 本批改动

新增 `app/agent_understanding/HybridIndicatorResolver`，在 Planner 前按以下顺序识别指标：

1. 使用现有术语库匹配正式名称、简称、口语别名和医院已审核别名。
2. 对未命中的疑似指标片段使用标准库字符相似度做本地语义召回，不引入向量库、Embedding 服务或新依赖。
3. 只有多个候选分数接近时，调用当前选择模型执行候选内消歧；模型工具列表为空，只能返回给定 `rule_id` 或 `null`。
4. 识别到 2 至 3 个指标后，服务端按原顺序生成隔离子计划，统一解析统计周期并按 Provider 配置执行。
5. 单指标高置信命中会把标准名称和 `rule_id` 注入 Planner 结果，避免小模型改写或丢失指标身份。

意图处理同样采用分层方式：SQL、明确诊断、实际结果和普通口径解释先用高置信规则识别；规则未命中时使用本地短句相似度；仍无法确认时保留现有 Planner LLM，不把工具路由权交给模型。

## 安全边界

- LLM 不能创建指标、扩大候选、输出工具名或生成 SQL。
- 返回不在候选组中的 `rule_id` 会被拒绝并转为用户澄清。
- 最多处理 3 个指标；超过上限要求用户缩小范围。
- 原 `CapabilitySpecRegistry → Controller → Dispatch → ToolGateway → Verifier` 执行链保持不变。
- 不增加数据库、中间件、模型服务或生产依赖。

## Trace

新增节点：

- `indicator_rule_match`
- `indicator_semantic_retrieval`
- `indicator_llm_disambiguation`（仅候选不唯一时）

复合拆分节点会记录每个指标的标准名称、`rule_id`、来源和置信度。

## 验证

新增覆盖：

- 只用逗号并列两个指标仍拆为两个子任务。
- 非精确指标名称可由本地语义层唯一召回。
- LLM 只能选择服务端候选 `rule_id`。
- LLM 虚构 `rule_id` 时拒绝并要求澄清。
- 原复数追问、Planner、Runner 和 Prompt 注册表回归保持通过。
