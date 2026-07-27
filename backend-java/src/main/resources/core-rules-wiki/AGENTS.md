# 核心制度指标 Wiki 维护契约

## 允许操作

- 读取 raw、wiki、indexes。
- 大模型按照 `prompts/knowledge-release-normalizer.md` 在 review/pending 创建结构化候选。
- 使用确定性脚本提取 Excel/Markdown、SQL 原文和来源位置。
- 执行 lint。
- 执行索引预构建。
- 提交人工审核。
- 经人工明确确认后，使用发版脚本生成不可变 release 并切换指针。

## 禁止操作

- 本地 4B/8B 小模型不得参与知识维护或发布。
- 大模型不得直接修改 current 指针或已发布 release。
- 大模型不得创建、改写或补全 SQL，只能引用确定性提取的 sql_block_id。
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

## 发布要求

- `Prepare` 只生成待审核候选。
- `Publish` 必须显式提供人工确认。
- SQL 文档可以离线发布，但可执行状态必须通过目标医院的元数据、编译、试运行和人工确认门禁。
- 医院差异包必须经过基础版本校验与三方 Diff，不得直接合并到公司版本。
