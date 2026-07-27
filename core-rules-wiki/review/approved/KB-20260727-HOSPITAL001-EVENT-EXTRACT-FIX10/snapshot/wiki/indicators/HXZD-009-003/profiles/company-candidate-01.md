---
page_type: caliber_profile
profile_id: HXZD-009-003-company-candidate-01
rule_id: HXZD-009-003
profile_name: 可选方案：科主任数据来源：统一配置行政职务包含“主任”
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
runtime_manifest: ../../../sql-specs/HXZD-009-003/runtime.json
updated_at: 2026-07-27
---

# 可选方案：科主任数据来源：统一配置行政职务包含“主任”

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | — |
| 扩展指标编码 | HXZD-009-003_002 |
| 版本 | 公版 |
| 指标名称别名 | 科主任主持死亡病例讨论率 |
| 源表主表来源 | EMPLOYEE_INFO, HOLIDAY, INPATIENT_EMR_CONTENT, INPATIENT_EMR_SECTION, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_EMR_SECTION_DATA_ELEMENT, MAHP_DMTS_MAIN, MRAS_MANAGE_COMPLAIN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DEATH |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 可选方案 |
| 方案说明 | 科主任数据来源：统一配置行政职务包含“主任” |
| 关联事件 | CORE_DEATH_EXT |
| 事件名称 | 死亡病例可选方案 |
| 时间维度 | 死亡时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 病案管理系统、电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 60% |

## 分子

死亡病例讨论由科主任主持的病例数量

### 统计口径

单位时间内死亡的患者数，死亡病例讨论记录主持人为科主任的数量。（根据医师工号查询其业务单元是否有对应科室的“主任”职务）

## 分母

同期死亡病例总数量

### 统计口径

1、单位时间内死亡的患者数
2、取病案首页中转归情况=死亡，死亡时间取病案中的出院时间

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-candidate-01/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-candidate-01/overview.sql`
- 科室 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-candidate-01/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-candidate-01/patient_detail.sql`
