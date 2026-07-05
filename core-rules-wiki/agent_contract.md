# Agent 接入协议

## 检索优先级

1. 通过 `indexes/rule_index.json` 定位 `rule_id`。
2. 通过 `indexes/hospital_override_index.json` 判断是否存在医院个性化口径。
3. 通过 `indexes/relation_index.json` 扩展国标、公司标准、概念、字段角色、表结构关系。
4. 通过 `indexes/search_index.json` 获取章节切片。
5. 必要时读取对应 Markdown 原文。

## 建议工具函数

### `kb_search(query, filters)`

从 `search_index.json` 检索切片，返回命中的 `rule_id`、路径、章节和相关关系。

### `kb_get_rule(rule_id, level="company")`

读取国标或公司标准规则页。若 `level=company` 且公司页存在，优先返回公司页，同时提供国标路径。

### `kb_get_hospital_override(rule_id, hospital_id)`

查询 `hospital_override_index.json`。当前初始库尚未配置医院个性化口径。

### `kb_get_field_mapping(rule_id)`

查询 `field_index.json` 和字段角色页。当前初始库标记为 `待医院字段映射确认`。

### `kb_submit_change_request(payload)`

将新增字段、SQL、医院口径或规则差异写入 `review/pending/`，人工审核通过后再更新 `wiki/` 并重建索引。

## 回答约束

当字段、表名或 SQL 在知识库中为 `原文未明确` 时，Agent 不能生成可执行 SQL，只能说明需要字段映射确认。
