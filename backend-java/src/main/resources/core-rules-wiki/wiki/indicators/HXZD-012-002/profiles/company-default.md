---
page_type: caliber_profile
profile_id: HXZD-012-002-company-default
rule_id: HXZD-012-002
profile_name: 推荐方案：手术数据来源：医嘱
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: surgery_at
patient_scope:
  - inpatient_discharged
dedup_key: encounter_id
direction: lower_is_better
runtime_manifest: ../../../sql-specs/HXZD-012-002/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：手术数据来源：医嘱

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-012-002 |
| 扩展指标编码 | HXZD-012-002_001 |
| 版本 | 公版 |
| 指标名称别名 | 四级手术与三级手术患者死亡率比 |
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
| 患者范围 | 出院患者 |
| 数据来源 | 病案管理系统、住院医生站 |
| 指标导向 | -- |
| 计量单位 | 比值 |
| 目标值 | 无 |
| 异常范围初筛 | 0 |

## 分子

四级手术患者死亡率

### 统计口径

1、单位时间内完成四级手术的患者且死亡的人数/单位时间完成的四级手术例数
2、四级手术相关数据来源为【医生站】，统计单位时间内完成四级手术的患者人数（一个患者多次四级手术，统一为1）。
3、说明：一个患者在院期间做了三级手术和四级手术，若患者死亡，则按高等级手术归属。
4、死亡患者取病案首页中转归情况=死亡

## 分母

三级手术患者死亡率

### 统计口径

1、单位时间内完成三级手术的患者且死亡的人数/单位时间完成的三级手术例数
2、三级手术相关数据来源为【医生站】，统计单位时间内完成三级手术的患者人数（一个患者多次四级手术，统一为1）。
3、说明：一个患者在院期间做了三级手术和四级手术，若患者死亡，则按高等级手术归属。
4、死亡患者取病案首页中转归情况=死亡

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-012-002/profiles/HXZD-012-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-012-002/profiles/HXZD-012-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-012-002/profiles/HXZD-012-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-012-002/profiles/HXZD-012-002-company-default/patient_detail.sql`
