# Agent 确定性工具直调设计

## 目标

消除状态控制器已经确定唯一业务工具后仍让 Executor 模型重复选择工具的冗余调用。工具调用由服务端确定性构造并通过现有 `ToolGateway` 执行，模型只保留 Planner 业务理解和最终答案组织。

## 方案选择

- 采用：`Planner → PlanCompiler/Validator → StateController → 服务端参数编译 → ToolGateway → Verifier → Answer LLM`。
- 不采用仅关闭工具路由思考：虽然单次更快，但仍保留多次无价值 LLM 调用。
- 不采用模型一次生成整条工具链：工具参数、顺序和前置条件容易漂移，不适合本地小模型和医疗指标受控执行。

## 组件边界

### `app.agent_planning.dispatch`

新增纯确定性参数编译器。输入为 `PlanningExecution`、`ControllerDecision`、`AgentRunState`、当前用户原文，输出唯一 `AgentToolCall`。映射如下：

| 业务能力 | 工具 | 参数来源 |
|---|---|---|
| `resolve_indicator` | `search_indicator_rules` | `target_indicator.raw_name` |
| `resolve_effective_rule` | `get_effective_rule` | `state.current_rule_id` 或计划 `rule_id` |
| `inspect_implementation` | `inspect_indicator_implementation` | 已确认 `rule_id` |
| `prepare_verified_sql` | `prepare_indicator_sql` | 已确认 `rule_id` 与 Validator 的 `resolved_time` |
| `execute_trial_run` | `trial_run_indicator_sql` | 当前会话最新 `validated_sql_ids` |
| `diagnose_indicator` | `diagnose_indicator_issue` | 已确认 `rule_id`、用户原文及确定性 SQL/参数提取结果 |
| `preview_rule_change` | `preview_rule_change` | 已确认 `rule_id` 与用户原文 |
| `analyze_uploaded_file` | `analyze_uploaded_indicators` | 本轮或最近对话中的“文件编号” |

缺少必需结构化参数时不调用模型猜测，返回明确的澄清或执行错误。

### `AgentRunner`

当 Controller 返回 `execute_tool` 时：

1. 调用确定性参数编译器；
2. 记录 `deterministic_tool_dispatch` 代码节点；
3. 仍通过现有 `ToolGateway` 完成权限、Pydantic 参数、重复调用、超时和风险校验；
4. 将工具结果追加到状态和模型消息；
5. 进入下一次 Controller 判断，不调用 Executor 模型。

只有 Controller 返回 `compose_answer` 时调用 Executor。现有 SQL 预览确定性回答继续保持零次 Executor 调用。

## 空模型动作

Executor 返回空 `content` 且无 `tool_calls` 时，不再记录为成功，Trace 节点标记为 `warning`，错误码为 `MODEL_EMPTY_ACTION`。最多追加一次最终回答纠错；再次为空则明确停止。Ollama 适配器只记录是否存在思考内容、思考字符数、token 计数和结束原因，不保存或展示思维链原文。

## 提示词与 Trace

- `agent_executor.txt` 改为只负责基于本轮证据生成最终中文答案，不再描述工具选择。
- `agent_executor_step.txt` 只声明当前为最终回答阶段、不得调用工具。
- Trace 新增蓝色代码节点“编译工具调用 / `deterministic_tool_dispatch`”。
- `executor_llm` 更名为“生成最终回答”，只在真正生成答案时出现。

## 验收标准

1. 公式解释的两次业务工具调用不产生 Executor 调用，整个 Runner 只有一次最终回答模型调用。
2. 试运行链路的规则读取、SQL 准备和只读试运行均由服务端直调，整个 Runner 只有一次最终回答模型调用。
3. 工具仍全部经过 `ToolGateway`，权限、安全、重复调用和超时边界不变。
4. 空模型动作不显示成功，且 Trace 不保存隐藏思维内容。
5. 当前自动化测试、README、提示词目录与架构文档同步更新。
