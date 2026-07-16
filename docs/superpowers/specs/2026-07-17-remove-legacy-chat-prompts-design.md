# 删除旧聊天提示词设计

## 目标

彻底删除已经退出生产链路的旧聊天意图识别与答案生成提示词，避免它们继续被误认为当前 Agent Runtime 的组成部分。

## 边界

- 删除 `legacy_chat_intent.txt` 和 `legacy_chat_answer.txt`。
- 删除 `app.prompts` 中对应的专用加载函数。
- 将 `HumanInteractionAgent` 收缩为确定性规则处理器，删除只为上述提示词存在的可选 LLM 分支。
- 保留当前业务 API 仍复用的意图规则、上下文动作改写、确定性规则答案和答案事实守卫。
- Planner、Executor、Replanner、指标草稿和诊断提示词不变。

## 验收

提示词目录、Python API、生产代码和当前文档中均不再引用两个旧提示词；确定性业务 API 与当前 Agent Runtime 测试继续通过。
