# 存储操作规范（按需：涉及 localStorage / sessionStorage 时）

> 所有 `localStorage` 和 `sessionStorage` 的读写操作，**必须通过 `src/storage` 模块中导出的函数进行**。

## 核心规则

严格禁止：

1. ❌ 直接使用 `localStorage.getItem()` / `localStorage.setItem()` 等原生 API
2. ❌ 直接使用 `sessionStorage.getItem()` / `sessionStorage.setItem()` 等原生 API
3. ❌ 使用任何第三方存储库（如 `store2`、`localForage`、`vue-ls`、`pinia-plugin-persistedstate` 等）

> **兼容性说明**：如果当前项目不存在 `src/storage` 模块，或模块的导出内容与本文档描述不一致，则**跳过本规则**。本文档描述的是目标规范，仅在项目已按此规范实现 `src/storage` 模块时生效。

## 模块结构

```
src/storage/
├── storage-defs.ts    # 存储项定义（STORAGE_DEFS）、类型、常量
└── storage.ts         # 统一 API：getStorage / setStorage / removeStorage / clearStorage
```

### `storage-defs.ts` — 存储项注册中心

所有需要持久化的数据项**必须在 `STORAGE_DEFS` 中注册**：

| 属性 | 说明 |
|------|------|
| `key` | 底层存储 key（不含命名空间前缀） |
| `storage` | 存储后端：`'localStorage'` 或 `'sessionStorage'` |
| `defaultValue` | 读取不存在时返回的默认值 |
| `namespace` | 是否添加命名空间前缀，默认 `true`；来自外部系统（如统一登录）设为 `false`；本项目前缀由 `storage-defs.ts` 中的 `NS` 常量定义（当前 `mrasAima`，实际 key 形如 `mrasAima:xxx`） |
| `serialize` | 是否 JSON 序列化/反序列化，默认 `false`；对象/数组类型设为 `true` |

### `storage.ts` — 统一操作 API

| 函数 | 签名 | 说明 |
|------|------|------|
| `getStorage` | `<K>(name: K) => StorageValue<K>` | 读取存储值，异常时返回默认值 |
| `setStorage` | `<K>(name: K, value: StorageValue<K>) => boolean` | 写入存储值，返回是否成功 |
| `removeStorage` | `(name: StorageKey) => void` | 删除指定存储项 |
| `clearStorage` | `() => void` | 清除所有已注册存储项 |

## 使用规范

```typescript
// ✅ 正确
import { getStorage, setStorage } from '@/storage/storage';
import { STORAGE_KEYS } from '@/storage/storage-defs';

const token = getStorage(STORAGE_KEYS.AUTH_TOKEN);
setStorage(STORAGE_KEYS.AUTH_TOKEN, 'Bearer eyJhb...');

// ❌ 错误：直接使用原生 API
const token = sessionStorage.getItem('Authorization');
// ❌ 错误：使用魔法字符串作为 key
const token = getStorage('AUTH_TOKEN');
// ❌ 错误：使用第三方库
import store from 'store2';
```

## 新增存储项流程

1. 在 `storage-defs.ts` 的 `STORAGE_DEFS` 中新增一项
2. 确定 `storage`：跨标签页共享 → `localStorage`；仅当前会话 → `sessionStorage`
3. 确定 `serialize`：对象/数组 → `true`；字符串/数字 → `false`
4. 确定 `namespace`：外部系统 → `false`；项目内部 → `true`
5. 通过 `STORAGE_KEYS` 常量使用，禁止直接写字符串 key

## 豁免场景

1. 第三方库内部实现（如 Vuetify、监控 SDK）内部使用的 `localStorage`
2. 调试目的：开发时在浏览器控制台手动执行 `localStorage` 命令

> 项目自身业务代码中**不得**以"调试方便"为由直接调用原生 API。

## 检查清单

- [ ] 是否直接使用了 `localStorage.getItem()` / `localStorage.setItem()`？
- [ ] 是否直接使用了 `sessionStorage.getItem()` / `sessionStorage.setItem()`？
- [ ] 是否引入了任何第三方存储库？
- [ ] 新增的存储项是否已在 `STORAGE_DEFS` 中注册？
- [ ] 调用 `getStorage` / `setStorage` 时是否使用了 `STORAGE_KEYS` 常量？
