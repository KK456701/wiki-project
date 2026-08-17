# 网络请求规范（API 层，按需：访问后端 API 时）

> 所有访问后端 API 的请求必须统一走 `src/utils/request.ts` 的 `request()`，禁止在业务代码中裸写 fetch/axios；SSE 走 `src/utils/sse.ts`。

## 核心规则

1. **所有后端 API 调用必须经由 `src/utils/request.ts` 的 `request()` 封装**，禁止在组件/store 中裸写 `fetch(url, ...)` 或引入 `axios`。
2. **SSE（流式通信）必须经由 `src/utils/sse.ts`**，不要自己实现 `fetch` + `ReadableStream` 流式读取。
3. **请求路径与全局配置从 `@/config/app` 读取**，禁止硬编码 `/wiki-agent`、`http://...` 等地址。
4. 新增 API 封装放在 `src/services/` 下（如 `chat.ts`），由视图/composable 调用，不要在组件里直接拼 URL。

## 现有封装能力（`src/utils/request.ts`）

| 导出 | 作用 |
|------|------|
| `request(path, options)` | 轻量 fetch 封装，返回 `Response` |
| `API_BASE` | 完整 API 前缀，值为 `${API_BASE_PREFIX}/api`（即 `/wiki-agent/api`） |
| `buildUrl(path)` | 相对路径自动补全 `API_BASE`；绝对 `http(s)` URL 原样返回 |
| `getAuthHeaders()` | 自动注入 `Authorization` 请求头（从 storage 读取 Token） |
| `handleUnauthorized()` | 401 时清除 Token 并跳转 `/login` 后抛 `UnauthorizedError` |
| `UnauthorizedError` | 401 错误类型，调用方可用 `instanceof` 区分 |

> `request()` 已自动处理 URL 前缀补全、认证头注入、401 拦截跳转。直接 `await request()` 即可拿到 `Response`。

## 正确用法

```typescript
// ✅ 在 services/chat.ts 中封装
import { request, API_BASE } from '@/utils/request';

export async function getCapabilities() {
  const res = await request('/agent/capabilities'); // 自动变 /wiki-agent/api/agent/capabilities
  if (!res.ok) throw new Error(`请求失败: ${res.status}`);
  return res.json();
}
```

```typescript
// ✅ 在调用方（composable / store）使用，不直接裸写 fetch
import { getCapabilities } from '@/services/chat';
const caps = await getCapabilities();
```

## 禁止的写法

```typescript
// ❌ 裸写 fetch（绕过认证头注入与 401 统一处理）
const res = await fetch('/wiki-agent/api/agent/capabilities');
// ❌ 硬编码后端地址
const BASE = 'http://192.168.101.26:8765';
// ❌ 引入 axios
import axios from 'axios';
// ❌ 在组件 <script> 里直接拼 URL 发请求
```

## 与全局配置的关系

- `src/config/app.ts` 提供 `API_BASE_PREFIX`（`/wiki-agent`）、`APP_BASE_URL`（`/webui-mras-aima/`）。
- 开发环境 Vite 代理：`/wiki-agent/**` → `env.VITE_AIMA_API_TARGET`。
- **不要新增 Vite 代理规则或硬编码目标地址**，统一复用现有 `API_BASE_PREFIX`。

## SSE（流式）

- 流式对话等场景使用 `src/utils/sse.ts` 提供的封装，复用同一套认证头与错误语义。
- 不要在业务代码里自行实现 `Response.body.getReader()` 流式解析。

## 检查清单

- [ ] 所有后端请求是否走了 `@/utils/request` 的 `request()`？
- [ ] 是否避免了裸 `fetch` / `axios` 直接调后端？
- [ ] SSE 是否走了 `@/utils/sse`？
- [ ] 是否使用了相对路径（交给 `API_BASE` 补全），而非硬编码 `/wiki-agent` 或 `http://...`？
- [ ] 新增接口封装是否放在了 `src/services/`？
- [ ] 是否复用了 `src/config/app.ts` 的全局常量，未新增代理/硬编码地址？
