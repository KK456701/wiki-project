---
page_type: caliber_profile
profile_id: HXZD-002-001-company-default
rule_id: HXZD-002-001
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
runtime_manifest: ../../../sql-specs/HXZD-002-001/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-002-001 |
| 扩展指标编码 | HXZD-002-001 |
| 版本 | 公版 |
| 指标名称别名 | 患者入院8小时内查房率 |
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
| 时间维度 | 入区时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

入院8小时内开具检查或治疗医嘱的患者人次数

### 统计口径

1、单位时间入区的患者，在入院8小时内开立过检查或治疗相关医嘱的患者数（一个患者8小时内开立多次相关医嘱，统计为1）
2、说明：检查或治疗相关的医嘱类型：西药、中草药、检验、检查、治疗、手术、用血、病理、膳食医嘱。不包括：患者流转、嘱托、卫材医嘱等。（取值的类型要求可配置）（概念域：医嘱类型代码）
3、医嘱都按照有效医嘱取值（不包含已作废和已失效的医嘱状态）

## 分母

同期入院患者总人次数

### 统计口径

单位时间内入区的患者人次

## 可配置参数

| 原始 Excel 行 | 配置类型 | 配置值 |
|---:|---|---|
| 5 | 下拉多选 | 医嘱类型概念域（256192） |

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-002-001/profiles/HXZD-002-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-002-001/profiles/HXZD-002-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-002-001/profiles/HXZD-002-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-002-001/profiles/HXZD-002-001-company-default/patient_detail.sql`
