# 删除旧稳定流程并接入 Qwen3 8B 思考模型设计

## 目标

系统只保留工具调用型 Agent 对话入口，彻底下线旧稳定流程、Shadow 灰度和前端自动回退；同时增加本地 `qwen3:8b` 思考模型供页面切换测试。

## 删除边界

- 删除旧 LangGraph 对话实现 `app/agent/graph.py` 及其包入口。
- 删除 `/api/chat`、`/api/chat/stream`、Shadow 调度和 Shadow Runtime。
- 删除前端 legacy 模式判断、旧 SSE 解析、自动回退文案和“稳定流程”徽标。
- 删除只服务于上述旧链的测试和旧灰度运维文档。
- `app/agents/` 不整体删除：其中契约、口径适配、字段解析和草稿生成仍被现有工具、诊断及审批 API 使用。

## 保留后的对话架构

浏览器只调用 `/api/agent/chat/stream`。登录态经医院权限校验后进入 `AgentRuntimeService`，所选模型先生成严格语义计划；服务端编译、校验并由确定性状态控制器逐步开放工具；工具结果经过证据和数值校验后，再由模型组织最终回答。任何不可用状态直接返回明确错误，不再执行第二套对话流程。

## Qwen3 8B 思考模型

- 注册模型 ID：`ollama-qwen3-8b-thinking`。
- Ollama 模型：`qwen3:8b`。
- 显示名称：`Qwen3 8B 思考模式（本地 Ollama）`。
- 模型配置增加布尔字段 `thinking`，只允许 Ollama 使用。
- `thinking: true` 时向 Ollama `/api/chat` 发送 `think: true`。
- 推理内容只在模型内部用于规划和工具选择，不进入公开 SSE、Trace 摘要或最终回答。
- 默认模型继续为 4B，避免默认资源占用突然增加。

## API 与前端行为

- capabilities 继续返回 `enabled`、模型列表和 `orchestration`，不再返回 legacy/shadow 模式。
- 页面加载 capabilities 后渲染模型选择器；发送消息始终走 Agent SSE。
- 未登录、无权限、Agent 关闭或服务不可用时显示明确错误，不调用旧接口。
- 页面顶部移除运行模式徽标，仅保留模型选择器和会话信息。

## 测试与验收

- 路由表中不存在 `/api/chat` 和 `/api/chat/stream`。
- 源码中不存在旧稳定流程前端分流、Shadow Runtime 和旧 LangGraph 对话包。
- capabilities 中存在 8B 思考模型且模型选择保持用户选择。
- Ollama 8B 请求包含 `think: true`，4B 请求不包含该字段。
- 原工具调用 Agent、计划控制、SQL 安全、上传和 Trace 测试继续通过。
- 本机 `ollama list` 已确认存在 `qwen3:8b`。

