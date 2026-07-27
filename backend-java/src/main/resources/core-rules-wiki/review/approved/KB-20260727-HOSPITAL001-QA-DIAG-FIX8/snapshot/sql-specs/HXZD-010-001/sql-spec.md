---
page_type: sql_spec
rule_id: HXZD-010-001
database_type: sqlserver
status: published
updated_at: 2026-07-27
---

# SQL 规格：长期医嘱当日终止率

## etl_source（源表/事件抽取）

- 用途：T+1 数据抽取和中间表加工
- Agent可执行：否
- 执行方：定时任务（XXJOB）
- 中间表：—
- 源表主表：MAHP_DMTS_MAIN,INPATIENT_ENCOUNTER,ORGANIZATION_X_EMPLOYEE,ORGANIZATION,INPATIENT_PARTICIPANT,EMPLOYEE_INFO,INP_EMR_SECTION_DATA_ELEMENT,INPATIENT_EMR_SECTION,INPATIENT_EMR_CONTENT,INPATIENT_EMR_SET

## overview（概览）

- 用途：计算全院指标结果
- Agent可执行：是
- 必填参数：begin_at, end_at
- 可选参数：department_ids
- 输出字段：numerator, denominator, rate, target, status

## department（科室统计）

- 用途：科室下钻
- Agent可执行：按需
- 必填参数：begin_at, end_at
- 可选参数：dept_id_in, qualified

## patient_detail（患者明细）

- 用途：患者明细核对
- Agent可执行：需要显式请求和权限
- 必填参数：begin_at, end_at
- 可选参数：dept_id_in, hospital_area_list, status
