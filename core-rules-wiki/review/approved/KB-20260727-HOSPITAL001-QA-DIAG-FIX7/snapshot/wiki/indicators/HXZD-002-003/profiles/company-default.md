---
page_type: caliber_profile
profile_id: HXZD-002-003-company-default
rule_id: HXZD-002-003
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
direction: lower_is_better
runtime_manifest: ../../../sql-specs/HXZD-002-003/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-002-003 |
| 扩展指标编码 | HXZD-002-003 |
| 版本 | 公版 |
| 指标名称别名 | 住院患者非计划手术率 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_WARDROUND |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_WARDROUND |
| 事件名称 | 三级查房 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 住院医生站 |
| 指标导向 | 逐步降低 |
| 计量单位 | 百分比 |
| 目标值 | 0.0015 |
| 异常范围初筛 | > 5%或0 |

## 分子

行非计划手术的住院患者人次数

### 统计口径

1、取手术医嘱中“非计划再次手术”标识（一个人多次非计划再次手术，统计为1）
2、说明：手术患者判断时，只需要统计手术类型为手术、介入治疗，排除诊断性操作、治疗性操作的数据。

## 分母

同期住院患者总人次数

### 统计口径

单位时间内出区患者总人次

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-002-003/profiles/HXZD-002-003-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-002-003/profiles/HXZD-002-003-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-002-003/profiles/HXZD-002-003-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-002-003/profiles/HXZD-002-003-company-default/patient_detail.sql`
