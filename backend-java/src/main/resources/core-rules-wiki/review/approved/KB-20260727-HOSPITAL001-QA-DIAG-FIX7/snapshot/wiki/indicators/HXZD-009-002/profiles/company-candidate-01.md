---
page_type: caliber_profile
profile_id: HXZD-009-002-company-candidate-01
rule_id: HXZD-009-002
profile_name: 可选方案（未实现）：纠纷数据来源：HIS患者标签
owner_scope: company
status: draft
execution_status: draft
effective_from: 2025-01-01
effective_to:
time_dimension: death_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: lower_is_better
runtime_manifest: ../../../sql-specs/HXZD-009-002/runtime.json
updated_at: 2026-07-27
---

# 可选方案（未实现）：纠纷数据来源：HIS患者标签

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | — |
| 扩展指标编码 | HXZD-009-002_002 |
| 版本 | 公版 |
| 指标名称别名 | 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值 |
| 源表主表来源 | EMPLOYEE_INFO, HOLIDAY, INPATIENT_EMR_CONTENT, INPATIENT_EMR_SECTION, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_EMR_SECTION_DATA_ELEMENT, MAHP_DMTS_MAIN, MRAS_MANAGE_COMPLAIN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | — |
| 业务表(不影响数据) | — |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 可选方案（未实现） |
| 方案说明 | 纠纷数据来源：HIS患者标签 |
| 关联事件 | CORE_DEATH_EXT |
| 事件名称 | 死亡病例可选方案 |
| 时间维度 | 死亡时间 |
| 患者范围 | — |
| 数据来源 | — |
| 指标导向 | — |
| 计量单位 | — |
| 目标值 | 无 |
| 异常范围初筛 | 0 |

## 分子

医务部门组织进行死亡病例讨论的病例数量

### 统计口径

1、单位时间内死亡且本次在院期间发生投诉纠纷的患者，死亡病例讨论记录的主持人为医务科人员
2、医务科人员定义：员工隶属科室名称为“医务科”或“医务部”

## 分母

同期发生医疗纠纷的死亡病例数量

### 统计口径

1、单位时间内死亡且本次在院期间发生投诉纠纷的患者数量
3、患者投诉纠纷数据来源为HIS的患者标签
4、取病案首页中转归情况=死亡，死亡时间取病案中的出院时间

## 可配置参数

无。

## 执行引用

- 当前执行状态：`draft`
- 阻断原因：方案标记为未实现
- 源表 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-candidate-01/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-candidate-01/overview.sql`
- 科室 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-candidate-01/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-candidate-01/patient_detail.sql`
