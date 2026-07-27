---
page_type: caliber_profile
profile_id: HXZD-009-002-company-default
rule_id: HXZD-009-002
profile_name: 推荐方案：纠纷数据来源：医务管理系统（需上线医务系统投诉纠纷管理功能）
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: death_at
patient_scope:
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-009-002/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：纠纷数据来源：医务管理系统（需上线医务系统投诉纠纷管理功能）

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-009-002 |
| 扩展指标编码 | HXZD-009-002_001 |
| 版本 | 公版 |
| 指标名称别名 | 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_出院患者 |
| 源表主表来源 | EMPLOYEE_INFO, HOLIDAY, INPATIENT_EMR_CONTENT, INPATIENT_EMR_SECTION, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_EMR_SECTION_DATA_ELEMENT, MAHP_DMTS_MAIN, MRAS_MANAGE_COMPLAIN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DEATH |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 纠纷数据来源：医务管理系统（需上线医务系统投诉纠纷管理功能） |
| 关联事件 | CORE_DEATH |
| 事件名称 | 死亡病例 |
| 时间维度 | 死亡时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 医务系统（投诉纠纷管理）、病案管理系统、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 比值 |
| 目标值 | 无 |
| 异常范围初筛 | 0 |

## 分子

医务部门组织进行死亡病例讨论的病例数量

### 统计口径

1、单位时间内死亡且本次在院期间发生投诉纠纷的患者，死亡病例讨论记录的主持人为医务科人员
2、医务科人员定义：员工隶属科室名称为“医务科”或“医务部”

## 分母

同期发生医疗纠纷的死亡病例数量

### 统计口径

1、单位时间内死亡的患者，且本次在院期间发生投诉纠纷的数量
2、患者投诉纠纷数据来源为医务管理系统投诉纠纷管理
3、取病案首页中转归情况=死亡，死亡时间取病案中的出院时间

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-009-002/profiles/HXZD-009-002-company-default/patient_detail.sql`
