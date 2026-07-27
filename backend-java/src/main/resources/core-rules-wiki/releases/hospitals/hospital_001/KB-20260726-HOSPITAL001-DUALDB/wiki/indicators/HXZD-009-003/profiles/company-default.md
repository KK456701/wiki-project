---
page_type: caliber_profile
profile_id: HXZD-009-003-company-default
rule_id: HXZD-009-003
profile_name: 推荐方案：科主任数据来源：死亡讨论记录病历模板限制主持人只能选科主任（需现场调整病历模板）
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
updated_at: 2026-07-26
---

# 推荐方案：科主任数据来源：死亡讨论记录病历模板限制主持人只能选科主任（需现场调整病历模板）

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-009-003 |
| 扩展指标编码 | HXZD-009-003_001 |
| 版本 | 公版 |
| 指标名称别名 | 科主任主持死亡病例讨论率_死亡讨论记录 |
| 源表主表来源 | EMPLOYEE_INFO, HOLIDAY, INPATIENT_EMR_CONTENT, INPATIENT_EMR_SECTION, INPATIENT_EMR_SET, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, INP_EMR_SECTION_DATA_ELEMENT, MAHP_DMTS_MAIN, MRAS_MANAGE_COMPLAIN, ORGANIZATION, ORGANIZATION_X_EMPLOYEE |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_DEATH |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 科主任数据来源：死亡讨论记录病历模板限制主持人只能选科主任（需现场调整病历模板） |
| 关联事件 | CORE_DEATH |
| 事件名称 | 死亡病例 |
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

单位时间内死亡的患者数，完成死亡病例讨论记录数量（需要病历模板中“主持人”下拉限定只能选择科主任）。

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
- 源表 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-009-003/profiles/HXZD-009-003-company-default/patient_detail.sql`
