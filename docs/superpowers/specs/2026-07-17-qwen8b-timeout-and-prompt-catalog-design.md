# Qwen3 8B 超时与提示词目录设计

## 问题证据

真实链路 `TRACE_9501ba72832b` 总耗时 120.12 秒：Planner 47.95 秒，前两次 Executor 分别 40.78 秒和 29.45 秒。计划尚需最终回答节点时，服务端命中统一的 120 秒整轮超时。`qwen3:8b` 已完全加载到 RTX 4060 Laptop GPU，问题不是模型无法运行，而是思考模式被同时用于 Planner 与 Executor，且本地思考模型没有独立的节点和整轮超时预算。

## 设计

1. 模型注册项增加三个内部配置：
   - `planner_thinking`：Planner 是否启用思考；Qwen3 8B 设为 `false`。
   - `call_timeout_seconds`：单次 Ollama 请求上限；Qwen3 8B 设为 120 秒。
   - `request_timeout_seconds`：整轮 Agent 上限；Qwen3 8B 设为 300 秒。
2. Executor 继续使用 `thinking: true`，保留工具选择和最终回答的推理能力；Planner 只做结构化意图解析，显式发送 `think: false`。
3. 全局 120 秒仍作为其他模型默认值，避免扩大 DeepSeek 和 4B 的故障等待时间。
4. 不把思考内容写入 Trace，不向前端公开内部思考配置。

## 提示词目录

所有生产提示词继续集中在 `app/prompts/`。新增 `app/prompts/README.md`，按 Planner、Executor、旧流程、诊断、指标草稿五类列出文件、角色、调用者和触发时机。把含义不清楚的 `intent.txt`、`answer.txt` 分别改名为 `legacy_chat_intent.txt`、`legacy_chat_answer.txt`，明确它们只属于旧聊天流程，不属于当前 Agent Runtime。

## 验收

- Qwen3 8B 的 Planner 适配器显式关闭思考，Executor 仍开启思考。
- Qwen3 8B 单次请求上限为 120 秒，整轮上限为 300 秒。
- 其他模型继续使用现有默认超时。
- `app/prompts/README.md` 能清楚回答“这是谁的提示词、谁调用、何时使用”。
- 旧的模糊提示词文件名不再存在，所有调用和测试同步更新。

