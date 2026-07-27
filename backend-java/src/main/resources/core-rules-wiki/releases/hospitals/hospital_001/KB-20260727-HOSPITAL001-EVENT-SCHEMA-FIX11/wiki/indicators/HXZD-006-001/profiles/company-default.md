---
page_type: caliber_profile
profile_id: HXZD-006-001-company-default
rule_id: HXZD-006-001
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
runtime_manifest: ../../../sql-specs/HXZD-006-001/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-006-001 |
| 扩展指标编码 | HXZD-006-001 |
| 版本 | 公版 |
| 指标名称别名 | 非计划再次住院/手术患者疑难病例讨论完成率 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DIFFI_EMR |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_DIFFI_EMR |
| 事件名称 | 疑难病例 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 住院医生站、病案管理系统、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 95% |
| 异常范围初筛 | < 70% |

## 分子

对非计划再次住院/手术患者进行疑难病例讨论的数量

### 统计口径

1、单位时间内出区的非计划再次手术或非计划再住院患者中，有疑难病历讨论记录的患者数
2、非计划再次入院定义：患者31天内再次住院，并且上次住院的病案首页“31天内再住院计划=无”，且患者本次入院主诊断和上次出院主诊断的诊断编码前5个字符相同（（例如诊断编码为Z51.003，则前5个字符为Z51.0））。
4、非计划再次手术定义：取手术医嘱“非计划二次手术”标记
5、说明：手术患者判断时，只需要统计手术类型为手术、介入治疗，排除诊断性操作、治疗性操作的数据

## 分母

同期非计划再次住院/手术的数量

### 统计口径

单位时间内出区的患者，住院期间发生非计划再次手术或属于非计划再住院的患者数（一个患者最多统一为1）

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-006-001/profiles/HXZD-006-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-006-001/profiles/HXZD-006-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-006-001/profiles/HXZD-006-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-006-001/profiles/HXZD-006-001-company-default/patient_detail.sql`
