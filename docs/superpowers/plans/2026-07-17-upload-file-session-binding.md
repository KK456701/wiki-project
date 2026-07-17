# Excel 上传文件会话关联实施计划

> **执行要求：** 使用 `executing-plans` 在当前会话逐项实施；按复选框跟踪完成情况，不启用子智能体或 worktree。

**目标：** 将最近上传 Excel 的 `file_key` 通过聊天 API 写入持久化 Agent 状态，使自然语言追问能够确定性分析该文件。

**架构：** 浏览器为当前聊天会话保存最近上传引用，并作为可选聊天字段发送。API 和服务层将其绑定到 `AgentRunState.current_upload_file_key`；确定性分发优先读取结构化状态，再回退到旧文本解析。上传工具现有的医院前缀校验继续作为最终租户边界。

**技术栈：** 原生 JavaScript、FastAPI、Pydantic、Agent 会话记忆、pytest、Node 前端单元测试。

## 全局约束

- 直接在当前 `main` 工作，不创建 worktree 或子智能体。
- 保留 `无标题.xlsx` 等 Unicode 文件名，同时拒绝路径分隔符。
- 不向模型或公开 SSE 暴露 Excel 患者行级数据。
- 同批更新 README 和变更文档。
- 浏览器验收由用户完成，自动化验证仍必须执行。

---

### 任务 1：在浏览器聊天载荷中携带上传引用

**文件：**
- Modify: `tests/test_agent_frontend_ui.py`
- Modify: `web/agent-runtime.js`
- Modify: `web/index.html`

**接口：**
- 输入：上传响应中的 `file_key`。
- 输出：`buildChatPayload(query, sessionId, modelId, fileKey)` 生成可选 `file_key`；`streamAgent({fileKey})` 负责转发。

- [x] 增加失败前端测试，验证第四个参数写入 `file_key`，空值时省略该字段。
- [x] 运行定向测试并确认修改前缺少 `file_key`。
- [x] 扩展 `buildChatPayload` 和 `streamAgent`；在页面保存、传递并于新会话清除 `latestUploadedFileKey`。
- [x] 运行完整前端 Agent 测试并通过。

### 任务 2：将文件引用绑定到 API 与会话状态

**文件：**
- Modify: `tests/test_agent_api.py`
- Modify: `tests/test_agent_capabilities.py`
- Modify: `app/api/agent_routes.py`
- Modify: `app/agent_runtime/contracts.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/agent_runtime/memory.py`

**接口：**
- 输入：可选 `AgentChatRequest.file_key`。
- 输出：`AgentRunState.current_upload_file_key`，通过 `_safe_state_metadata` 与 `AgentConversationMemory.open` 保存和恢复。

- [x] 增加失败 API 测试，验证 Unicode `file_key` 转发并拒绝 `/` 或 `\\`。
- [x] 增加失败服务测试，验证 Runner 执行前状态已绑定 `file_key`。
- [x] 增加失败记忆往返测试，验证安全元数据保存并恢复 `current_upload_file_key`。
- [x] 运行定向测试并确认修改前字段和方法签名缺失。
- [x] 增加受校验请求字段，经流式和非流式入口转发，在执行前更新状态，并通过安全元数据持久化。
- [x] 运行 API、服务和会话记忆测试并通过。

### 任务 3：确定性分发优先使用结构化附件状态

**文件：**
- Modify: `tests/test_agent_deterministic_dispatch.py`
- Modify: `app/agent_planning/dispatch.py`

**接口：**
- 输入：`AgentRunState.current_upload_file_key`。
- 输出：无需用户文本包含文件编号即可生成 `analyze_uploaded_indicators` 工具调用。

- [x] 增加失败测试：用户只说“帮我分析刚上传的文件”，状态包含 Unicode 上传编号。
- [x] 验证新结构化编号优先于历史中的旧文件编号。
- [x] 运行定向测试并确认修改前错误选择历史编号。
- [x] 更新 `_file_key`，优先返回结构化编号并保留文本兼容解析。
- [x] 运行确定性分发测试并通过。

### 任务 4：文档、完整验证与交付

**文件：**
- Modify: `README.md`
- Modify: `docs/operations/2026-07-16-changes.md`
- Modify: `docs/superpowers/plans/2026-07-17-upload-file-session-binding.md`

**接口：**
- 输入：最终实现与测试证据。
- 输出：准确的运行文档和可交付提交。

- [x] 记录显式上传引用绑定，以及“新上传替换旧引用、新会话清除引用”的行为。
- [x] 对变更 Python 文件运行 Ruff，执行 `compileall`、定向测试和完整 `pytest -q`。
- [x] 检查 `git diff --check`，扫描凭据和患者行级数据。
- [ ] 提交当前变更，非强制推送 `main`，并重启 8765 服务加载新代码。
