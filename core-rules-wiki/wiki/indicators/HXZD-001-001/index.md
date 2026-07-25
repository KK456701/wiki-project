---
page_type: indicator
rule_id: HXZD-001-001
title: 患者入院48小时内转科的比例
status: published
system_id: HXZD-001
system_name: 首诊负责制度
aliases:
  - 患者入院48小时内转科的比例_入区时间
keywords:
  - 患者入院
  - 首诊负责制度
  - 入区时间
  - 首诊负责
direction: lower_is_better
unit: percentage
default_profile: HXZD-001-001-company-default
updated_at: 2026-07-25
---

# 患者入院48小时内转科的比例

## 检索卡片

- 指标编码：HXZD-001-001
- 所属制度：[[HXZD-001-首诊负责制度]]
- 默认口径：[[HXZD-001-001-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

入院48小时内转科患者人次数占同期入院患者总人次数的比例。

## 计算公式

分子：入院48小时内转科患者人次数
分母：同期入院患者总人次数
公式：入院48小时内转科患者人次数/同期入院患者总人次数 * 100%

## 指标说明

本指标不包括患者转入/转出ICU的情况。

## 指标意义

反映首诊医师和首诊科室对患者病情评估的充分性。

## 已发布口径

- [[HXZD-001-001-company-default]] 推荐方案：公版
