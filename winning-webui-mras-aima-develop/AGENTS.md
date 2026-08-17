# AGENTS.md — 指标助手前端（winning-webui-mras-aima）

> **本文件是所有 AI 编程智能体（Claude Code / Codex / Cursor / Copilot / CodeBuddy / Roo 等）的统一入口与规则真相来源。**
> 完整规则正文拆分在 [`docs/agent-rules/`](docs/agent-rules/) 下，本文档只做**索引与分派**——避免单文件膨胀，也避免多份正文漂移。
> **Roo Code 用户注意**：`.roo/rules/` 仅作为 Roo 的自动加载缓存，其正文应与 `docs/agent-rules/` 保持一致；以 `docs/agent-rules/` 为准。

---

## 1. 这是什么项目

面向**医院管理人员**的 AI 驱动医疗指标查询与分析智能助手。用户用自然语言对话完成指标定义查询、公式解析、数据统计与可视化。核心是**对话式 SSE 流式交互** + **指标异常排查（查故障）状态机** + **前端运行时监控 SDK**。

应用内名为「指标助手」，部署在 `/webui-mras-aima/` 路径下。

当前项目对应的后端仓库为 `E:\workspace\tfs\winex-managerment\winning-winex-mras-aima`，参考的前端仓库为 `E:\workspace\tfs\winex-managerment\readonly\winning-webui-mras-aima`，前端仓库为另一种完整实现，相关目录、文件名、组件库、规范可能都不一样，但是功能是完整的，实现时可用作参考。

## 2. 技术栈速览

| 层 | 选型 |
|----|------|
| 框架 | Vue 3.5（**Composition API + `<script setup>`**，禁止 Options API） |
| 语言 | TypeScript 6.0（`strict` 倾向，`noUnusedLocals` / `noUnusedParameters` 已开） |
| 构建 | Vite 8.1 |
| 路由 | Vue Router 4.6（`createWebHistory`，懒加载） |
| 状态 | Pinia 4.0 |
| UI | Vuetify 4.1（Material Design 3） |
| 原子化 CSS | Tailwind CSS 4.3（**CSS 优先配置，无 `tailwind.config.js`**，由 `@tailwindcss/vite` 启用） |
| 预处理 | Sass（`sass-embedded`） |
| 工具 | @vueuse/core、date-fns、markdown-it、highlight.js、idb、@mdi/font |
| 可视化 | @antv/g6、@vue-flow/*、dagre |

## 3. 常用命令

```bash
npm install            # 安装依赖
npm run dev            # 启动开发服务器
npm run build          # vue-tsc 类型检查 → vite 生产构建
npm run lint           # ESLint 检查（--max-warnings 0，零警告）
npm run typecheck      # 仅类型检查（vue-tsc -b）
npm run format         # Prettier 格式化 src/
npm run format:check   # Prettier 合规检查
```

**新增/修改文件后**：务必跑 `npm run lint` 与 `npm run typecheck`（CI 会卡零警告）。

## 4. 规则分派表（重要）

> **常驻规则**：任何任务都应遵循，正文在 `docs/agent-rules/`。
> **按需规则**：当任务命中「触发条件」时，**先读对应子文件正文并遵循**，再动手写代码（非 Roo 智能体无自动监听，需主动套用）。

| 前缀 | 规则文件（docs/agent-rules/） | 类型 | 触发条件 |
|------|------------------------------|------|----------|
| A 段（常驻，always） | | | |
| A00 | `A00-project-conventions.md` | 常驻 | 总入口：技术栈/目录/分层/路径别名/命名；写代码前应先读 |
| A01 | `A01-technical-rigor.md` | 常驻 | 任何技术问答 / 编码 / 方案输出 |
| A02 | `A02-verification-gate.md` | 常驻 | 任何代码改动完成后（校验通过前不得宣称完成） |
| A03 | `A03-vue-component-size.md` | 常驻 | 任何 Vue 单文件组件的**创建与编辑**（硬上限 250 行） |
| B 段（按需，on-demand） | | | |
| B00 | `B00-avoid-reinventing-wheel.md` | 按需 | 架构设计 / 方案输出 / 代码生成时（优先成熟方案） |
| B01 | `B01-import-style.md` | 按需 | **创建或编辑** `src/**/*.ts`、`src/**/*.vue` 的 import 语句 |
| B02 | `B02-no-magic-strings.md` | 按需 | **创建或编辑** `src/**/*.ts`/`.vue` 时定义常量 / 条件判断 |
| B03 | `B03-numeric-computation.md` | 按需 | 涉及浮点精度 / 大整数运算时（优先 big.js / BigInt） |
| B04 | `B04-code-formatting.md` | 按需 | **创建或编辑** `src/**/*.ts`/`.vue`/`.js`/`.scss` 后需格式化 |
| B05 | `B05-storage.md` | 按需 | **创建或编辑**涉及 localStorage/sessionStorage 的代码（走 `src/storage`） |
| B06 | `B06-vue-template.md` | 按需 | **创建或编辑** Vue SFC（含未落盘的新组件） |
| B07 | `B07-api-network-layer.md` | 按需 | **创建或编辑**访问后端 API 的请求（走 `@/utils/request`） |
| B08 | `B08-vuetify4.md` | 按需 | **创建或编辑**任何 Vuetify 组件 / 类名 / 主题样式（`src/**/*.vue`/`.scss`），必须遵循 Vuetify4（MD3）语法，禁止 Vuetify3 写法 |

> **编号约定**：`A` 前缀 = 常驻（always），`B` 前缀 = 按需（on-demand）；两位数字段内独立计数（A00–A99 / B00–B99），新增规则直接在对应段续号，**不影响另一段**。

**冲突处理**：专项规则优先于通用约定；`A00` 是总入口，不替代专项规则正文。

## 5. 核心约定入口（正文见 A00）

> 项目约定的**完整正文在 `docs/agent-rules/A00-project-conventions.md`**（技术栈细节、目录结构、分层职责、路径别名、命名规范、验证闭环）。以下仅列最高频的 3 条硬性红线，详细约束以 A00 为准：

- **路径别名**：一律 `@/` 指向 `src/`，禁止相对路径跨层（`../../`）。
- **网络请求**：统一走 `src/utils/request.ts` 的 `request()`；SSE 走 `src/utils/sse.ts`；禁止裸 `fetch` / `axios`。
- **查故障**：`src/views/ChatView/components/diagnosis/`，状态 `src/stores/diagnosis.ts`；`profileId` 恒等于 `ruleId`；前端是排查 UI 唯一真相来源。

> 存储、样式三层级、安全等其余约定请直接读 `A00-project-conventions.md`，**不要在此处复述**。

## 6. Do / Don't

**Do**
- 改 SSE 事件处理先读 `src/types/chat.ts` 的 `SSE_EVENT`。
- 新增页面：在 `src/router/index.ts` 加懒加载路由 + `meta.title`。
- 诊断动作只从 `DIAGNOSIS_ACTION` 白名单取值。
- 用 `monitorSDK.report()` 手动上报关键错误，用 `ErrorBoundary` 兜底白屏。
- 写 Vuetify 模板/样式前先读 `docs/agent-rules/B08-vuetify4.md`；颜色/排版只用 MD3 token（`text-body-small`、`text-medium-emphasis`、`on-surface-variant` 等）。

**Don't**
- 不要原生 `fetch` 直连后端 / 手写 `new XMLHttpRequest`。
- 不要在组件里维护应属于 Pinia 的全局状态。
- 不要引入 `any` 偷懒（warning 会卡 lint）；用精确类型或 `unknown` + 收窄。
- 不要复制 readonly 仓库的自定义 CSS / DOM 结构。
- 不要提交 `.env*`、密钥、或把 `console.log` 留进生产逻辑。
- **不要写 Vuetify3 写法**（MD2 排版类 `text-caption`/`text-subtitle-1`/`text-h5`、中性色 `grey-darken-1`/`grey-lighten-4`、布尔变体 `outlined`/`text`、`<v-flex>`/`grid-list-*`）。项目已接入 `eslint-plugin-vuetify` 的 `recommended-v4`，这类写法会卡 lint。

## 7. 常见任务入口速查

| 任务 | 去哪 |
|------|------|
| 加 API 接口 | `src/services/*.ts`（调 `request`） |
| 定义数据类型 | `src/types/*.ts` |
| 加全局状态 | `src/stores/*.ts`（Pinia setup 风格） |
| 加对话框/卡片/面板 | `src/views/ChatView/components/diagnosis/` |
| 改主题色 / 暗黑模式 | `src/plugins/vuetify.ts` |
| 改日志监控/脱敏 | `src/monitor/` |
| 改路由 / 标题 | `src/router/index.ts` |
| 改构建 / 代理 / CSS 层 | `vite.config.ts` |
| 死代码检测 | `npx knip`（`knip.config.ts`） |
| **规则详情** | **`docs/agent-rules/`** |
