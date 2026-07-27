---
page_type: indicator
rule_id: HXZD-009-003
title: 科主任主持死亡病例讨论率
status: published
system_id: HXZD-009
system_name: 死亡病例讨论制度
aliases:
  - 科主任主持死亡病例讨论率_死亡讨论记录
  - 科主任主持死亡病例讨论率
keywords:
  - 死亡病例讨论制度
  - 死亡时间
  - 死亡病例
  - 死亡病例可选方案
direction: higher_is_better
unit: percentage
default_profile: HXZD-009-003-company-default
updated_at: 2026-07-26
---

# 科主任主持死亡病例讨论率

## 检索卡片

- 指标编码：HXZD-009-003
- 所属制度：[[HXZD-009-死亡病例讨论制度]]
- 默认口径：[[HXZD-009-003-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

死亡病例讨论由科主任主持的病例数量占同期死亡病例总数量的比例。

## 计算公式

分子：死亡病例讨论由科主任主持的病例数量
分母：同期死亡病例总数量
公式：死亡病例讨论由科主任主持的病例数量 / 同期死亡病例总数量 * 100%

## 指标说明

——

## 指标意义

反映死亡病例讨论制度落实和管理情况。

## 已发布口径

- [[HXZD-009-003-company-default]] 推荐方案：科主任数据来源：死亡讨论记录病历模板限制主持人只能选科主任（需现场调整病历模板）
- [[HXZD-009-003-company-candidate-01]] 可选方案：科主任数据来源：统一配置行政职务包含“主任”
