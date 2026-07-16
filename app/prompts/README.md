# 提示词目录

这里集中保存项目中所有仍在使用的生产 LLM 提示词。文件名按“业务模块 + 模型角色 + 用途”命名；已退出生产链路的旧聊天提示词不再保留。

## 当前 Agent Runtime

| 文件 | 模型角色 | 调用者 | 使用时机 |
|---|---|---|---|
| `agent_planner.txt` | Planner / 业务目标规划器 | `app.agent_planning.planner.ModelRequestPlanner` | 每轮开始，把用户问题转换为严格 `RequestPlan`，不选择工具 |
| `agent_planner_context.txt` | Planner / 上下文包装器 | `ModelRequestPlanner` | 把 Planner 主提示词、日期和结构化会话状态组合成系统消息 |
| `agent_planner_repair.txt` | Planner / JSON 修复器 | `ModelRequestPlanner` | Planner 首次输出不满足合约时，最多修复一次 |
| `agent_replanner.txt` | Replanner / 方向重规划器 | `ModelRequestPlanner.replan` | 只有计划方向被工具证据证明错误时使用，默认最多一次 |
| `agent_executor.txt` | Executor / 最终回答模型 | `app.agent_runtime.runner.AgentRunner` | 服务端完成受控工具链且证据齐全后，只组织最终中文回答 |
| `agent_executor_context.txt` | Executor / 会话上下文包装器 | `AgentConversationMemory` | 注入当前日期、结构化状态和最多 8 轮最近对话 |
| `agent_executor_step.txt` | Executor / 最终回答约束 | `AgentPlanningRuntime.instruction` | 声明目标指标、规则、统计周期以及当前阶段禁止调用工具 |
| `agent_executor_corrections.txt` | Executor / 回答纠错器 | `AgentRunner` | 最终回答为空、非中文、缺少证据或事实不一致时最多纠正一次 |

## 指标实施草稿

| 文件 | 模型角色 | 调用者 | 使用时机 |
|---|---|---|---|
| `indicator_draft_parser.txt` | Draft Parser | `app.indicators.parser` | 把自然语言指标需求解析为结构化草稿 |
| `indicator_draft_repair.txt` | Draft Repair | `app.indicators.parser` | 草稿 JSON 首次校验失败时修复 |

## 指标诊断

| 文件 | 模型角色 | 调用者 | 使用时机 |
|---|---|---|---|
| `diagnosis_evidence.txt` | Diagnosis Evidence Extractor | `app.diagnose.evidence` | 从诊断输入中提取受控证据 |
| `diagnosis_compose.txt` | Diagnosis Narrator | `app.diagnose.narrator` | 根据诊断证据组织中文结论，不执行修复 |

## 修改规则

1. 新提示词必须放在本目录，并在本文件登记角色、调用者和触发时机。
2. 当前 Agent Runtime 文件统一以 `agent_planner_`、`agent_executor_` 或 `agent_replanner` 开头。
3. 旧聊天流程提示词不得重新放回生产目录；兼容逻辑应使用确定性代码或迁移到当前 Agent Runtime。
4. 提示词不得包含密钥、数据库连接串、患者明细或隐藏思维链要求。
5. 修改提示词后必须运行 `pytest -q tests/test_prompt_registry.py`，并同步 README 与架构文档。
