---
page_type: caliber_profile
profile_id: HXZD-001-001-company-default
rule_id: HXZD-001-001
profile_name: 推荐方案：公版
owner_scope: company
status: published
effective_from: 2025-01-01
effective_to:
time_dimension: admitted_to_ward_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: lower_is_better
sql_spec: ../../../sql-specs/HXZD-001-001/sql-spec.md
updated_at: 2026-07-25
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-001-001 |
| 扩展指标编码 | HXZD-001-001 |
| 版本 | 公版 |
| 指标名称别名 | 患者入院48小时内转科的比例_入区时间 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_FIRSTVISIT |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION, ORGANIZATION |
| 业务表(影响数据) | BUSINESS_UNIT_X_BU_TYPE, INPAT_TRANSFER |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_FDR |
| 事件名称 | 首诊负责 |
| 时间维度 | 入区时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 护士站、住院医生站 |
| 指标导向 | 逐步降低 |
| 计量单位 | 百分比 |
| 目标值 | 无 |
| 异常范围初筛 | > 5%或0 |

## 分子

入院48小时内转科患者人次数

### 统计口径

1、统计患者入区时间在48小时内发生转科的患者人数，按人数统计，一个人48小时多次转科，算1次（不包括患者转入/转出ICU的情况）
2、转科数据：取护士住院转科表转出数据，不包含转区数据。（该表数据为护士操作确认转科/转区后生成的）
3、转科定义：患者流转类型=转科，或流转类型=转区（转入科室≠转出科室），即转科前后科室不相同。

## 分母

同期入院患者总人次数

### 统计口径

单位时间内入区的患者总人次

## 可配置参数

无。

## 执行引用

- 源表 SQL：`etl_source`
- 概览 SQL：`overview`
- 科室 SQL：`department`
- 患者明细 SQL：`patient_detail`
