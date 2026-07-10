# 国标与本院口径双执行诊断设计

## 1. 目标与范围

第四批增强诊断第二层“口径规则校验”：对存在本院定制口径的国标指标，在同一医院、同一统计周期、同一字段映射下分别执行纯国标口径和本院生效口径，比较执行状态与指标结果，定位医院定制口径是否引入异常。

本批只覆盖已经迁移到 MySQL 且具备可执行 SQL 和字段映射的指标。首批验收范围仍为：

- `MQSI2025_001` 患者入院 48 小时内转科的比例；
- `MQSI2025_005` 急会诊及时到位率；
- `MQSI2025_014` 急危重症患者抢救成功率；
- `MQSI2025_035` 术中自体血回输率。

本批不实现第六批完整工作台。指标设计稿的结构化编辑、国标模板同屏对照、前端版本恢复、完整指标文档导出和设计稿专用 Trace 页面统一延后到第六批。

## 2. 设计原则

1. 国标口径与本院口径都从医院运行库 MySQL 读取，不从 Wiki 拼接当前生效配置。
2. 两套口径必须使用相同医院、统计周期、业务数据源和已确认字段映射。
3. 所有业务库访问继续通过 DBHub MCP，只允许单条参数化 `SELECT`。
4. 结果不同只能证明医院定制引入了计算差异，不能在缺少业务阈值时直接证明本院口径错误。
5. 只有执行状态形成明确证据时才阻断诊断，例如国标成功而本院口径执行失败。
6. 本院新增指标没有国标基线，双口径比较标记为不适用，不伪造国标结果。

## 3. Agent 边界

### 3.1 口径适配 Agent

`CaliberAdaptationAgent` 负责从 `RuleRepository` 获取类型化双口径上下文，不执行 SQL。上下文包含：

- `rule_id`、`hospital_id`；
- 是否存在有效本院定制口径；
- 国标 SQL 模板、国标参数、国标版本；
- 本院生效 SQL 模板、本院参数、本院版本；
- 本院覆盖字段；
- 口径来源和不可比较原因。

`MySQLRuleRepository` 从 `med_index_standard` 与当前有效的 `med_index_hospital_custom` 组装上下文。`FallbackRuleRepository` 在 MySQL 不可用时返回不可比较状态，不允许用 Wiki 伪造医院定制口径。

### 3.2 统一编排器

`CoreIndicatorOrchestrator.diagnose()` 在调用根因排查 Agent 前：

1. 获取双口径上下文；
2. 复用已经解析的 `FieldMapping`；
3. 将双口径上下文、字段映射和统计周期一并传给根因排查 Agent。

HTTP API 和 LangGraph/SSE 适配层不直接读取规则仓库，也不执行双口径 SQL。

### 3.3 根因排查 Agent

新增独立 `caliber_compare` 模块，职责是：

1. 渲染国标与本院 SQL；
2. 分别执行 SQL 安全校验；
3. 绑定同一组 `hospital_id`、`start_time`、`end_time` 及各自口径参数；
4. 通过 `BusinessDBClient`/DBHub 只读执行；
5. 返回结构化比较结果；
6. 将比较结果交给第二层 `rule_check` 形成诊断结论。

静态公式完整性检查继续保留，但不再承担 SQL 执行职责。

## 4. 双口径上下文

新增类型化契约 `CaliberComparisonContext`：

```text
rule_id
hospital_id
applicable
reason
national_sql_template
national_params
national_version
effective_sql_template
effective_params
hospital_version
overridden_fields
```

组装规则：

- 国标 SQL 始终来自 `med_index_standard.standard_sql`；
- 国标参数始终来自 `med_index_standard.rule_params`；
- 本院 SQL 优先使用有效医院记录的 `custom_sql`，为空时复用国标 SQL 模板；
- 本院参数以国标参数为基础，合入有效医院记录的 `custom_params`；
- 没有有效医院记录时 `applicable=false`，原因是 `no_hospital_customization`；
- 本院新增指标 `applicable=false`，原因是 `hospital_defined_has_no_national_baseline`；
- MySQL 不可用或规则未迁移时 `applicable=false`，原因分别为 `rule_store_unavailable` 或 `rule_not_migrated`。

## 5. 统计周期

双执行必须使用半开区间 `[start_time, end_time)`。

- API 继续兼容 `stat_period="YYYY-MM-DD HH:MM:SS~YYYY-MM-DD HH:MM:SS"`；
- 仅提供日期时，开始时间补 `00:00:00`，结束日期按次日 `00:00:00` 处理；
- 未提供周期时使用服务器当前自然月；
- 无法解析时在进入业务库前失败，返回明确的周期格式错误；
- 最终采用的开始、结束时间写入比较结果和诊断报告。

## 6. SQL 渲染与执行

双口径执行复用：

- `render_sql()` 渲染 Jinja2 字段模板；
- `validate_select_sql()` 校验只读、单语句、主表和统计周期参数；
- `_bind_sql_params()` 进行安全字面量绑定；
- `BusinessDBClient.execute_select()` 通过 DBHub 执行。

每一侧只读取第一行的 `index_value` 与 `sample_count`。比较结果不保存患者明细行，不向 LLM 发送业务数据。

执行结果结构：

```text
status: success | empty | failed | skipped
result_value
sample_count
no_sample
duration_ms
error_code
error_message
version
```

诊断报告保存两侧状态、结果、样本量、耗时、版本、参数差异和结果差值，不保存展开后的含值 SQL。

## 7. 诊断分类

第二层按以下固定顺序分类：

| 国标执行 | 本院执行 | 第二层结论 | 是否继续第三层 |
|---|---|---|---|
| 成功 | 失败 | `hospital_caliber_execution_failed`，确认本院定制口径或定制 SQL 问题 | 否 |
| 失败 | 成功 | `national_caliber_execution_failed`，国标实现异常 | 否 |
| 失败 | 失败 | `shared_caliber_execution_failed`，共同 SQL、映射或业务库问题 | 否 |
| 成功 | 成功且结果不同 | `caliber_result_diff`，口径差异风险 | 是 |
| 成功 | 成功且结果一致 | `caliber_result_same`，基本排除医院定制导致结果异常 | 是 |
| 无样本 | 无样本 | `caliber_no_sample`，当前周期无法比较 | 是 |
| 不适用 | 不适用 | `caliber_compare_not_applicable` | 是 |

数值结果使用绝对差值比较，默认容差为 `0.01`。超过容差才视为结果不同。报告同时返回：

- `national_value`；
- `hospital_value`；
- `absolute_delta`；
- `relative_delta_percent`，国标结果为零时返回空；
- `national_version` 与 `hospital_version`；
- `overridden_fields`。

`caliber_result_diff` 是警告，不是失败。只有单侧或双侧执行失败才令第二层 `ok=false` 并停止后续数据层诊断。

## 8. 错误处理

- 字段映射缺失由第一层拦截，不进入双执行。
- 任一 SQL 未通过安全校验，该侧状态为 `failed`，错误码为 `CALIBER_SQL_INVALID`。
- DBHub 不可用，该侧状态为 `failed`，错误码为 `CALIBER_DBHUB_UNAVAILABLE`。
- 参数缺失在调用 DBHub 前失败，错误码为 `CALIBER_PARAM_MISSING`。
- 结果没有 `index_value` 时状态为 `empty`，不能误记为零。
- 诊断报告保存失败不影响已经形成的内存诊断结果，但 API 必须返回持久化错误，防止用户误认为报告已留存。

## 9. Trace 与可观测性

现有 `diagnose_rule_check` 节点扩展为真实计时节点，并在详情中展示：

- 比较模式与是否适用；
- 国标执行状态、结果、耗时和版本；
- 本院执行状态、结果、耗时和版本；
- 差值、结论代码和定位建议。

摘要只显示“结果一致”“存在口径差异”“本院口径执行失败”或“不适用”，具体参数和结果继续在节点详情中展开。

## 10. API 与前端验收

`POST /api/diagnose/run` 保持兼容，返回的第二层增加 `caliber_comparison`。前端现有诊断结果和执行链路无需新页面即可展示新增字段。

验收至少覆盖：

1. 指标五国标 10 分钟、本院 20 分钟时，两侧结果分别为 `33.33` 与 `66.67`，第二层为警告且继续第三层；
2. 没有医院定制口径时不执行两遍 SQL；
3. 国标成功、本院自定义 SQL 失败时停止在第二层并给出本院口径修复建议；
4. 两侧 SQL 使用同一医院和统计周期；
5. DBHub 调用只有只读 `SELECT`，不保存患者明细；
6. 双执行结果、版本和差值写入 `med_index_diagnose_report.layer_results`；
7. 四个首批指标原有 SQL 结果和全部自动化测试不回归。
