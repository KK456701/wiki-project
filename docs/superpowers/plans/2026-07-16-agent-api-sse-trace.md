# Agent API、SSE 与 Trace 实施计划

**目标：** 在不改变旧聊天接口的前提下，为工具调用型 Agent 增加登录态 API、业务级 SSE 事件、运行 Trace 查询和能力开关。

**架构：** 新建独立 `agent_routes.py` 和 `AgentRuntimeService`。路由只负责认证、请求契约、HTTP/SSE 转换；服务负责从 `HospitalPrincipal` 构造不可伪造的 `AgentRuntimeContext`、组装 Runner/八工具目录并桥接事件与 Trace。旧 `/api/chat` 与 `/api/chat/stream` 保持原状。首期模型轮仍为非流式，最终答案一次发送。

**技术栈：** FastAPI、Pydantic 2、现有 HospitalAuth、AgentRunner、ToolGateway、TraceRecorder、OllamaToolCallingAdapter、pytest/TestClient。

## 全局约束

- 不接受请求体 `hospital_id`、`user_id`、权限、角色、数据库源或 trace ID。
- 所有 `/api/agent/*` 接口要求医院登录；未认证不得启动模型或工具。
- `agent_enabled=false` 或模式不是 `tool_calling` 时，聊天接口返回明确不可用；旧聊天继续可用。
- SSE 只发送业务事件，不发送思维链、Prompt、SQL、连接信息、患者明细或堆栈。
- Trace 参数和工具结果使用现有脱敏函数，最终查询也按当前医院隔离。
- 不新增提交、审批、发布、回退 API。
- 每个任务遵循 TDD、中文 Conventional Commit、推送；阶段完成后全量测试并自动合并 `main`。

---

### Task 1：运行事件协议与 Trace 桥接

**Files:**

- Create: `app/agent_runtime/events.py`
- Create: `app/agent_runtime/tracing.py`
- Modify: `app/agent_runtime/runner.py`
- Modify: `app/agent_runtime/__init__.py`
- Modify: `app/agent_tools/gateway.py`
- Create: `tests/test_agent_runtime_events.py`
- Create: `tests/test_agent_trace_bridge.py`

**步骤：**

1. 先写失败测试，覆盖 `agent_start/model_start/tool_call/tool_result/clarification_required/assistant_message/agent_done/agent_error` 顺序和白名单字段。
2. 给 Runner 增加可选同步 `event_callback`，每次模型调用前发 `model_start`，终止时统一发 `agent_done` 或 `agent_error`；不得包含 messages/Prompt。
3. Gateway 的既有 trace callback 复用同一事件出口，并为工具事件增加耗时、结果码和脱敏载荷。
4. 新建 Trace 桥接器，将事件记录为 `agent_runtime` workflow 节点，`config_data` 保存 `agent_mode/agent_step/model_name/stop_reason` 等非敏感字段。
5. 运行 Agent Runner/Gateway/Trace 相关测试并提交：`feat: 增加 Agent 运行事件与 Trace 桥接`。

### Task 2：登录态 Agent 服务与非流式 API

**Files:**

- Create: `app/agent_runtime/service.py`
- Create: `app/api/agent_routes.py`
- Modify: `app/api/main.py`
- Modify: `app/config.py`（仅在需要可测试设置对象时）
- Modify: `config.yaml`
- Create: `tests/test_agent_api.py`
- Create: `tests/test_agent_capabilities.py`

**接口：**

- `POST /api/agent/chat`
- `GET /api/agent/capabilities`
- `GET /api/agent/runs/{trace_id}`

**步骤：**

1. 请求模型只含 `query` 和可选 `session_id`，`extra="forbid"`；跨租户字段返回 422。
2. 使用 `require_hospital_session` 构造 context。兼容现有医院权限：`indicator_detail_view` 映射只读 `indicator_read`；具有导出权限的账号映射为实施角色，其他账号为医生角色。
3. 默认服务组装 Ollama adapter、八工具目录、SQL 对象 Store、AgentRunner 和 Trace；测试通过 FastAPI dependency override 注入 Fake Service，不连接 Ollama/DBHub。
4. chat 返回 `answer/stop_reason/trace_id/session_id/step_count`，不返回内部 messages、SQL 或完整工具结果。
5. capabilities 返回配置模式、是否启用、模型名、流式支持、最大步骤及安全边界，不执行模型探针。
6. runs 查询必须确认 Trace 的 `hospital_id` 与 principal 一致；不存在返回 404，跨院返回 403。
7. 增加扁平配置默认值并提交：`feat: 增加登录态 Agent API 与能力开关`。

### Task 3：SSE 业务流与取消闭环

**Files:**

- Modify: `app/agent_runtime/service.py`
- Modify: `app/api/agent_routes.py`
- Create: `tests/test_agent_sse_api.py`
- Modify: `tests/test_agent_api.py`

**接口：**

- `POST /api/agent/chat/stream`

**步骤：**

1. 先写失败测试，解析 SSE 并断言首尾事件、工具事件、最终答案和 trace ID 一致。
2. 服务使用 `asyncio.Queue` 桥接同步事件回调与异步生成器；Runner 在后台任务运行，最终答案只发送一次。
3. 对外事件只保留 `event/trace_id/step/tool_name/status/code/message/retryable` 等白名单；工具参数和数据默认不对前端发送。
4. 客户端断开时设置 `AgentRunState.cancelled=True`，停止后续轮次并以 `cancelled` 完成 Trace；生成器异常转为 `agent_error`，不泄漏异常文本。
5. 响应头设置禁缓存与禁代理缓冲；旧 SSE 接口测试保持通过。
6. 阶段回归、完整 `pytest -q`、`git diff --check`，提交：`feat: 完成 Agent SSE 与审计闭环`。

## 完成标准

1. 未登录和跨医院请求不能进入 Agent 循环或读取 Trace。
2. 请求体不能覆盖服务端租户、用户、权限、角色、请求 ID、Trace ID 和数据库源。
3. chat、stream、runs、capabilities 四个端点均位于独立 `/api/agent` 路由。
4. 配置关闭时旧聊天不受影响，新入口明确不可用。
5. SSE 事件完整、顺序稳定、断开可取消，不伪造 token 流。
6. Trace 包含模型/工具/证据/停止原因元数据且不含敏感载荷。
7. 全量测试通过，合并并推送 `main`。
