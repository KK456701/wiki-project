---
page_type: indicator
rule_id: HXZD-003-001
title: 急会诊及时到位率
status: published
system_id: HXZD-003
system_name: 会诊制度
aliases:
  - 急会诊及时到位率
keywords:
  - 会诊制度
  - 会诊完成时间
  - 门急诊会诊
direction: higher_is_better
unit: percentage
default_profile: HXZD-003-001-company-default
updated_at: 2026-07-26
---

# 急会诊及时到位率

## 检索卡片

- 指标编码：HXZD-003-001
- 所属制度：[[HXZD-003-会诊制度]]
- 默认口径：[[HXZD-003-001-company-default]]
- SQL规格：[[sql-spec]]

## 指标定义

急会诊请求发出后，10分钟内到达现场的急会诊次数占同期急会诊总次数的比例。

## 计算公式

分子：急会诊记录中10分钟内到位的急会诊次数
分母：同期急会诊总次数
公式：急会诊记录中10分钟内到位的急会诊次数 / 同期急会诊总次数 * 100%

## 指标说明

急会诊范围包括当患者罹患疾病超出本科室诊疗范围和处置能力，且经评估可能随时危及生命，需要院内其他科室医师立刻协助诊疗、参与抢救所发出的会诊申请。

## 指标意义

反映急会诊制度执行的规范性。

## 已发布口径

- [[HXZD-003-001-company-default]] 推荐方案：公版
