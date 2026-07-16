# Agent 评测、Shadow 与前端灰度实施计划

**目标：** 用固定业务评测证明工具调用能力与安全边界；提供不重复执行高成本操作的只读 Shadow；让内部实施人员在现有助手页按配置灰度使用新 Agent，并随时回退旧流程。

**架构：** 评测集与执行器独立于生产 API，默认使用 Fake Adapter，真实 Ollama 仅显式启用。Shadow 只注册搜索、规则和实施状态三个只读工具，旧流程仍返回用户答案，后台只记录选择差异。前端先读取登录态 capabilities：`tool_calling` 使用新 SSE，`legacy/shadow` 使用旧 SSE；新流开始后不自动重放请求，避免重复草稿或数据库负载。

**界面方向（frontend-design）：** 保留现有 `#f6f9f8` 纸张网格、`#172320` 深墨、`#087b75` 医疗青绿、`#cfddda` 分隔线与现有中文无衬线/等宽数据字体。标题区增加克制的“运行模式”签章；回答气泡内加入纵向“证据轨道”，像医院流转单一样按顺序显示搜索、规则、字段、SQL 校验和试运行。除这一个签名元素外不增加装饰；移动端折叠为短状态列表，支持键盘焦点和 reduced motion。

## Task 1：固定业务评测集与门槛报告

- Create: `evaluations/agent_business_cases.yaml`（至少 60 例，16 类全覆盖）
- Create: `app/agent_evaluation/contracts.py`
- Create: `app/agent_evaluation/runner.py`
- Create: `scripts/run_agent_evaluation.py`
- Create: `tests/test_agent_evaluation_dataset.py`
- Create: `tests/test_agent_evaluation_metrics.py`

步骤：先测试数据集数量、分类、三次关键重复、安全期望；实现离线指标统计（工具选择、任务完成、Schema、中文、循环、澄清、证据、安全违规）；报告逐项显示分子/分母和是否过门槛。默认不访问 Ollama/DBHub，真实模式要求显式环境变量。提交 `test: 增加 Agent 固定业务评测集`。

## Task 2：低成本只读 Shadow 与对比 Trace

- Create: `app/agent_runtime/shadow.py`
- Modify: `app/agent_runtime/service.py`
- Modify: `app/api/main.py`
- Modify: `app/agent_tools/catalog.py`（增加只读目录构造器，不改变正式目录）
- Create: `tests/test_agent_shadow.py`
- Modify: `tests/test_api.py`

步骤：Shadow 目录严格只有 `search_indicator_rules/get_effective_rule/inspect_indicator_implementation`；禁止 SQL、试运行、诊断、草稿和预览。旧 `/api/chat` 与旧 SSE 仍返回原结果；仅在登录态、配置为 shadow 且问题属于内部试用范围时后台运行，设置短超时，失败不影响旧响应。Trace 记录旧/新是否命中规则、工具序列和停止原因，不记录完整答案或敏感结果。提交 `feat: 增加 Agent 只读 Shadow 对比`。

## Task 3：内部实施人员灰度入口与旧流程回退

- Modify: `web/index.html`
- Create: `web/agent-runtime.css`
- Create: `web/agent-runtime.js`
- Modify: `tests/test_chat_markdown_ui.py`
- Create: `tests/test_agent_frontend_ui.py`

步骤：登录后带 Bearer token 读取 `/api/agent/capabilities`；实施账号且 `tool_calling` 才走 `/api/agent/chat/stream`，医生或 legacy/shadow 继续旧入口。新 SSE 映射到证据轨道，最终 `assistant_message` 只渲染一次。新流在首事件前返回 503 可提示用户切旧入口；收到任何 `agent_start` 后不得自动向旧接口重放。加入模式签章、明确回退文案、移动端和无动画样式。用 DOM/静态契约测试验证认证头、端点选择、敏感字段不渲染和旧入口保留。提交 `feat: 增加 Agent 内部灰度助手入口`。

## Task 4：最终验收与上线说明

- Create: `docs/operations/agent-tool-calling-rollout.md`
- Modify: `docs/PROJECT_HANDOFF_2026-07-16.md`

运行阶段回归、`python -m pytest -q`、真实 Ollama 探针（若本机显式启用）与前端截图检查。文档列出 `legacy → shadow → tool_calling` 配置、内部账号范围、指标门槛、监控项和回退命令；安全指标任何一项非 100% 不允许灰度。提交 `docs: 完成 Agent 灰度与回退说明`，自动合并推送 main 并清理工作树。

## 完成标准

1. 至少 60 个固定案例，关键案例三次重复，指标报告可复现。
2. Shadow 不执行 SQL、诊断、试运行或任何写/预览持久化操作。
3. 新旧入口由服务端 capabilities 决定，用户不能伪造医院与权限。
4. 新 SSE 开始后不自动重放旧请求；旧流程始终可配置回退。
5. 前端只显示业务证据轨迹，不显示 Prompt、思维链、SQL、患者数据或内部错误。
6. 全量测试通过，安全指标 100%，合并推送 main。
