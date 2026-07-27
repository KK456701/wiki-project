---
page_type: caliber_profile
profile_id: HXZD-012-004-company-default
rule_id: HXZD-012-004
profile_name: 推荐方案：手术数据来源：医嘱
owner_scope: company
status: approved
execution_status: documentation_only
effective_from: 2025-01-01
effective_to:
time_dimension: surgery_at
patient_scope:
  - inpatient_current
  - inpatient_discharged
dedup_key: encounter_id
direction: higher_is_better
runtime_manifest: ../../../sql-specs/HXZD-012-004/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：手术数据来源：医嘱

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-012-004 |
| 扩展指标编码 | HXZD-012-004_001 |
| 版本 | 公版 |
| 指标名称别名 | 三、四级手术实际开展率_手术完成时间 |
| 源表主表来源 | CLIBASIC_SURGERY, INPATIENT_EMR_SET, INP_SURGICAL_ANESTHESIA_PLAN, INP_SURGICAL_PLAN, MAHP_DMTS_MAIN, MRAS_PATIENT_EVENT, VALUE_SET |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_SUR_GRADE |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | CLIBASIC_SURGERY |
| 是否影响数据 | 是 |
| 方案类型 | 推荐方案 |
| 方案说明 | 手术数据来源：医嘱 |
| 关联事件 | CORE_SUR_GRADE |
| 事件名称 | 手术分级管理制度 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、手术临床服务 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 1 |
| 异常范围初筛 | < 30% |

## 分子

实际开展的三、四级手术术种数

### 统计口径

1、单位时间内完成的三四级手术术种数（相同手术去重）
2、三、四级手术相关数据来源为【医生站】
3、手术结束时间由手麻回写给医生站。
4、说明：只统计手术类型为手术、介入治疗的数据

## 分母

同期备案的三、四级手术术种数

### 统计口径

1、三、四级手术临床服务总数量
2、取统一配置维护的手术临床服务中，启用状态的三、四级手术总数
3、说明：只统计手术类型为手术、介入治疗的数据

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-012-004/profiles/HXZD-012-004-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-012-004/profiles/HXZD-012-004-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-012-004/profiles/HXZD-012-004-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-012-004/profiles/HXZD-012-004-company-default/patient_detail.sql`
