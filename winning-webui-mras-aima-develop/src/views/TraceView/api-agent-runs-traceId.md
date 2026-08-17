# GET /api/agent/runs/{traceId} — Agent 运行链路详情

## 概述

获取单次 Agent 运行的完整链路详情，包括所有 Trace 节点、执行流向边、Evidence 证据链以及耗时汇总。

- **方法**: `GET`
- **路径**: `/api/agent/runs/{traceId}`
- **鉴权**: 需要 `Authorization` 请求头（Bearer Token）
- **Controller**: [`AgentTraceController.get()`](../../src/main/java/com/hospital/wikiagent/api/AgentTraceController.java:74)
- **Service**: [`AgentTraceService.get()`](../../src/main/java/com/hospital/wikiagent/agent/trace/AgentTraceService.java:110)

---

## 请求

### 路径参数

| 参数名    | 类型     | 必填 | 说明                                                                     |
| --------- | -------- | ---- | ------------------------------------------------------------------------ |
| `traceId` | `string` | 是   | Trace 唯一标识，格式 `TRACE_` + 16 位 hex，例如 `TRACE_a1b2c3d4e5f6g7h8` |

### 请求头

| 参数名          | 类型     | 必填 | 说明                                |
| --------------- | -------- | ---- | ----------------------------------- |
| `Authorization` | `string` | 是   | Bearer Token，格式 `Bearer <token>` |

### 错误响应

| HTTP 状态码 | 错误码            | 说明                         |
| ----------- | ----------------- | ---------------------------- |
| `401`       | —                 | 未提供或无效的 Authorization |
| `404`       | `TRACE_NOT_FOUND` | 未找到本院可访问的运行链路   |

---

## 响应结构

### 顶层字段

| 字段名                 | 类型            | 说明                                                                   |
| ---------------------- | --------------- | ---------------------------------------------------------------------- |
| `id`                   | `number`        | Trace 表自增主键                                                       |
| `trace_id`             | `string`        | Trace 唯一标识                                                         |
| `session_id`           | `string`        | 关联会话 ID，可能为 `null`                                             |
| `hospital_id`          | `string`        | 医院 ID                                                                |
| `user_id`              | `string`        | 用户 ID                                                                |
| `user_query`           | `string`        | 用户原始输入文本                                                       |
| `intent`               | `string`        | 业务意图，详见 [PlanIntent 枚举](#planintent-枚举)                     |
| `final_status`         | `string`        | 最终运行状态，详见 [final_status 枚举](#final_status-枚举)             |
| `final_answer_summary` | `string`        | 最终回答摘要（截断至 2000 字符）                                       |
| `error_count`          | `number`        | 错误节点数                                                             |
| `fallback_count`       | `number`        | 兜底/降级次数                                                          |
| `started_at`           | `string`        | 开始时间（ISO datetime）                                               |
| `ended_at`             | `string`        | 结束时间（ISO datetime），运行中为 `null`                              |
| `duration_ms`          | `number`        | 总耗时（毫秒）                                                         |
| `created_at`           | `string`        | 创建时间（ISO datetime）                                               |
| `nodes`                | `array<object>` | Trace 节点列表，详见 [Nodes 节点结构](#nodes-节点结构)                 |
| `flow_edges`           | `array<object>` | 节点间执行流向边，详见 [flow_edges 结构](#flow_edges-结构)             |
| `evidence`             | `array<object>` | Evidence 证据链，详见 [Evidence 证据链结构](#evidence-证据链结构)      |
| `trace_version`        | `string`        | Trace 版本，固定值 `"java-agent-trace-v2"`                             |
| `timing_summary`       | `object`        | 按节点类型的耗时汇总，详见 [timing_summary 结构](#timing_summary-结构) |

---

## final_status 枚举

| 值           | 说明                                       |
| ------------ | ------------------------------------------ |
| `running`    | 运行中                                     |
| `success`    | 成功结束（stop_reason 为 `final_answer`）  |
| `failed`     | 执行失败                                   |
| `incomplete` | 需要澄清（stop_reason 为 `clarification`） |

---

## Nodes 节点结构

每个节点包含数据库原始字段以及服务端增强字段。

### 数据库原始字段

| 字段名                  | 类型     | 说明                                                                                   |
| ----------------------- | -------- | -------------------------------------------------------------------------------------- |
| `id`                    | `number` | 节点自增主键                                                                           |
| `trace_id`              | `string` | 所属 Trace ID                                                                          |
| `node_id`               | `string` | 节点唯一标识，格式 `NODE_` + 16 位 hex                                                 |
| `node_name`             | `string` | 节点名称（内部标识），详见 [node_name 枚举](#node_name-枚举)                           |
| `node_type`             | `string` | 节点类型，详见 [node_type 枚举](#node_type-枚举)                                       |
| `status`                | `string` | 节点执行状态，详见 [node status 枚举](#node-status-枚举)                               |
| `input_summary`         | `string` | 输入摘要（JSON 字符串）                                                                |
| `output_summary`        | `string` | 输出摘要（JSON 字符串）                                                                |
| `error_code`            | `string` | 错误码，成功时为 `null`                                                                |
| `error_message`         | `string` | 错误消息，成功时为 `null`                                                              |
| `tool_name`             | `string` | 工具名称，非工具节点为 `null`                                                          |
| `db_source`             | `string` | 数据库源标识，非数据库节点为 `null`                                                    |
| `sql_id`                | `string` | SQL 对象 ID，非 SQL 节点为 `null`                                                      |
| `run_id`                | `string` | 运行 ID，非 SQL 执行节点为 `null`                                                      |
| `rule_id`               | `string` | 关联规则 ID                                                                            |
| `llm_model`             | `string` | LLM 模型名（已弃用，请使用 `model_id`）                                                |
| `model_id`              | `string` | 模型 ID                                                                                |
| `started_at`            | `string` | 节点开始时间（ISO datetime）                                                           |
| `ended_at`              | `string` | 节点结束时间（ISO datetime）                                                           |
| `duration_ms`           | `number` | 节点耗时（毫秒）                                                                       |
| `parent_node_id`        | `string` | 父节点 ID                                                                              |
| `subtask_id`            | `string` | 子任务 ID，顶层为 `"root"`                                                             |
| `sequence`              | `number` | 节点在同 subtask 内的执行序号                                                          |
| `started_offset_ms`     | `number` | 相对于 Trace 开始的偏移（毫秒）                                                        |
| `exclusive_duration_ms` | `number` | 排他耗时（不含子节点，毫秒）                                                           |
| `capability`            | `string` | 关联的业务能力，详见 [PlanCapability 枚举](#plancapability-枚举)                       |
| `failure_class`         | `string` | 失败分类标签，成功时为 `null`。详见 [代理层 failure_class 值](#failure_class-标签枚举) |
| `input_tokens`          | `number` | LLM 输入 token 数，非 LLM 节点为 `null`                                                |
| `output_tokens`         | `number` | LLM 输出 token 数，非 LLM 节点为 `null`                                                |
| `cache_reused`          | `number` | 是否命中缓存（0/1）                                                                    |
| `retry_count`           | `number` | 重试次数                                                                               |
| `created_at`            | `string` | 节点创建时间（ISO datetime）                                                           |

### 服务端增强字段

| 字段名                 | 类型     | 说明                                                                                       |
| ---------------------- | -------- | ------------------------------------------------------------------------------------------ |
| `node_title`           | `string` | 中文标题，如"规划业务目标"、"生成最终回答"                                                 |
| `processing_summary`   | `string` | 详细处理说明，如"LLM 只生成业务 RequestPlan，不选择工具。"                                 |
| `flow_stage`           | `string` | 架构阶段 ID，详见 [flow_stage 枚举](#flow_stage-枚举)                                      |
| `flow_stage_title`     | `string` | 架构阶段中文名，如"规划与目标校验"                                                         |
| `flow_stage_order`     | `number` | 架构阶段序号（1–6）                                                                        |
| `input_data`           | `object` | 解码后的输入数据（JSON 对象，脱敏后）                                                      |
| `output_data`          | `object` | 解码后的输出数据（JSON 对象，脱敏后）                                                      |
| `capability_readiness` | `object` | 能力就绪状态（仅相关节点有）。详见 [capability_readiness 结构](#capability_readiness-结构) |

---

## node_name 枚举

> 注：旧版本节点名可能不在此列表中，未知节点名按 `node_type` 归类到对应 `flow_stage`。

| node_name                               | node_title (中文)  | processing_summary                                                 |
| --------------------------------------- | ------------------ | ------------------------------------------------------------------ |
| `memory_load`                           | 读取会话上下文     | 从持久化存储加载历史对话上下文。                                   |
| `indicator_rule_match`                  | 规则精确识别指标   | 用正式名称和已审核同义词确定性匹配指标。                           |
| `indicator_semantic_retrieval`          | 本地语义召回指标   | 对未命中片段执行本地字符语义召回，不调用模型。                     |
| `indicator_llm_disambiguation`          | 模型候选内消歧     | LLM 只能从服务端候选 rule_id 中消歧。                              |
| `planner_llm`                           | 规划业务目标       | LLM 只生成业务 RequestPlan，不选择工具。                           |
| `followup_plan_resolve`                 | 跨轮确定性解析     | 指标和目标可由结构化会话唯一确定，本轮未调用 LLM Planner。         |
| `plan_goal_alignment`                   | 校验目标与计划     | 确定性核对用户目标、会话事实、指标和候选口径是否与计划一致。       |
| `plan_alignment_review_llm`             | 审核复杂口径目标   | 仅在规则无法确定的复杂语义下，从允许候选中审核目标口径。           |
| `plan_replan`                           | 重新规划业务目标   | 仅在允许的方向性错误下由 LLM 重规划一次。                          |
| `plan_alignment_revalidate`             | 复核替代计划       | 替代计划必须再次通过同一套目标一致性校验。                         |
| `plan_alignment_deterministic_fallback` | 生成受控修正计划   | 模型仍未纠正且候选唯一时，由服务端生成受控计划。                   |
| `plan_compile`                          | 编译业务计划       | 将 RequestPlan 编译为 CompiledPlanIR。                             |
| `plan_validate`                         | 校验业务计划       | 校验编译后的计划是否符合约束。                                     |
| `failure_router`                        | 路由失败处理       | 统一判断本次失败应重规划一次，还是直接澄清、拒绝或兜底。           |
| `state_controller`                      | 选择下一业务能力   | 根据未完成事实选择下一项业务能力。                                 |
| `deterministic_tool_dispatch`           | 编译受控工具调用   | 服务端按 CapabilitySpec 编译工具与参数。                           |
| `tool_result`                           | 执行并观察工具结果 | 执行受控工具调用并记录结果。                                       |
| `plan_verify`                           | 校验证据完整性     | 只接受医院、规则、周期和对象链一致的 Evidence。                    |
| `final_answer_llm`                      | 生成最终回答       | LLM 只根据 VerifiedEvidence 组织回答。                             |
| `prepared_sql_answer`                   | 生成受控 SQL 回答  | 服务端从本轮私有 SQL 对象确定性生成回答，不调用 Final Answer LLM。 |
| `caliber_options_answer`                | 整理口径选项       | 服务端按 Profile 状态分类展示口径，不执行数据库。                  |
| `caliber_simulation_answer`             | 生成候选口径回答   | 服务端使用已验证候选 profile 和试运行结果生成回答。                |
| `difference_diagnosis_layer_1`          | 诊断范围预检       | 固定指标、医院、统计周期、文件类型和外部声明值。                   |
| `difference_diagnosis_layer_2`          | 实时结构核验       | 对比 Wiki 字段契约、医院映射与 DBHub 实时元数据。                  |
| `difference_diagnosis_layer_3`          | 执行当前口径       | 按当前生效口径生成、校验并试运行基准 SQL。                         |
| `difference_diagnosis_layer_4`          | 试运行候选口径     | 在同医院、同周期和同数据源下试运行最多五个已审批候选口径。         |
| `difference_diagnosis_layer_5`          | 核对记录集合       | 核对双方都有、单边记录、字段值和达标判定差异。                     |
| `difference_diagnosis_layer_6`          | 检查数据质量       | 执行 Wiki 允许列表中的空值、重复和时间逻辑检查。                   |
| `difference_diagnosis_conclusion`       | 生成诊断结论       | 根据已确认 Evidence 给出保守的差异结论。                           |
| `difference_diagnosis_answer`           | 整理差异诊断回答   | 服务端按固定报告模板整理回答，不允许模型补造原因。                 |
| `dual_period_validation`                | 校验统计范围       | 在访问抽取接口和 DBHub 前强制校验统计区间不超过一个自然月。        |
| `source_extraction_prepare`             | 准备源数据抽取     | 固定发布版本、规则、Profile、源 SQL 哈希和幂等键。                 |
| `source_data_extraction`                | 抽取数据到真实库   | 调用受控抽取网关一次；后续诊断复用本轮抽取回执。                   |
| `business_overview`                     | 计算业务库概览     | 在业务库执行已验证的同一份概览 SQL。                               |
| `real_overview`                         | 计算真实库概览     | 在真实库执行与业务库相同的概览 SQL 和参数。                        |
| `dual_comparison`                       | 核对双库结果       | 分子和分母必须同时相等；仅比例相等仍属于不一致。                   |
| `dual_department_detail`                | 核对科室差异       | 仅在概览不一致时按已验证科室比较键核对集合差异。                   |
| `dual_patient_detail`                   | 核对患者明细       | 仅在概览不一致时按已验证业务主键核对记录差异。                     |
| `dual_diagnosis_conclusion`             | 生成诊断结论       | 输出已确认差异统计；契约缺失时不猜测具体原因。                     |
| `response_guard`                        | 检查回答协议       | 检查最终回答是否符合协议约定。                                     |
| `memory_save`                           | 保存会话上下文     | 将本次对话上下文持久化。                                           |
| `compound_split`                        | 拆分复合指标请求   | 将复合请求拆分为多个独立子任务。                                   |
| `compound_subtask`                      | 执行指标子任务     | 执行单个指标的完整链路。                                           |
| `compound_merge`                        | 按输入顺序合并结果 | 合并所有子任务结果。                                               |
| `metadata_sync_dbhub`                   | 同步数据库元数据   | 经 DBHub 只读采集表目录和指标映射依赖字段。                        |
| `low_confidence_clarification`          | 低置信度澄清       | 意图置信度不足时请求用户澄清。                                     |
| `multiple_indicator_clarification`      | 多指标澄清         | 多个指标同时命中时请求用户选择。                                   |
| `unsupported_feature_guard`             | 不支持功能拦截     | 拦截不支持的功能请求。                                             |
| `batch_scope_resolve`                   | 批量范围解析       | 解析批量请求的指标范围。                                           |
| `batch_time_resolve`                    | 批量时间解析       | 解析批量请求的时间范围。                                           |
| `batch_indicator_enumerate`             | 批量指标枚举       | 枚举批量请求中的全部指标。                                         |
| `batch_indicator`                       | 批量执行指标       | 执行单个批量指标。                                                 |
| `batch_result_merge`                    | 批量结果合并       | 合并批量执行结果。                                                 |
| `batch_job_persist`                     | 批量作业持久化     | 持久化批量作业结果。                                               |

---

## node_type 枚举

| 值         | 说明           |
| ---------- | -------------- |
| `llm`      | LLM 模型调用   |
| `tool`     | 工具调用       |
| `database` | 数据库操作     |
| `code`     | 服务端代码逻辑 |
| `storage`  | 存储操作       |

---

## node status 枚举

| 值        | 说明     |
| --------- | -------- |
| `success` | 执行成功 |
| `failed`  | 执行失败 |
| `error`   | 执行错误 |

---

## flow_stage 枚举

| id             | title (中文)           | order | 包含的典型 node_name                                                                                                                                                                                               |
| -------------- | ---------------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `context`      | 上下文与指标识别       | 1     | `memory_load`, `indicator_rule_match`, `indicator_semantic_retrieval`, `indicator_llm_disambiguation`, `compound_split`                                                                                            |
| `planning`     | 规划与目标校验         | 2     | `planner_llm`, `followup_plan_resolve`, `plan_goal_alignment`, `plan_replan`, `low_confidence_clarification`, `multiple_indicator_clarification`, `unsupported_feature_guard`，以及所有 `plan_alignment*` 前缀节点 |
| `compilation`  | IR编译与能力选择       | 3     | `plan_compile`, `plan_validate`, `state_controller`, `deterministic_tool_dispatch`, `failure_router`                                                                                                               |
| `execution`    | 工具与数据库执行       | 4     | `tool_result`, `compound_subtask`, `metadata_sync_dbhub`，所有 `dual_*` 和 `difference_diagnosis_layer_*` 前缀节点，以及 `node_type` 为 `tool` 或 `database` 的未知节点                                            |
| `verification` | Evidence验证与安全检查 | 5     | `plan_verify`, `response_guard`, `difference_diagnosis_conclusion`, `dual_diagnosis_conclusion`                                                                                                                    |
| `answer`       | 回答组织与会话保存     | 6     | `final_answer_llm`, `prepared_sql_answer`, `caliber_options_answer`, `caliber_simulation_answer`, `difference_diagnosis_answer`, `compound_merge`, `memory_save`，以及 `node_type` 为 `storage` 的未知节点         |

> 规则：未知 `node_name` 按 `node_type` 归类 — `llm` → `planning`，`storage` → `answer`，其他 → `execution`。

---

## PlanIntent 枚举

`intent` 字段可能的值，来源 [`PlanIntent`](../../src/main/java/com/hospital/wikiagent/agent/ir/PlanIntent.java:11) 枚举：

| 值                               | 说明          |
| -------------------------------- | ------------- |
| `general_chat`                   | 通用闲聊      |
| `rule_explanation`               | 规则解释      |
| `indicator_sql_prepare`          | 指标 SQL 准备 |
| `indicator_trial_run`            | 指标试运行    |
| `indicator_caliber_query`        | 指标口径查询  |
| `indicator_caliber_simulation`   | 指标口径模拟  |
| `indicator_diagnosis`            | 指标诊断      |
| `indicator_difference_diagnosis` | 指标差异诊断  |
| `rule_change_preview`            | 规则变更预览  |
| `upload_analysis`                | 上传文件分析  |
| `unknown`                        | 未知意图      |

---

## PlanCapability 枚举

`capability` 字段可能的值，来源 [`PlanCapability`](../../src/main/java/com/hospital/wikiagent/agent/ir/PlanCapability.java:11) 枚举：

| 值                              | 说明             |
| ------------------------------- | ---------------- |
| `resolve_indicator`             | 解析指标         |
| `resolve_effective_rule`        | 解析生效规则     |
| `resolve_time_range`            | 解析时间范围     |
| `inspect_implementation`        | 检查实现         |
| `prepare_verified_sql`          | 准备已验证 SQL   |
| `execute_trial_run`             | 执行试运行       |
| `list_caliber_profiles`         | 列出可选口径     |
| `resolve_caliber_profile`       | 解析口径 Profile |
| `prepare_caliber_sql`           | 准备口径 SQL     |
| `execute_caliber_trial_run`     | 执行口径试运行   |
| `diagnose_indicator`            | 诊断指标         |
| `diagnose_indicator_difference` | 指标差异诊断     |
| `preview_rule_change`           | 预览规则变更     |
| `analyze_uploaded_file`         | 分析上传文件     |
| `compose_answer`                | 组织回答         |

---

## failure_class 标签枚举

`failure_class` 字段是由服务端 [`AgentTraceService.classify()`](../../src/main/java/com/hospital/wikiagent/agent/trace/AgentTraceService.java:784) 从错误码归并的业务分类标签：

| 值                | 说明             | 匹配的错误码模式                        |
| ----------------- | ---------------- | --------------------------------------- |
| `TIMEOUT`         | 模型/工具超时    | 含 `TIMEOUT` 的错误码                   |
| `PERMISSION`      | 权限错误         | 含 `PERMISSION` 或 `FORBIDDEN` 的错误码 |
| `DATABASE`        | 数据库错误       | 含 `DB` 或 `SQL` 的错误码               |
| `CLARIFICATION`   | 需要澄清         | 含 `TIME_RANGE` 或 `AMBIGUOUS` 的错误码 |
| `TOOL_OR_RUNTIME` | 工具或运行时错误 | 其他所有错误                            |

### 底层错误码 → FailureClass 完整映射

来源 [`FailureClass.classify()`](../../src/main/java/com/hospital/wikiagent/agent/ir/FailureClass.java:84)：

| 错误码                                     | FailureClass 枚举值               |
| ------------------------------------------ | --------------------------------- |
| `PLAN_INTENT_MISMATCH`                     | `semantic_plan_error`             |
| `TASK_TYPE_MISMATCH`                       | `task_type_error`                 |
| `USER_GOAL_CHANGED`                        | `user_goal_changed`               |
| `ASSUMPTION_INVALID_ALTERNATIVE_AVAILABLE` | `alternative_direction_available` |
| `INTENT_AMBIGUOUS`                         | `user_clarification_required`     |
| `INDICATOR_AMBIGUOUS`                      | `user_clarification_required`     |
| `INDICATOR_NOT_FOUND`                      | `user_clarification_required`     |
| `TARGET_INDICATOR_AMBIGUOUS`               | `user_clarification_required`     |
| `TIME_RANGE_AMBIGUOUS`                     | `user_clarification_required`     |
| `STAT_PERIOD_MISSING`                      | `user_clarification_required`     |
| `STAT_PERIOD_EXCEEDS_ONE_YEAR`             | `user_clarification_required`     |
| `CALIBER_PROFILE_NOT_FOUND`                | `user_clarification_required`     |
| `CALIBER_PROFILE_AMBIGUOUS`                | `user_clarification_required`     |
| `CALIBER_PROFILE_MISSING`                  | `user_clarification_required`     |
| `DATABASE_ACCESS_CONFLICT`                 | `database_error`                  |
| `DATABASE_UNAVAILABLE`                     | `database_error`                  |
| `DB_SOURCE_RETIRED`                        | `database_error`                  |
| `DB_SOURCE_ROLE_INVALID`                   | `database_error`                  |
| `DUAL_DATABASE_CONFIG_INCOMPLETE`          | `database_error`                  |
| `TRIAL_RUN_FAILED`                         | `database_error`                  |
| `BUSINESS_DATABASE_OVERVIEW_FAILED`        | `database_error`                  |
| `REAL_DATABASE_OVERVIEW_FAILED`            | `database_error`                  |
| `SOURCE_EXTRACTION_FAILED`                 | `tool_error`                      |
| `EXTRACTION_GATEWAY_UNAVAILABLE`           | `tool_error`                      |
| `DUAL_DATABASE_SCHEMA_INCOMPATIBLE`        | `tool_error`                      |
| `DUAL_DIAGNOSIS_REPORT_PERSIST_FAILED`     | `tool_error`                      |
| `DUAL_RUN_PERSIST_FAILED`                  | `tool_error`                      |
| `DETAIL_COMPARISON_CONTRACT_MISSING`       | `tool_error`                      |
| `DUAL_DETAIL_QUERY_FAILED`                 | `tool_error`                      |
| `DIAGNOSIS_FAILED`                         | `tool_error`                      |
| `PERMISSION_DENIED`                        | `permission_error`                |
| `PATIENT_DETAIL_FORBIDDEN`                 | `permission_error`                |
| `SQL_OBJECT_EXPIRED`                       | `object_expired`                  |
| `SQL_CHAIN_INCONSISTENT`                   | `evidence_conflict`               |
| `SQL_PERIOD_INCONSISTENT`                  | `evidence_conflict`               |
| `NUMERIC_RESULT_INCONSISTENT`              | `evidence_conflict`               |
| `EVIDENCE_HOSPITAL_MISMATCH`               | `evidence_conflict`               |
| `EVIDENCE_SUBTASK_MISMATCH`                | `evidence_conflict`               |
| `EVIDENCE_RULE_MISMATCH`                   | `evidence_conflict`               |
| `EVIDENCE_PERIOD_MISMATCH`                 | `evidence_conflict`               |
| `EVIDENCE_SQL_MISMATCH`                    | `evidence_conflict`               |
| `EVIDENCE_CALIBER_MISMATCH`                | `evidence_conflict`               |
| `CALIBER_PROFILE_CHAIN_MISMATCH`           | `evidence_conflict`               |
| `EVIDENCE_EXPIRED`                         | `object_expired`                  |
| `TOOL_TIMEOUT`                             | `tool_error`                      |
| `TOOL_EXECUTION_FAILED`                    | `tool_error`                      |

---

## flow_edges 结构

描述节点间的执行流向。

| 字段名         | 类型     | 说明                                              |
| -------------- | -------- | ------------------------------------------------- |
| `from_node_id` | `string` | 源节点 ID                                         |
| `to_node_id`   | `string` | 目标节点 ID                                       |
| `edge_type`    | `string` | 边类型，详见 [edge_type 枚举](#edge_type-枚举)    |
| `label`        | `string` | 边的附加标签（如循环次数），普通边为空字符串 `""` |

### edge_type 枚举

| 值         | 说明                                              |
| ---------- | ------------------------------------------------- |
| `parent`   | 父子关系（由 `parent_node_id` 确定）              |
| `sequence` | 顺序执行（同 subtask 内按 sequence 排序）         |
| `replan`   | 重规划边（目标节点名含 `replan`）                 |
| `failure`  | 失败流转边（源或目标节点状态为 `failed`/`error`） |

---

## Evidence 证据链结构

来源表 `med_agent_evidence`，仅返回非敏感字段。

| 字段名             | 类型     | 说明                                  |
| ------------------ | -------- | ------------------------------------- |
| `evidence_id`      | `string` | Evidence 唯一标识                     |
| `fact_type`        | `string` | 事实类型（如 `sql_execution_result`） |
| `rule_id`          | `string` | 关联规则 ID                           |
| `rule_version`     | `string` | 规则版本                              |
| `stat_start`       | `string` | 统计起始时间                          |
| `stat_end`         | `string` | 统计结束时间                          |
| `source_tool`      | `string` | 来源工具名                            |
| `source_object_id` | `string` | 来源对象 ID                           |
| `created_at`       | `string` | Evidence 创建时间（ISO datetime）     |
| `expires_at`       | `string` | Evidence 过期时间（ISO datetime）     |

---

## timing_summary 结构

| 字段名       | 类型     | 说明                           |
| ------------ | -------- | ------------------------------ |
| `llm_ms`     | `number` | LLM 调用总耗时（毫秒）         |
| `tool_ms`    | `number` | 工具与数据库操作总耗时（毫秒） |
| `code_ms`    | `number` | 服务端代码逻辑总耗时（毫秒）   |
| `storage_ms` | `number` | 存储操作总耗时（毫秒）         |

---

## capability_readiness 结构

仅当节点输出数据中包含能力就绪状态时出现，用于前端快速判断能力可用性。

| 字段名             | 类型      | 说明                                              |
| ------------------ | --------- | ------------------------------------------------- |
| `知识治理状态`     | `string`  | 知识治理状态，如 `"已发布"`, `"草稿"`, `"未提供"` |
| `SQL展示能力`      | `boolean` | 是否具备 SQL 展示能力                             |
| `双库概览试算能力` | `boolean` | 是否支持双库概览试算                              |
| `科室明细诊断能力` | `boolean` | 是否支持科室级明细诊断                            |
| `患者明细诊断能力` | `boolean` | 是否支持患者级明细诊断                            |

---

## 完整响应示例

```json
{
  "id": 142,
  "trace_id": "TRACE_a1b2c3d4e5f6g7h8",
  "session_id": "SESSION_xyz789",
  "hospital_id": "HOSP_001",
  "user_id": "user_123",
  "user_query": "查询我院上月四级手术术前多学科讨论完成率",
  "intent": "indicator_diagnosis",
  "final_status": "success",
  "final_answer_summary": "根据查询结果，贵院上月四级手术术前多学科讨论完成率为 92.5%……",
  "error_count": 0,
  "fallback_count": 0,
  "started_at": "2026-07-28T14:30:00",
  "ended_at": "2026-07-28T14:30:08",
  "duration_ms": 8234,
  "created_at": "2026-07-28T14:30:00",
  "nodes": [
    {
      "id": 1001,
      "trace_id": "TRACE_a1b2c3d4e5f6g7h8",
      "node_id": "NODE_e8f9a0b1c2d3e4f5",
      "node_name": "memory_load",
      "node_type": "storage",
      "status": "success",
      "input_summary": "{\"session_id\":\"SESSION_xyz789\"}",
      "output_summary": "{\"history_turns\":3}",
      "error_code": null,
      "error_message": null,
      "tool_name": null,
      "db_source": null,
      "sql_id": null,
      "run_id": null,
      "rule_id": null,
      "llm_model": null,
      "model_id": null,
      "started_at": "2026-07-28T14:30:00",
      "ended_at": "2026-07-28T14:30:00",
      "duration_ms": 12,
      "parent_node_id": null,
      "subtask_id": "root",
      "sequence": 1,
      "started_offset_ms": 0,
      "exclusive_duration_ms": 12,
      "capability": null,
      "failure_class": null,
      "input_tokens": null,
      "output_tokens": null,
      "cache_reused": 0,
      "retry_count": 0,
      "created_at": "2026-07-28T14:30:00",
      "node_title": "读取会话上下文",
      "processing_summary": "从持久化存储加载历史对话上下文。",
      "flow_stage": "context",
      "flow_stage_title": "上下文与指标识别",
      "flow_stage_order": 1,
      "input_data": { "session_id": "SESSION_xyz789" },
      "output_data": { "history_turns": 3 }
    },
    {
      "id": 1002,
      "trace_id": "TRACE_a1b2c3d4e5f6g7h8",
      "node_id": "NODE_f1a2b3c4d5e6f7a8",
      "node_name": "indicator_rule_match",
      "node_type": "code",
      "status": "success",
      "input_summary": "{\"query\":\"查询我院上月四级手术术前多学科讨论完成率\",\"hospital_id\":\"HOSP_001\"}",
      "output_summary": "{\"matched_rules\":[{\"rule_id\":\"HXZD-012-003\",\"rule_name\":\"四级手术术前多学科讨论完成率\"}]}",
      "error_code": null,
      "error_message": null,
      "tool_name": null,
      "db_source": null,
      "sql_id": null,
      "run_id": null,
      "rule_id": "HXZD-012-003",
      "llm_model": null,
      "model_id": null,
      "started_at": "2026-07-28T14:30:00",
      "ended_at": "2026-07-28T14:30:00",
      "duration_ms": 45,
      "parent_node_id": "NODE_e8f9a0b1c2d3e4f5",
      "subtask_id": "root",
      "sequence": 2,
      "started_offset_ms": 12,
      "exclusive_duration_ms": 45,
      "capability": "resolve_indicator",
      "failure_class": null,
      "input_tokens": null,
      "output_tokens": null,
      "cache_reused": 0,
      "retry_count": 0,
      "created_at": "2026-07-28T14:30:00",
      "node_title": "规则精确识别指标",
      "processing_summary": "用正式名称和已审核同义词确定性匹配指标。",
      "flow_stage": "context",
      "flow_stage_title": "上下文与指标识别",
      "flow_stage_order": 1,
      "input_data": {
        "query": "查询我院上月四级手术术前多学科讨论完成率",
        "hospital_id": "HOSP_001"
      },
      "output_data": {
        "matched_rules": [
          { "rule_id": "HXZD-012-003", "rule_name": "四级手术术前多学科讨论完成率" }
        ]
      }
    }
  ],
  "flow_edges": [
    {
      "from_node_id": "NODE_e8f9a0b1c2d3e4f5",
      "to_node_id": "NODE_f1a2b3c4d5e6f7a8",
      "edge_type": "sequence",
      "label": ""
    }
  ],
  "evidence": [
    {
      "evidence_id": "EVID_a1b2c3d4",
      "fact_type": "sql_execution_result",
      "rule_id": "HXZD-012-003",
      "rule_version": "v2.1",
      "stat_start": "2026-06-01",
      "stat_end": "2026-06-30",
      "source_tool": "dbhub_query",
      "source_object_id": "SQL_xyz",
      "created_at": "2026-07-28T14:30:05",
      "expires_at": "2026-08-28T14:30:05"
    }
  ],
  "trace_version": "java-agent-trace-v2",
  "timing_summary": {
    "llm_ms": 3200,
    "tool_ms": 4800,
    "code_ms": 210,
    "storage_ms": 24
  }
}
```

---

## 前端对接注意事项

1. **节点顺序依赖 `flow_edges`**：不要仅依赖节点的 `sequence` 排序，应结合 `flow_edges` 构建 DAG 图。同 `subtask_id` 内的节点按 `sequence` 排序，父子关系由 `parent_node_id` 确定。

2. **`flow_stage` 用于架构图着色**：前端可依据 `flow_stage` 将节点按不同泳道或颜色分组渲染。

3. **`input_data` / `output_data` 已脱敏**：敏感字段（含 `password`、`secret`、`token`、`sql`、`raw_sql`、`patient_rows`、`raw_rows`、`authorization`、`api_key` 等关键词的键）已被替换为 `"[已脱敏]"`。

4. **`capability_readiness` 仅部分节点出现**：前端需做好 `null` 检查。

5. **旧版本兼容**：部分历史 Trace 的 `node_name` 可能不在当前枚举中，`flow_stage` 会按 `node_type` 安全归类。

6. **`final_answer_summary` 已截断**：最大 2000 字符，完整回答请通过 SSE 流或会话接口获取。
