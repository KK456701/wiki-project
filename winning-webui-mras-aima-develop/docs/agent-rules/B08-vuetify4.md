# Vuetify 4（Material Design 3）语法强制规范（按需：涉及 Vuetify 组件 / 类名 / 主题时）

> 本项目使用 **Vuetify 4.1（Material Design 3，简称 MD3）**。
> Vuetify 3 的 MD2 排版类名、中性色 step 命名（`grey-lighten-*` / `grey-darken-*`）、旧栅格 props 等**已全部废弃**，混入后会静默降级为无样式或错误表现。
> 本规则是**硬红线**：任何 AI 生成 / 修改的 Vue 模板与 SCSS 都必须遵循 Vuetify4 语法，禁止出现 Vuetify3 写法。

## 1. 长效护栏（最重要）

本项目已在 `eslint.config.js` 中注册官方 **`eslint-plugin-vuetify`** 的 **`recommended-v4`** 预设（作用于 `**/*.vue`），开启以下规则，从 CI / lint 层面阻断 Vuetify3 写法：

- `vuetify/no-deprecated-typography`：禁止 MD2 排版类（`text-caption` / `text-subtitle-1` / `text-h5` 等），并可由 `eslint --fix` 自动迁移为 MD3 token。
- `vuetify/no-legacy-grid-props`：禁止 `<v-flex>`、`grid-list-*` 等旧栅格 props。
- `vuetify/no-elevation-overflow`：禁止超出 Vuetify4 支持范围的 `elevation` 值（v4 仍为 `elevation="0".."24"`，但规则防越界）。
- `vuetify/no-deprecated-snackbar`：禁止废弃的 snackbar 写法。

> 生成代码后务必执行 `npm run lint`（含 `--max-warnings 0`），不得遗留任何 `vuetify/*` 报错/警告。
> 迁移存量代码可用：`npx eslint src --ext .vue --fix`（自动修复 typography 类）。

## 2. 排版类（Typography）—— 必须使用 MD3 token

Vuetify4 排版工具类为 **MD3 type scale**，与 Vuetify3 MD2 完全不兼容。

### 2.1 强制映射表（v3 → v4，官方 `no-deprecated-typography` 自动修复口径）

| Vuetify3（禁止） | Vuetify4（正确） | 说明 |
|------|------|------|
| `text-h1` | `text-display-large` | 最大显示标题 |
| `text-h2` | `text-display-medium` | |
| `text-h3` | `text-display-small` | |
| `text-h4` | `text-headline-large` | |
| `text-h5` | `text-headline-medium` | 区块标题常用 |
| `text-h6` | `text-headline-small` | |
| `text-subtitle-1` | `text-title-large` | 一级副标题 |
| `text-subtitle-2` | `text-title-medium` | 二级副标题 |
| `text-body-1` | `text-body-large` | 正文大字 |
| `text-body-2` | `text-body-medium` | 正文（默认） |
| `text-caption` | `text-body-small` | 辅助 / 说明文字（最常被误用） |
| `text-button` | `text-label-large` | 按钮内文字 |
| `text-overline` | `text-label-small` | 上标小字 |

> 本项目已统一用 `text-body-small` 表示说明文字、`text-medium-emphasis` 表示弱化文字、`text-title-medium` / `text-title-large` 表示标题。**新增代码沿用这些已存在的 token，不要自创**。

### 2.2 其他可用 MD3 token（按需）

- 显示：`text-display-large/medium/small`
- 标题：`text-headline-large/medium/small`、`text-title-large/medium/small`
- 正文：`text-body-large/medium/small`
- 标签：`text-label-large/medium/small`
- `text-overline`（保留，但语义为 label-small 风格）

## 3. 颜色与主题 —— 只用 MD3 角色名

Vuetify4 移除了 `grey` / `blue` / `red` 等按 step 命名的色板（如 `grey-lighten-4`、`grey-darken-1`、`red-accent-2`）。

- ✅ 文本 / 图标颜色用 **MD3 角色**：`primary`、`secondary`、`tertiary`、`error`、`success`、`warning`、`info`、`surface-variant`、`on-surface`、`on-surface-variant`、`background`、`outline` 等；弱化文字用 `text-medium-emphasis` / `text-high-emphasis`。
- ✅ 背景用 `bg-surface-variant`、`bg-primary` 等角色类。
- ❌ 禁止：`text-grey`、`text-grey-darken-1`、`text-grey-lighten-1`、`bg-grey-lighten-4`、`color="grey"`、`color="grey-lighten-1"` 等任何 `grey-*` / `*-lighten-*` / `*-darken-*` 写法。
- 本项目已约定：`on-surface-variant` 用于中性图标（占位图），`text-medium-emphasis` 用于次级 / 空状态文字。

## 4. 组件 props —— 使用 v4 MD3 变体系统

- ✅ 按钮 / 卡片 / 图标等用 `variant="flat" | "tonal" | "outlined" | "text" | "plain" | "elevated"`（v4 标准）。
- ❌ 禁止 Vuetify3 布尔写法：`<v-btn text>`、`<v-card outlined>`、`<v-text-field outlined>`、`<v-text-field filled>`。
- ✅ 圆角用 `rounded` / `rounded-sm` / `rounded-md` / `rounded-lg` / `rounded-xl` / `rounded-pill` / `rounded-circle`（v4 标准 scale）。
- ✅ 间距 / 布局类 `ma-*`、`pa-*`、`ga-*`、`d-flex`、`align-*`、`justify-*` 在 v4 仍然有效（间距 scale 与 v3 一致）。
- ✅ `<v-row>` / `<v-col cols="12" sm="6">` 在 v4 仍然有效；仅禁止 `<v-flex>` 与 `grid-list-*`。
- ✅ `<v-spacer>` 在 v4 仍有效。

## 5. 反模式清单（禁止）

- [ ] 出现任何 `text-h1`..`text-h6`、`text-subtitle-1/2`、`text-caption`、`text-button`、`text-overline`
- [ ] 出现任何 `grey-*` / `*-lighten-*` / `*-darken-*` 颜色命名
- [ ] 组件使用 v3 布尔变体（`text` / `outlined` / `filled` 作为无值属性）
- [ ] 使用 `<v-flex>` 或 `grid-list-*`
- [ ] 使用 `elevation` 越界值
- [ ] 在 `<style>` 中手写 Vuetify3 已移除的 CSS 变量名（如 `--v-grey-*`，应改用 `--v-theme-on-surface-variant` 等 MD3 变量）

## 6. 检查清单

- [ ] 模板中所有文字类均为 MD3 token（`text-display-*` / `text-headline-*` / `text-title-*` / `text-body-*` / `text-label-*`）
- [ ] 所有颜色均为 MD3 角色名，`npm run lint` 无 `vuetify/*` 报错
- [ ] 组件变体统一用 `variant="..."` 而非 v3 布尔属性
- [ ] 未引入 `<v-flex>` / `grid-list-*`
