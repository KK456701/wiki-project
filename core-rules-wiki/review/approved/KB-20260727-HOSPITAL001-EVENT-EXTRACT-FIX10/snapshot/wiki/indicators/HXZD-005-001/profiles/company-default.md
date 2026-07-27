---
page_type: caliber_profile
profile_id: HXZD-005-001-company-default
rule_id: HXZD-005-001
profile_name: 推荐方案：公版
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
runtime_manifest: ../../../sql-specs/HXZD-005-001/runtime.json
updated_at: 2026-07-27
---

# 推荐方案：公版

## 元数据

| 字段 | 值 |
|---|---|
| 指标编码 | HXZD-005-001 |
| 扩展指标编码 | HXZD-005-001 |
| 版本 | 公版 |
| 指标名称别名 | 四级手术患者手术当日床旁交接班占比 |
| 源表主表来源 | — |
| XXJOB作业 | T+1 |
| 中间表 | MRAS_BUSINESS_SHIFTHANDOVER |
| 业务表(不影响数据) | INPATIENT_ENCOUNTER, MRAS_ORGANIZATION |
| 业务表(影响数据) | — |
| 是否影响数据 | 否 |
| 方案类型 | 推荐方案 |
| 方案说明 | 公版 |
| 关联事件 | CORE_SHIFTHANDOVER |
| 事件名称 | 值班交接班 |
| 时间维度 | 手术完成时间 |
| 患者范围 | 在院+出院患者 |
| 数据来源 | 住院医生站、交接班管理系统 |
| 指标导向 | 逐步提高 |
| 计量单位 | 百分比 |
| 目标值 | 1 |
| 异常范围初筛 | < 70% |

## 分子

四级手术患者手术当日进行床旁交接班的患者数量

### 统计口径

1、当天完成四级手术，且完成床旁交班的患者数量（手术完成时间对比床旁交班时间）
2、说明：一个患者单次住院期间多次四级手术时，要完成多次床旁交班才算合格。
3、当天定义：手术完成后后按24小时内计算
4、说明：手术患者判断时，只需要统计手术类型=手术、介入治疗，排除诊断性操作、治疗性操作的数据
5、床旁交班数据来源为交接班系统，以下任意一个满足，即表示有做床旁交班：
5.1）当天有创建床旁交班（班次）
5.2）当天有创建交班，且手术患者的床旁标记=是
5.3）当天有创建交班，且手术患者有被添加到床旁交班的类型中（交班患者类型的显示名称包含“床旁”字样）

## 分母

同期进行四级手术的患者总数量

### 统计口径

单位时间内完成四级手术患者人数

## 可配置参数

无。

## 执行引用

- 当前执行状态：`documentation_only`
- 阻断原因：缺少经确认的医院字段契约和统一结果列映射
- 源表 SQL：`sql-specs/HXZD-005-001/profiles/HXZD-005-001-company-default/etl_source.sql`
- 概览 SQL：`sql-specs/HXZD-005-001/profiles/HXZD-005-001-company-default/overview.sql`
- 科室 SQL：`sql-specs/HXZD-005-001/profiles/HXZD-005-001-company-default/department.sql`
- 患者明细 SQL：`sql-specs/HXZD-005-001/profiles/HXZD-005-001-company-default/patient_detail.sql`
