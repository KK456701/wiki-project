# 指标助手 (winning-webui-mras-aima)

> 面向医院管理人员的 AI 驱动医疗指标查询与分析智能助手

基于自然语言对话实现医疗指标定义查询、公式解析、数据统计与可视化，降低医疗质量管理的数据获取与分析门槛。用户无需掌握 SQL 或 BI 工具，通过简单对话即可完成复杂指标分析。

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Vue 3 (Composition API + `<script setup>`) | 3.5.x |
| 语言 | TypeScript | 6.0.x |
| 构建工具 | Vite | 8.1.x |
| 路由 | Vue Router | 4.6.x |
| 状态管理 | Pinia | 4.0.x |
| UI 组件库 | Vuetify (Material Design 3) | 4.1.x |
| 原子化 CSS | Tailwind CSS | 4.3.x |
| CSS 预处理 | Sass (sass-embedded) | 1.100.x |
| 工具集 | VueUse | 14.3.x |
| Markdown 渲染 | markdown-it | 14.3.x |
| 代码高亮 | highlight.js | 11.11.x |
| 日期处理 | date-fns | 4.4.x |
| IndexedDB | idb | 8.0.x |
| 图标 | @mdi/font | 7.4.x |
| SSE 流式通信 | 原生 fetch + ReadableStream | — |

### 开发工具

| 工具 | 用途 |
|------|------|
| ESLint 10 + Prettier | 代码规范与格式化 |
| vue-tsc | Vue SFC 类型检查 |
| Knip | 死代码/未使用导出检测 |
| unplugin-icons | Iconify 图标按需导入 |

---

## 快速开始

### 环境要求

- **Node.js** ≥ 18.x
- **npm** ≥ 9.x

### 安装

```bash
git clone <your-repo-url>
cd winning-webui-mras-aima
npm install
```

### 环境配置

复制环境变量示例文件并修改为你的本地配置：

```bash
cp .env.local.example .env.local
```

`.env.local` 可配置项：

| 变量 | 说明 | 示例值 |
|------|------|--------|
| `VITE_AIMA_API_TARGET` | 后端 API 服务地址 | `http://192.168.101.26:8765` |

项目使用 [`API_BASE_PREFIX`](src/config/app.ts:15) (`/wiki-agent`) 作为代理前缀，前端所有 API 请求路径均以该前缀开头，开发环境下由 Vite 代理到上述目标地址。

### 开发

```bash
npm run dev
```

启动后访问 `http://localhost:5173/webui-mras-aima/`。开发服务器默认开启 LAN 访问。

### 构建

```bash
npm run build
```

构建流程：TypeScript 类型检查 → 生成构建信息 (git 分支/commit/hash) → Vite 生产构建。

输出目录：`dist/`

### 预览

```bash
npm run preview
```

预览生产构建结果。

---

## 命令速查

| 命令 | 说明 |
|------|------|
| `npm run dev` | 启动开发服务器 (`vite --host`) |
| `npm run build` | 类型检查 + 生成构建信息 + 生产构建 |
| `npm run preview` | 预览生产构建 |
| `npm run lint` | ESLint 代码检查 |
| `npm run lint:fix` | ESLint 自动修复 |
| `npm run format` | Prettier 格式化 `src/` 下所有文件 |
| `npm run format:check` | 检查代码格式是否合规 |

---

## 开发指南

### Vite 代理配置

开发环境下，Vite 自动代理以 [`API_BASE_PREFIX`](src/config/app.ts:15) 开头的请求到后端服务：

| 前端路径 | 代理目标 | 说明 |
|----------|----------|------|
| `/wiki-agent/**` | `.env.local` 中的 `VITE_AIMA_API_TARGET` | 后端 Agent API |

配置参见 [`vite.config.ts`](vite.config.ts:20)。

### 路径别名

- `@` → `src/` (在 [`vite.config.ts`](vite.config.ts:15) 和 `tsconfig.app.json` 中同步配置)

### 应用基础路径

应用部署在 `/webui-mras-aima/` 路径下，由 [`src/config/app.ts`](src/config/app.ts:12) 中的 `APP_BASE_URL` 常量统一管理，Vite `base` 和 Vue Router `createWebHistory` 均引用此常量。

### 路由表

| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | — | 重定向到 `/chat` |
| `/login` | LoginView | 登录页 (占位) |
| `/chat/:sessionId?` | ChatView | 对话主页面 (sessionId 可选) |
| `/settings` | SettingsView | 设置页 (占位) |

参见 [`src/router/index.ts`](src/router/index.ts:5)。

### 样式体系

项目采用三层样式体系协同工作：

1. **Vuetify 组件自带样式** (优先) — 组件 `type`/`size`/`color` props
2. **Tailwind CSS 4** (补充) — 布局/间距/排版等实用工具类
3. **自定义 SCSS** (最后手段) — 页面级/组件级特定样式

---

## 许可证

内部项目，未开放外部许可。
