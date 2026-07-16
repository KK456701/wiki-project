# Agent 计划编译与受控执行设计

## 目标

在保留现有工具安全边界、确定性 SQL 和证据守卫的基础上，将工具调用型 Agent 从“模型自由选择全部工具”调整为“模型理解目标、服务端编译计划、控制器约束执行、Verifier 确定性验收”。重点提升 4B 本地模型在多轮指标查询、统计周期变更和试运行场景中的稳定性。

## 核心原则

1. Planner 不输出工具名，也不输出 `proposed_steps`。
2. Planner 只输出用户目标、目标指标、原始时间表达、请求输出、约束和语义歧义。
3. PlanValidator 校验 Planner 结构、权限和目标冲突。
4. PlanCompiler 根据业务能力依赖生成正式计划和强制验收条件。
5. StateController 根据正式计划和当前事实决定下一项能力，仅开放对应的一个或两个工具。
6. ToolRouter 将业务能力映射到实际工具；工具重命名不影响 Planner 合约。
7. Verifier 负责状态、证据、数值和 SQL 链路的一致性校验。
8. Replanner 只处理计划方向错误，默认最多一次；参数缺失和前置步骤缺失由控制器补齐或澄清。
9. 未授权患者明细直接进入安全拒绝，不允许通过人工兜底绕过。

## Planner 合约

Planner 输出 `RequestPlan`：

```json
{
  "intent": "indicator_trial_run",
  "goal": "计算急会诊及时到位率本月结果",
  "target_indicator": {
    "raw_name": "急会诊及时到位率",
    "rule_id": null
  },
  "time_expression": {
    "raw_text": "这个月",
    "start_time": null,
    "end_time": null
  },
  "requested_outputs": ["trial_result", "explanation"],
  "constraints": [],
  "semantic_ambiguities": []
}
```

允许的意图：

- `general_chat`
- `rule_explanation`
- `indicator_trial_run`
- `indicator_diagnosis`
- `rule_change_preview`
- `upload_analysis`
- `unknown`

允许的输出：

- `definition`
- `formula`
- `implementation_status`
- `prepared_sql_handle`
- `trial_result`
- `diagnosis`
- `change_preview`
- `file_analysis`
- `explanation`

Planner 合约中不存在工具名称和业务步骤字段，额外字段一律拒绝。

## 正式计划

PlanCompiler 将 `RequestPlan` 编译为 `CompiledPlan`。正式计划节点使用业务能力，不使用工具名：

- `resolve_indicator`
- `resolve_effective_rule`
- `resolve_time_range`
- `inspect_implementation`
- `prepare_verified_sql`
- `execute_trial_run`
- `diagnose_indicator`
- `preview_rule_change`
- `analyze_uploaded_file`
- `compose_answer`

能力依赖由服务端维护。例如 `execute_trial_run` 依赖 `prepare_verified_sql`，而后者依赖 `resolve_effective_rule` 和 `resolve_time_range`。编译器从请求输出反向展开依赖，并注入强制验收条件。

`prepared_sql_handle` 表示受控 `sql_id`，不得解释为 SQL 文本。

## 工具绑定

ToolRouter 维护能力到工具的绑定：

- `resolve_indicator` → `search_indicator_rules`
- `resolve_effective_rule` → `get_effective_rule`
- `inspect_implementation` → `inspect_indicator_implementation`
- `prepare_verified_sql` → `prepare_indicator_sql`
- `execute_trial_run` → `trial_run_indicator_sql`
- `diagnose_indicator` → `diagnose_indicator_issue`
- `preview_rule_change` → `preview_rule_change`
- `analyze_uploaded_file` → `analyze_uploaded_indicators`

绑定是条件式的：若会话已有可信 `rule_id`，控制器跳过搜索但重新读取最新生效规则；若指标存在多个候选，则停止执行并要求用户澄清。

## 时间解析

服务端解析并校验常见相对时间表达，Planner 保留原始文本。第一阶段支持：

- 本月、这个月
- 上月、上个月
- 今年、今年至今
- 今年 1 月到现在等月份范围
- 明确的 YYYY-MM-DD 起止日期

正式统计周期采用左闭右开区间，并携带时区和业务时间字段语义。无法唯一确定时进入 `USER_CLARIFICATION`。

## 执行控制

控制器根据计划节点的 `requires` 和当前事实选择首个未完成节点。每一步只向模型暴露该能力绑定的工具；确实存在等价合法动作时最多暴露两个工具。普通问候和已经具备全部验收事实的请求不暴露工具。

现有 AgentRunner 循环继续存在，但不再允许模型从完整工具目录自由路由。模型负责填写当前能力所需的语义参数和组织最终回答。

## Verifier

Verifier 检查四类内容：

1. 状态完整性：规则、版本、统计周期、字段映射、SQL、试运行结果是否满足计划验收条件。
2. 证据完整性：每个完成事实必须能追溯到医院、规则、SQL、结果、Trace 和上下文摘要。
3. 数值一致性：使用 `Decimal` 按分子、分母重新计算百分比；处理分母为零、百分比单位和舍入容差。
4. SQL 链路一致性：生成、校验和试运行的 `sql_id`、医院、数据源、规则、时间和 `context_digest` 必须一致。

验证失败时禁止生成完成性答案。

## 失败、重规划与人工分类

补充步骤或澄清不触发 Replanner。Replanner 仅用于：原意图根本误解、用户中途改变主要目标、工具事实证明任务类型错误、原假设失效但存在替代路径。默认 `max_replan_count = 1`。

重新规划保留原计划、失败代码、失败原因、已验证事实、已完成能力、失败能力和禁止重复的计划指纹。

人工/终止分类：

- `USER_CLARIFICATION`
- `BUSINESS_CONFIRMATION`
- `ADMIN_APPROVAL`
- `IMPLEMENTATION_SUPPORT`
- `SYSTEM_OPERATOR`
- `SECURITY_DENIAL`

数据库不可用且无备用数据源时进入 `SYSTEM_OPERATOR`，不进行无意义重规划。

## 兼容与上线

新编排通过配置开关启用。直接构造 `AgentRunner` 的现有测试和 Shadow 流程保持旧行为；正式 `AgentRuntimeService` 在工具调用模式下启用计划编译。能力接口增加编排模式标识，便于前端和回归测试确认。

## 验收标准

1. Planner 合约拒绝 `steps`、`proposed_steps` 和具体工具名字段。
2. 试运行请求自动编译规则解析、时间解析、SQL 准备和试运行能力依赖。
3. 当前状态每步最多暴露两个工具，标准链路通常只暴露一个。
4. 规则已确认时不重复搜索；统计周期改变时重新准备 SQL。
5. 数值和 SQL 链路不一致时 Verifier 阻止最终回答。
6. 参数缺失不触发 Replanner；计划方向错误最多重规划一次。
7. 人工分类和安全拒绝具有确定性代码。
8. 现有 Agent API、SSE、模型选择、Excel 上传和旧稳定流程保持兼容。
