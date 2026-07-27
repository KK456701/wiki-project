---
page_type: caliber_profile
profile_id: HXZD-012-003-company-default
rule_id: HXZD-012-003
profile_name: 推荐方案：手术数据来源：医嘱
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
runtime_manifest: ../../../sql-specs/HXZD-012-003/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：手术数据来源：医嘱

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-012-003 |
| 扩展指标编码 | HXZD-012-003_001 |
| 版本 | 公版 |
| 指标名称别名 | 四级手术术前多学科讨论完成率 |
| 源表主表来源 | CLIBASIC_SURGERY, INPATIENT_EMR_SET, INP_SURGICAL_ANESTHESIA_PLAN, INP_SURGICAL_PLAN, MAHP_DMTS_MAIN, MRAS_PATIENT_EVENT, VALUE_SET |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_SUR_GRADE |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 手术数据来源：医嘱 |
| 关联事件 | CORE_SUR_GRADE |
| 事件名称 | 手术分级管理制度 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 电子病历系统、住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 95% |
| 异常范围初筛 | < 60% |

## 分子

术前完成多学科讨论的四级手术例数

### 统计口径

1、单位时间内完成的四级手术，前48h内完成多学科讨论的例数（多学科讨论数据取病历的术前讨论记录中，名称包含“多学科”的数量）
2、手术相关数据来源为【医生站】
3、说明：手术患者判断时，只需要统计手术类型=手术、介入治疗，排除诊断性操作、治疗性操作的数据
4、一个人多次手术算多次

## 分母

同期四级手术总例数

### 统计口径

单位时间内，患者完成四级手术总例数（一个患者多次四级手术算多次）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-012-003/profiles/HXZD-012-003-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-012-003/profiles/HXZD-012-003-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-012-003/profiles/HXZD-012-003-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-012-003/profiles/HXZD-012-003-company-default/patient_detail.sql`
