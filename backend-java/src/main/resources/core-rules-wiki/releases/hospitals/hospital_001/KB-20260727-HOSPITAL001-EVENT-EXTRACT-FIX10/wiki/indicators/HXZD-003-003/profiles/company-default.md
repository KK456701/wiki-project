---
page_type: caliber_profile
profile_id: HXZD-003-003-company-default
rule_id: HXZD-003-003
profile_name: 推荐方案：按会诊申请科室数统计
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
runtime_manifest: ../../../sql-specs/HXZD-003-003/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：按会诊申请科室数统计

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-003-003 |
| 扩展指标编码 | HXZD-003-003_001 |
| 版本 | 公版 |
| 指标名称别名 | 普通会诊及时完成率 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_CONSULTATION |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION, VALUE_SET |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 按会诊申请科室数统计 |
| 关联事件 | CORE_CONSUL |
| 事件名称 | 会诊 |
| 时间维度 | 会诊完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 会诊管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

普通会诊24小时内完成次数

### 统计口径

1、单位时间完成的普通会诊中，申请科室会诊完成时间-会诊申请时间≤24h
2、会诊完成定义：会诊医师完成会诊答复
3、说明：该指标包含门急诊住院的数据，不包含院外会诊

## 分母

同期普通会诊总次数

### 统计口径

申请科室单位时间内完成普通会诊的数量

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-003-003/profiles/HXZD-003-003-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-003-003/profiles/HXZD-003-003-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-003-003/profiles/HXZD-003-003-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-003-003/profiles/HXZD-003-003-company-default/patient_detail.sql`
