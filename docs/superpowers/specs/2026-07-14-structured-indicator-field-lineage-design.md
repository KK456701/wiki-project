# 结构化指标字段血缘与计算规则设计

## 背景

当前系统已经具备指标规则、医院字段映射和 DBHub 元数据同步，但三者之间仍有一段关系依赖文字解释：

- `rule_sql_spec.yaml` 使用 `arrive_minutes` 等业务计算名描述分子、分母。
- `field_contract.yaml` 定义 `request_time`、`arrive_time` 等业务字段。
- `med_field_mapping` 和医院 YAML 映射把业务字段落到物理表字段。
- `information_schema` 与 DBHub 只能证明物理表字段存在，不能判断它们应当用于哪个指标。

因此，系统虽然可以生成 SQL，但无法仅依赖结构化数据完整回答“分子为什么使用这些表和字段、分母为什么使用另外一些字段、本院口径具体作用在哪个计算条件上”。本设计增加明确的指标计算定义和字段血缘，让 SQL 生成、业务解释、字段校验和版本审计使用同一份事实。

## 目标

1. 分子、分母的每个条件都明确引用业务字段、派生字段或参数。
2. 派生字段明确记录计算方法和来源字段，例如到位耗时由到位时间减申请时间得到。
3. 通过医院字段映射把业务字段解析为数据库、表和列，不从字段名或 SQL 文本猜测。
4. 多表指标明确记录主表、关联表、关联字段和连接方式。
5. DBHub 元数据同步负责验证物理结构，不参与业务语义推断。
6. 前端用业务人员可理解的方式展示分母取数、分子追加条件和本院口径落点。
7. 兼容现有四个指标和旧版规则，迁移期间不影响现有 SQL 生成与试运行。

## 不在本批范围

- 不让系统根据 `information_schema` 自动决定指标字段。
- 不允许 LLM 未经确认自动写入正式字段映射。
- 不提供患者级明细展示或下载。
- 不一次性迁移全部35个指标，只为后续指标提供统一结构，并迁移当前四个已实现指标。
- 不把 Excel 作为运行时口径存储；Excel 仅作为导入和协作审阅格式。

## 总体架构

系统采用六层协作，而不是在三种方案中单选：

1. **指标规则层**：定义分母、分子、统计范围、聚合方式和参数。
2. **业务语义层**：定义跨医院稳定的业务字段和派生字段。
3. **医院字段映射层**：使用现有 `med_field_mapping` 将业务字段映射到医院物理字段。
4. **表关联层**：记录多表场景的关联方向、关联键、连接类型和确认状态。
5. **数据库结构校验层**：通过 DBHub 同步的 `med_metadata_table`、`med_metadata_column` 验证表字段存在性和类型。
6. **SQL 与解释层**：从同一结构化计算定义生成 SQL 和业务说明，禁止各自维护另一套规则。

运行时以 MySQL 为权威记录。Wiki/YAML 和 Excel 是导入来源，审批发布后写入 MySQL；数据库不可用或规则尚未迁移时，Wiki 只读兜底继续沿用现有机制。

## 结构化计算定义

### 标准结构

标准指标新增 `calculation_definition`，使用结构化条件替代仅供人阅读的逻辑字符串。原有 `numerator_rule`、`denominator_rule` 继续保留，作为业务摘要和兼容字段。

```yaml
schema_version: 1
scope:
  conditions:
    - id: hospital_scope
      field: hospital_id
      operator: equals
      parameter: hospital_id
    - id: period_scope
      field: request_time
      operator: half_open_range
      parameters: [start_time, end_time]

derived_fields:
  arrive_minutes:
    name: 申请至到位耗时
    operation: timestamp_diff_minutes
    source_fields: [request_time, arrive_time]

denominator:
  name: 同期急会诊总次数
  inherits: scope
  conditions:
    - id: urgent_consult
      field: consult_type
      operator: equals
      parameter: consult_type_value
  aggregate:
    method: count_rows

numerator:
  name: 及时到位急会诊次数
  inherits: denominator
  conditions:
    - id: timely_arrival
      field: arrive_minutes
      operator: inclusive_range
      values: [0, arrive_minutes_threshold]
  aggregate:
    method: count_rows

result:
  operation: ratio_percent
  numerator: numerator
  denominator: denominator
```

### 继承语义

- `scope` 是分子和分母共同适用的医院、统计周期等范围。
- `denominator.inherits: scope` 表示分母包含全部统计范围条件。
- `numerator.inherits: denominator` 表示分子先满足全部分母条件，再应用分子追加条件。
- 前端必须明确展示继承关系，避免让用户误以为分子和分母来自互不相关的数据集。

### 派生字段血缘

派生字段不是医院物理列，必须包含：

- 中文业务名称。
- 确定性的运算类型。
- 来源业务字段列表。
- 可选的空值处理和异常值处理规则。

首批只支持当前四个指标需要的有限运算，例如分钟差、去重计数和条件计数，不设计任意表达式执行器。SQL 模板仍负责数据库方言差异，结构化定义负责提供可审计的业务语义。

## 医院字段映射

继续使用现有 `med_field_mapping`，其职责保持单一：

```text
(hospital_id, rule_id, business_field)
    -> (db_name, table_name, column_name, data_type, status)
```

例如：

```text
request_time -> hospital_demo_data.consult_record.request_time
arrive_time  -> hospital_demo_data.consult_record.arrive_time
```

解析链路为：

```text
分子条件 arrive_minutes
-> 派生字段来源 request_time + arrive_time
-> med_field_mapping
-> consult_record.request_time + consult_record.arrive_time
```

字段映射必须处于 `confirmed` 状态才可生成可执行 SQL。LLM 或字段名相似度只能产生“待确认候选”，不能直接进入正式映射。

## 表关联关系

单表指标使用字段映射中的同一物理表即可。多表指标新增医院级表关联配置，至少记录：

- 医院和数据库。
- 左表、左字段、右表、右字段。
- 连接类型，首批仅允许 `INNER JOIN` 和 `LEFT JOIN`。
- 关系来源：数据库外键、实施配置或已审批建议。
- 确认状态、维护人和更新时间。

物理外键可以从元数据中发现，但医院数据库经常没有完整外键，因此实施配置仍是必要来源。未确认的表关联关系必须阻断 SQL 生成，并在前端指出缺少哪两个表之间的关系。

## MySQL 存储

### 标准指标

`med_index_standard` 增加 `calculation_definition JSON`，保存已发布的标准结构化计算定义。现有字段保持不变：

- `numerator_rule`、`denominator_rule`：业务摘要。
- `rely_table_field`：业务字段契约。
- `rule_params`：默认参数。
- `standard_sql`：已审核 SQL 模板。
- `calculation_definition`：分子、分母、派生字段和聚合关系的权威定义。

### 医院口径

医院通常只覆盖参数、筛选条件或少量计算条件，不复制整份标准定义。`med_index_hospital_custom` 增加可选的 `custom_calculation_patch JSON`，审批后按字段级补丁合成“本院生效计算定义”。版本快照必须包含该补丁，从而支持审计和回退。

### 表关联

新增 `med_table_relation` 保存医院已确认的物理表关联。唯一性由医院、数据库、左右表和左右字段共同约束。表关联配置同样需要状态、维护人和更新时间。

数据库升级使用幂等迁移脚本，不能只修改 `CREATE TABLE IF NOT EXISTS`，以保证已有部署能够补充新列和新表。

## YAML 与 Excel 导入

### YAML

四个现有 `rule_sql_spec.yaml` 增加 `schema_version`、`scope`、`derived_fields`、结构化分子和分母。导入器校验后写入 MySQL 的 `calculation_definition`。

### Excel

后续 Excel 模板面向质控办和信息科，至少包含以下工作表：

1. 指标基本信息。
2. 分母条件。
3. 分子追加条件。
4. 派生字段。
5. 医院字段映射。
6. 表关联关系。

Excel 解析后必须转换为与 YAML 相同的内部结构，并经过相同校验、审批和版本发布流程。Excel、YAML 不形成第二套运行时规则。

## 运行流程

1. 根据指标编码读取标准结构化计算定义。
2. 合成已审批且处于生效期的医院计算补丁和参数。
3. 收集分子、分母和派生字段依赖的全部业务字段。
4. 从 `med_field_mapping` 解析医院数据库、表和列。
5. 若涉及多表，从 `med_table_relation` 解析已确认关联。
6. 使用 DBHub 元数据快照验证表、字段、类型和关联字段是否存在。
7. SQL 生成器使用同一计算定义、字段映射和参数渲染 SQL。
8. 业务解释器使用同一上下文生成分母取数表、分子追加条件表和口径落点表。
9. 试运行只返回聚合分子、分母和指标值，不返回患者明细。

## 前端业务说明

生成 SQL 和试运行结果均展示以下内容。

### 分母如何取数

| 条件 | 数据库表字段 | 判断方式 | 条件来源 | 对分母的作用 |
|---|---|---|---|---|
| 限定医院 | `consult_record.hospital_id` | 等于 `hospital_001` | 当前医院 | 只统计本院数据 |
| 限定急会诊 | `consult_record.consult_type` | 等于“急会诊” | 指标口径 | 每条符合条件的记录进入分母 |
| 限定统计月份 | `consult_record.request_time` | 7月1日至8月1日 | 本次参数 | 只统计本月申请记录 |
| 分母计数 | `consult_record` | 按记录计数 | 标准定义 | 得到急会诊总次数 |

### 分子如何从分母中筛选

| 步骤 | 数据库表字段 | 字段关系或条件 | 口径来源 | 对分子的作用 |
|---|---|---|---|---|
| 继承分母 | 分母全部字段 | 先满足全部分母条件 | 指标定义 | 分子一定是分母的子集 |
| 计算到位耗时 | `arrive_time`、`request_time` | 到位时间减申请时间 | 派生字段定义 | 得到每次会诊的到位分钟数 |
| 判断及时到位 | 上述到位分钟数 | 0至20分钟 | 本院版本 v1 | 满足时进入分子 |

### 本院口径作用在哪

| 本院口径 | 标准值 | 作用对象 | 对应字段 | 影响范围 |
|---|---|---|---|---|
| 20分钟 | 10分钟 | 分子及时到位条件 | `arrive_time - request_time` | 只改变分子，不改变分母 |

普通用户看到中文表格；实施人员可以展开查看业务字段编码、物理字段和技术 SQL。

## 校验与失败处理

发布和生成 SQL 前必须完成以下校验：

1. 条件引用的业务字段或派生字段已定义。
2. 派生字段的所有来源字段已定义且无循环依赖。
3. 分子继承分母，比例指标不得形成分子范围大于分母的定义。
4. 所有必需业务字段都有已确认医院映射。
5. 物理表字段存在于最新元数据快照，类型与业务字段兼容。
6. 跨表字段之间存在已确认关联关系。
7. 条件引用的参数存在于标准参数或本院生效参数中。
8. 本院计算补丁只能修改允许覆盖的节点，不能静默修改标准定义。

失败时不生成可执行 SQL，并使用业务语言指出具体缺失项，例如“分子条件需要到位时间，但本院尚未确认该字段映射”。不得回退为猜测字段后继续执行。

## 兼容迁移

迁移分三步进行：

1. 为四个现有指标补充结构化计算定义，并保留旧 `logic` 文本和 SQL 模板。
2. 导入 MySQL，校验结构化定义生成的字段依赖与现有 SQL 模板一致。
3. 解释器优先读取结构化定义；旧规则缺少定义时继续使用原说明，但明确标记“字段关系尚未结构化”，不伪造血缘。

迁移稳定后，新指标必须提供结构化定义才能发布；旧字符串逻辑只保留为展示摘要。

## 测试与验收

1. 四个现有指标均能从结构化定义解析出分母字段、分子追加字段和派生字段来源。
2. 急会诊指标明确显示分母使用 `hospital_id`、`consult_type`、`request_time`，分子额外使用 `request_time`、`arrive_time`。
3. 本院20分钟口径明确显示作用于 `arrive_minutes`，并说明只改变分子。
4. 医院字段映射缺失、字段不存在、类型不兼容和表关联缺失时均阻断 SQL 生成。
5. 单表和多表定义均能生成确定的字段依赖清单。
6. 标准定义与医院补丁合成后可以版本审计和回退。
7. YAML 与 Excel 导入相同内容时得到等价的内部结构。
8. SQL 生成说明和试运行说明使用同一字段血缘，不出现解释与 SQL 不一致。
9. 前端不展示患者级数据，执行链路只记录规则版本、映射状态和聚合结果。
10. 现有 SQL 安全校验、监控、诊断、审批和知识库兜底测试保持通过。
