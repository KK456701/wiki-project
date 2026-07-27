---
page_type: caliber_profile
profile_id: HXZD-012-001-company-candidate-01
rule_id: HXZD-012-001
profile_name: 可选方案：手术数据来源：病案首页
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
runtime_manifest: ../../../sql-specs/HXZD-012-001/runtime.json
updated_at: 2026-07-26
---

# 可选方案：手术数据来源：病案首页

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | — |
| 扩展指标编码 | HXZD-012-001_002 |
| 版本 | 公版 |
| 指标名称别名 | 四级手术与三级手术并发症发生率比 |
| 源表主表来源 | CLIBASIC_SURGERY, INPATIENT_EMR_SET, MAHP_DMTS_ICD_OPERATION, MAHP_DMTS_MAIN, MRAS_PATIENT_EVENT, VALUE_SET |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_SUR_GRADE |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION, MRAS_PATIENT_EVENT |
| 业务表(影响数据) | MRAS_PATIENT_EVENT |
| 是否影响数据 | 是 |
| 方案类型 | 可选方案 |
| 方案说明 | 手术数据来源：病案首页 |
| 关联事件 | CORE_SUR_GRADE_V2 |
| 事件名称 | 手术分级管理制度2 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 病案管理系统、住院医生站 |
| 指标导向 | -- |
| 计量单位 | 比值 |
| 目标值 | 无 |
| 异常范围初筛 | 0 |

## 分子

四级手术并发症发生率

### 统计口径

单位时间内出区的患者中，四级手术并发症患者数/四级手术患者数
2、四级手术相关数据来源为【病案首页】（一个患者多次四级手术，统一为1）
3、一个患者如果在院区间做了三级和四级手术，且发生了并发症，按高等级手术归属
4、并发症取值逻辑：病案首页中患者“出院诊断”包含并发症诊断编码（例如：肺栓塞为I26.9），且该诊断的“入院病情=无”
5、并发症定义参照sheet页<并发症定义>）

## 分母

三级手术并发症发生率

### 统计口径

1、单位时间内出区的患者中，三级手术并发症患者数/三级手术患者数
2、四级手术相关数据来源为【病案首页】（一个患者多次四级手术，统一为1）
3、一个患者如果在院区间做了三级和四级手术，且发生了并发症，按高等级手术归属
4、并发症取值逻辑：病案首页中患者“出院诊断”包含并发症诊断编码（例如：肺栓塞为I26.9），且该诊断的“入院病情=无”
5、并发症定义参照sheet页<并发症定义>）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-012-001/profiles/HXZD-012-001-company-candidate-01/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-012-001/profiles/HXZD-012-001-company-candidate-01/overview.sql`
- 科室 SQL：`sql-specs/HXZD-012-001/profiles/HXZD-012-001-company-candidate-01/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-012-001/profiles/HXZD-012-001-company-candidate-01/patient_detail.sql`
