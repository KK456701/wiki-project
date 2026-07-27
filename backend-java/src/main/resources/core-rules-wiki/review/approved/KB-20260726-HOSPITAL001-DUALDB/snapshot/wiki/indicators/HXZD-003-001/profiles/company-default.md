---
page_type: caliber_profile
profile_id: HXZD-003-001-company-default
rule_id: HXZD-003-001
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
runtime_manifest: ../../../sql-specs/HXZD-003-001/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-003-001 |
| 扩展指标编码 | HXZD-003-001 |
| 版本 | 公版 |
| 指标名称别名 | 急会诊及时到位率 |
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
| 数据来源 | 会诊管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

急会诊记录中10分钟内到位的急会诊次数

### 统计口径

1、单位时间内完成的急会诊，在10分钟到位的数量（会诊到位时间-会诊发起时间）。
2、会诊到位时间：优先取会诊签到时间（需要开启会诊签到功能（同一个会诊有多个到位时间，取第一个签到的时间），若无签到时间，则取“会诊计划到场时间”。
3、说明：该指标包含门急诊住院的数据，不包含院外会诊

## 分母

同期急会诊总次数

### 统计口径

单位时间内完成急会诊的数量

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-003-001/profiles/HXZD-003-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-003-001/profiles/HXZD-003-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-003-001/profiles/HXZD-003-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-003-001/profiles/HXZD-003-001-company-default/patient_detail.sql`
