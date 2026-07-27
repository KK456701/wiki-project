---
page_type: caliber_profile
profile_id: HXZD-002-002-company-default
rule_id: HXZD-002-002
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
runtime_manifest: ../../../sql-specs/HXZD-002-002/runtime.json
updated_at: 2026-07-26
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-002-002 |
| 扩展指标编码 | HXZD-002-002 |
| 版本 | 公版 |
| 指标名称别名 | 上级医师查房记录规范率 |
| 源表主表来源 | 住院医生站主表： INPATIENT_ENCOUNTER |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_WARDROUND |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_WARDROUND |
| 事件名称 | 三级查房 |
| 时间维度 | 出区时间 |
| 患者范围 | 出院患者 |
| 数据来源 | 电子病历系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 0.95 |
| 异常范围初筛 | < 70% |

## 分子

住院患者病历中上级医师查房记录规范、完整的病例数量

### 统计口径

1、单位时间内出区患者中，按时完成了所有查房记录的患者数（一个患者一次住院最多统计为1）
1.1）病历监控类型（支持配置）：上级医师首次查房记录、主治医师首次查房记录、主任医师首次查房记录、副主任医师首次查房记录、上级医师查房记录、主治医师查房记录、主任医师查房记录、副主任医师查房记录。
1.2）监测病历状态：已提交
2、病历时限数据取EMR的病历时限完成状态=按时完成

## 分母

同期住院患者病例总数量

### 统计口径

单位时间内出区的患者总数

## 可配置参数

| 原始 Excel 行 | 配置类型 | 配置值 |
|---:|---|---|
| 7 | 下拉多选 | 病历文书监控类型 |

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-002-002/profiles/HXZD-002-002-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-002-002/profiles/HXZD-002-002-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-002-002/profiles/HXZD-002-002-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-002-002/profiles/HXZD-002-002-company-default/patient_detail.sql`
