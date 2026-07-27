---
page_type: caliber_profile
profile_id: HXZD-010-001-company-default
rule_id: HXZD-010-001
profile_name: 推荐方案：公版
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: order_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: lower_is_better
runtime_manifest: ../../../sql-specs/HXZD-010-001/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-010-001 |
| 扩展指标编码 | HXZD-010-001 |
| 版本 | 公版 |
| 指标名称别名 | 长期医嘱当日终止率 |
| 源表主表来源 | MAHP_DMTS_MAIN,INPATIENT_ENCOUNTER,ORGANIZATION_X_EMPLOYEE,ORGANIZATION,INPATIENT_PARTICIPANT,EMPLOYEE_INFO,INP_EMR_SECTION_DATA_ELEMENT,INPATIENT_EMR_SECTION,INPATIENT_EMR_CONTENT,INPATIENT_EMR_SET |
| XXJOB作业 | T+1 |
| 中间表 | — |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, MRAS_ORGANIZATION, ORGANIZATION |
| 业务表(影响数据) | INPATIENT_ENCOUNTER, INP_CLI_ORDER |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | 无 |
| 事件名称 | 无 |
| 时间维度 | 医嘱开立时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站 |
| 指标导向 | 逐步降低 |
| 计量单位 | 百分比 |
| 目标值 | 无 |
| 异常范围初筛 | > 50%或0 |

## 分子

开具长期医嘱后当日终止执行的医嘱数量

### 统计口径

1、单位时间内开立的长期医嘱中，开立日期=停止医嘱日期的数量（只判断日期）（排除当日停止医嘱后，24小时内开立了转科、出院、死亡医嘱的医嘱）
2、“当日”定义：24小时内停止的医嘱，都算作当日停止
3、不包含24小时出入院患者的医嘱，以及已失效和已作废的医嘱；

## 分母

同期开具长期医嘱总数量

### 统计口径

单位时间内医生站开立长期医嘱的数量

## 可配置参数

| 原始 Excel 行 | 配置类型 | 配置值 |
|---:|---|---|
| 57 | 下拉单选 | 当天停止医嘱定义：24小时内停止的医嘱；<br>当天停止医嘱定义：当天凌晨24点前停止的医嘱 |

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-010-001/profiles/HXZD-010-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-010-001/profiles/HXZD-010-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-010-001/profiles/HXZD-010-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-010-001/profiles/HXZD-010-001-company-default/patient_detail.sql`
