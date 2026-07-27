---
page_type: caliber_profile
profile_id: HXZD-016-001-company-default
rule_id: HXZD-016-001
profile_name: 推荐方案：公版
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: order_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-016-001/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-016-001 |
| 扩展指标编码 | HXZD-016-001 |
| 版本 | 公版 |
| 指标名称别名 | 临床用血后评估记录率 |
| 源表主表来源 | EMPLOYEE_INFO, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_CLI_ORDER, INP_CLI_ORDER_ITEM, ORGANIZATION |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_BLOOD_AUDIT |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, INPAT_TRANSFER, MRAS_ORGANIZATION, ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_BLOOD_RECORD |
| 事件名称 | 临床用血后评估记录 |
| 时间维度 | 医嘱开立时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 电子病历系统、住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 80% |

## 分子

输血治疗后规范书写评估输血记录例次数

### 统计口径

1、单位时间内用血的患者中，完成用血记录的数量
2、取病历文书中监控类型=输血病程记录的数据

## 分母

同期临床输血治疗病例总例次数

### 统计口径

1、单位时间内用血医嘱总数
2、说明：备血和用血都是属于用血医嘱，需要通过关联关系去重
3、不包含已失效和已作废的医嘱

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-016-001/profiles/HXZD-016-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-016-001/profiles/HXZD-016-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-016-001/profiles/HXZD-016-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-016-001/profiles/HXZD-016-001-company-default/patient_detail.sql`
