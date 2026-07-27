---
page_type: caliber_profile
profile_id: HXZD-014-002-company-default
rule_id: HXZD-014-002
profile_name: 推荐方案：公版
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: report_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-014-002/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-014-002 |
| 扩展指标编码 | HXZD-014-002 |
| 版本 | 公版 |
| 指标名称别名 | 住院患者危急值当日及时处置率 |
| 源表主表来源 | CRITICAL_VALUE_REPORT, CV_REPORT_ACTION_LOG, EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, ORGANIZATION |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_CRITICAL_RPT |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPAT_TRANSFER, MRAS_ORGANIZATION, ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_CV_RPT |
| 事件名称 | 危急值报告 |
| 时间维度 | 危急值接收时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 电子病历系统、住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 1 |
| 异常范围初筛 | < 80% |

## 分子

当日处置的住院患者危急值项目数

### 统计口径

1、单位时间内接收的危急值，其危急值记录为当日完成的数量，排除已召回的危急值。
2、“当日完成”定义：危急值接收后24小时内完成危急值记录

## 分母

同期临床科室接获住院患者危急值项目数

### 统计口径

1、单位时间内，危急值接收数量
2、医生或护士任意一人接收即可（取第一个接收的时间）
3、当前指标只统计住院危急值，排除已召回的危急值。

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-014-002/profiles/HXZD-014-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-014-002/profiles/HXZD-014-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-014-002/profiles/HXZD-014-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-014-002/profiles/HXZD-014-002-company-default/patient_detail.sql`
