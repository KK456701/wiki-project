---
# DESIGN.md — 指标助手（winning-webui-mras-aima）视觉设计系统
# 格式参考 Google Stitch 开源规范（google-labs-code/design.md, Apache-2.0, alpha）
# 上层 YAML = 机器可读令牌；下层 Markdown = 设计判断（给人类/AI 的解释）
# Agent 生成任何 UI 时，颜色/间距/圆角必须引用下方令牌名，禁止凭默认审美猜测。

brand:
  name: 指标助手
  vibe: 医疗质量管理 · 专业 · 可信 · 克制
  basePath: /webui-mras-aima/
  uiKit: Vuetify 4 (Material Design 3)
  utilityCss: Tailwind CSS 4
  iconSet: MDI (Material Design Icons)

colors:
  primary:    { value: "#2D5AFA", on: "#FFFFFF", hover: "#5175F4", press: "#1D39C4", role: "主题蓝主色 / 关键操作 / 导航激活态" }
  secondary:  { value: "#666666", on: "#FFFFFF", role: "中性灰 / 次要文字 / 辅助信息" }
  accent:     { value: "#41EDFF", on: "#000000", role: "强调青 / 顶部导航激活描边" }
  success:    { value: "#00AB44", on: "#FFFFFF", hover: "#08C955", press: "#186C3A", role: "通过 / 正常 / 完成" }
  warning:    { value: "#FF8C00", on: "#FFFFFF", hover: "#FFAC48", press: "#DB5B03", role: "待确认 / 阻塞 / 需注意" }
  error:      { value: "#EC0000", on: "#FFFFFF", hover: "#FF5555", press: "#B61E1E", role: "失败 / 异常 / 错误" }
  info:       { value: "#999999", on: "#FFFFFF", hover: "#B1B1B1", press: "#7D7D7D", role: "信息 / 中性状态" }
  text:
    primary:   "#000000"
    secondary: "#666666"
    disabled:  "#999999"
    white:     "#FFFFFF"
  border:
    primary:   "#BABABA"
    secondary: "#C9C9C9"
    tertiary:  "#E9E9E9"
  surface:
    page:      "#FAFAFA"
    white:     "#FFFFFF"
    variant:   "#F5F5F5"
  # 暗色主题覆盖（仅列差异，其余继承亮色语义）
  dark:
    primary:   "#5175F4"
    surface:   "#1E1E1E"
    onSurface: "#E0E0E0"
    background: "#121212"
    onBackground: "#E0E0E0"
    surfaceVariant: "#2C2C2C"
    outline:   "#3D3D3D"
  # 可选品牌方案（替换 primary 即可，不要自由发挥新色值）
  altSchemes:
    sauce-purple:   "#722ED1"
    maternity-pink: "#F24F86"
    innovation-green: "#1F8970"
    calendula:      "#7B4A36"

typography:
  fontFamily: # 未在 vuetify.ts 自定义，使用 Vuetify 内置默认（Roboto）+ 系统 CJK 回退；不要假设存在品牌定制字体
    base: ['Roboto', 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', 'sans-serif']
    mono: ['Fira Code', 'Monaspace Krypton', 'source-code-pro', 'Menlo', 'Monaco', 'Consolas', 'monospace']
  scale: # Vuetify 默认排版令牌（fontSize / lineHeight / weight）
    display-1: { size: "6rem",   lh: 1.167, weight: 300 }
    display-2: { size: "3.75rem", lh: 1.2,  weight: 300 }
    display-3: { size: "3rem",   lh: 1.167, weight: 400 }
    headline-4: { size: "2.125rem", lh: 1.235, weight: 400 }
    headline-5: { size: "1.5rem", lh: 1.333, weight: 400 }
    headline-6: { size: "1.25rem", lh: 1.6, weight: 500 }
    subtitle-1: { size: "1rem",   lh: 1.75, weight: 400 }
    subtitle-2: { size: "0.875rem", lh: 1.57, weight: 500 }
    body-1:    { size: "1rem",   lh: 1.5, weight: 400 }
    body-2:    { size: "0.875rem", lh: 1.43, weight: 400 }
    button:    { size: "0.875rem", lh: 1.75, weight: 500 }
    caption:   { size: "0.75rem", lh: 1.667, weight: 400 }
    overline:  { size: "0.75rem", lh: 2.333, weight: 500, transform: "uppercase" }

spacing: # 基准 4px；Vuetify 与 Tailwind 共用同一栅格
  unit: 4
  scale: { 1: 4, 2: 8, 3: 12, 4: 16, 6: 24, 8: 32, 12: 48, 16: 64 }

radius:
  none: 0
  sm: 2     # rounded-sm
  base: 4   # rounded（Vuetify 组件默认）
  lg: 8     # rounded-lg（卡片推荐）
  xl: 12    # rounded-xl
  pill: 9999

elevation: # Vuetify 阴影层级（0–24）；克制使用
  flat: 0
  card: 1
  raised: 2
  dialog: 24

icons:
  set: mdi
  usage: "统一使用 Material Design Icons；禁止引入其它图标集"

components:
  button-primary: { color: "{colors.primary}", radius: "{radius.base}", weight: 500 }
  button-text:    { color: "{colors.primary}", variant: "text" }
  card:           { surface: "{colors.surface.white}", radius: "{radius.lg}", elevation: "{elevation.card}" }
  chip-status:    { palette: ["success", "warning", "error", "info", "primary"] } # 状态色见 diagnosis STEP_STATUS_COLOR
  dialog:         { radius: "{radius.lg}", elevation: "{elevation.dialog}" }
  table:          { kit: "v-data-table / v-table", surface: "{colors.surface.white}" }
  input:          { kit: "v-text-field / v-select / v-checkbox-btn" }
  nav-active:     { color: "{colors.accent}" }
---

# 指标助手 · 视觉设计系统（DESIGN.md）

> 本文件是项目的**视觉宪法**。AI 编码代理读到它，应按规则执行，而不是凭默认审美猜。
> 机器可读令牌见上方 YAML；下方是人类可读的设计判断、场景说明与设计禁忌。
> 架构/工程约定请见 [`AGENTS.md`](AGENTS.md)；逐层技术架构见 [`docs/frontend-architecture.md`](docs/frontend-architecture.md)。

## 1. 品牌调性（Overview）

指标助手是面向**医院管理人员**的 AI 对话式医疗指标分析工具。视觉上要传达**专业、可信、克制**：

- 用「主题蓝」`{colors.primary}` 作为唯一主色承载关键操作与导航激活，不堆砌多彩。
- 大量界面是**数据表格与统计结果**，因此中性灰（`{colors.secondary}` / 边框色）承担主要视觉重量，保证信息密度下的可读性。
- 语义色（成功/警告/错误/信息）**只用于状态反馈**，不作为装饰；滥用会削弱警示意味。
- 整体密度偏「中等偏紧凑」，契合后台数据工具的长期盯屏场景。

## 2. 色彩体系与语义角色（Colors）

| 角色 | 色值（亮色） | 用途 |
|------|--------------|------|
| 主色 primary | `#2D5AFA` | 主按钮、链接、导航激活、关键 CTA |
| 次要 secondary | `#666666` | 次要文字、辅助说明 |
| 强调 accent | `#41EDFF` | 顶部导航激活描边（极少大面积使用） |
| 成功 success | `#00AB44` | 校验通过 / 指标正常 / 排查完成 |
| 警告 warning | `#FF8C00` | 待确认 / 阻塞 / 需注意 |
| 错误 error | `#EC0000` | 失败 / 异常 / 错误状态 |
| 信息 info | `#999999` | 中性信息 |
| 页面背景 | `#FAFAFA` | App 背景 |
| 表面 | `#FFFFFF` | 卡片 / 弹窗 / 表格 |

**状态色映射（诊断功能强制）**：`src/views/ChatView/components/diagnosis/diagnosis-constants.ts` 的 `STEP_STATUS_COLOR` 已定义步骤状态 → Vuetify 语义色（如 `WAITING_CALIBER_CONFIRMATION→warning`、`GATES_PASSED→success`、`SHADOW_FAILED→error`）。生成状态 `v-chip` 时**直接引用该映射**，不要另起一套颜色。

**品牌方案切换**：需要换肤时，从 `altSchemes` 四选一替换 `primary`；**不要**自由发明新色值。

**暗色模式**：仅覆盖 `dark.*` 中列出的差异项（主色提亮、表面/背景转深），其余语义色继承亮色。两种主题均需满足 **WCAG AA 文本对比度**（≥4.5:1）。

## 3. 字体层级与排版（Typography）

- **正文字体**：未自定义，使用 Vuetify 内置默认（Roboto）+ 系统 CJK 回退（PingFang SC / 微软雅黑 / Noto Sans SC）。**不要假设项目有品牌定制字体**，也不要引入网页字体额外下载。
- **代码/等宽**：使用 `typography.fontFamily.mono` 栈（Fira Code 优先，回退到系统等宽），仅用于代码块、trace 节点、SQL 片段。
- **层级**：严格使用 Vuetify 排版令牌（`text-h1`…`text-overline`，见 YAML `scale`），不要手写 `font-size`。中文正文主用 `text-body-1`(16px) / `text-body-2`(14px)；数据密集表格允许 `text-caption`(12px)。
- 标题与正文保持 ≥1.5 行高，数据表格行距可适当收紧但仍需可点击区域 ≥32px。

## 4. 间距系统（Spacing）

- 基准 **4px**，Vuetify 与 Tailwind 共用同一栅格（如 `ma-2`=8px，`gap-4`=16px）。
- 组件内边距常规节奏：卡片 `pa-4`(16px)，区块间距 `ma-4`~`ma-6`(16–24px)，密集列表可降到 `ma-2`(8px)。
- 不要在 Tailwind 里用非 4 倍数的魔法间距（如 `mt-5`=20px 例外需有理由）；保持节奏统一是「看起来一致」的关键。

## 5. 形状与圆角（Shape）

- 组件默认圆角 **4px**（`rounded`）；**卡片推荐 8px**（`rounded-lg`），更柔和且区分层级。
- 按钮、输入框统一 `rounded`（4px），不要做 0 圆角的「硬边」工业风，也不要做 `rounded-xl`(12px) 以上的大圆角（会显得轻浮）。
- 头像/徽标可用 `rounded-pill`；除此外避免 pill。

## 6. 阴影与深度（Elevation）

- 克制使用阴影。卡片 `elevation-1`~`elevation-2`，弹窗 `elevation-24`（Vuetify 默认）。
- **不要**给页面背景或整块内容区加阴影；用 `surface-variant`(#F5F5F5) 或边框色区分区块，比阴影更克制、更适合数据后台。
- 悬停态优先用 `primary-hover` 变色，而非加深阴影。

## 7. 组件样式（Components）

统一基于 **Vuetify 4 官方组件**，用组件 props（`color` / `size` / `variant`）控制外观，**不要手写等效 HTML+CSS**。

| 组件 | 规范 |
|------|------|
| 按钮 `v-btn` | 主操作 `color="primary"`；次级用 `variant="text"` 或 `tonal`；禁用自定义背景 hex |
| 卡片 `v-card` | `surface` 白底 + `rounded-lg` + `elevation-1`；页面级区块可用 `flat`+边框 |
| 状态 `v-chip` | 颜色取自 `STEP_STATUS_COLOR` 语义映射；小圆角、不描边 |
| 表格 `v-data-table` / `v-table` | 所有对比/明细表格的**唯一**实现方式（含诊断对比表） |
| 表单 `v-text-field` / `v-select` / `v-checkbox-btn` | 统一控件，禁止自绘 input |
| 弹窗 `v-dialog` | `rounded-lg`，内容区留白 `pa-4`~`pa-6` |
| 导航 `v-app-bar` / `v-navigation-drawer` | 激活项用 `accent` 描边或 `primary` 文字 |

**查故障（诊断）功能红线**：`readonly/winning-webui-mras-aima` 仅作参考，**禁止拷贝其自定义 CSS 类**（如 `.diagnosis-compare-table`、`data-state` 徽章）。任何移植能力必须用 Vuetify 4 官方组件 + Material Design 重写——对比表用 `v-data-table`、状态用 `v-chip`、折叠用 `v-expansion-panel`、加载用 `v-progress-linear`。

## 8. 图标（Icons）

- 统一 **Material Design Icons (MDI)**，经 `vuetify/iconsets/mdi` 注册，默认集 `mdi`。
- 诊断步骤图标已在 `DIAGNOSIS_STEP_ICONS` 中定义（`mdi-file-document-check-outline`、`mdi-table-cog`、`mdi-calendar-check-outline`、`mdi-counter` 等），新增步骤图标须沿用 MDI 且语义贴合。
- **禁止**引入 Font Awesome、Iconfont 等其它图标集。

## 9. 布局与栅格（Layout）

- 应用部署于 `/webui-mras-aima/`，所有路由与资源路径基于该 base（勿硬编码根路径）。
- 响应式：优先用 Vuetify 栅格（`v-container` / `v-row` / `v-col`）承载页面结构，Tailwind flex 工具类只做局部微调。
- 对话主区（ChatView）为「左会话栏 + 中消息流 + 右详情抽屉」三栏；抽屉在窄屏降级为 `temporary` 覆盖。

## 10. 设计禁忌（Do's & Don'ts）

**Do**
- 生成 UI 时先读本文件，颜色/间距/圆角引用 YAML 令牌名（如 `{colors.primary}`）。
- 状态色用语义角色（success/warning/error/info），不硬编码 hex。
- 明暗主题都验证对比度（WCAG AA）。
- 间距走 4px 栅格；组件优先 Vuetify props。

**Don't**
- 不要手写 hex 覆盖 Vuetify 组件颜色（除非自定义 SCSS 这「最后手段」）。
- 不要引入非 MDI 图标集或网页字体。
- 不要用 Tailwind preflight 去重置 Vuetify 边框——层叠冲突已由 `vite.config.ts` 的 `cssLayerOrderPlugin` 在构建期自动修复。
- 不要复制 readonly 仓库的自定义 CSS 类；诊断 UI 必须 Vuetify 4 重写。
- 不要随意改主色；换肤从四个预置 `altSchemes` 选一。
- 不要给大块内容区加阴影；用 surface-variant / 边框区分层级。

## 11. Agent 使用指引

1. 在本仓库生成或修改任何界面组件前，本文件为**最高优先视觉约束**，高于通用审美默认。
2. 新组件一律基于 Vuetify 4；需要颜色/尺寸时引用上方令牌，例如主按钮 `color="{colors.primary}"`、卡片 `rounded="{radius.lg}"`。
3. 涉及诊断/查故障界面，遵循 §7 红线与 `STEP_STATUS_COLOR` 映射。
4. 不确定某令牌值时，以 `src/plugins/vuetify.ts`（`WD_COLORS` 常量）与 YAML 为准，二者冲突以 `vuetify.ts` 运行时值为真值。
5. 校验：可用 Google `DESIGN.md` CLI 的 `validate`（含 WCAG 对比度检查）对设计资产做一致性核对。
