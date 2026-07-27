---
page_type: caliber_profile
profile_id: HXZD-008-001-company-default
rule_id: HXZD-008-001
profile_name: 推荐方案：公版
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: surgery_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-008-001/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-008-001 |
| 扩展指标编码 | HXZD-008-001 |
| 版本 | 公版 |
| 指标名称别名 | 术前讨论完成率 |
| 源表主表来源 | EMPLOYEE_INFO, MRAS_PATIENT_EVENT, VALUE_SET |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_OP_DISC |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_OP_DISC |
| 事件名称 | 术前讨论制度 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 100% |
| 异常范围初筛 | < 90% |

## 分子

完成术前讨论的手术例数

### 统计口径

1、单位时间完成手术的患者中，术前讨论记录（病历）完成数
2、说明：手术患者判断时，只需要统计手术类型=手术、介入治疗，排除诊断性操作、治疗性操作的数据
4、一个患者多次手术，统计为多次。
5、术前讨论记录完成定义：由于手术医嘱跟术前讨论记录没有关联关系，按医嘱开立时间往前48小时内是否有术前讨论记录判断。

## 分母

同期手术总例数

### 统计口径

单位时间内完成的手术总例数（手术结束时间取医生站，排除急诊手术）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-008-001/profiles/HXZD-008-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-008-001/profiles/HXZD-008-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-008-001/profiles/HXZD-008-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-008-001/profiles/HXZD-008-001-company-default/patient_detail.sql`
