---
page_type: caliber_profile
profile_id: HXZD-007-001-company-default
rule_id: HXZD-007-001
profile_name: 推荐方案：公版
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
runtime_manifest: ../../../sql-specs/HXZD-007-001/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-007-001 |
| 扩展指标编码 | HXZD-007-001 |
| 版本 | 公版 |
| 指标名称别名 | 急危重症患者抢救成功率 |
| 源表主表来源 | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, MAHP_MAIN, MAHP_TREATMENT, ORGANIZATION |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_PATRESCUE |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_RESCUE |
| 事件名称 | 急危重患者抢救记录 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 病案管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 95% |
| 异常范围初筛 | < 70% |

## 分子

急危重症患者抢救成功的例次数

### 统计口径

单位时间内抢救成功的患者人次（取病案中的抢救成功次数）

## 分母

同期急危重症患者抢救的总例次数

### 统计口径

单位时间内出区且发生抢救的患者次数（取病案中的抢救次数，一个患者多次抢救，算多次）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-007-001/profiles/HXZD-007-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-007-001/profiles/HXZD-007-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-007-001/profiles/HXZD-007-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-007-001/profiles/HXZD-007-001-company-default/patient_detail.sql`
