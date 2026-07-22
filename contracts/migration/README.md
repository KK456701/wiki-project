# Java / Vue 对外契约（保留原目录名）

这里冻结 Java 服务与 Vue、DBHub 之间必须保持兼容的边界。目录名 `migration` 为历史兼容路径，不代表当前仍有双运行时。

## 当前冻结范围

- `agent-api.schema.json`：Agent 请求、非流式响应和上传响应。
- `agent-sse.schema.json`：`POST /api/agent/chat/stream` 对外 SSE 事件。
- `dbhub-mcp.md`：主服务与现有 DBHub sidecar 的 JSON-RPC 约定。
- `hospital-auth-and-rule-read.md`：跨语言认证、医院隔离和规则只读约定。
- `auth-crypto-vector.json`：PBKDF2 与令牌摘要的非生产测试向量。
- `agent-plan-ir.schema.json`：Planner 业务输出与服务端编译 IR 的跨语言结构。

## 兼容规则

1. 请求体拒绝未声明字段，医院、用户、权限和 Trace 身份只从登录态取得。
2. SSE 每个事件由 `event: <name>` 和一行 JSON `data:` 组成，前端不得依赖内部工具参数或原始结果。
3. Java 接口变更必须先通过契约测试，再更新 Vue 调用方。
4. 新字段只能以可选字段方式加入 v1；破坏性变更必须建立新版本目录。
5. SQL、患者明细、模型提示词和完整 Evidence 不属于浏览器 SSE 契约。
