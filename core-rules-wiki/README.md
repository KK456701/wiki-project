# 医务核心制度指标 Markdown Wiki 知识库

本知识库根据《Markdown Wiki 规则知识库建库规范》和 `medical_quality_core_system_indicators_2025_dify_cleaned.md` 初始生成，用于 Agent 检索医务核心制度指标规则。

## 本次建库范围

- 指标数量：35
- 国标规则页：35
- 公司标准规则页：35
- 原始资料：`raw/national/medical_quality_core_system_indicators_2025_dify_cleaned.md`
- 字段、表、SQL：原文未明确，已进入 `review/pending/CR_20260705_001_字段映射与SQL实现待审核.md`

## 使用入口

- [[index]]
- [[schema]]
- [[agent_contract]]
- `indexes/rule_index.json`
- `indexes/relation_index.json`
- `indexes/search_index.json`

## 更新原则

正式知识位于 `wiki/`。任何新增字段映射、SQL、医院个性化口径或规则差异都应先进入 `review/pending/`，审核后再写入 `wiki/` 并重建 `indexes/`。
