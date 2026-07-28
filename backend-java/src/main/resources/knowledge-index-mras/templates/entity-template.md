---
title: "衍生指标实体模板 - {扩展指标编码}_{指标名称}"
created: {YYYY-MM-DD}
updated: {YYYY-MM-DD}
type: entity
tags: [{扩展指标编码}, {制度标签}, {事件编码}, {类型标签}]
sources: [raw/excel/AI指标顾问【AI Metrics Advisor】.xls]
confidence: high
---

# {扩展指标编码} — {指标名称}

> **指标概念：** [[concepts/{指标名称}]] | **制度：** {制度名称} | **事件编码：** {事件编码}
> **版本：** {版本} | **指标导向：** {指标导向}
{变体标记：⚠️ 可选方案/变体，与主方案共用指标定义}

## 指标定义

{指标的具体定义描述}

## 计算公式

- **分母：** {分母定义}
- **分子：** {分子定义}
- **公式：** {分子/分母 * 100%}

## 说明

{指标的补充说明、排除条件等}

## 指标意义

{指标的业务意义和监测目的}

## 统计口径

{统计口径描述}

## 数据来源

| 数据层 | 表名/说明 |
|--------|----------|
| 源表 | `{源表主表来源}` |
| XXJOB作业 | {XXJOB作业} |
| 目标表-概览 | `{目标表-概览}` |
| 目标表-科室统计 | `{目标表-科室统计}` |
| 目标表-患者明细 | `{目标表-患者明细}` |
| 中间表 | `{中间表}` |
| 业务表(不影响数据) | `{业务表(不影响数据)}` |
| 业务表(影响数据) | `{业务表(影响数据)}` |

## 是否影响数据

**{是/否}**

## 监测参数

| 参数 | 值 |
|------|-----|
| 时间维度 | {时间维度} |
| 患者范围 | {患者范围} |
| 数据来源 | {数据来源} |
| 目标值 | {目标值} |
| 异常范围初筛 | {异常范围初筛} |
| 方案类型 | {方案类型} |

## 源表

{源表SQL脚本}

## 目标表-概览

{目标表-概览SQL脚本}

## 目标表-科室统计

{目标表-科室统计SQL脚本}

## 目标表-患者明细

{目标表-患者明细SQL脚本}

## sql自定义参数

本模块用于定义 SQL 脚本中的动态占位符语法，支持条件分支与参数替换。以下为两种核心占位符的规范说明。

### #ETC — 条件占位符

**定义：** SQL 中的条件/其他占位符。入参存在时注入 SQL 片段，入参未传时自动移除该行。

**语法：** `#ETC{SQL片段}`

**示例：**

```sql
-- 原始脚本
WHERE 1=1
#ETC{AND ie.CURRENT_DEPT_ID NOT IN (:exDeptSet)}
#ETC{AND ie.FULL_NAME NOT LIKE :exPatientSet}
```

**展开规则：**

| 入参情况 | 最终 SQL |
|----------|----------|
| `exDeptSet=[101,102]`，`exPatientSet` 未传 | `WHERE 1=1`<br>`AND ie.CURRENT_DEPT_ID NOT IN (:exDeptSet)` |
| `exDeptSet` 未传，`exPatientSet='%张三%'` | `WHERE 1=1`<br>`AND ie.FULL_NAME NOT LIKE :exPatientSet` |
| 两者都未传 | `WHERE 1=1` |

---

### #EQUALS — 等值分支占位符

**定义：** SQL 中的等值比较占位符。根据入参值选择对应的 SQL 分支。

**语法：** `#EQUALS{:paramName; value; SQL片段}`

**示例：**

```sql
-- 原始脚本
WHERE 1=1
#EQUALS{:syncType; increment; AND (a.MODIFIED_AT BETWEEN :startTime AND :endTime)}
#EQUALS{:syncType; single; AND a.ENCOUNTER_ID IN (SELECT ... WHERE DISCHARGED_FROM_WARD_AT IS NULL OR ...)}
#EQUALS{:syncType; outHosp; AND a.ENCOUNTER_ID IN (SELECT ... WHERE DISCHARGED_FROM_WARD_AT BETWEEN ...)}
```

**展开规则：**

| 入参 | 值域 | 含义 | SQL 效果 |
|------|------|------|----------|
| `:syncType` | `increment` | 增量同步 | `AND (MODIFIED_AT BETWEEN :startTime AND :endTime)` |
| | `single` | 在院患者 | `AND ENCOUNTER_ID IN (SELECT ... WHERE DISCHARGED_FROM_WARD_AT IS NULL OR ...)` |
| | `outHosp` | 出院患者 | `AND ENCOUNTER_ID IN (SELECT ... WHERE DISCHARGED_FROM_WARD_AT BETWEEN ...)` |

---

### 使用说明

| 占位符类型 | 触发方式 | 多值行为 | 典型场景 |
|-----------|---------|---------|---------|
| `#ETC{}` | 入参存在则注入 | 支持多个独立 `#ETC{}` | 可选过滤条件、排除规则 |
| `#EQUALS{}` | 入参值匹配则注入 | 同一参数多个分支，互斥命中 | 同步模式切换、数据源选择 |

## 中间表

{中间表说明}

## 业务表(不影响数据)

{业务表(不影响数据)}

## 业务表(影响数据)

{业务表(影响数据)}

## 相关链接

- 指标概念：[[concepts/{指标名称}]]
- ETL抽取逻辑：[[concepts/ETL抽取逻辑说明]]
- 证据链：[[references/evidence-chain]]
- 指标映射表：[[references/indicator-map]]
- 目标表-概览索引：[[concepts/目标表-概览索引]]
- 源表索引：[[concepts/源表索引]]

<!-- entity: {扩展指标编码} | code: {指标编码} | system: {制度} | dim: {维度} -->
