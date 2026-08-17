# AI 助手 Web 应用 — 开发计划

> **相关文档**：[技术方案](./technical-plan.md)

## 总览

本计划将开发工作分为 7 个阶段，每个阶段产出可验证的交付物。阶段之间尽量保持松耦合，便于并行或调整顺序。

### 复杂度标记说明

| 标记 | 含义 |
|------|------|
| 🟢 低 | 简单任务，配置/模板化工作 |
| 🟡 中 | 常规开发任务，需要一定逻辑实现 |
| 🔴 高 | 复杂任务，涉及核心逻辑或技术难点 |

---

## 阶段 1：基础设施搭建

**目标**：完成项目依赖安装、基础配置、目录结构创建。

**整体复杂度**：🟢 低

### 任务清单

- [ ] 1.1 安装核心依赖：`pinia`、`markdown-it`、`highlight.js`
- [ ] 1.2 安装开发依赖：`tailwindcss`、`@tailwindcss/vite`、`unplugin-icons`、`@iconify-json/mdi`、`@types/markdown-it`
- [ ] 1.3 配置 Tailwind CSS（创建 `tailwind.config.ts`，引入 `vite.config.ts`）
- [ ] 1.4 配置 Vite 代理（`/api/ollama` → `http://localhost:11434`）
- [ ] 1.5 创建目录结构（`adapters/`、`stores/`、`services/`、`types/`、`constants/`、`composables/`、`mocks/`）
- [ ] 1.6 在 `main.ts` 中注册 Pinia
- [ ] 1.7 定义全局 CSS 变量（浅色/深色主题色板）于 `style.css`

### 交付物

- 项目可正常 `npm run dev` 启动
- Tailwind CSS 原子化类名可用
- Vite 代理配置完成

---

## 阶段 2：类型系统与常量定义

**目标**：建立完整的 TypeScript 类型体系和常量定义。

**整体复杂度**：🟢 低 ｜ **任务数**：4

### 任务清单

- [ ] 2.1 编写 `src/types/model.ts`：`MessageRole`、`ContentBlock`、`ChatMessage`、`Conversation`、`ModelConfig`、`ModelCapabilities`、`StreamCallbacks`、`IModelAdapter`
- [ ] 2.2 编写 `src/types/settings.ts`：`AppSettings`、`ThemeMode`
- [ ] 2.3 编写 `src/constants/model.ts`：默认模型配置、提供商常量
- [ ] 2.4 编写 `src/constants/storage.ts`：localStorage key 常量

### 交付物

- 类型文件无 TS 编译错误
- 常量文件被后续模块引用

---

## 阶段 3：模型适配层

**目标**：实现 Ollama 适配器，支持流式和非流式对话。

**整体复杂度**：🔴 高 ｜ **任务数**：3（含 6 个子任务）

### 任务清单

- [ ] 3.1 实现 `src/adapters/OllamaAdapter.ts`
  - [ ] `chat()` 方法：流式模式（ReadableStream + NDJSON 解析）
  - [ ] `chat()` 方法：非流式模式（单次 JSON 响应）
  - [ ] `abort()` 方法：取消请求
  - [ ] `listModels()` 方法：获取 Ollama 可用模型列表
  - [ ] 处理 `message.thinking`（思考过程）
  - [ ] 处理 `message.tool_calls`（工具调用）
- [ ] 3.2 实现 `src/adapters/index.ts`：适配器工厂函数
- [ ] 3.3 实现 `src/mocks/chatMock.ts`：Mock 流式响应（含思考过程模拟）

### 交付物

- 适配器可通过浏览器控制台手动调用验证
- Mock 模式可模拟完整流式输出

### 验证方式

```
// 在浏览器控制台执行（需 Ollama 运行中）
import { createAdapter } from './src/adapters';
const adapter = createAdapter('ollama');
adapter.chat(
  [{ id: '1', role: 'user', content: '你好', blocks: [], createdAt: 0, updatedAt: 0 }],
  { id: '1', name: 'deepseek-r1', provider: 'ollama', baseUrl: '/api/ollama', capabilities: { streaming: true, thinking: true, toolCall: false, vision: false } },
  { onText: (c) => console.log(c), onDone: () => console.log('done') }
);
```

---

## 阶段 4：状态管理与服务层

**目标**：实现 Pinia Store 和核心服务。

**整体复杂度**：🔴 高 ｜ **任务数**：8（含 18 个子任务）

### 任务清单

- [ ] 4.1 实现 `src/stores/chatStore.ts`
  - [ ] 会话 CRUD（创建、切换、删除、重命名）
  - [ ] `sendMessage()` 流式对话流程
  - [ ] `stopGeneration()` 停止生成
  - [ ] 消息 blocks 的实时更新逻辑
  - [ ] 流式/非流式模式切换
- [ ] 4.2 实现 `src/stores/modelStore.ts`
  - [ ] 模型配置管理（增删改）
  - [ ] 当前选中模型
  - [ ] 从 Ollama 拉取模型列表
- [ ] 4.3 实现 `src/stores/settingsStore.ts`
  - [ ] 主题切换（浅色/深色/跟随系统）
  - [ ] 流式模式默认值
  - [ ] 发送快捷键设置（Enter / Ctrl+Enter）
- [ ] 4.4 实现 `src/stores/authStore.ts`
  - [ ] 登录状态管理（简单 token 机制，预留扩展）
- [ ] 4.5 实现 `src/services/storageService.ts`
  - [ ] 会话持久化（localStorage）
  - [ ] 模型配置持久化
  - [ ] 设置持久化
- [ ] 4.6 实现 `src/services/markdownService.ts`
  - [ ] markdown-it 初始化与配置
  - [ ] highlight.js 代码高亮集成
- [ ] 4.7 实现 `src/composables/useAutoScroll.ts`
  - [ ] 新消息自动滚动到底部
  - [ ] 用户手动滚动时暂停自动滚动
- [ ] 4.8 实现 `src/utils/id.ts`
  - [ ] 基于 `crypto.randomUUID()` 的 ID 生成

### 交付物

- Store 可通过 Pinia DevTools 查看状态
- 存储服务可正确读写 localStorage

---

## 阶段 5：UI 组件开发

**目标**：实现所有对话相关 UI 组件。

**整体复杂度**：🟡 中 ｜ **任务数**：7（含 22 个子任务）

### 任务清单

- [ ] 5.1 实现 `src/components/common/MarkdownRenderer.vue`
  - [ ] 接收 Markdown 字符串，渲染为 HTML
  - [ ] 代码块语法高亮
  - [ ] 代码块复制按钮
- [ ] 5.2 实现 `src/components/chat/ThinkingBlock.vue`
  - [ ] 可折叠/展开面板
  - [ ] 流式时显示闪烁光标动画
  - [ ] 完成后显示思考耗时
  - [ ] 默认折叠
- [ ] 5.3 实现 `src/components/chat/ToolCallBlock.vue`
  - [ ] 四状态展示（generating / executing / completed / error）
  - [ ] 参数 JSON 格式化展示
  - [ ] 结果 JSON 格式化展示
  - [ ] 状态图标与颜色区分
- [ ] 5.4 实现 `src/components/chat/MessageItem.vue`
  - [ ] 区分用户消息和助手消息样式
  - [ ] 遍历 `blocks` 渲染 ThinkingBlock / TextBlock / ToolCallBlock
  - [ ] 消息时间戳展示
- [ ] 5.5 实现 `src/components/chat/MessageList.vue`
  - [ ] 虚拟滚动或普通滚动（根据消息量决定）
  - [ ] 集成 `useAutoScroll`
  - [ ] 空状态提示
- [ ] 5.6 实现 `src/components/chat/ChatInput.vue`
  - [ ] 多行文本输入（自适应高度）
  - [ ] 流式/非流式切换开关
  - [ ] 模型选择下拉框
  - [ ] 发送按钮 + 停止生成按钮
  - [ ] 快捷键支持（Enter 发送 / Ctrl+Enter 换行，可配置）
- [ ] 5.7 实现 `src/components/chat/ChatSidebar.vue`
  - [ ] 新建对话按钮
  - [ ] 会话列表（按更新时间倒序）
  - [ ] 当前会话高亮
  - [ ] 会话删除（带确认）
  - [ ] 会话重命名
  - [ ] 底部导航（设置入口）

### 交付物

- 所有组件可在 Storybook 或页面中独立预览
- 组件 Props 类型完整

---

## 阶段 6：页面集成

**目标**：将组件组装到页面中，完成完整交互流程。

**整体复杂度**：🟡 中 ｜ **任务数**：5（含 11 个子任务）

### 任务清单

- [ ] 6.1 实现 `src/views/Chat.vue`
  - [ ] 左侧 Sidebar + 右侧主内容区布局
  - [ ] 集成 MessageList + ChatInput
  - [ ] 连接 ChatStore
  - [ ] 响应式布局（移动端侧边栏可收起）
- [ ] 6.2 实现 `src/views/Settings.vue`
  - [ ] 模型配置管理（添加/编辑/删除 Ollama 模型）
  - [ ] 通用设置（主题、流式模式默认值、快捷键）
  - [ ] 关于页面
- [ ] 6.3 实现 `src/views/Login.vue`
  - [ ] 简单登录表单（预留，当前可跳过直接进 Chat）
  - [ ] 登录成功后跳转 Chat
- [ ] 6.4 完善 `src/App.vue`
  - [ ] 全局布局容器
  - [ ] 主题切换逻辑
- [ ] 6.5 路由守卫
  - [ ] 未登录跳转 Login（可选，视需求决定是否强制登录）

### 交付物

- 完整可交互的对话应用
- 从新建对话 → 输入消息 → 流式接收回复 → 思考过程折叠 → 工具调用展示 全流程可用

---

## 阶段 7：优化与收尾

**目标**：性能优化、错误处理、体验打磨。

**整体复杂度**：🟡 中 ｜ **任务数**：5（含 11 个子任务）

### 任务清单

- [ ] 7.1 流式渲染性能优化
  - [ ] 使用 `requestAnimationFrame` 节流 UI 更新
  - [ ] 对 blocks 数组使用 `shallowRef` 减少深层响应式开销
- [ ] 7.2 错误处理完善
  - [ ] 网络错误友好提示
  - [ ] Ollama 未启动检测
  - [ ] 模型不存在错误处理
  - [ ] 请求超时处理
- [ ] 7.3 体验优化
  - [ ] 消息发送中禁用输入框
  - [ ] 复制消息功能
  - [ ] 重新生成回复
  - [ ] 编辑已发送消息
- [ ] 7.4 深色主题完善
  - [ ] 所有组件适配深色主题
  - [ ] 主题切换无闪烁
- [ ] 7.5 构建与部署
  - [ ] `npm run build` 无错误
  - [ ] 打包体积分析与优化

### 交付物

- 生产构建成功
- 无明显性能问题
- 错误场景有友好提示

---

## 工作量概览

### 团队配置

- 前端开发：1 人（经验丰富）

### 工作量与时间估算

| 阶段 | 复杂度 | 任务数 | 子任务数 | 预估工时 | 说明 |
|------|--------|--------|----------|----------|------|
| 阶段 1 基础设施搭建 | 🟢 低 | 7 | 7 | 0.5 天 | 配置为主，快速完成 |
| 阶段 2 类型系统与常量 | 🟢 低 | 4 | 4 | 0.5 天 | 纯类型定义，无运行时逻辑 |
| 阶段 3 模型适配层 | 🔴 高 | 3 | 9 | 1.5 天 | 核心难点：流式 NDJSON 解析、思考/工具调用处理 |
| 阶段 4 状态管理与服务 | 🔴 高 | 8 | 18 | 2 天 | 核心难点：流式回调与 blocks 实时更新 |
| 阶段 5 UI 组件开发 | 🟡 中 | 7 | 22 | 3 天 | 任务量最大，但各组件独立可逐步推进 |
| 阶段 6 页面集成 | 🟡 中 | 5 | 11 | 1.5 天 | 组装性质，依赖阶段 5 完成 |
| 阶段 7 优化与收尾 | 🟡 中 | 5 | 11 | 1.5 天 | 体验打磨，可分优先级逐步完成 |
| **合计** | — | **39** | **82** | **≈ 10.5 天** | — |

### 里程碑节点

| 里程碑 | 包含阶段 | 累计工时 | 交付标志 |
|--------|----------|----------|----------|
| M1 基础就绪 | 阶段 1 + 2 | 1 天 | 项目可启动，类型系统就绪 |
| M2 核心能力可用 | 阶段 3 + 4 | 4.5 天 | 可通过控制台验证流式对话 |
| M3 界面完成 | 阶段 5 + 6 | 9 天 | 完整可交互的对话应用 |
| M4 发布就绪 | 阶段 7 | 10.5 天 | 生产构建通过，体验完善 |

> **说明**：以上估算基于 1 名经验丰富前端开发者全职投入、本地有 Ollama 环境的前提。如 Ollama 环境不可用，阶段 3 的调试时间可能增加 0.5 天（需依赖 Mock 模式开发）。阶段 3 和阶段 4 理论上可交替推进，但单人开发实际仍为串行。

---

## 依赖关系

```mermaid
graph LR
    P1[阶段1 基础设施] --> P2[阶段2 类型系统]
    P2 --> P3[阶段3 适配层]
    P2 --> P4[阶段4 状态管理]
    P3 --> P5[阶段5 UI组件]
    P4 --> P5
    P5 --> P6[阶段6 页面集成]
    P6 --> P7[阶段7 优化收尾]
```

阶段 3 和阶段 4 可并行开发（适配层和状态管理互不依赖实现细节，只依赖类型定义）。

---

## 技术决策记录

| 决策 | 选择 | 备选方案 | 理由 |
|------|------|----------|------|
| 流式读取 | 原生 fetch + ReadableStream | ofetch / axios | 零依赖，Ollama NDJSON 格式简单 |
| 状态管理 | Pinia | Vuex 4 | Vue 3 官方推荐，TS 友好 |
| CSS 方案 | Tailwind CSS | UnoCSS | 生态成熟，社区资源丰富，插件完善 |
| Markdown | markdown-it | marked | 插件生态更丰富 |
| 存储 | localStorage | IndexedDB | 初期数据量小，API 简单；后续可迁移 |
| 组件样式 | Scoped CSS + Tailwind CSS | CSS Modules | 与 Tailwind 配合更自然 |
