# 指标生成闭环设计

## 1. 目标与范围

本批实现“自然语言描述 -> 指标设计稿 -> 元数据确认 -> 安全 SQL -> 试运行 -> 审批 -> 正式版本”的完整闭环，同时支持：

- 基于现有国标指标创建“本院口径差异”；
- 创建国标中不存在的“本院新增指标”。

用户界面统一使用“指标设计稿”“本院口径差异”“本院新增指标”。代码内部可以保留 `draft`、`hospital_custom`、`hospital_defined` 等技术命名。

本批不允许修改 `med_index_standard`，不支持模型直接提交任意 SQL，不支持自动猜测多表关联，也不建设第六批的完整五页面工作台。

## 2. 数据模型

新增 `med_indicator_draft` 保存当前设计稿投影，主要字段包括：

- `draft_id`、`hospital_id`、`base_index_code`、`proposed_index_code`；
- 名称、类型、定义、统计周期、分子、分母、筛选、排除条件；
- `metric_type`、`metadata_requirements`、`field_mapping`、`sql_plan`；
- 当前 SQL、SQL 参数、最近试运行结果、状态和当前版本；
- 创建人、更新人和时间。

新增 `med_indicator_draft_version`，以完整 JSON 快照追加保存每次生成、编辑、元数据确认、SQL 生成和试运行后的版本。旧快照不可修改。

新增 `med_index_hospital_defined` 保存本院新增指标的当前生效投影，新增 `med_index_hospital_defined_version` 保存不可变历史。字段覆盖口径、SQL 模板、参数、字段契约、审批、生效区间和版本信息。

现有指标的设计稿审批后继续写入 `med_index_hospital_custom` 及其版本表；本院新增指标审批后写入 `med_index_hospital_defined` 及其版本表。两类发布都在单个事务内完成。

## 3. 状态机

设计稿状态固定为：

```text
drafting
metadata_pending
metadata_ready
sql_ready
trial_passed
pending_approval
published
rejected
```

状态约束：

- 自然语言生成后进入 `metadata_pending`；
- 字段映射全部确认后进入 `metadata_ready`；
- SQL 计划渲染并通过只读校验后进入 `sql_ready`；
- DBHub 试运行成功后进入 `trial_passed`；
- 只有 `trial_passed` 可以提交审批；
- 只有 `pending_approval` 可以审批或拒绝；
- 修改口径、映射或 SQL 计划会创建新版本，并使旧 SQL 与试运行结果失效。

分母为零但 SQL 成功执行时允许进入 `trial_passed`，同时保留“当前周期无样本”警告。

## 4. 自然语言结构化解析

指标生成 Agent 调用本地 Ollama，把用户描述解析为类型化 `IndicatorDraftSpec`，至少包含：

- 指标名称、定义、指标类型和统计周期；
- 分子、分母、筛选和排除规则；
- `ratio` 或 `count` 计算类型；
- 主表、统计对象字段、时间字段、医院字段；
- 分子和分母条件；
- 所需业务字段清单。

解析结果必须经过 Pydantic 校验。模型输出缺字段、未知操作符或多表关联时不保存为可执行稿，而是返回明确的补充信息。用户可以在前端编辑结构化字段后保存新版本。

如果描述明确引用现有指标，系统设置 `base_index_code`；否则生成医院级候选编码，例如 `HOSP001_20260710_001`。候选编码在设计阶段保留，审批发布后正式占用。

## 5. 元数据确认

元数据解析 Agent 从 `med_metadata_table`、`med_metadata_column` 读取本院最近一次同步快照，为设计稿所需业务字段提供候选表列。

- 唯一候选可以自动建议，但仍标记来源和置信度；
- 多个候选必须由用户选择；
- 不存在的字段进入缺失清单；
- 用户确认后，映射只保存在设计稿中，不提前写入正式 `med_field_mapping`；
- 审批发布时才把确认映射复制到正式映射表。

多表需求返回 `multi_table_not_supported`，不自动生成 JOIN。

## 6. 安全 SQL 计划

模型不直接生成 SQL 字符串，只生成结构化 `IndicatorSQLPlan`：

- `main_table`；
- `metric_type`；
- `subject_field`、`time_field`、`hospital_field`；
- 分子条件、分母条件；
- 允许的参数值。

第一版支持 `ratio`、`count`，以及 `eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`in`、`not_in`、`is_null`、`not_null` 操作符。系统按确认映射渲染单条参数化 MySQL `SELECT`，强制带医院和统计周期条件，再调用现有 SQL 安全校验。

生成结果写入 `med_generated_sql`，并关联 `draft_id` 和设计稿版本。试运行继续通过 DBHub MCP，只读、超时和参数绑定规则保持不变。

## 7. 审批与发布

提交审批前校验：

- 当前版本是最新版本；
- 元数据映射全部确认；
- SQL 通过安全校验；
- 最近一次试运行对应当前版本且执行成功。

审批现有指标设计稿时，创建新的医院口径版本；审批本院新增指标时，创建或更新本院新增指标当前投影和不可变版本。发布后设计稿状态变为 `published`，并记录正式指标编码和版本。

本院新增指标加入 MySQL 规则检索。检索顺序为本院新增指标、国标指标、本院口径合成，数据库异常时仅国标类规则允许 Wiki 只读兜底。本院新增指标不能因数据库故障从 Wiki 伪造。

恢复本院新增指标历史版本时复制旧快照创建更高的新版本，不修改历史。

## 8. API 与前端

新增 `/api/indicator-drafts` 管理接口：

- 自然语言生成设计稿；
- 列表、详情和保存新版本；
- 元数据候选与映射确认；
- SQL 生成和试运行；
- 提交审批、管理员批准或拒绝；
- 查询本院新增指标版本并恢复。

前端顶部增加“指标设计稿”入口，使用一个轻量弹窗展示输入、结构化口径、字段映射、SQL、试运行和状态操作。不提前拆分五个业务页面。

每个步骤记录 Trace 节点，节点归属为指标生成 Agent、元数据解析 Agent或口径适配 Agent；详情继续默认折叠。

## 9. 错误与安全边界

- LLM 解析失败只保留错误说明，不生成可发布口径；
- 元数据缺失停止 SQL 生成；
- SQL 计划引用未确认字段时停止渲染；
- SQL 校验或试运行失败不能提交审批；
- 发布事务失败时设计稿保持 `pending_approval`；
- 医院用户不能批准自己的设计稿；
- 写操作在 MySQL 不可用时失败关闭，不写 Wiki；
- 所有正式 SQL 只能是带医院隔离和时间范围的单条只读 `SELECT`。

## 10. 验收标准

- 可从自然语言创建比例类和计数类指标设计稿；
- 每次编辑和流程推进均产生不可变版本；
- 未确认字段、多表需求和非法条件无法生成 SQL；
- 生成 SQL 强制医院和周期过滤，并可通过 DBHub 试运行；
- 现有指标发布为本院口径差异，新指标发布为本院新增指标；
- 发布后的新指标可以在对话中检索、生成 SQL、试运行和诊断；
- 历史版本恢复采用追加版本；
- 现有四指标结果和全部自动化测试不回归。
