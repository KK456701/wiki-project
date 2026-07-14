# 指标明细数据来源展示设计

## 目标

让医生在查看指标明细或下载 Excel 时，能直接确认数据来自哪个医院数据库、哪些业务表；让实施人员按需展开字段对应关系，定位每一列来自哪个医院字段，以及哪些列是由原始字段计算得到的。

本设计只展示非患者级来源元数据，不展示连接地址、数据库账号、密码、SQL、服务器路径或内部异常信息。

## 当前问题

试运行保存的 `RunContext` 已包含 `db_source`、`main_table`、`field_mapping` 和派生字段定义，但 `DetailSnapshotSummary` 只返回指标、口径、统计区间、数量和明细列。前端与 Excel 只消费后者，因此来源信息在展示层丢失。

以急会诊及时到位率为例，当前明细实际来自：

- 数据库：`hospital_demo_data`
- 主表：`consult_record`
- 直接字段：`patient_id`、`dept_id`、`consult_type`、`request_time`、`arrive_time`
- 派生字段：`arrive_minutes`，由 `request_time` 和 `arrive_time` 计算

## 采用方案

采用方案 B：页面始终展示数据库和数据表，字段来源按需展开；Excel 在每个现有工作表顶部同时写入数据库、数据表和字段对应关系。继续保持“统计范围、达到要求、未达到要求”三个工作表，不增加第四个工作表。

### 医生默认视图

明细窗口标题区新增“数据来源”区域，直接显示：

```text
来源数据库：hospital_demo_data
取数表：consult_record
```

名称使用“来源数据库”和“取数表”，不使用“DBHub source”“schema”等内部术语。来源区域位于口径和统计区间之后、三个数量标签之前，在桌面和窄屏下都不能被横向表格遮挡。

### 实施人员展开视图

数据来源区域提供原生可展开的“查看字段来源”，内容使用中文业务列在前、医院字段在后：

| 页面列 | 来源说明 |
|---|---|
| 患者标识 | 来自 `consult_record.patient_id` |
| 科室 | 来自 `consult_record.dept_id` |
| 会诊类型 | 来自 `consult_record.consult_type` |
| 申请时间 | 来自 `consult_record.request_time` |
| 到位时间 | 来自 `consult_record.arrive_time` |
| 到位耗时（分钟） | 由申请时间和到位时间计算 |

派生字段不能伪装成原始数据库列。其说明从结构化 `calculation_definition.derived_fields[*].source_fields` 生成，并把源业务字段继续解析为医院表字段。

### Excel

三个工作表顶部都增加：

- 来源数据库
- 取数表
- 字段来源

“字段来源”单元格按行展示“中文业务列 → 医院表字段”；派生字段展示“中文业务列 → 由……计算”。元数据行增加后，明细表头和冻结窗格按实际元数据行数动态下移。Excel 仍保留三个工作表，仍不包含 SQL、宏、外部链接或数据库连接信息。

## 后端数据契约

`DetailSnapshotSummary` 增加以下非患者级字段：

```python
source_database: str
source_tables: list[str]
field_lineage: list[DetailFieldLineage]
```

`DetailFieldLineage` 固定字段：

```python
field: str
label: str
kind: Literal["column", "derived"]
sources: list[str]
explanation: str
```

来源生成规则：

1. 数据库优先读取 `field_mapping.db_name`；旧运行上下文没有该字段时回退 `RunContext.db_source`。
2. 直接字段从 `field_mapping.fields` 解析完整的 `table.column`，数据表按首次出现顺序去重，`main_table` 始终排在第一位。
3. 派生字段从 `calculation_definition.derived_fields` 读取源业务字段，再解析为医院表字段；`sources` 保存这些完整字段名。
4. 第一版四指标仍只允许单主表查询，但契约使用 `source_tables` 数组，避免未来多表指标再次修改接口结构。
5. 字段映射缺失时沿用现有安全策略阻断明细生成，不输出猜测来源。

短期 `jsonl.gz` 快照的患者行格式不变；来源元数据继续从试运行口径快照生成，避免复制到每条患者记录中。

## 安全与兼容

- API 只返回逻辑数据库名、表名、列名和派生说明，不返回主机、端口、账号、密码、连接串或 SQL。
- 来源信息允许进入执行审计之外的响应和 Excel，因为它不包含患者字段值；审计日志仍不记录整份字段映射。
- 已生成的短期快照可以继续复用，因为读取摘要时仍可从对应 `med_sql_run_log.run_context_json` 补全来源信息。
- 模型新增字段提供空默认值，用于兼容测试替身和极少数旧运行记录；正式创建明细时必须返回非空数据库和数据表。
- 页面字段来源使用文本节点构造，不把后端值拼接为 HTML。

## 错误处理

- 缺少数据库名：显示“来源数据库未记录”，并提示重新试运行；不猜测当前配置值。
- 缺少直接字段映射：沿用“明细字段尚未完成本院映射”阻断，不生成快照或 Excel。
- 派生字段源字段不存在：阻断明细生成，错误定位到对应派生字段。
- 多表来源：页面和 Excel 展示全部表名；查询是否允许仍由现有 SQL 生成安全规则决定。

## 验收标准

1. 急会诊明细窗口直接显示 `hospital_demo_data` 和 `consult_record`。
2. 展开字段来源后，五个直接字段显示完整 `table.column`，到位耗时显示由申请时间和到位时间计算。
3. 三个 Excel 工作表顶部都包含相同的数据库、表和字段来源，工作表数量仍为三个。
4. 页面和 Excel 不出现数据库密码、连接串、SQL 或服务器绝对路径。
5. 现有 576/488/88 数量、脱敏预览、完整授权导出和 24 小时清理行为不变。
6. 1440、768 和 390 像素宽度下，来源区域、展开控件和明细表格不重叠。

## 测试范围

- 模型和快照测试：直接字段、派生字段、数据库回退和来源表去重。
- API 测试：来源字段序列化且不包含连接串。
- Excel 测试：三个工作表均有来源元数据，表头位置动态正确。
- 前端测试：来源区域、字段展开和安全文本渲染存在。
- 端到端测试：576/488/88 数量和三工作表保持一致，同时来源信息一致。
- 浏览器验收：桌面、平板和手机宽度检查展开前后布局。
