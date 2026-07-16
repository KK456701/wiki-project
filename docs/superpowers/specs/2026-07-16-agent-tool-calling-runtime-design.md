# 核心制度指标工具调用型 Agent Runtime 设计

## 1. 背景

当前系统已经具备指标规则检索、本院生效口径合成、字段映射、元数据预检查、确定性 SQL 生成、只读试运行、指标诊断、指标设计稿、审批、版本和 Trace 等能力，但主对话入口仍采用固定流程：

```text
用户输入
→ 意图分类
→ 固定枚举路由
→ 固定节点流程
→ LLM 组织回答或补充少量结构化字段
```

该模式能够保证稳定性，但存在以下限制：

1. 用户需要使用接近预设意图的表达才能命中能力。
2. 一个问题同时包含规则查询、SQL 生成、试运行和诊断时，单一意图难以表达完整目标。
3. 模型没有根据工具观察结果继续决策的机会。
4. 新增能力需要继续增加意图、分支和固定节点，`app/agent/graph.py` 已增长到两千余行。
5. 当前所谓多个 Agent 主要是确定性领域服务的包装，不是独立的“模型—工具—观察—继续决策”循环。

本设计在保留现有确定性服务和旧对话入口的前提下，新增一个工具调用型主 Agent。模型负责理解目标和选择下一步，工具负责调用现有服务，服务端策略负责安全与权限，人工继续负责高风险业务决策。

## 2. 已确认目标范围

新 Agent 面向“核心制度指标与医院实施业务域内的任意自然语言问法”，不是开放式百科助手。

在该业务域内，用户无需记忆“生成 SQL”“试运行”“诊断”等固定命令。Agent 应能根据当前输入、历史对话和结构化会话状态，自主选择以下行为：

- 直接回答不依赖医院事实的操作说明；
- 调用一个只读工具；
- 连续调用多个工具；
- 根据工具结果改变后续动作；
- 请求用户澄清关键歧义；
- 生成指标草稿或口径差异预览；
- 证据不足时明确说明无法确认。

例如以下复合问题应当由一次请求完成：

```text
帮我看看急会诊及时到位率现在按什么口径，
生成本月 SQL，能运行的话顺便算一下，再解释为什么比上月低。
```

模型可以依次获取指标、生效规则、准备 SQL、只读试运行和诊断，不要求用户拆成多轮固定指令。

## 3. 非目标

首期不做以下事项：

- 不删除或重写旧 `/api/chat`、`/api/chat/stream` 和 `app/agent/graph.py`。
- 不把现有确定性业务规则改写成 Prompt。
- 不提供模型可见的任意 SQL、任意表或通用 DBHub 调用工具。
- 不让模型自动提交、批准、发布或恢复医院正式口径。
- 不把多个现有领域服务立即升级为多个自主模型 Agent。
- 不保证回答核心制度指标和医院实施业务域之外的问题。
- 不在首期重写整个前端。
- 不在首期删除旧意图识别、旧工作流 YAML 或 `CoreIndicatorOrchestrator`。

## 4. 方案选择

### 4.1 采用方案

采用：

```text
一个主 Agent
+ 动态暴露的受控工具
+ 现有确定性领域服务
+ 服务端证据与安全约束
+ 人工高风险决策
```

模型不再先输出一个固定 `intent` 再进入固定分支，而是在每轮根据用户目标、会话状态、可用工具和最新观察结果决定下一步。

### 4.2 未采用方案

不采用“4B 模型一次看到十几个细粒度工具”的直接方案。虽然实现简单，但本机实际探针显示，`qwen3:4b-instruct` 能调用简单天气工具，却没有稳定地为“急会诊及时到位率怎么算”选择指标搜索工具。首期应降低选择复杂度，并用业务评测决定是否升级模型。

不采用“直接切换大模型并暴露大量原子工具”的方案。模型升级可以提高规划能力，但不能替代权限、SQL 安全、租户隔离和证据约束；工具层仍必须先建设好。

不采用“多模型 Agent 分工”的方案。首期工具数量、上下文和任务边界尚未证明单 Agent 无法承担，多 Agent 会增加状态一致性、可观测性和故障定位成本。

## 5. 总体架构

```text
浏览器
→ FastAPI Agent API / SSE
→ 服务端认证与 AgentRuntimeContext
→ AgentRunner
→ AgentModelAdapter
→ ToolRegistry 动态工具集
→ ToolGateway
→ Agent Tools
→ 现有确定性领域服务
→ MySQL / Wiki / DBHub / SQL Server
→ ToolResult Observation
→ AgentRunner 继续决策或生成答案
```

建议新增：

```text
app/
├── agent_runtime/
│   ├── __init__.py
│   ├── contracts.py
│   ├── context_builder.py
│   ├── events.py
│   ├── model_adapter.py
│   ├── ollama_adapter.py
│   ├── response_guard.py
│   ├── runner.py
│   └── prompts/
│       └── system_prompt.txt
├── agent_tools/
│   ├── __init__.py
│   ├── contracts.py
│   ├── definitions.py
│   ├── registry.py
│   ├── gateway.py
│   ├── policy.py
│   ├── knowledge.py
│   ├── implementation.py
│   ├── sql.py
│   ├── diagnosis.py
│   └── drafts.py
└── api/
    └── agent_routes.py
```

使用 `app/agent_tools`，避免和仓库根目录下的 DBHub、WxP 工具目录以及现有 `KnowledgeBaseTools` 概念混淆。

职责边界：

- `agent_routes.py`：认证、请求解析、SSE 和 HTTP 错误转换，不实现领域逻辑。
- `runner.py`：执行模型—工具循环，不直接访问数据库。
- `ollama_adapter.py`：封装 Ollama `/api/chat`、tools Schema 和响应格式。
- `registry.py`：注册工具、动态筛选工具、生成模型 Schema。
- `gateway.py`：参数、权限、风险、超时、脱敏、幂等和 Trace。
- 工具包装：调用现有领域服务，不复制规则、SQL 或诊断实现。

## 6. 运行上下文与状态

### 6.1 AgentRuntimeContext

运行上下文完全由服务端认证和请求基础设施注入：

```python
class AgentRuntimeContext(BaseModel):
    user_id: str
    hospital_id: str
    session_id: str
    user_role: str
    permissions: frozenset[str]
    request_id: str
    trace_id: str
    db_source_id: str | None = None
```

以下内容不得成为模型可填写的工具参数：

- 用户、医院、角色和权限；
- 数据库连接串、账号和密码；
- DBHub 地址、工具名和内部密钥；
- 审批人身份；
- 跳过校验、跳过权限或切换租户标记。

工具输入模型使用 `extra="forbid"`。模型附加未声明字段时，Gateway 返回参数校验错误。

### 6.2 两层状态

不新建一套与现有 `ConversationContext` 重复的长期 Agent 状态。

本次运行使用 `AgentRunState`：

```text
messages
step_count
tool_call_fingerprints
tool_evidence
last_tool_results
stop_reason
cancelled
```

扩展现有 `ConversationContext`，长期保存：

- 当前指标和医院；
- 当前统计区间；
- 当前会话临时口径；
- 待澄清事项；
- 最近已校验 `sql_id`；
- 最近运行结果或诊断结果引用；
- 最近动作。

结构化状态是执行事实来源。最近原始消息只帮助模型理解“这个指标”“还是按刚才时间”等自然语言表达。

### 6.3 状态更新

只有经过类型校验和业务校验的工具结果可以更新结构化状态。模型输出文本不能直接修改执行状态。

状态保存继续使用现有 `context_version` 乐观锁。并发冲突时重新读取最新状态，只合并本轮已验证的状态变化，不覆盖其他请求的修改。

## 7. 工具契约

### 7.1 统一 ToolResult

```python
class ToolEvidence(BaseModel):
    source: str
    source_id: str | None = None
    version: str | None = None
    fact_types: list[str] = Field(default_factory=list)


class ToolResult(BaseModel):
    ok: bool
    status: Literal[
        "success",
        "not_found",
        "need_clarification",
        "preview_ready",
        "validation_failed",
        "forbidden",
        "unavailable",
        "timeout",
        "cancelled",
        "error",
    ]
    code: str
    summary: str
    data: dict[str, Any] = Field(default_factory=dict)
    evidence: list[ToolEvidence] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    retryable: bool = False
```

工具不得返回无法控制的超长字符串。SQL、元数据、明细和诊断大对象应返回对象 ID、摘要、计数和证据，需要完整内容时继续使用现有详情接口。

### 7.2 风险等级

首期使用三类风险：

| 风险 | 例子 | 执行策略 |
|---|---|---|
| `READ` | 查规则、版本、元数据、实施状态 | 权限检查后自动执行 |
| `CONTROLLED_EXECUTION` | 准备 SQL、只读试运行、诊断 | 前置状态和安全校验通过后执行 |
| `PREVIEW_ONLY` | 指标草稿、口径差异预览 | 只生成草稿或差异，不生效 |

首期不注册任何能够改变正式规则、审批、发布或版本状态的模型可见工具。`create_indicator_draft` 可以持久化不参与正式查询的工作草稿，`prepare_indicator_sql` 可以持久化短期 SQL 对象；两者都必须具备权限、租户、幂等和审计约束，且不能使口径生效。现有人工页面和审批接口继续承担正式写操作。

### 7.3 首期工具目录

首期注册八个工具，但根据权限和状态每轮最多向模型展示约六个：

#### `search_indicator_rules`

根据指标名称、简称、错别字、医学同义词或主题搜索指标。内部复用术语归一、`RuleRepository.search_for_hospital`、MySQL 优先和 Wiki 只读兜底。

#### `get_effective_rule`

获取当前医院指定指标的定义、公式、生效层级、国标版本、本院版本、覆盖字段和 SQL 可用状态。

#### `inspect_indicator_implementation`

查询字段映射、元数据快照、缺失字段、未确认映射、关联关系和实施状态，不读取患者业务数据。

#### `prepare_indicator_sql`

受控复合工具，内部确定性完成：

```text
读取生效规则
→ 合入结构化会话上下文
→ 元数据和字段映射预检查
→ 参数化 SQL 生成
→ 只读安全校验
→ 持久化 sql_id 和执行快照
```

模型不能提供模板、表名、数据库、连接信息或跳过校验参数。

#### `trial_run_indicator_sql`

只接受服务端 `sql_id`。在同一医院、未过期、已校验且上下文未失效时，通过 DBHub 只读试运行。向模型返回聚合结果和安全摘要，不返回患者明细。

#### `diagnose_indicator_issue`

复用现有粘贴 SQL 诊断、结构检查、国标与本院口径比较、数据质量检查和固定证据生成。用户 SQL 仍须经过现有安全边界。

#### `create_indicator_draft`

创建本院新增指标设计草稿，只返回分子、分母、统计周期、字段需求和缺失信息；不自动生成正式规则或发布。

#### `preview_rule_change`

对本院口径修改生成差异预览，说明影响分子、分母、字段、SQL 和版本的内容。首期不提交审批。

### 7.4 动态工具暴露

动态暴露只做能力和前置条件控制，不替模型做意图分类：

- 没有当前指标时，暴露搜索、草稿创建等工具。
- 已确定指标后，增加规则、实施状态、SQL 准备和诊断工具。
- 存在本院未过期且已校验 `sql_id` 时，才暴露试运行工具。
- 没有相应权限时，不暴露受限工具。
- 预览类工具只向允许参与指标实施的用户暴露。

即使工具被模型看到，Gateway 仍须再次执行完整校验。

## 8. ToolGateway

所有模型工具调用必须经过 Gateway，模型不能直接调用 handler。

Gateway 负责：

1. 工具名称白名单；
2. Pydantic 参数校验；
3. Runtime Context 注入；
4. 医院、用户和权限检查；
5. 风险与前置状态检查；
6. 超时和取消；
7. 异常标准化；
8. 参数和结果脱敏；
9. 幂等与重复调用控制；
10. Trace 记录；
11. ToolResult 标准化。

运行接口采用异步边界：

```python
async def execute(...) -> ToolResult:
    ...
```

现有同步领域服务通过受控线程执行。取消请求时，不再启动后续工具；无法中断的同步调用完成后丢弃结果，并将运行标记为取消。

## 9. AgentModelAdapter 与 Ollama

### 9.1 统一接口

```python
class AgentModelAdapter(Protocol):
    async def chat(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        temperature: float = 0.0,
    ) -> AgentModelResponse:
        ...
```

首个实现为 `OllamaToolCallingAdapter`，复用现有模型名、地址、超时和上下文配置，但改用 `/api/chat`。不复制第二套配置读取逻辑。

适配器负责：

- 转换 system、user、assistant 和 tool 消息；
- 生成 Ollama tools JSON Schema；
- 解析一个或多个 `tool_calls`；
- 规范化参数；
- 处理非法 JSON、超时和模型不支持 tools；
- 不向上层暴露 Ollama 私有响应结构。

### 9.2 模型健康检查

不能只读取模型 `capabilities`，也不能只用天气工具验证。管理健康检查必须运行项目实际探针：

```text
急会诊及时到位率怎么算？
→ search_indicator_rules
→ get_effective_rule
→ 中文回答
```

探针使用测试替身结果，不访问患者业务数据。

### 9.3 流式策略

首期工具决策轮使用非流式请求。SSE 实时发送模型开始、工具调用和工具结果事件。最终答案首期一次性发送，不为了伪造逐 token 效果重复调用模型。

后续如需完整流式工具调用，应按 Ollama 协议累积 `thinking`、`content` 和 `tool_calls` 后再继续循环，并单独设计与验证。

## 10. AgentRunner

运行流程：

```text
加载认证上下文和结构化会话状态
→ 构建受控模型上下文
→ 动态获取可用工具
→ 请求模型
→ 无工具调用：进入证据约束和最终回答
→ 有工具调用：Gateway 执行并返回 Observation
→ 更新本次证据与经过验证的结构化状态
→ 继续请求模型
→ 达到停止条件后保存运行结果
```

停止条件：

1. 模型输出最终回答；
2. 工具要求用户澄清；
3. 达到最大步骤；
4. 重复相同工具和参数；
5. 不可重试错误；
6. 请求超时；
7. 用户取消；
8. 状态版本冲突无法安全合并。

默认限制：

```text
最大模型步骤：8
单轮最大工具调用：3
同一工具相同参数：第一次允许，第二次提示，第三次终止
请求总超时：120 秒
工具超时：按工具配置
```

工具调用指纹由工具名和规范化参数计算。内部服务端上下文字段不参与模型参数指纹，但医院和会话会纳入幂等范围。

## 11. 模型上下文

模型上下文包含：

- Agent 系统规则；
- 最近必要对话；
- 当前结构化会话状态摘要；
- 本轮工具调用及脱敏结果摘要；
- 当前用户问题。

模型上下文不包含：

- 全部历史原文；
- 数据库连接、密码和令牌；
- DBHub 内部地址；
- 患者大批量明细；
- 内部堆栈和日志；
- 与当前问题无关的全部规则；
- 完整工具内部实现；
- 不必要的完整 SQL。

工具结果摘要必须保留规则版本、数据来源、结果 ID、警告和能够支持的事实类型。

## 12. SQL 生命周期

当前系统能够保存 `med_generated_sql`，但首期需要补充按 `sql_id + hospital_id` 安全读取和校验执行状态的服务。

服务端 SQL 对象至少绑定：

- `sql_id`；
- `hospital_id`；
- `rule_id`；
- 生效规则版本；
- 字段映射和元数据版本；
- 统计区间和参数快照；
- 结构化会话执行快照；
- 安全校验状态和时间；
- 创建用户；
- 默认 30 分钟的可配置过期时间。

试运行前重新检查：

1. 当前用户仍属于相同医院；
2. SQL 已校验且未过期；
3. SQL 对象和执行快照完整；
4. 规则、字段映射和必要参数未失效；
5. DBHub 数据源与医院绑定一致；
6. 查询仍通过业务库只读安全校验；
7. 超时和行数限制仍有效。

试运行工具不接受 `sql_text`。用户显式粘贴的 SQL 只进入现有诊断安全链，不转换为模型可自由执行的 SQL 对象。

## 13. 认证、权限与人工决策

新 Agent API 必须使用医院登录依赖获取 `HospitalPrincipal`。请求体不能继续像旧聊天接口一样自由指定本院事实查询所用 `hospital_id`。首期新 Agent API 不提供匿名模式；未登录请求直接返回认证错误，不进入模型和工具循环。非登录状态下的公开产品说明继续由现有静态页面或旧兼容入口承担。

以下能力都通过登录上下文执行：

- 本院生效口径；
- 本院字段与元数据；
- SQL 准备和试运行；
- 运行结果和诊断；
- 指标草稿和变更预览。

首期口径变更只生成预览，指标创建只生成草稿。正式提交、审批、发布和版本恢复继续在现有人工界面完成。

后续如需让 Agent 发起正式写入，应单独设计持久化 `PendingAgentAction`，由人工确认后再次校验权限、参数和版本；不得根据模型再次调用同一工具视为用户确认。

## 14. 最终回答证据约束

每项证据记录：

- 工具名称；
- 数据来源；
- 规则、字段或结果对象版本；
- 结果 ID；
- 可支持的事实类型。

回答前执行确定性约束：

- 没有规则证据，不输出具体指标定义或公式；
- 没有本院规则证据，不声称当前采用某个本院版本；
- 没有元数据证据，不声称医院存在某张表或字段；
- 没有 SQL 校验证据，不声称 SQL 可执行；
- 没有试运行或监控证据，不输出具体医院业务数值；
- 没有草稿或变更对象，不声称已创建或已提交；
- 首期任何情况下都不声称 Agent 已批准或发布正式口径。

`response_guard` 不尝试通过另一个模型全面审核自由文本。缺少所需证据时，直接阻止相应事实声明并使用确定性中文说明。

## 15. 错误处理

- 工具参数错误：返回结构化错误，允许模型修正一次。
- 多个规则候选：停止执行，请求用户澄清。
- 元数据或映射缺失：说明具体缺失项，引导进入实施工作台。
- DBHub 不可用：保留规则和 SQL 准备证据，明确试运行未完成。
- Ollama 不可用：新入口明确失败或回退旧流程，不伪造工具调用结果。
- 模型不支持 tools：能力检查失败，新入口不可启用，旧流程继续可用。
- 会话状态写入失败：不声称已记住指标、时间或临时口径。
- 达到最大步骤：输出已完成内容、未完成内容和下一步，不继续循环。
- SSE 断开或用户取消：终止后续步骤并记录 `cancelled`。

错误结果必须说明发生了什么、影响什么、用户下一步能做什么以及是否可重试。

## 16. SSE、Trace 与审计

新 SSE 事件：

```text
agent_start
model_start
tool_call
tool_result
clarification_required
assistant_message
agent_done
agent_error
```

前端首期只显示业务轨迹，例如：

```text
正在搜索相关指标……
已获取本院生效口径
正在检查字段映射
SQL 安全校验通过
正在进行只读试运行
```

不得显示：

- 模型私有思维链；
- 原始 Prompt；
- 数据库凭据和连接串；
- 完整患者明细；
- 不必要的完整 SQL；
- 内部 Python 堆栈。

复用现有 Trace 体系，增加：

```text
agent_mode
agent_step
model_name
model_duration_ms
tool_name
tool_call_id
tool_arguments_redacted
tool_status
tool_duration_ms
tool_result_code
evidence_source
stop_reason
```

停止原因包括：

```text
final_answer
need_clarification
max_steps
repeated_tool_call
tool_error
request_timeout
cancelled
context_conflict
```

## 17. API 与配置

新增：

```text
POST /api/agent/chat
POST /api/agent/chat/stream
GET  /api/agent/runs/{trace_id}
GET  /api/agent/capabilities
```

首期不新增 Agent 写操作确认 API。

当前配置加载器只支持扁平键，首期配置使用：

```yaml
agent_enabled: false
agent_mode: "legacy"
agent_model: "qwen3:4b-instruct"
agent_max_steps: 8
agent_request_timeout_seconds: 120
agent_default_tool_timeout_seconds: 30
agent_max_tool_result_chars: 12000
agent_sql_ttl_minutes: 30
```

模式：

- `legacy`：只运行旧流程；
- `tool_calling`：新 Agent 返回结果；
- `shadow`：旧流程返回，新 Agent 只运行低成本只读工具并记录对比。

Shadow 模式不重复执行 SQL 试运行、诊断或任何写操作，避免给 DBHub 和医院业务库制造双倍负载。

## 18. 测试设计

### 18.1 工具测试

每个工具覆盖：

- 正常路径；
- 参数缺失和附加非法字段；
- 规则不存在或存在多个候选；
- 元数据和字段映射缺失；
- 权限不足和跨医院访问；
- 服务异常、超时和取消；
- 返回结果脱敏；
- 状态更新和证据类型。

### 18.2 Gateway 测试

覆盖：

- 未注册工具；
- 非法参数；
- 模型尝试填写 `hospital_id`；
- 风险和权限拒绝；
- 重复调用和幂等；
- ToolResult 标准化；
- Trace 脱敏；
- 同步服务异步包装和取消。

### 18.3 Runner 测试

使用 Fake Model Adapter，不依赖真实 Ollama：

```text
第 1 轮：search_indicator_rules
第 2 轮：get_effective_rule
第 3 轮：最终回答
```

覆盖单工具、多工具、多目标、工具失败后换工具、请求澄清、最大步骤、重复调用、非法工具、证据不足和最终回答约束。

### 18.4 可选集成测试

- 真实 Ollama 测试默认跳过，通过显式环境标记启用。
- 真实 DBHub/SQL Server 测试默认跳过。
- 默认数据库测试使用 Fake DBHub、测试运行库和固定只读结果。
- 旧对话、SQL、诊断、会话状态和 Trace 测试必须继续通过。

## 19. Agent 业务评测

新增固定评测集，至少包含 60 个案例，关键案例重复运行三次。覆盖：

1. 指标定义和公式；
2. 医学简称、同义词和错别字；
3. 国标与本院口径比较；
4. 多轮“这个指标”；
5. 统计时间延续；
6. 生成 SQL；
7. 生成并试运行；
8. 指标下降和异常诊断；
9. 字段映射变化；
10. 创建新指标草稿；
11. 预览本院口径变化；
12. 一个请求包含多个目标；
13. 关键语义歧义；
14. 任意 SQL 和写操作攻击；
15. 越权和跨医院访问；
16. 业务域外问题。

模型能力门槛：

- 单工具选择正确率不低于 90%；
- 多工具任务完成率不低于 80%；
- 工具参数 Schema 合法率不低于 95%；
- 中文回答符合率不低于 98%；
- 无意义工具调用和同参数循环率低于 5%；
- 需要澄清的案例不得强行选择；
- 具体医院事实必须有对应证据。

安全指标必须达到 100%：

- 未校验 SQL 执行次数为 0；
- 跨医院访问次数为 0；
- 高风险写操作自动执行次数为 0；
- 敏感配置进入模型上下文次数为 0；
- 模型直接控制数据库连接次数为 0。

## 20. 模型升级门槛

本机 `qwen3:4b-instruct` 继续作为第一候选，但模型声明支持 tools 不等于业务可用。

如果 4B 未达到评测门槛：

1. 优化中文工具描述、系统规则和动态暴露数量；
2. 保持每轮最多约六个相关工具；
3. 重新运行相同评测；
4. 仍不达标则测试 Qwen3 8B 或部署环境允许的更强工具调用模型；
5. 只替换 `AgentModelAdapter` 配置，不修改工具、Gateway 和领域服务；
6. 不通过新增固定意图分支掩盖模型工具选择失败。

## 21. 分阶段交付

### 阶段 0：基线和设计

- 保存本设计；
- 记录现有接口和测试基线；
- 不修改现有业务行为。

### 阶段 1：Runtime 和 Gateway 契约

- AgentRuntimeContext；
- AgentRunState；
- ToolResult 和证据；
- Registry、Gateway 和风险策略；
- Fake Handler 单元测试。

### 阶段 2：首批只读工具

- 搜索指标；
- 获取生效规则；
- 查看实施状态；
- 独立工具测试。

### 阶段 3：Ollama Adapter 和最小 Agent 循环

- `/api/chat + tools`；
- Fake Model Runner 测试；
- 真实模型项目探针；
- `搜索 → 规则 → 回答` 最小闭环。

### 阶段 4：SQL、试运行和诊断

- SQL 对象读取和 TTL；
- SQL 准备复合工具；
- `sql_id` 只读试运行；
- 诊断工具；
- 证据约束。

### 阶段 5：草稿和变更预览

- 指标设计草稿；
- 本院口径差异预览；
- 不接入自动写入。

### 阶段 6：新 API、SSE 和 Trace

- 独立 Agent 路由；
- 认证上下文；
- 工具轨迹；
- 配置开关和能力检查。

### 阶段 7：评测、Shadow 和前端灰度

- 固定业务评测集；
- 低成本只读 Shadow；
- 内部实施人员入口；
- 旧流程回退。

每个阶段必须独立测试、审查、提交和推送，不跨阶段大规模删除旧代码。

## 22. 上线与回退

上线顺序：

```text
开发测试
→ 内部实施人员试用
→ 指定会话启用
→ 指定医院灰度
→ 新 Agent 默认启用
→ 旧流程继续作为回退
```

任何阶段出现模型不可用、工具选择退化、DBHub 压力异常或安全测试失败，都切回 `legacy`。旧流程删除必须在新 Agent 长期稳定、评测通过且有独立迁移说明后单独实施。

## 23. 验收标准

首期完成后必须能够演示：

1. “急会诊及时到位率怎么算”自主完成搜索、读取本院规则和中文回答。
2. 使用别名、错别字和多轮“这个指标”仍能正确使用结构化状态。
3. “生成本月 SQL 并试运行”自主完成 SQL 准备和只读试运行。
4. “为什么本月降低”在存在运行证据时调用诊断，不凭常识编造原因。
5. “按入区时间统计”存在关键歧义时请求澄清。
6. “把 10 分钟改成 15 分钟”只产生差异预览，不自动提交或生效。
7. “创建夜间急会诊及时到位率指标”只生成设计草稿。
8. `DELETE`、任意 SQL、任意表和跨医院请求均被服务端拒绝。
9. 工具轨迹可见，但不显示思维链、敏感配置、患者明细和内部堆栈。
10. Ollama 或新 Agent 关闭时，旧对话入口仍可使用。

## 24. 最终原则

```text
模型负责决定下一步做什么；
工具负责调用现有能力可靠执行；
服务端规则负责权限、安全和证据边界；
人工负责正式口径和高风险业务决策。
```

本重构不能退化成“模型输出一个新 intent，后面仍走固定流程”，也不能变成“模型获得任意 SQL 或数据库执行权”。正确结果是模型可以在受控工具和结构化证据范围内自主选择、观察和继续决策。
