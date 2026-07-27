---
page_type: caliber_profile
profile_id: HXZD-008-002-company-candidate-01
rule_id: HXZD-008-002
profile_name: 可选方案：按患者出区时间统计
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: admitted_to_ward_at
patient_scope:
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-008-002/runtime.json
updated_at: 2026-07-27
---

# 可选方案：按患者出区时间统计

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | — |
| 扩展指标编码 | HXZD-008-002_002 |
| 版本 | 公版 |
| 指标名称别名 | 术者参加术前讨论率_出区时间 |
| 源表主表来源 | EMPLOYEE_INFO, MRAS_PATIENT_EVENT, VALUE_SET |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_OP_DISC |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 可选方案 |
| 方案说明 | 按患者出区时间统计 |
| 关联事件 | CORE_OP_DISC_V2 |
| 事件名称 | 术前讨论制度V2 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 电子病历系统、住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 100% |
| 异常范围初筛 | < 50% |

## 分子

术者参加术前讨论的手术例数

### 统计口径

单位时间内出区的患者，术前讨论记录（病历）中，讨论人或主持人包含手术记录（病历）中手术医师的数量
说明：EMR的术前讨论记录跟手术记录没有关联关系，本指标通过手术记录创建前48h内书写的最近一份术前讨论记录来做对比

## 分母

同期进行术前讨论手术总例数

### 统计口径

单位时间内出区的患者，完成术前讨论记录的数量

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-008-002/profiles/HXZD-008-002-company-candidate-01/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-008-002/profiles/HXZD-008-002-company-candidate-01/overview.sql`
- 科室 SQL：`sql-specs/HXZD-008-002/profiles/HXZD-008-002-company-candidate-01/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-008-002/profiles/HXZD-008-002-company-candidate-01/patient_detail.sql`
