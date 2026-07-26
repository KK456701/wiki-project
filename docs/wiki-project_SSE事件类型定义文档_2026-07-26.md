# Wiki Project SSE 事件类型

> 版本：2026-07-26。权威接口为 `POST /api/agent/chat/stream`。

## 事件顺序

```text
agent_start
→ trace_node(memory_load)
→ model_start / trace_node(planner_llm)
→ trace_node(plan_goal_alignment / plan_compile / plan_validate)
→ tool_call / tool_result / trace_node(tool_result)（零到多次）
→ trace_node(evidence_verify)
→ trace_node(final_answer_llm)
→ answer
```

需要澄清时可在工具执行前返回 `clarification`；运行异常返回 `agent_error`。

## 通用字段

| 字段 | 说明 |
|---|---|
| `event` | 事件类型 |
| `trace_id` | 本轮链路编号 |
| `subtask_id` | 多指标子任务编号 |
| `step` | 当前工具步骤 |
| `status` | `running`、`success`、`failed` 等 |
| `message` | 用户可见阶段说明 |

`trace_node` 另包含 `node_id`、`parent_node_id`、`sequence`、`node_name`、`node_type`、`duration_ms`、`capability`、`tool_name`、`model_id`、`failure_class`、安全输入输出和版本信息。

## 工具节点

当前工具节点名称：

- `search_indicator_rules`
- `get_effective_rule`
- `preview_rule_change`
- `inspect_indicator_implementation`
- `prepare_indicator_sql`
- `trial_run_indicator_sql`
- `resolve_indicator_caliber`
- `prepare_indicator_caliber_sql`
- `trial_run_indicator_caliber_sql`
- `diagnose_indicator_issue`
- `diagnose_indicator_difference`
- `analyze_uploaded_indicators`

不存在 `implementation_validation_*` 或监控调度节点。

## 最终事件示例

```text
event: answer
data: {
  "event": "answer",
  "trace_id": "TRACE_xxx",
  "session_id": "session_001",
  "message": "已完成回答",
  "stop_reason": "final_answer",
  "status": "completed"
}
```

前端应使用事件中的当前节点更新单个阶段状态槽；完整执行历史只在“查看链路”的六阶段架构图中展示。
