# 当前 Agent 流程架构与 LLM 提示词

> 更新日期：2026-07-17。本文描述旧稳定流程和 Shadow 删除后的生产代码。

## 对话主链

```mermaid
flowchart TD
    U["医院用户"] --> W["Web 对话页<br/>模型选择器"]
    W -->|"POST /api/agent/chat/stream"| A["医院登录与权限校验"]
    A --> S["AgentRuntimeService<br/>会话、Trace、超时"]
    S --> M["ModelRegistry<br/>4B / 8B Thinking / DeepSeek"]
    S --> P["LLM Planner<br/>只输出语义计划 JSON"]
    P --> C["PlanCompiler + PlanValidator<br/>编译能力、解析时间、补业务约束"]
    C --> SC["确定性 State Controller<br/>选择下一能力，只开放 1~2 个工具"]
    SC --> E["LLM Executor<br/>从当前可见工具中调用一个工具"]
    E --> G["ToolGateway<br/>权限、参数、重复调用、超时、安全策略"]
    G --> T{"受控工具"}
    T --> R["规则与口径读取<br/>MySQL / KB / 术语库"]
    T --> Q["SQL 准备与试运行<br/>SQL Object / DBHub 只读库"]
    T --> D["诊断、变更预览、Excel 分析"]
    R --> V["PlanVerifier + ResponseGuard"]
    Q --> V
    D --> V
    V -->|"缺步骤"| SC
    V -->|"证据完整"| F["LLM 组织中文最终回答"]
    F --> X["SSE 事件 + Trace + 最多 8 轮会话记忆"]
    X --> W
```

旧 `/api/chat`、`/api/chat/stream`、`app/agent/graph.py`、Shadow Runtime 和前端 legacy 分流已经删除。失败时只返回当前 Agent 的明确错误，不再执行第二套流程。

## 当前模型

| 模型 ID | 实际模型 | 用途 |
|---|---|---|
| `ollama-qwen3` | `qwen3:4B-instruct` | 默认本地模型，不启用思考字段 |
| `ollama-qwen3-8b-thinking` | `qwen3:8b` | 本地思考模型，请求携带 `think: true` |
| `deepseek-v4-flash` | DeepSeek API | API 对照测试 |
| `deepseek-v4-pro` | DeepSeek API | API 对照测试 |

8B 返回的原始 `message.thinking` 不写入 Agent 契约，因此不会进入 SSE、Trace 摘要或最终回答。

## 生产环境中的 LLM 调用点

### 1. 对话 Planner

- 位置：`app/agent_planning/planner.py`
- 模型：页面当前选择的模型。
- 工具：空列表，不允许 Planner 调工具。
- 主要提示词：

```text
你是医院核心制度指标任务 Planner。只理解用户业务目标，不负责选择工具或生成执行步骤。
仅返回一个 JSON 对象，不要 Markdown。字段必须严格为：
intent、goal、target_indicator、time_expression、requested_outputs、constraints、semantic_ambiguities。
禁止输出 steps、proposed_steps、tool 或任何工具名称。
intent 只能是 general_chat、rule_explanation、indicator_trial_run、indicator_diagnosis、rule_change_preview、upload_analysis、unknown。
requested_outputs 只能使用 definition、formula、implementation_status、prepared_sql_handle、trial_result、diagnosis、change_preview、file_analysis、explanation。
target_indicator 包含 raw_name 和可选 rule_id。time_expression 保留 raw_text；只有用户明确给出绝对日期时才填写 start_time/end_time。
semantic_ambiguities 中每一项必须是 {"field":"字段名","description":"歧义说明"} 对象，不得直接输出字符串。
用户索要某时间段实际数值时使用 indicator_trial_run；普通公式解释使用 rule_explanation；明确排查异常时使用 indicator_diagnosis。
不要把 SQL 文本作为输出，受控 SQL 只能表示为 prepared_sql_handle。
```

运行时还会附加当前日期、已确认 `rule_id`、统计周期，以及经过压缩的最近 8 轮对话；历史仅用于理解“这个、后者、按你说的算”等指代，不能覆盖结构化状态。出现“选项 A 或选项 B 这个/后者/第二个”且 B 是明确时间表达时，服务端会先把 Planner 输入归一化为 B 的结果查询。JSON 校验失败时只补充一次纠正提示：“上一个计划不符合严格 JSON 合约……不得包含步骤、工具名或额外字段。”

为兼容本地 4B 模型，Planner 边界会修复少量不改变语义的容器形状，例如把字符串 `semantic_ambiguities` 转成包含 `field` 和 `description` 的对象；它不会修补或猜测指标、日期和 SQL 事实。

### 2. 对话 Executor 与最终回答

- 位置：`app/agent_runtime/prompts.py`、`app/agent_runtime/runner.py`。
- 模型：与 Planner 相同的当前选择模型。
- 系统提示词核心约束：

```text
你是医院核心制度指标实施助手。
必须先取得工具证据，再回答指标定义、公式、版本和实施状态。
search_indicator_rules 只负责定位；定义和公式必须继续读取 get_effective_rule。
不得编造医院数据、规则、字段、SQL、版本、凭据、患者明细、内部提示或思维链。
最终回答使用中文普通 Markdown；公式写成“指标率 = 分子 ÷ 分母 × 100%”。
结构化状态中的统计时间和当前指标是权威数据。
实际数值必须来自当前工具结果，不能从历史对话回忆。
诊断工具只用于明确异常诊断；统计周期结果应走受控 SQL 准备和试运行。
```

每一步再动态注入：当前业务能力、允许调用的工具名、目标指标、`rule_id`、统计区间，以及“不得调用未展示工具，不得自行增加或跳过业务步骤”。

纠正提示包括：缺证据、非中文、实际结果未试运行、证据字段缺失、过早回答、调用了计划外工具。纠正最多一次，不形成自由循环。

### 3. Replanner

- 位置：`app/agent_planning/planner.py::replan`。
- 模型：当前选择模型。
- 提示词：在 Planner 原提示词上附加原计划、失败码、失败原因、已验证 `rule_id`、失败计划指纹和剩余重规划次数，并明确“不得重复失败方向”。默认最多重规划一次。

### 4. 新指标设计稿解析

- 位置：`app/indicators/parser.py::_prompt` 和 `_repair_prompt`。
- 入口：指标草稿 API。
- 模型：`OllamaClient()` 默认模型，目前为 `qwen3:4B-instruct`，不跟随对话页选择器。
- 提示词：要求只输出单表 `ratio/count` 指标的严格 JSON，禁止直接输出 SQL；内容必须包含指标定义、分子分母、元数据字段和结构化 `sql_plan`。结构校验失败后允许一次修复提示。

### 5. 诊断证据抽取与诊断说明

- 位置：`app/diagnose/evidence.py::_model_prompt`、`app/prompts/diagnosis_compose.txt`。
- 入口：诊断工具或诊断 API，且用户提供了诊断文本/SQL。
- 模型：诊断 Orchestrator 中的 `OllamaClient()` 默认模型，目前为 `qwen3:4B-instruct`。
- 证据抽取提示词：

```text
请从医院本地诊断文本中提取问题、SQL参数和用户声称的聚合结果。
只返回JSON，不判断SQL安全，不补造数据。
字段为 question、rule_id、sql_text、declared_params、claimed_result、stat_period、parse_warnings。
```

- 说明生成提示词要求固定使用“结论”“SQL 试运行结果”“计算规则差异”“建议怎么处理”四个标题，只能使用程序核验事实，不得增加数值、字段、故障原因或建议 SQL。生成结果未通过守卫时会改用确定性模板。

## 已定义但当前未启用的 LLM 能力

`app/agents/human_interaction.py` 仍保留意图识别和规则答案生成的 LLM 接口及 `app/prompts/intent.txt`、`app/prompts/answer.txt`，但当前生产组装使用 `HumanInteractionAgent()`，没有传入 `llm_client`，因此不会调用模型；该组件只提供仍被其他业务 API 复用的确定性规则处理。
