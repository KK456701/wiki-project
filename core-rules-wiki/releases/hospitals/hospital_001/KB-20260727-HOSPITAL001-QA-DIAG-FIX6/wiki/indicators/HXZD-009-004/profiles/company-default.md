---
page_type: caliber_profile
profile_id: HXZD-009-004-company-default
rule_id: HXZD-009-004
profile_name: 推荐方案：未实现
owner_scope: company
status: draft
execution_status: draft
effective_from: 2025-01-01
effective_to:
time_dimension: admitted_to_ward_at
patient_scope:
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-009-004/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：未实现

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-009-004 |
| 扩展指标编码 | HXZD-009-004 |
| 版本 | 公版 |
| 指标名称别名 | 死亡患者病案上传率 |
| 源表主表来源 | EMPLOYEE_INFO, HOLIDAY, INPATIENT_EMR_CONTENT, INPATIENT_EMR_SECTION, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_EMR_SECTION_DATA_ELEMENT, MAHP_DMTS_MAIN, MRAS_MANAGE_COMPLAIN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DEATH |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 未实现 |
| 关联事件 | CORE_DEATH |
| 事件名称 | 死亡病例 |
| 时间维度 | — |
| 患者范围 | 出院患者 |
| 数据来源 | — |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 100% |
| 异常范围初筛 | — |

## 分子

按要求完整上传本机构死亡患者病案的数量

### 统计口径

—

## 分母

同期应上传死亡患者病案总数量

### 统计口径

—

## 可配置参数

无。

## 执行引用

- 当前执行状态：`draft`
- 阻断原因：方案标记为未实现
- 源表 SQL：`sql-specs/HXZD-009-004/profiles/HXZD-009-004-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-009-004/profiles/HXZD-009-004-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-009-004/profiles/HXZD-009-004-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-009-004/profiles/HXZD-009-004-company-default/patient_detail.sql`
