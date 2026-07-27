---
page_type: caliber_profile
profile_id: HXZD-016-002-company-default
rule_id: HXZD-016-002
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
runtime_manifest: ../../../sql-specs/HXZD-016-002/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-016-002 |
| 扩展指标编码 | HXZD-016-002 |
| 版本 | 公版 |
| 指标名称别名 | 术中自体血回输率 |
| 源表主表来源 | — |
| XXJOB作业 | T+1 |
| 中间表 | — |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | MRAS_INDEX_SURGREC, MRAS_PATIENT_EVENT |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_BLOOD_SURG |
| 事件名称 | 术中自体血回输 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 手麻管理系统（数据平台） |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.4 |
| 异常范围初筛 | < 10% |

## 分子

术中使用自体血回输的患者数量

### 统计口径

1、单位时间内完成手术，且术中使用自体血回输的患者数
2、自体血数据来源为手麻记录中的“自体血”标识，走数据平台抽取。

## 分母

同期术中进行输血患者总数量

### 统计口径

1、单位时间内完成手术，且术中进行过输血的患者数
2、术中输血数据来源为手麻记录单中的输血信息，走数据平台抽取。

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-016-002/profiles/HXZD-016-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-016-002/profiles/HXZD-016-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-016-002/profiles/HXZD-016-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-016-002/profiles/HXZD-016-002-company-default/patient_detail.sql`
