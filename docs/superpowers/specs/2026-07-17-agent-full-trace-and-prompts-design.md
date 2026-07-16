# Agent 全链路诊断与提示词集中化设计

## 背景与根因

当前本地 4B 模型在普通寒暄“你好”时会返回 `target_indicator: null`、`time_expression: null`。这两个字段在业务上等价于空对象，但 Planner 只兼容字符串和对象，导致两次计划校验失败。现有 Agent Trace 只监听 Executor 的 `model_start`、`tool_call`、`tool_result`，Planner 失败发生在这些记录点之前，因此失败运行只有开始和结束，没有可查看节点。

## 目标

1. 普通寒暄允许 Planner 将可选容器的 `null` 归一化为空对象，不降低业务字段校验强度。
2. 每轮 Agent 对话都记录从会话读取到会话保存的完整阶段，包括失败路径。
3. 节点同时提供英文 ID 和中文名称，并按 LLM、确定性代码、工具/数据库、记忆/存储四种类型着色。
4. 每个节点记录开始时间、结束时间、耗时、完整安全输入、完整安全输出、数据处理说明和运行配置。
5. 所有实际会发送给模型的提示词集中到 `app/prompts/`，代码通过统一加载器读取。

## 后端链路

运行时按实际执行记录以下节点，工具循环中的节点可重复出现：

| 英文 ID | 中文名称 | 类型 |
| --- | --- | --- |
| `memory_load` | 读取会话上下文 | storage |
| `planner_llm` | 规划业务目标 | llm |
| `plan_compile` | 编译业务计划 | code |
| `plan_validate` | 校验业务计划 | code |
| `state_controller` | 选择下一业务能力 | code |
| `executor_llm` | 调用执行模型 | llm |
| `tool_gateway` | 校验并调用业务工具 | tool |
| `tool_result` | 观察工具结果 | tool |
| `plan_verify` | 校验证据完整性 | code |
| `response_guard` | 校验并整理回答 | code |
| `memory_save` | 保存会话上下文 | storage |

Planner 自身记录实际发送的 messages、模型参数、原始模型响应、归一化后的 JSON 和校验错误。Executor 记录完整 messages、当前可见工具 schema、温度、模型响应和工具调用。控制器、编译器和验证器记录强类型对象的完整输入输出。

## 安全边界

“完整参数”指安全边界内未截断的实际参数，包括 system prompt、用户问题、最近会话、结构化状态、工具 schema、计划和聚合结果。密码、令牌、Authorization、数据库连接串、患者行级明细和模型隐藏思维链永不写入 Trace。SQL 与字段映射可供同医院已登录实施人员查看，但患者明细仍通过既有明细权限与专用接口访问。

Trace 继续使用认证后的 `/api/agent/runs/{trace_id}`，并继续校验医院隔离。公开 SSE 仍只发送业务摘要，不透传完整参数。

## 前端设计

“查看链路”按钮在成功和失败消息上都显示。弹窗顶部显示总耗时、节点数和四类节点图例；其下是按执行顺序排列的彩色链路轨道。节点详情卡同时显示 `中文名称 / english_id`、类型、状态和耗时，并提供“输入参数”“输出参数”“数据处理”“节点配置”四块完整 JSON/文本区域。长内容使用等宽字体、独立滚动区域和复制按钮，不做摘要替代。

配色使用现有医院界面的低饱和色系：LLM 紫色、代码蓝色、工具橙色、存储绿色；失败状态继续使用红色，不依赖颜色作为唯一识别方式。

## 提示词目录

统一放入 `app/prompts/`：

- `agent_planner.txt`
- `agent_planner_context.txt`
- `agent_planner_repair.txt`
- `agent_replanner.txt`
- `agent_executor.txt`
- `agent_executor_context.txt`
- `agent_executor_step.txt`
- `agent_executor_corrections.txt`
- `indicator_draft_parser.txt`
- `indicator_draft_repair.txt`
- `diagnosis_evidence.txt`
- `diagnosis_compose.txt`
- 已有但当前未启用的 `intent.txt`、`answer.txt`

统一加载器提供读取、格式化和 SHA-256 短版本号。Trace 节点配置记录提示词文件名和版本号，使界面展示与磁盘文件可对应。

## 验收标准

- “你好”不再因两个 `null` 容器失败。
- Planner 失败时仍至少显示会话读取、Planner 失败、会话保存三个节点。
- 成功的指标试运行能显示所有实际经过的计划、模型、工具、校验节点。
- 每个节点有中英文名称、类型、耗时、完整安全输入输出、处理说明和配置。
- 成功与失败消息均有“查看链路”。
- 所有生产 LLM 提示词均可在 `app/prompts/` 找到，代码中不再保留大段内联提示词。
- 公开 SSE 不包含完整输入输出；不同医院不能读取对方 Trace。
