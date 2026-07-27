---
page_type: caliber_profile
profile_id: HXZD-011-001-company-default
rule_id: HXZD-011-001
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
direction: lower_is_better
runtime_manifest: ../../../sql-specs/HXZD-011-001/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-011-001 |
| 扩展指标编码 | HXZD-011-001 |
| 版本 | 公版 |
| 指标名称别名 | 手术医师手术时间重合率 |
| 源表主表来源 | CLIBASIC_SURGERY, EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_SURGICAL_ANESTHESIA_PLAN, INP_SURGICAL_OPERATOR_PLAN, INP_SURGICAL_ORDER_ITEM, INP_SURGICAL_PLAN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_SURGERY |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, INP_SURGICAL_PLAN, MRAS_ORGANIZATION |
| 业务表(影响数据) | INP_SURGICAL_ANESTHESIA_PLAN |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_SURGERY |
| 事件名称 | 手术安全 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站 |
| 指标导向 | 逐步降低 |
| 计量单位 | 百分比 |
| 目标值 | 0 |
| 异常范围初筛 | > 5% |

## 分子

同一时间内手术医师为同一人的手术例数

### 统计口径

1、单位时间内，同一个主刀医师，手术时间重合的手术数量。
2、手术重合举例：A手术开始时间和结束时间区间跟任意B手术开始时间和结束时间的区间有重合。即A手术和B手术都属于重合，统计为2。
3、说明：手术患者判断时，只需要统计手术类型为手术、介入治疗，排除诊断性操作、治疗性操作的数据

## 分母

同期住院患者手术总例数

### 统计口径

单位时间内完成手术总例数

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-011-001/profiles/HXZD-011-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-011-001/profiles/HXZD-011-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-011-001/profiles/HXZD-011-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-011-001/profiles/HXZD-011-001-company-default/patient_detail.sql`
