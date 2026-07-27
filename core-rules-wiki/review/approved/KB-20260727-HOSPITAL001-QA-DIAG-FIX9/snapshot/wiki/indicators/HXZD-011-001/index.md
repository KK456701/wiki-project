---
page_type: indicator
rule_id: HXZD-011-001
title: 手术医师手术时间重合率
status: published
system_id: HXZD-011
system_name: 手术安全核查制度
aliases:
  - 手术医师手术时间重合率
keywords:
  - 手术安全核查制度
  - 手术完成时间
  - 手术安全
direction: lower_is_better
unit: percentage
default_profile: HXZD-011-001-company-default
updated_at: 2026-07-27
---

# 手术医师手术时间重合率

## 检索卡片

- 指标编码：HXZD-011-001
- 所属制度：[[HXZD-011-手术安全核查制度]]
- 默认口径：[[HXZD-011-001-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

同一时间内手术医师为同一人的手术例数占同期住院患者手术总例数的比例。

## 计算公式

分子：同一时间内手术医师为同一人的手术例数
分母：同期住院患者手术总例数
公式：同一时间内手术医师为同一人的手术例数 / 同期住院患者手术总例数 * 100%

## 指标说明

本指标中"同一时间"是指手术未结束时间与其他手术开始时间重合。

## 指标意义

反映手术医师参与手术安全核查情况。

## 已发布口径

- [[HXZD-011-001-company-default]] 推荐方案：公版
