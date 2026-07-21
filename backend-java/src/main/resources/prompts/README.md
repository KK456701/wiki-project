# Java Agent 提示词

这里集中保存 Java 迁移运行时正在使用的提示词，与 Python `app/prompts/` 的角色边界保持一致。

| 文件 | LLM 角色 | 使用者 | 禁止事项 |
|---|---|---|---|
| `planner-system.txt` | Planner / 业务目标规划器 | `ModelRequestPlanner` | 不选工具、不写 SQL、不生成执行步骤 |
| `planner-repair.txt` | Planner / JSON 修复器 | `ModelRequestPlanner` | 仅修复一次，不改变用户目标 |
| `final-answer-system.txt` | Final Answer / 最终回答模型 | `FinalAnswerComposer` | 不调用工具，只消费 `VerifiedEvidence` |
| `final-answer-correction.txt` | Final Answer / 回答纠错器 | `FinalAnswerComposer` | 仅修复空回答或工具协议泄漏 |

Java 与 Python 的提示词可以分别演进，但必须保持相同业务边界，并在 Trace 中记录提示词版本。
