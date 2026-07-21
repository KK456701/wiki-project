# Java / Vue 迁移契约

这里冻结迁移期间必须保持兼容的跨语言边界。Python、Java 和 Vue 实现都以 `v1/` 为准，不能通过复制内部类来隐式推断协议。

## 当前冻结范围

- `agent-api.schema.json`：Agent 请求、非流式响应和上传响应。
- `agent-sse.schema.json`：`POST /api/agent/chat/stream` 对外 SSE 事件。
- `dbhub-mcp.md`：主服务与现有 DBHub sidecar 的 JSON-RPC 约定。
- `hospital-auth-and-rule-read.md`：跨语言认证、医院隔离和规则只读约定。
- `auth-crypto-vector.json`：PBKDF2 与令牌摘要的非生产测试向量。

## 兼容规则

1. 请求体拒绝未声明字段，医院、用户、权限和 Trace 身份只从登录态取得。
2. SSE 每个事件由 `event: <name>` 和一行 JSON `data:` 组成，前端不得依赖内部工具参数或原始结果。
3. Java 迁移接口先通过契约测试，再替换同路径 Python 接口。
4. 新字段只能以可选字段方式加入 v1；破坏性变更必须建立新版本目录。
5. SQL、患者明细、模型提示词和完整 Evidence 不属于浏览器 SSE 契约。
