---
page_type: caliber_profile
profile_id: HXZD-013-001-company-default
rule_id: HXZD-013-001
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
runtime_manifest: ../../../sql-specs/HXZD-013-001/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-013-001 |
| 扩展指标编码 | HXZD-013-001 |
| 版本 | 公版 |
| 指标名称别名 | 新技术新项目留存转化率 |
| 源表主表来源 | — |
| XXJOB作业 | T+1 |
| 中间表 | — |
| 业务表(不影响数据) | EMPLOYEE_INFO, INPATIENT_ENCOUNTER, INPATIENT_PARTICIPANT, MRAS_ORGANIZATION |
| 业务表(影响数据) | MRAS_MEDTECH_PRO, MRAS_MEDTECH_PROC |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | 无 |
| 事件名称 | — |
| 时间维度 | 新技术审批通过时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 医务系统（新技术新项目管理） |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 90% |
| 异常范围初筛 | < 20% |

## 分子

(A + C + E)<br>A: 第二年新增并在第三和第四年继续开展的技术项目<br>C: 第二年新增并只在第四年继续开展的技术项目<br>E: 第三年新增并在第四年继续开展的技术项目

### 统计口径

1、统计单位时间内开展的新技术新项目属于前两年新增的项目数量
2、“开展”定义：医务系统（新技术新项目统计）中的新技术开展数据。

## 分母

(A + B + C + D + E + F) <br>A: 第二年新增并在第三和第四年继续开展的技术项目种类数。<br>B: 第二年新增并只在第三年继续开展的技术项目种类数。<br>C: 第二年新增并只在第四年继续开展的技术项目种类数。<br>D: 第二年新增并未在第三和第四年继续开展的技术项目种类数。<br>E: 第三年新增并在第四年继续开展的技术项目种类数。<br>F: 第三年新增并未在第四年开展的技术项目种类数。

### 统计口径

1、统计单位时间内，前两年新增的新技术新项目数量
2、“新增”定义：医务系统新增且审批通过的新技术/项目。

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-013-001/profiles/HXZD-013-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-013-001/profiles/HXZD-013-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-013-001/profiles/HXZD-013-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-013-001/profiles/HXZD-013-001-company-default/patient_detail.sql`
