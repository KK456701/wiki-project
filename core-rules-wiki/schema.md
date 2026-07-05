# 知识库 Schema

## 页面类型

| type | 用途 |
|---|---|
| `standard_rule` | 国标规则页或公司标准规则页 |
| `hospital_override` | 医院个性化口径页 |
| `concept` | 制度主题或公共概念页 |
| `table_metadata` | 表结构元数据页 |
| `field_role_mapping` | 字段角色映射页 |
| `change_request` | 待审核变更申请页 |

## 必填关系

正式 Markdown 页必须同时维护：

- frontmatter `related`
- 正文 `[[WikiLink]]`
- `indexes/relation_index.json`
- `indexes/search_index.json`

## 缺失信息处理

当原文没有明确字段、表名或 SQL 时，必须写 `原文未明确` 或 `待医院字段映射确认`，不得编造实现细节。
