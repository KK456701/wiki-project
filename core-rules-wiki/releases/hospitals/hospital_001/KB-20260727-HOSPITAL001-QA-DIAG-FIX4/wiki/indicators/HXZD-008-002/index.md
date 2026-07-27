---
page_type: indicator
rule_id: HXZD-008-002
title: 术者参加术前讨论率
status: published
system_id: HXZD-008
system_name: 术前讨论制度
aliases:
  - 术者参加术前讨论率_病历完成时间
  - 术者参加术前讨论率_出区时间
keywords:
  - 术前讨论制度
  - 病历完成时间
  - 出区时间
  - 术前讨论制度V2
direction: higher_is_better
unit: percentage
default_profile: HXZD-008-002-company-default
updated_at: 2026-07-26
---

# 术者参加术前讨论率

## 检索卡片

- 指标编码：HXZD-008-002
- 所属制度：[[HXZD-008-术前讨论制度]]
- 默认口径：[[HXZD-008-002-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

术者参加术前讨论的手术例数占同期进行术前讨论手术总例数的比例。

## 计算公式

分子：术者参加术前讨论的手术例数
分母：同期进行术前讨论手术总例数
公式：术者参加术前讨论的手术例数 / 同期进行术前讨论手术总例数 * 100%

## 指标说明

本指标中术者是指手术的主要完成人。

## 指标意义

反映术前讨论规范性。

## 已发布口径

- [[HXZD-008-002-company-default]] 推荐方案：按病历完成时间统计
- [[HXZD-008-002-company-candidate-01]] 可选方案：按患者出区时间统计
