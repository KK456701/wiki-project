---
page_type: caliber_profile
profile_id: HXZD-003-002-company-default
rule_id: HXZD-003-002
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
runtime_manifest: ../../../sql-specs/HXZD-003-002/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-003-002 |
| 扩展指标编码 | HXZD-003-002 |
| 版本 | 公版 |
| 指标名称别名 | 急会诊有效率 |
| 源表主表来源 | OUTPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_CONSULTATION |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION, VALUE_SET |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_CONSUL_OUT |
| 事件名称 | 门急诊会诊 |
| 时间维度 | 会诊完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、会诊管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

急会诊后开具相关医嘱的次数

### 统计口径

1、急会诊后，由任意会诊医师开立的首条有效医嘱在会诊完成时间后40分钟内的患者例次数。
2、说明：目前无法判定医嘱是否为会诊相关医嘱，按会诊后任意会诊医师开立的首条医嘱取值（在统计明细中显示医嘱名称）
3、说明：该指标包含门急诊住院的数据，不包含院外会诊

## 分母

同期急会诊总次数

### 统计口径

单位时间内完成急会诊的次数

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-003-002/profiles/HXZD-003-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-003-002/profiles/HXZD-003-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-003-002/profiles/HXZD-003-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-003-002/profiles/HXZD-003-002-company-default/patient_detail.sql`
