---
page_type: indicator
rule_id: HXZD-003-003
title: 普通会诊及时完成率
status: published
system_id: HXZD-003
system_name: 会诊制度
aliases:
  - 普通会诊及时完成率
keywords:
  - 会诊制度
  - 会诊完成时间
  - 会诊
direction: higher_is_better
unit: percentage
default_profile: HXZD-003-003-company-default
updated_at: 2026-07-26
---

# 普通会诊及时完成率

## 检索卡片

- 指标编码：HXZD-003-003
- 所属制度：[[HXZD-003-会诊制度]]
- 默认口径：[[HXZD-003-003-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

普通会诊24小时内完成次数占同期普通会诊总次数的比例。

## 计算公式

分子：普通会诊24小时内完成次数
分母：同期普通会诊总次数
公式：普通会诊24小时内完成次数 / 同期普通会诊总次数 * 100%

## 指标说明

病历中会诊医师电子签章时间即为会诊完成时间。

## 指标意义

反映普通会诊制度执行的规范性。

## 已发布口径

- [[HXZD-003-003-company-default]] 推荐方案：按会诊申请科室数统计
- [[HXZD-003-003-company-candidate-01]] 可选方案：按会诊受邀科室数统计
