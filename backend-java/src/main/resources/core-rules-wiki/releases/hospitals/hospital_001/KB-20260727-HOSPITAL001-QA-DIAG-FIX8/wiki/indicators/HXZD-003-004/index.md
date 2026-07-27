---
page_type: indicator
rule_id: HXZD-003-004
title: 普通会诊有效率
status: published
system_id: HXZD-003
system_name: 会诊制度
aliases:
  - 普通会诊有效率
keywords:
  - 会诊制度
  - 会诊完成时间
  - 会诊
direction: higher_is_better
unit: percentage
default_profile: HXZD-003-004-company-default
updated_at: 2026-07-27
---

# 普通会诊有效率

## 检索卡片

- 指标编码：HXZD-003-004
- 所属制度：[[HXZD-003-会诊制度]]
- 默认口径：[[HXZD-003-004-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

普通会诊结束后开具相关医嘱的次数占同期普通会诊总次数的比例。

## 计算公式

分子：普通会诊结束后开具相关医嘱的次数
分母：同期普通会诊患者总次数
公式：普通会诊结束后开具相关医嘱的次数 / 同期普通会诊患者总次数 * 100%

## 指标说明

本指标中普通会诊结束后开具相关医嘱情况统计方法为在申请普通会诊后24小时内开具相关医嘱。

## 指标意义

反映普通会诊意见的有效性和普通会诊申请的合理性。

## 已发布口径

- [[HXZD-003-004-company-default]] 推荐方案：公版
