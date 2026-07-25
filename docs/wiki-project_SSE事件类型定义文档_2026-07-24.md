# Wiki Project SSE 事件类型定义文档

> 基于 Java 17 / Spring Boot 3.5.16 源码生成，覆盖 Agent 对话流式接口 `POST /api/agent/chat/stream` 的全部 SSE 事件类型。
>
> 生成日期：2026-07-24

---

## 文档基本信息

| 项目 | 说明 |
|------|------|
| 接口路径 | `POST /api/agent/chat/stream` |
| 通信协议 | Server-Sent Events (SSE)，`text/event-stream` |
| JSON 命名 | 统一 `snake_case` |
| 事件顺序 | 严格按后端 emit 先后排列；tool_call / tool_result 可能多轮出现 |
| 字段兼容 | 事件可能新增字段，前端应安全忽略未知字段 |

---

## 事件类型总览

| 序号 | event 值 | 用途 | 触发时机 |
|------|----------|------|----------|
| 1 | `agent_start` | Agent 开始运行 | 收到请求后首个事件 |
| 2 | `trace_node` | Trace 完整链路节点（含起止时间、输入输出） | 每个业务节点完成/失败时 |
| 3 | `model_start` | 模型开始推理 | Planner 和 Final Answer 调用前 |
| 4 | `tool_call` | 工具开始调用 | 每个工具执行前 |
| 5 | `tool_result` | 工具执行结果 | 每个工具执行后 |
| 6 | `stage_update` | 实时阶段状态 | 业务节点推进时（前端进度条） |
| 7 | `clarification_required` | 需要用户选择/输入才能继续 | 指标不明确、时间缺失、口径歧义 |
| 8 | `assistant_message` | 最终回答内容 | 确定性模板或 LLM 生成完成时 |
| 9 | `agent_done` | Agent 本轮完成 | 正常结束或澄清暂停 |
| 10 | `agent_error` | Agent 运行失败 | 工具失败、模型异常等不可恢复错误 |

---

## 1. agent_start — Agent 开始运行

收到请求后立即发出的第一个事件。

```json
{
  "event": "agent_start",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "status": "running"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 本轮运行的唯一链路编号，后续所有事件复用 |
| status | String | 是 | 固定值 `"running"` |

---

## 2. trace_node — Trace 链路节点

每个业务节点完成或失败后发出，用于"查看链路"详情页绘制瀑布图。节点按 `node_type` 分为 LLM 节点、代码节点、工具节点、存储节点四类。

### 2.1 成功节点

```json
{
  "event": "trace_node",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "node_id": "NODE_7f8e9d0c1b2a",
    "node_name": "planner_llm",
    "node_type": "llm",
    "status": "success",
    "started_at_epoch_ms": 1721798400000,
    "ended_at_epoch_ms": 1721798401200,
    "duration_ms": 1200,
    "subtask_id": "SUB_3c4d5e6f7a8b",
    "input": {
      "query": "患者入院48小时内转科的比例是多少？",
      "structured_state": ""
    },
    "output": {
      "intent": "indicator_trial_run",
      "requested_outputs": ["trial_result"]
    },
    "model_id": "qwen-plus",
    "repaired": false
  }
}
```

### 2.2 失败节点

```json
{
  "event": "trace_node",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "node_id": "NODE_7f8e9d0c1b2a",
    "node_name": "tool_result",
    "node_type": "tool",
    "status": "failed",
    "started_at_epoch_ms": 1721798403000,
    "ended_at_epoch_ms": 1721798403500,
    "duration_ms": 500,
    "subtask_id": "SUB_3c4d5e6f7a8b",
    "input": {},
    "output": {},
    "error_code": "DBHUB_TIMEOUT",
    "error_message": "DBHub 查询超时，请稍后重试"
  }
}
```

### 2.3 通用字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| node_id | String | 是 | 节点唯一编号（NODE_ 前缀） |
| node_name | String | 是 | 节点名称，见下方 node_name 枚举表 |
| node_type | String | 是 | `"llm"` \| `"code"` \| `"tool"` \| `"storage"` |
| status | String | 是 | `"success"` \| `"failed"` \| `"warning"` |
| started_at_epoch_ms | Long | 是 | 节点开始时间（毫秒时间戳） |
| ended_at_epoch_ms | Long | 是 | 节点结束时间（毫秒时间戳） |
| duration_ms | Long | 是 | 节点耗时（毫秒） |
| subtask_id | String | 是 | 子任务编号；根任务为 `"root"` |
| input | Object | 是 | 节点输入（Map） |
| output | Object | 是 | 节点输出（Map） |
| error_code | String | 否 | 失败节点的错误码 |
| error_message | String | 否 | 失败节点的错误描述 |

### 2.4 node_name 枚举

| node_name | node_type | 说明 |
|-----------|-----------|------|
| `memory_load` | storage | 读取会话上下文 |
| `planner_llm` | llm | Planner 模型生成 RequestPlan |
| `followup_plan_resolve` | code | 追问场景的确定性计划解析 |
| `plan_goal_alignment` | code | 目标—计划一致性校验 |
| `plan_alignment_review_llm` | llm | 复杂口径目标的 LLM 审核 |
| `plan_replan` | code | 重新规划业务目标 |
| `plan_alignment_revalidate` | code | 复核替代计划一致性 |
| `plan_alignment_deterministic_fallback` | code | 生成受控修正计划 |
| `plan_compile` | code | 编译 RequestPlan → CompiledPlanIR |
| `plan_validate` | code | 校验编译后计划 |
| `failure_router` | code | 统一失败分类 |
| `state_controller` | code | 选择下一业务能力 |
| `deterministic_tool_dispatch` | code | 编译确定性工具调用参数 |
| `tool_result` | tool | 工具执行结果 |
| `plan_verify` | code | 验证证据完整性（事实—证据闭环） |
| `final_answer_llm` | llm | 生成 LLM 最终回答 |
| `prepared_sql_answer` | code | 生成确定性受控 SQL 回答 |
| `implementation_validation_answer` | code | 生成实施验收回答 |
| `caliber_simulation_answer` | code | 生成候选口径模拟回答 |
| `difference_diagnosis_layer_1` | code | 差异诊断—范围预检 |
| `difference_diagnosis_layer_2` | code | 差异诊断—实时结构核验 |
| `difference_diagnosis_layer_3` | code | 差异诊断—执行当前口径 |
| `difference_diagnosis_layer_4` | code | 差异诊断—试运行候选口径 |
| `difference_diagnosis_layer_5` | code | 差异诊断—核对记录集合 |
| `difference_diagnosis_layer_6` | code | 差异诊断—检查数据质量 |
| `difference_diagnosis_conclusion` | code | 差异诊断—生成结论 |
| `difference_diagnosis_answer` | code | 差异诊断—整理回答 |
| `response_guard` | code | 安全回答协议检查 |
| `memory_save` | storage | 保存会话上下文 |
| `compound_split` | code | 拆分复合指标请求 |
| `compound_subtask` | code | 执行指标子任务 |
| `compound_merge` | code | 按输入顺序合并结果 |
| `implementation_validation_*` | code | 实施验收各阶段（字段映射、SQL 生成等） |

---

## 3. model_start — 模型开始推理

LLM 推理调用前发出，用于前端展示"模型处理中"状态。

```json
{
  "event": "model_start",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "message": "规划业务目标",
    "step": 0
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| message | String | 是 | 模型正在执行的阶段描述，如 `"规划业务目标"`、`"生成最终回答"` |
| step | Integer | 否 | 当前步骤序号（0 表示初始化阶段） |

---

## 4. tool_call — 工具开始调用

每个受控业务工具执行前发出。

```json
{
  "event": "tool_call",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 1,
    "tool_name": "search_indicator_rules",
    "status": "running"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| step | Integer | 是 | 当前步骤序号（从 1 开始） |
| tool_name | String | 是 | 工具名称，见下方工具枚举表 |
| status | String | 是 | 固定值 `"running"` |

### 4.1 工具名称枚举

| tool_name | 说明 |
|-----------|------|
| `search_indicator_rules` | 搜索相关指标 |
| `get_effective_rule` | 读取本院生效口径 |
| `inspect_indicator_implementation` | 检查字段与实施状态 |
| `prepare_indicator_sql` | 生成并校验受控 SQL |
| `trial_run_indicator_sql` | 执行只读试运行 |
| `resolve_indicator_caliber` | 解析候选口径 |
| `prepare_indicator_caliber_sql` | 准备候选口径 SQL |
| `trial_run_indicator_caliber_sql` | 试运行候选口径 |
| `diagnose_indicator_issue` | 分析指标异常 |
| `diagnose_indicator_difference` | 执行指标差异分层诊断 |
| `create_indicator_draft` | 生成指标工作草稿 |
| `preview_rule_change` | 预览本院口径变化 |
| `analyze_uploaded_indicators` | 分析上传的指标文件 |

---

## 5. tool_result — 工具执行结果

每个工具执行完成后发出。

```json
{
  "event": "tool_result",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 1,
    "tool_name": "search_indicator_rules",
    "status": "success",
    "code": "RULE_SEARCHED",
    "message": "找到 1 个匹配指标",
    "retryable": false,
    "reused": false,
    "duration_ms": 150
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| step | Integer | 是 | 步骤序号 |
| tool_name | String | 是 | 工具名称 |
| status | String | 是 | `"success"` \| `"preview_ready"` \| `"failed"` |
| code | String | 是 | 工具业务结果码，见下方常见结果码表 |
| message | String | 是 | 工具执行摘要 |
| retryable | Boolean | 是 | 失败时是否可重试 |
| reused | Boolean | 是 | 是否复用本轮已有结果（缓存命中） |
| duration_ms | Long | 是 | 工具执行耗时（毫秒） |

### 5.1 常见工具结果码

| code | 工具 | 说明 |
|------|------|------|
| `RULE_SEARCHED` | search_indicator_rules | 指标匹配成功 |
| `RULE_EFFECTIVE_LOADED` | get_effective_rule | 生效口径加载完成 |
| `IMPLEMENTATION_INSPECTED` | inspect_indicator_implementation | 实施状态检查完成 |
| `SQL_OBJECT_PREPARED` | prepare_indicator_sql | SQL 生成成功 |
| `TRIAL_RUN_COMPLETED` | trial_run_indicator_sql | 试运行完成 |
| `CALIBER_RESOLVED` | resolve_indicator_caliber | 候选口径解析完成 |
| `CALIBER_SQL_PREPARED` | prepare_indicator_caliber_sql | 候选口径 SQL 准备完成 |
| `DIAGNOSIS_REPORT_COMPLETED` | diagnose_indicator_issue | 异常诊断完成 |
| `DIFFERENCE_DIAGNOSIS_COMPLETED` | diagnose_indicator_difference | 差异分层诊断完成 |
| `UPLOAD_ANALYSIS_COMPLETED` | analyze_uploaded_indicators | 上传文件分析完成 |
| `IMPLEMENTATION_VALIDATION_COMPLETED` | implement_validation | 实施验收完成 |
| `FIELD_MAPPING_INCOMPLETE` | prepare_indicator_sql | 字段映射缺失（工具失败） |
| `DBHUB_TIMEOUT` | 多个 | DBHub 查询超时 |
| `NO_RULE_MATCHED` | search_indicator_rules | 未找到匹配指标 |
| `SQL_VALIDATION_FAILED` | prepare_indicator_sql | SQL 安全校验失败 |

---

## 6. stage_update — 实时阶段进度

业务节点推进时发出的轻量事件，用于前端实时进度条/阶段指示器。

```json
{
  "event": "stage_update",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "node_name": "planner_llm",
    "node_type": "llm",
    "status": "success",
    "duration_ms": 1200,
    "message": "规划业务目标"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| node_name | String | 是 | 节点名称，对应 trace_node 的 node_name |
| node_type | String | 是 | `"llm"` \| `"code"` \| `"tool"` \| `"storage"` \| `"database"` |
| status | String | 是 | `"success"` \| `"warning"` \| `"failed"` \| `"running"` |
| duration_ms | Long | 否 | 节点耗时 |
| message | String | 否 | 前端展示的状态文本 |

---

## 7. clarification_required — 需要用户介入

Agent 遇到指标不明确、时间缺失或口径歧义时暂停执行，要求用户选择后继续。

```json
{
  "event": "clarification_required",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 2,
    "message": "无法确定您指的是哪一个指标。",
    "code": "AMBIGUOUS_INDICATOR",
    "fallback_category": "AMBIGUOUS_INDICATOR",
    "stop_reason": "clarification",
    "clarification": {
      "code": "AMBIGUOUS_INDICATOR",
      "kind": "indicator_disambiguation",
      "title": "请确认需要查询的指标",
      "question": "您想查询以下哪一个指标？",
      "help_text": "请从以下选项中选择一个指标。",
      "selection_mode": "single",
      "options": [
        {
          "id": "MQSI2025_001",
          "label": "患者入院 48小时内转科的比例",
          "value": "MQSI2025_001",
          "description": "三级查房制度 · 公司标准",
          "group": ""
        },
        {
          "id": "MQSI2025_002",
          "label": "患者入院 8 小时内查房率",
          "value": "MQSI2025_002",
          "description": "三级查房制度 · 公司标准",
          "group": ""
        }
      ],
      "allow_free_text": false,
      "free_text_placeholder": "",
      "resume_prefix": "继续处理上一条请求。补充信息："
    }
  }
}
```

### 7.1 AgentClarification 对象

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 业务澄清码，如 `"AMBIGUOUS_INDICATOR"` |
| kind | String | 是 | 澄清类别：`"indicator_disambiguation"` \| `"time_clarification"` \| `"caliber_disambiguation"` \| `"missing_goal"` |
| title | String | 是 | 弹窗标题 |
| question | String | 是 | 主问题文本 |
| help_text | String | 是 | 辅助提示文本 |
| selection_mode | String | 是 | `"single"`（单选）\| `"multiple"`（多选） |
| options | Option[] | 是 | 可选选项列表 |
| allow_free_text | Boolean | 是 | 是否允许自由输入 |
| free_text_placeholder | String | 是 | 自由输入框占位文字 |
| resume_prefix | String | 是 | 用户选择后系统拼接到下一轮请求开头的文字前缀 |

### 7.2 Option 对象

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | String | 是 | 选项唯一编号（如 `rule_id`） |
| label | String | 是 | 展示名称 |
| value | String | 是 | 选项值（业务表达，非 SQL 或工具参数） |
| description | String | 是 | 辅助说明（可为空） |
| group | String | 是 | 分组名称（可为空） |

### 7.3 前端处理方式

用户选择后，前端将 `resume_prefix + value1、value2、…` 拼接为新的 `query`，作为下一轮 `POST /api/agent/chat/stream` 的请求体重新发送，**不能绕过 Planner、IR 或 Evidence 链**。

```typescript
// 示例：用户选择了 MQSI2025_001
const continuation = `${clarification.resumePrefix}${values.join('、')}`
// 结果："继续处理上一条请求。补充信息：MQSI2025_001"
```

---

## 8. assistant_message — 最终回答

Agent 完成所有工具调用和证据校验后发出的最终回答内容。

```json
{
  "event": "assistant_message",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 6,
    "message": "**结论速览**\n\n患者入院 48 小时内转科的比例为 **2.81%**。\n\n---\n\n**数据表**\n\n| 项目 | 数值 |\n|------|------|\n| 统计区间 | 2026-01-01 ~ 2026-07-24 |\n| 分母（入院患者总数） | 356 |\n| 分子（48h 内转科患者数） | 10 |\n| 指标结果 | 2.81% |\n\n---\n\n**口径依据**\n\n- 定义来源：MQSI2025_001 公司标准\n- 生效版本：hospital_001 v3\n- 试运行 SQL 编号：RUN_a1b2c3d4e5f6\n",
    "status": "completed"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| step | Integer | 是 | 步骤序号 |
| message | String | 是 | Markdown 格式的回答内容（不执行 HTML/脚本） |
| status | String | 是 | `"completed"` |

### 8.1 特殊标记

回答正文中可能包含以下模板标记，前端应识别并替换为对应的交互组件：

| 标记 | 说明 | 前端处理 |
|------|------|----------|
| `{{detail_export:RUN_xxx}}` | 明细导出入口 | 展示"导出明细"按钮，点击调用明细导出 API |
| `{{upload_comparison_export:RUN_xxx:TOKEN}}` | 上传文件对比导出 | 展示"导出对比结果"按钮 |
| `{{diagnosis_export:DDR_xxx}}` | 诊断报告导出 | 展示"导出诊断报告"按钮 |

---

## 9. agent_done — 本轮完成

Agent 正常结束时发出。

### 9.1 正常完成

```json
{
  "event": "agent_done",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 6,
    "stop_reason": "final_answer",
    "status": "completed",
    "step_count": 6
  }
}
```

### 9.2 澄清暂停

```json
{
  "event": "agent_done",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 2,
    "stop_reason": "clarification",
    "status": "incomplete",
    "step_count": 2
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| step | Integer | 是 | 步骤序号 |
| stop_reason | String | 是 | `"final_answer"`（正常完成）\| `"clarification"`（等待用户选择） |
| status | String | 是 | `"completed"` \| `"incomplete"` |
| step_count | Integer | 是 | 本轮总步骤数 |

---

## 10. agent_error — 运行失败

Agent 因工具失败、模型异常等不可恢复错误终止时发出。

```json
{
  "event": "agent_error",
  "data": {
    "trace_id": "TRACE_a1b2c3d4e5f6",
    "step": 3,
    "message": "DBHub 查询超时，请稍后重试",
    "failure_code": "DBHUB_TIMEOUT",
    "stop_reason": "tool_error",
    "status": "failed"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| trace_id | String | 是 | 链路编号 |
| step | Integer | 是 | 步骤序号 |
| message | String | 是 | 面向用户的错误描述 |
| failure_code | String | 是 | 统一失败分类码 |
| stop_reason | String | 是 | 固定值 `"tool_error"` |
| status | String | 是 | 固定值 `"failed"` |

### 10.1 常见 failure_code

| failure_code | 说明 |
|--------------|------|
| `DBHUB_TIMEOUT` | DBHub 查询超时 |
| `DBHUB_CONNECTION_FAILED` | DBHub 连接失败 |
| `SQL_VALIDATION_FAILED` | SQL 安全校验失败 |
| `FIELD_MAPPING_INCOMPLETE` | 字段映射缺失 |
| `PLANNER_FAILED` | Planner 模型调用失败 |
| `NO_RULE_MATCHED` | 未找到匹配指标 |
| `AMBIGUOUS_INDICATOR` | 指标歧义需澄清 |
| `MISSING_TIME_RANGE` | 缺少时间范围 |
| `RUNTIME_ERROR` | 运行时内部异常 |

---

## 完整 SSE 事件流示例

以下是一次"查询患者入院48小时内转科比例"请求的完整 SSE 事件流：

```
event: agent_start
data: {"trace_id":"TRACE_a1b2c3","status":"running"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_001","node_name":"memory_load","node_type":"storage","status":"success","duration_ms":5,...}

event: model_start
data: {"trace_id":"TRACE_a1b2c3","message":"规划业务目标"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_002","node_name":"planner_llm","node_type":"llm","status":"success","duration_ms":1200,"model_id":"qwen-plus",...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_003","node_name":"plan_goal_alignment","node_type":"code","status":"success","duration_ms":2,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_004","node_name":"plan_compile","node_type":"code","status":"success","duration_ms":5,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_005","node_name":"plan_validate","node_type":"code","status":"success","duration_ms":3,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_006","node_name":"state_controller","node_type":"code","status":"success","duration_ms":1,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_007","node_name":"deterministic_tool_dispatch","node_type":"code","status":"success","duration_ms":2,...}

event: tool_call
data: {"trace_id":"TRACE_a1b2c3","step":1,"tool_name":"get_effective_rule","status":"running"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_008","node_name":"tool_result","node_type":"tool","status":"success","duration_ms":45,...}

event: tool_result
data: {"trace_id":"TRACE_a1b2c3","step":1,"tool_name":"get_effective_rule","status":"success","code":"RULE_EFFECTIVE_LOADED","message":"加载完成","duration_ms":45,...}

event: tool_call
data: {"trace_id":"TRACE_a1b2c3","step":2,"tool_name":"prepare_indicator_sql","status":"running"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_009","node_name":"tool_result","node_type":"tool","status":"success","duration_ms":230,...}

event: tool_result
data: {"trace_id":"TRACE_a1b2c3","step":2,"tool_name":"prepare_indicator_sql","status":"success","code":"SQL_OBJECT_PREPARED","duration_ms":230,...}

event: tool_call
data: {"trace_id":"TRACE_a1b2c3","step":3,"tool_name":"trial_run_indicator_sql","status":"running"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_010","node_name":"tool_result","node_type":"tool","status":"success","duration_ms":890,...}

event: tool_result
data: {"trace_id":"TRACE_a1b2c3","step":3,"tool_name":"trial_run_indicator_sql","status":"success","code":"TRIAL_RUN_COMPLETED","duration_ms":890,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_011","node_name":"plan_verify","node_type":"code","status":"success","duration_ms":5,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_012","node_name":"prepared_sql_answer","node_type":"code","status":"success","duration_ms":10,...}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_013","node_name":"response_guard","node_type":"code","status":"success","duration_ms":2,...}

event: assistant_message
data: {"trace_id":"TRACE_a1b2c3","step":3,"message":"**结论速览**\n\n...","status":"completed"}

event: trace_node
data: {"event":"trace_node","trace_id":"TRACE_a1b2c3","node_id":"NODE_014","node_name":"memory_save","node_type":"storage","status":"success","duration_ms":8,...}

event: agent_done
data: {"trace_id":"TRACE_a1b2c3","step":3,"stop_reason":"final_answer","status":"completed","step_count":3}
```

---

## 前端 SSE 解析建议

```typescript
function parseSseBlock(block: string): AgentEvent | null {
  let eventName = 'message'
  const data: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  const payload = JSON.parse(data.join('\n'))
  payload.event = eventName
  return payload
}

// SSE 流读取
const reader = response.body.getReader()
const decoder = new TextDecoder('utf-8')
let buffer = ''
while (true) {
  const chunk = await reader.read()
  if (chunk.done) break
  buffer += decoder.decode(chunk.value, { stream: true })
  const blocks = buffer.split('\n\n')
  buffer = blocks.pop() || ''
  for (const block of blocks) {
    const event = parseSseBlock(block)
    if (event) handleEvent(event)
  }
}
```

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-24 | 初始版本，覆盖 10 种事件类型 |
