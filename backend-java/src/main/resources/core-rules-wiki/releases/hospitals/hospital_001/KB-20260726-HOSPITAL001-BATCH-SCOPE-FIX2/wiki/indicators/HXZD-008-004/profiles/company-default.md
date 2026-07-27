---
page_type: caliber_profile
profile_id: HXZD-008-004-company-default
rule_id: HXZD-008-004
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
runtime_manifest: ../../../sql-specs/HXZD-008-004/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-008-004 |
| 扩展指标编码 | HXZD-008-004 |
| 版本 | 公版 |
| 指标名称别名 | 实际手术术者与计划手术术者一致率 |
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
| 时间维度 | 手术结束时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 50% |

## 分子

实际开展手术术者与计划手术术者一致的手术例数

### 统计口径

1、单位时间内完成手术的患者中，手术记录（病历）中手术主刀医师与手术申请单（医嘱）中一致的数量
2、说明：手术患者判断时，只需要统计手术类型=手术、介入治疗，排除诊断性操作、治疗性操作的数据
3、手术记录和手术医嘱有关联关系

## 分母

同期手术总例数

### 统计口径

单位时间内完成手术总例数（手术完成时间取医生站）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-008-004/profiles/HXZD-008-004-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-008-004/profiles/HXZD-008-004-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-008-004/profiles/HXZD-008-004-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-008-004/profiles/HXZD-008-004-company-default/patient_detail.sql`
