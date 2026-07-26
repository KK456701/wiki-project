# Java Agent 提示词

这里集中保存 Java 单运行时正在使用的提示词。提示词只负责语义理解与回答组织，工具选择、SQL 安全和状态推进由 Java 代码控制。

| 文件 | LLM 角色 | 使用者 | 禁止事项 |
|---|---|---|---|
| `planner-system.txt` | Planner / 业务目标规划器 | `ModelRequestPlanner` | 生成 `RequestPlan v3`，并在规则解释中标注 `explanation_focuses`；不选工具、不写 SQL、不生成执行步骤 |
| `planner-repair.txt` | Planner / JSON 修复器 | `ModelRequestPlanner` | 仅修复一次，不改变用户目标 |
| `replanner-instruction.txt` | Replanner / 方向重规划器 | `ModelRequestPlanner.replan` | 仅处理允许的方向性错误、最多一次、不处理数据库或权限故障 |
| `plan-alignment-review.txt` | 计划一致性审核器 | `ModelRequestPlanner.reviewAlignment` | 仅在确定性规则无法判断时审核原问题与计划，不生成计划或工具 |
| `indicator-candidate-disambiguator.txt` | 指标候选消歧器 | `HybridIndicatorResolver` | 只能从服务端候选 rule_id 中选择，不识别意图、不选择工具 |
| `final-answer-system.txt` | Final Answer / 最终回答模型 | `FinalAnswerComposer` | 不调用工具，只消费 `VerifiedEvidence` |
| `final-answer-correction.txt` | Final Answer / 回答纠错器 | `FinalAnswerComposer` | 修复空回答、协议泄漏、缺失章节、占位符或数值丢失；再次失败时改用已验证证据模板 |

修改提示词时必须保持上述业务边界，并在 Trace 中记录提示词版本。

结果差异诊断没有单独的 LLM 提示词。Planner 只产出
`indicator_difference_diagnosis` 业务意图，后续范围、结构、口径、记录集合、数据质量和
结论代码全部由 Java `IndicatorDifferenceDiagnosisWorkflow` 确定性执行。

最终回答的具体版式不保存在本目录。`AnswerTemplateRegistry` 根据本轮
`RequestPlan.intent + requested_outputs + explanation_focuses`，只加载
`resources/answer-templates/` 中的一份回答或报告模板并传给 Final Answer LLM。
基础 Prompt 只声明必须遵守本轮模板，不包含任何具体业务模板正文。

“分子是什么”“分母怎么算”“按哪个时间字段统计”等明确追问由 Java
确定性解析并复用会话中的已确认指标，不调用 Planner。复杂规则问法才由 Planner
输出一个或多个 `explanation_focuses`；旧版计划没有该字段时按 `overview` 兼容。
