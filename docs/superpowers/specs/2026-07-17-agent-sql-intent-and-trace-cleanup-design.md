# Agent SQL 意图与链路精简设计

## 目标

修复“只生成 SQL”被错误编译为“生成并试运行”的问题，让用户可以取得经过只读校验的 SQL 预览；同时改进试运行连接故障反馈和 Trace 可读性。

## 已确认根因

1. Planner 对“SQL 怎么写”可能输出 `rule_explanation`，但 `requested_outputs` 已包含 `prepared_sql_handle`；业务输出比模型意图更具体。
2. `PlanCompiler` 当前只要 `intent=indicator_trial_run` 就强制加入 `execute_trial_run`，即使模型同时明确只请求 `prepared_sql_handle`，导致确定性代码放大模型分类偏差。
3. `PlanValidator` 把“需要统计时间”和“必须执行业务库”混为一体，无法清楚区分 SQL 准备与试运行。
4. 本次试运行日志中的实际错误为 DBHub 连接中断 `Connection lost - socket hang up`，但工具结果只返回通用失败文案。
5. Trace 将用户澄清类 fallback 标为失败，`tool_result` 事件也没有携带本次工具参数。
6. 前端“开发与排障”区块与完整节点数据重复。

## 方案

### 计划语义

- 新增业务意图 `indicator_sql_prepare`，用于“生成 SQL”“SQL 怎么写”“先写出来但不要运行”。
- 是否试运行以 `requested_outputs` 为最终依据：只有包含 `trial_result` 才编译 `execute_trial_run`。
- 包含 `prepared_sql_handle` 或 `trial_result` 时都必须解析统计时间；只有 `trial_result` 才属于业务库执行。
- Planner 提示明确区分 SQL 准备与实际结果，降低不同模型的分类漂移；确定性编译器仍作为最终安全边界。

### SQL 输出与执行

- `prepare_indicator_sql` 在 SQL 完成字段预检、只读安全校验并保存受控对象后，返回 `sql_preview` 和命名参数；不执行数据库。
- Executor 只能逐字引用工具返回的 `sql_preview`，不得自行改写或编造。
- `trial_run_indicator_sql` 仅在明确请求 `trial_result` 时开放。
- 对 DBHub 连接中断类错误自动重试一次；仍失败时返回安全的“业务数据源连接中断”原因、`run_id` 和 `sql_id`，不暴露连接串或底层堆栈。

### Trace 与界面

- 用户澄清、业务确认类 fallback 记录为 `warning`，界面显示“需关注/待确认”，不显示失败。
- ToolGateway 在 `tool_result` 内部事件中附带与对应调用相同的完整安全参数，Trace 结果节点可以还原实际输入。
- 删除“开发与排障”区块；保留处理结果和四类完整节点数据。

## 安全边界

- SQL 预览必须来自已验证 SQL 对象，不允许模型直接生成。
- SQL 仍保持只读、聚合、医院隔离、超时和字段预检。
- 公开 SSE 不返回工具参数或 SQL；完整内容只通过现有认证 Trace 接口和最终聊天回答提供。
- 不返回患者行级数据、连接串、令牌、密码或隐藏思维链。

## 验收标准

1. “SQL 怎么写”在缺时间时只澄清时间，Trace 不标失败。
2. 给出时间后生成并展示已验证 SQL，不调用 `trial_run_indicator_sql`。
3. “查询从 1 月到现在的结果”仍执行试运行。
4. “不用运行先写出来”即使模型错误给出 `indicator_trial_run`，编译器也不会加入试运行步骤。
5. Tool result Trace 包含完整安全工具参数。
6. DBHub 连接中断自动重试一次，失败文案包含安全原因和可追踪编号。
7. 前端不再显示“开发与排障”。

