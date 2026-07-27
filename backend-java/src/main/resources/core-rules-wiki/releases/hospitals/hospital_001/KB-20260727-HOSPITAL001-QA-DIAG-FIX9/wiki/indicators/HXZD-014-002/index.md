---
page_type: indicator
rule_id: HXZD-014-002
title: 住院患者危急值当日及时处置率
status: published
system_id: HXZD-014
system_name: 危急值报告制度
aliases:
  - 住院患者危急值当日及时处置率
keywords:
  - 危急值报告制度
  - 危急值接收时间
  - 危急值报告
direction: higher_is_better
unit: percentage
default_profile: HXZD-014-002-company-default
updated_at: 2026-07-27
---

# 住院患者危急值当日及时处置率

## 检索卡片

- 指标编码：HXZD-014-002
- 所属制度：[[HXZD-014-危急值报告制度]]
- 默认口径：[[HXZD-014-002-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

当日处置的住院患者危急值项目数占同期临床科室接获住院患者危急值项目数的比例。

## 计算公式

分子：当日处置的住院患者危急值项目数
分母：同期临床科室接获住院患者危急值项目数
公式：当日处置的住院患者危急值项目数 / 同期临床科室接获住院患者危急值项目数 * 100%

## 指标说明

1.本指标只统计住院患者危急值处置情况，不包括门急诊患者危急值。
2.当日处置的危急值项目数以危急值出现当日的病程记录为准，若无记录则视为未处置。

## 指标意义

反映危急值规范化管理程度。

## 已发布口径

- [[HXZD-014-002-company-default]] 推荐方案：公版
