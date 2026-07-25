# 核心制度指标 Wiki 维护契约

## 允许操作

- 读取 raw、wiki、indexes。
- 在 review/pending 创建变更草稿。
- 更新草稿中的摘要、链接和关键词。
- 执行 lint。
- 执行索引预构建。
- 提交人工审核。

## 禁止操作

- 不得直接修改已发布口径。
- 不得直接修改正式 SQL 模板。
- 不得自行发布医院 override。
- 不得猜测缺失业务字段。
- 不得把 #NAME? 解释成业务条件。
- 不得修改指标编码。
- 不得将不同指标合并。

## 每次变更必须包含

- rule_id
- affected_profile_id
- source_file
- source_location
- before
- after
- reason
- confidence
- unresolved_questions
