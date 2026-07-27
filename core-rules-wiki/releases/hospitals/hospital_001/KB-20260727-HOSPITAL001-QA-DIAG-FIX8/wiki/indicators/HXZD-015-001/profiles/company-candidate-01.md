---
page_type: caliber_profile
profile_id: HXZD-015-001-company-candidate-01
rule_id: HXZD-015-001
profile_name: 可选方案：特殊级抗菌药物走会诊管理系统
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
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-015-001/runtime.json
updated_at: 2026-07-27
---

# 可选方案：特殊级抗菌药物走会诊管理系统

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | — |
| 扩展指标编码 | HXZD-015-001_002 |
| 版本 | 公版 |
| 指标名称别名 | 特殊使用级抗菌药物使用会诊率_会诊管理 |
| 源表主表来源 | EMPLOYEE_INFO, INPATIENT_CONSULT_APPLY, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_CLI_ORDER, INP_CLI_ORDER_ITEM, INP_CLI_ORDER_ITEM_EXT, MEDICINE, MEDICINE_DETAIL, ORGANIZATION |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_ANTI |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 可选方案 |
| 方案说明 | 特殊级抗菌药物走会诊管理系统 |
| 关联事件 | CORE_SPECIAL_ANTI_EXT |
| 事件名称 | 特殊级抗菌药物(会诊管理) |
| 时间维度 | 医嘱开立时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 会诊管理系统、住院医生站 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 95% |
| 异常范围初筛 | < 90% |

## 分子

特殊使用级抗菌药物使用医嘱与会诊记录相对应的医嘱数量

### 统计口径

1、单位时间内开立的特殊级抗菌药物，跟特殊级抗菌药物会诊的药品一致的数量
2、数据来源为会诊管理系统，通过会诊和医嘱关联关系判断一致性。

## 分母

同期特殊使用级抗菌药物使用医嘱总数量

### 统计口径

单位时间内特殊级抗菌药物医嘱总次数

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-015-001/profiles/HXZD-015-001-company-candidate-01/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-015-001/profiles/HXZD-015-001-company-candidate-01/overview.sql`
- 科室 SQL：`sql-specs/HXZD-015-001/profiles/HXZD-015-001-company-candidate-01/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-015-001/profiles/HXZD-015-001-company-candidate-01/patient_detail.sql`
