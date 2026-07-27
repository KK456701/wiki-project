---
page_type: caliber_profile
profile_id: HXZD-003-004-company-default
rule_id: HXZD-003-004
profile_name: 推荐方案：公版
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: admitted_to_ward_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-003-004/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-003-004 |
| 扩展指标编码 | HXZD-003-004 |
| 版本 | 公版 |
| 指标名称别名 | 普通会诊有效率 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_CONSULTATION |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION, VALUE_SET |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_CONSUL |
| 事件名称 | 会诊 |
| 时间维度 | 会诊完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、会诊管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

普通会诊结束后开具相关医嘱的次数

### 统计口径

1、普通会诊后，由任意会诊医师开立的首条有效医嘱在会诊完成时间后24小时内的患者例次数。
2、说明：目前无法判定医嘱是否为会诊相关医嘱，先按照24小时内有医嘱即表示合格，并在明细中显示医嘱名称）
3、说明：该指标包含门急诊住院的数据，不包含院外会诊

## 分母

同期普通会诊患者总次数

### 统计口径

单位时间内完成普通会诊的次数（多次会诊统计为多次）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-003-004/profiles/HXZD-003-004-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-003-004/profiles/HXZD-003-004-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-003-004/profiles/HXZD-003-004-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-003-004/profiles/HXZD-003-004-company-default/patient_detail.sql`
