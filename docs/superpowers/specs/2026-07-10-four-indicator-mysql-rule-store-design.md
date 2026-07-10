# 四指标 MySQL 规则主存储设计

## 1. 背景与目标

当前项目以 Markdown、YAML 和 JSON 索引作为指标规则的主要运行时来源，MySQL 主要保存元数据、SQL 运行、诊断和 Trace 数据。需求规格要求指标国标规则和医院定制口径结构化落库，并以数据库支持口径隔离、版本追溯和业务运行。

本设计是整体工程化改造的第一批，只覆盖以下四个指标和 `hospital_001`：

- `MQSI2025_001`：患者入院 48 小时内转科的比例
- `MQSI2025_005`：急会诊及时到位率
- `MQSI2025_014`：急危重症患者抢救成功率
- `MQSI2025_035`：术中自体血回输率

本批目标是将 MySQL 设为规则运行时主存储，保留 Wiki 作为导入来源和只读故障兜底，并让四个指标完成规则查询、字段预校验、SQL 生成、安全校验、试运行、版本追溯和 Trace 闭环。

本批不扩展其余 31 个指标，不建设完整运维工作台，也不在本批重写全部 Agent。五类 Agent 拆分将在规则数据边界稳定后作为下一批实施。

## 2. 核心决策

### 2.1 主存储与兜底

运行时优先级固定为：

1. 读取 `hospital_001` 已审批、启用且处于生效期内的医院定制口径。
2. 对未被医院覆盖的字段读取 MySQL 国标规则。
3. MySQL 查询失败或指标尚未迁移时，使用 Wiki 只读兜底。

兜底不是第二写入源。审批、修改、启停和版本恢复等写操作在 MySQL 不可用时必须失败，禁止自动写回 Wiki。

每次规则解析必须返回 `rule_source`：

- `mysql`：规则由 MySQL 正常解析。
- `wiki_fallback`：规则由 Wiki 只读兜底获得。

Trace 同时记录国标版本、医院版本、覆盖字段和最终生效值。

### 2.2 双层业务口径

业务口径只保留“国标”和“医院定制”两层，医院口径优先。

现有“公司标准”不再作为业务口径优先级的一层。它只作为首次导入 SQL 模板、字段契约和实现说明的技术来源，不能覆盖医院或国标业务定义。

### 2.3 范围控制

第一批仅导入四个指标。其他指标仍可通过 Wiki 查询，但会显示 `wiki_fallback`，且不承诺 SQL 生成和试运行能力。

## 3. 数据模型

### 3.1 `med_index_standard`

保存四个国标指标的结构化规则。保留需求文档指定字段，并补充运行所需字段：

- `id`
- `index_code`
- `index_name`
- `index_type`
- `index_desc`
- `stat_cycle`
- `numerator_rule`
- `denominator_rule`
- `filter_rule`
- `exclude_rule`
- `rely_table_field`
- `standard_sql`
- `rule_params`
- `source_path`
- `version`
- `status`
- `create_time`
- `update_time`

`rely_table_field` 和 `rule_params` 使用 JSON 保存字段契约及参数定义。`standard_sql` 保存参数化 MySQL Jinja 模板。

### 3.2 `med_index_hospital_custom`

保存医院当前生效口径，每个 `hospital_id + index_code` 仅保留一条当前投影：

- 需求文档原有的医院、指标、分子、分母、筛选、排除、SQL、版本、状态和操作人字段
- `custom_params`
- `approval_status`
- `effective_from`
- `effective_to`
- `update_time`

只有 `approval_status=approved`、`status=1` 且当前时间处于生效区间内的记录可以覆盖国标。

### 3.3 `med_index_hospital_custom_version`

以追加方式保存每次审批、修改和恢复的完整快照，至少记录：

- 医院、指标和版本号
- 完整口径快照
- 变更类型和来源版本
- 操作人、审批人和操作时间
- 是否曾作为生效版本

恢复历史版本时，不直接修改旧快照，而是创建一个新版本并把其内容设为当前投影。

### 3.4 复用现有表

继续使用以下现有结构：

- `med_field_mapping`：四指标的医院字段映射
- `med_generated_sql`：生成 SQL
- `med_sql_run_log`：试运行日志
- `med_index_run_result`：指标结果
- `med_index_diagnose_report`：诊断报告
- `med_agent_trace` 和 `med_agent_trace_node`：执行链路

## 4. 组件边界

### 4.1 `WikiRuleImporter`

只负责将四个指标从 Wiki、现有 SQL 规格和医院映射导入 MySQL。导入按指标独立事务执行，并使用 upsert 保证重复运行不会重复插入。

导入结果返回每个指标的 `inserted`、`updated` 或 `failed` 状态以及失败原因。

### 4.2 `RuleRepository`

为查询、SQL 生成、诊断和审批提供统一接口：

- 搜索和解析指标
- 获取国标规则
- 获取医院当前口径
- 解析最终生效口径
- 获取字段契约、参数和 SQL 模板
- 保存医院口径
- 查询历史版本
- 恢复历史版本

业务模块不再直接读取 Markdown、YAML 或 JSON 索引。

### 4.3 `MySQLRuleRepository`

负责 MySQL 规则查询和写入，返回结构化领域对象。医院口径按字段覆盖国标，未配置字段继续继承国标。

### 4.4 `WikiRuleSource`

复用现有 `KnowledgeBaseTools` 的解析能力，但只暴露查询接口，不暴露审批和版本写入接口。

### 4.5 `FallbackRuleRepository`

先调用 MySQL。MySQL 不可用或查不到未迁移指标时调用 Wiki，并给结果和 Trace 增加降级标记。写操作只委托给 MySQL。

## 5. 四指标业务口径

### 5.1 MQSI2025_001

业务表：`inpatient_transfer_record`。

- 分母：统计周期内不同 `admission_id` 的入院人次数。
- 分子：`transfer_time` 不为空，转科时间与入院时间差在 0 至 48 小时之间，且 `from_dept_id` 和 `to_dept_id` 均不是 ICU 的不同入院人次数。
- 48 小时边界包含正好 48 小时。
- 转科早于入院的数据不计入分子，并由数据诊断报告。
- 不再使用 `count_multiple_transfers=true` 改变分母计数方式。

演示数据：4 次入院，1 次符合，结果为 `25.00%`。

### 5.2 MQSI2025_005

业务表：`consult_record`。

- 分母：统计周期内全部急会诊记录。
- 国标分子：请求后 0 至 10 分钟内到位的急会诊次数。
- `hospital_001` 分子：请求后 0 至 20 分钟内到位的急会诊次数。
- 删除现有 SQL 规格中 30 分钟的历史错误默认值。

演示数据：3 次急会诊，国标结果 `33.33%`，医院口径结果 `66.67%`。

### 5.3 MQSI2025_014

新增业务表：`critical_rescue_record`。

固定字段为：`id`、`hospital_id`、`patient_id`、`rescue_id`、`rescue_time`、`severity_level`、`rescue_result`、`dept_id`。`rescue_id` 唯一标识一次抢救，`severity_level` 本批使用“急危重症”，`rescue_result` 本批只允许“成功”或“失败”。

- 分母：统计周期内全部急危重症患者抢救例次。
- 分子：`rescue_result=成功` 的抢救例次。
- 本批医院口径直接继承国标。

演示数据：4 次急危重症抢救，其中 3 次成功、1 次失败，结果为 `75.00%`。

### 5.4 MQSI2025_035

新增业务表：`intraoperative_transfusion_record`。

固定字段为：`id`、`hospital_id`、`patient_id`、`surgery_id`、`surgery_time`、`intraoperative_transfusion_flag`、`autologous_reinfusion_flag`、`dept_id`。两个标志字段使用 `TINYINT(1)`，只允许 `0` 或 `1`。

- 分母：统计周期内术中输血患者数，按患者去重。
- 分子：使用自体血回输的患者数，按患者去重。
- 本批医院口径直接继承国标。

演示数据：4 名术中输血患者，其中 2 名使用自体血回输；另增加同一患者的重复输血明细以验证按患者去重，结果仍为 `50.00%`。

## 6. SQL 生成与执行

四个指标的 MySQL 模板全部保存到 `med_index_standard.standard_sql`。医院存在 `custom_sql` 时使用医院 SQL；否则使用国标 SQL 模板和医院 `custom_params` 渲染。

所有 SQL 必须满足：

- 单条只读 `SELECT`
- 医院、统计开始时间、统计结束时间和阈值参数化
- 通过现有 SQL 安全校验后才能调用 DBHub
- 分母为零时返回数值 `0`，并在执行结果中返回 `no_sample=true`
- 生成记录保存规则来源、国标版本和医院版本

## 7. 数据流

```text
用户问题
  -> 指标搜索
  -> MySQL 国标规则
  -> hospital_001 生效覆盖
  -> 最终口径和结构化参数
  -> 字段预校验
  -> SQL 模板渲染
  -> SELECT 安全校验
  -> DBHub 试运行
  -> 运行结果和 Trace
```

现有对话、SQL 和诊断链路先通过 `RuleRepository` 接入新数据层。五类 Agent 的模块拆分在下一批进行，避免数据迁移和工作流重写同时发生。

## 8. 错误处理

- MySQL 规则读取失败：查询回退 Wiki，返回降级信息并记录 `wiki_fallback`。
- MySQL 写入失败：操作失败，不写 Wiki。
- 指标未迁移：允许 Wiki 查询，不允许假定其具备结构化 SQL 能力。
- 字段映射缺失：停止 SQL 生成并返回缺失业务字段清单。
- SQL 校验失败：禁止调用 DBHub，保存失败节点和校验原因。
- 分母为零：返回 `0` 和 `no_sample=true`。
- 单指标导入失败：回滚该指标事务，不影响其他指标导入和重试。

## 9. 测试与验收

采用 TDD 实施，每项行为先增加失败测试，再编写最小实现。

自动化测试至少覆盖：

- 四指标幂等导入
- MySQL 优先和 Wiki 只读兜底
- 医院口径字段级覆盖
- 指标五 10 分钟与 20 分钟差异
- 医院口径版本创建、查询和恢复
- 四指标字段预校验
- 四个 SQL 模板的安全校验与参数绑定
- 四个演示计算结果
- 分母为零和 `no_sample`
- SQL 校验失败时不调用 DBHub
- 原有测试回归

本地集成验收使用真实 MySQL 和 DBHub，确认：

- 四个指标正常查询时 `rule_source=mysql`
- 缺失或禁用 MySQL 规则时可以只读回退 Wiki
- 四个指标均可生成并试运行 SQL
- 演示结果为 `25.00%`、`66.67%`、`75.00%`、`50.00%`
- 指标五可以同时展示国标 `33.33%` 与医院 `66.67%` 的差异
- 现有 87 项测试和新增测试全部通过

## 10. 后续批次

完成本设计后，整体工程化继续按以下顺序推进，每批单独设计、实施和验收：

1. 五类 Agent 边界和统一编排
2. 四指标自然语言生成与结构化口径适配
3. 国标与医院 SQL 双执行的第二层诊断
4. 定时运算、同比环比预警和自动诊断
5. 五个业务工作台与生产级权限隔离
