---
page_type: indicator
rule_id: HXZD-009-002
title: 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值
status: published
system_id: HXZD-009
system_name: 死亡病例讨论制度
aliases:
  - 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值_出院患者
  - 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值
keywords:
  - 死亡病例讨论制度
  - 死亡时间
  - 死亡病例
  - 死亡病例可选方案
direction: higher_is_better
unit: ratio
default_profile: HXZD-009-002-company-default
updated_at: 2026-07-26
---

# 医务部门组织讨论的死亡病例与发生纠纷的死亡病例比值

## 检索卡片

- 指标编码：HXZD-009-002
- 所属制度：[[HXZD-009-死亡病例讨论制度]]
- 默认口径：[[HXZD-009-002-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

医务部门组织进行死亡病例讨论的病例数量与同期发生医疗纠纷的死亡病例数量的比值。

## 计算公式

分子：医务部门组织进行死亡病例讨论的病例数量
分母：同期发生医疗纠纷的死亡病例数量
公式：医务部门组织进行死亡病例讨论的病例数量 ： 同期发生医疗纠纷的死亡病例数量

## 指标说明

——

## 指标意义

反映死亡病例讨论制度落实和管理情况。

## 已发布口径

- [[HXZD-009-002-company-default]] 推荐方案：纠纷数据来源：医务管理系统（需上线医务系统投诉纠纷管理功能）
- [[HXZD-009-002-company-candidate-01]] 可选方案（未实现）：纠纷数据来源：HIS患者标签
