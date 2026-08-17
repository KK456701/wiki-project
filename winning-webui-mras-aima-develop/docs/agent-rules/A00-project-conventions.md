# 项目总体约定与架构地图（常驻）

> 本文件是**项目约定与架构地图的正文唯一来源**（所有智能体通用）。
> AI 在新建/修改代码前应先通读本规则，明确"代码该放在哪里、用什么技术、遵循什么命名"，再结合各专项规则（导入风格、Vue 模板、存储、数值、格式化等）落地。
> 完整规则索引见仓库根 `AGENTS.md` 的「规则分派表」（第 4 节）。
> 技术栈选型速览（含版本号）见 `AGENTS.md` 第 2 节，本文件不再复述，避免版本号漂移。

## 目录结构

```text
src/
├── main.ts              # 应用入口（knip 入口之一）
├── App.vue              # 根组件
├── router/              # Vue Router 配置（index.ts 为 knip 入口）
├── views/               # 页面级视图，按功能模块分目录
│   └── {Module}/        # 如 ChatView / MonitorView / TraceView
│       ├── components/  # 该视图私有的子组件
│       ├── composables/ # 该视图复用的组合式函数（useXxx）
│       └── styles/      # 该视图抽离的 scss
├── services/            # 后端 API 调用封装（如 chat.ts），走 @/utils/request
├── stores/              # Pinia store（如 chat.ts）
├── utils/               # 通用工具：request.ts / sse.ts / markdown.ts
├── storage/             # 统一存储封装：storage.ts + storage-defs.ts
├── config/              # 全局常量（app.ts：APP_BASE_URL / API_BASE_PREFIX）
├── types/               # 全局类型定义（chat.ts / user.ts / env.d.ts）
├── monitor/             # 自包含功能模块（components/config/constants/core/db/types/utils）
├── assets/              # 静态资源
└── style.scss           # 全局样式入口
```

## 分层职责（新代码放哪）

| 你要做的 | 放在 |
|----------|------|
| 新增页面 | `src/views/{Module}/index.vue`（多词组件名；`index.vue` 豁免多词规则） |
| 页面内可复用 UI 区块 | `src/views/{Module}/components/*.vue` |
| 页面内复用逻辑（>10 行） | `src/views/{Module}/composables/useXxx.ts` |
| 跨页面复用的业务逻辑/状态 | `src/stores/*.ts`（Pinia）或 `src/utils/*.ts` |
| 调后端接口 | `src/services/*.ts`，内部调用 `@/utils/request` 的 `request()` |
| 全局常量/配置 | `src/config/*.ts`（不要硬编码到组件里） |
| 类型/接口定义 | `src/types/*.ts` 或就近 `types.ts` |
| 浏览器存储读写 | 经 `src/storage`（禁止裸 `localStorage`/`sessionStorage`，见 `B05-storage.md`） |
| 魔法字符串/枚举值 | 提取为 `constants.ts` 中的 `UPPER_SNAKE_CASE` 常量（见 `B02-no-magic-strings.md`） |

## 路径别名

- `@` → `src/`（在 `vite.config.ts` 与 `tsconfig.app.json` 中同步配置）
- **统一使用 `@/` 别名导入**，不要写相对路径 `../../`

```typescript
import { request } from '@/utils/request';
import { STORAGE_KEYS } from '@/storage/storage-defs';
import { API_BASE_PREFIX } from '@/config/app';
```

## 命名与语法规范

- 组件：`<script setup lang="ts">`；组件名多词（`vue/multi-word-component-names`，`index.vue` 豁免）
- 组合式函数：`use` 开头（`useChatData`）
- Store：`src/stores/*.ts`，用 `defineStore`
- 常量：`UPPER_SNAKE_CASE` + `as const`（见 `B02-no-magic-strings.md`）
- 禁止裸 `any`（`@typescript-eslint/no-explicit-any` 为 warn）；优先精确类型
- 禁止 `var`，优先 `const`（`no-var` / `prefer-const` 为 error）

## 验证闭环

任何代码改动完成后，必须运行校验（详见 `A02-verification-gate.md`）：

```bash
npm run lint       # ESLint，0 warning
npm run typecheck  # vue-tsc 类型检查
```

## 检查清单

动手写代码前，确认：

- [ ] 已知本项目的真实技术栈（Vue3 + Vuetify + TS + Vite，非 win-design/Element Plus）
- [ ] 新代码落在上表正确的目录
- [ ] 使用 `@/` 别名而非相对路径
- [ ] 调后端走 `@/utils/request`，未裸写 fetch
- [ ] 存储走 `@/storage`，未裸写 localStorage
- [ ] 完成改动后打算跑 `npm run lint` 与 `npm run typecheck`
