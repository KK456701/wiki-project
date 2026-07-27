---
page_type: caliber_profile
profile_id: HXZD-006-003-company-default
rule_id: HXZD-006-003
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
runtime_manifest: ../../../sql-specs/HXZD-006-003/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-006-003 |
| 扩展指标编码 | HXZD-006-003_001 |
| 版本 | 公版 |
| 指标名称别名 | 高额异常费用患者进行疑难病例讨论的占比_出区时间 |
| 源表主表来源 | EMPLOYEE_INFO, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, MAHP_COST, MAHP_MAIN, ORGANIZATION |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DIFFI_EMR_SECOND |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_DIFFI_EMR_SECOND |
| 事件名称 | 疑难病例2 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 病案管理系统、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 95% |
| 异常范围初筛 | < 70% |

## 分子

对产生高额异常费用患者进行疑难病例讨论的数量

### 统计口径

1、单位时间出区患者中，属于高额费用患者，且完成了疑难病历讨论记录的患者数
2、由于各地区对高额异常费用定义会有不同，这个数值可配置
3、一个患者单次住院最多统计为1

## 分母

同期高额异常费用患者数量

### 统计口径

单位时间出区患者中，住院总费用>=20万的患者数（费用取病案）

## 可配置参数

| 原始 Excel 行 | 配置类型 | 配置值 |
|---:|---|---|
| 30 | 数值框 | 200000 |

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-006-003/profiles/HXZD-006-003-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-006-003/profiles/HXZD-006-003-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-006-003/profiles/HXZD-006-003-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-006-003/profiles/HXZD-006-003-company-default/patient_detail.sql`
