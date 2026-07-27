---
page_type: indicator
rule_id: HXZD-014-001
title: 危急值报告时间
status: published
system_id: HXZD-014
system_name: 危急值报告制度
aliases:
  - 危急值报告时间
keywords:
  - 危急值报告制度
  - 危急值接收时间
  - 危急值报告
direction: lower_is_better
unit: minutes
default_profile: HXZD-014-001-company-default
updated_at: 2026-07-26
---

# 危急值报告时间

## 检索卡片

- 指标编码：HXZD-014-001
- 所属制度：[[HXZD-014-危急值报告制度]]
- 默认口径：[[HXZD-014-001-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

将出现危急值到临床科室获取危急值的时间，由长到短排序后取其中位数。

## 计算公式

危急值报告时间(中位数) = X(n+1)/2, n为奇数；
危急值报告时间(中位数) = (Xn/2 + Xn/2+1)/2, n为偶数

## 指标说明

1. n为实际报告的危急值项目数; X为出现危急值到临床科室获取危急值的时间。
2.分别计算住院、门诊、急诊危急值报告时间。

## 指标意义

反映危急值上报的效率。

## 已发布口径

- [[HXZD-014-001-company-default]] 推荐方案：公版
