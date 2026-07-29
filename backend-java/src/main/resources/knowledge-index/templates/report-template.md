# 报告模板

> 基于 LLM Wiki v3 的标准化指标报告格式。
> 变量用 `{var}` 表示，生成时替换为实际值。

## 文件命名

```
{yyyymmddhhmmss}.md
```

例：`20260726143522.md`

## 输出路径

```
wiki/report/{filename}
```

---

## 模板正文

```markdown
---
tags: [{tags}]
created: {created_date}
period: {startDate} ~ {endDate}
event_code: {event_code}
indicators: [{indicator_list}]
system: {system_name}
db: WiNEX_All_QA (172.17.0.117:1455)
schema: WINDBA_GN
evidence_chain:
  layers: [L1, L2, L3, L4, L5, L5+, L6]
  verified_at: {verified_at}
  source_tables: [{source_tables}]
  target_table: {target_table}
  rule_files: [{rule_files}]
  cross_check: "{mol}/{den}={rate}% (sqlcmd 直连 {verified_at})"
params:
  marptBeginAt: "{startDate} 00:00:00"
  marptEndAt: "{endDate} 23:59:59"
---

# {system_name}监测指标报告

> 统计周期：{startDate} ~ {endDate} | 生成日期：{created_datetime} |
> 制度：{system_name} | 指标：{indicator_ids} · {indicator_names} | 事件：{event_code}

---

## 一、报告概述

| 参数 | 值 |
|------|-----|
| 统计周期 | {startDate} ~ {endDate} |
| 制度 | {system_name} |
| 指标 | {indicator_ids} — {indicator_names} |
| 事件编码 | {event_code} |
| 目标表 | WINDBA_GN.{target_table} |
| 数据来源 | WiNEX_All_QA sqlcmd 实时查询 |
| 指标导向 | {direction} |

---

## 二、指标结果

| 统计项 | 数值 |
|--------|------|
| 入院总人次（分母） | **{den}** |
| 48h内转科人次（分子） | **{mol}** |
| **指标值** | **{rate}%** |
| 目标值 | {target_value} |
| 达标判定 | {pass_fail_emoji} {pass_fail_text} |
| {judge_field} 空值 | {null_count} {null_status} |

### 枚举值分布

| 编码 | 含义 | 记录数 | 占比 |
|------|------|--------|------|
| 98175 | {meaning_yes} | {count_98175} | {pct_98175}% |
| 98176 | {meaning_no} | {count_98176} | {pct_98176}% |
| NULL | 异常 | {count_null} | {pct_null}% |

### 按月趋势

| 时间段 | 入院人次 | 分子 | 指标值 |
|--------|---------|------|--------|
{monthly_rows}

> {trend_comment}

---

## 三、科室明细

| 科室 | 入院人次 | 分子 | 指标值 |
|------|---------|------|--------|
{dept_rows}

> {dept_summary}

---

## 四、证据链验证

```
{evidence_chain_ascii}
```

| 层级 | 验证内容 | 状态 |
|------|---------|------|
{evidence_table_rows}

---

## 五、推导SQL

```sql
{derivation_sql}

-- 结果: {den} | {mol} | {rate}%
```

### 按月趋势SQL

```sql
{trend_sql}
```

---

## 六、字段溯源链

```
推导SQL字段              目标表字段                    源SQL表达式                 源表列
─────────────────────────────────────────────────────────────────────────────────────────
{traceability_rows}
```

---

## 七、验证结论

| # | 检查项 | 状态 |
|---|--------|------|
{checklist_rows}

> **总评：{overall_assessment}**

## 八、相关页面

- 规则：[[../synthesis/rule/{rule_file}]]
- 源SQL：[[../synthesis/sql/{source_sql_file}]]
- 模型：[[../model/{model_file}]]
- 应用层：[[../synthesis/application/{app_file}]]
- 推导SQL：../../command/{command_sql_file}
- 维度分类：[[../topics/{dimension_file}]]
- 人工确认：[[../synthesis/manual/{manual_file}]]

<!-- report: {report_id} | period: {startDate}~{endDate} | event: {event_code} | value: {rate}% ({mol}/{den}) | layers: {passed}/{total} PASS -->
```
