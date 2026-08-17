# Vue 单文件组件结构规范（按需：创建 / 编辑 Vue SFC 时）

> 保持 Vue 单文件组件**结构规范、模板简洁**，遵循统一的块顺序，避免不必要的嵌套、冗余属性和额外样式。
> 本项目的真实技术栈：**Vue 3.5 + Vuetify 4（Material Design 3）** + **Tailwind CSS 4**（CSS 优先配置，无 `tailwind.config.js`）+ **Sass（sass-embedded）**。

## 1. 避免不必要的元素嵌套

Vue 3 模板支持**多个根节点**，因此不要仅因 Vue 2 习惯添加包裹层。

```vue
<!-- ❌ 错误：仅因 Vue 2 习惯添加的包裹层 -->
<template>
  <div>
    <v-form>
      <v-text-field label="名称" v-model="form.name" />
    </v-form>
    <v-btn color="primary" @click="onSubmit">提交</v-btn>
  </div>
</template>

<!-- ✅ 正确：利用 Vue 3 多根节点 -->
<template>
  <v-form>
    <v-text-field label="名称" v-model="form.name" />
  </v-form>
  <v-btn color="primary" @click="onSubmit">提交</v-btn>
</template>
```

> 仍需要外层容器的情况：需要统一布局（如 `d-flex` / `grid`）、整体样式、语义化 HTML、统一 `v-if`/`v-for`、统一过渡动画。

## 2. 避免不必要的属性

除非有必要，**不要给元素添加 `id`、`class` 等属性**。

- `id`：仅在表单关联、无障碍访问或 DOM 操作时添加
- `class`：仅在需要添加样式时添加
- `style`：尽量避免内联样式，优先用 Vuetify 组件 props / Utility Classes / Tailwind / 自定义样式

## 3. 样式优先级

按以下优先级选择样式方案：

1. **Vuetify 组件自带样式**（首选）：组件 props（`color`、`size`、`variant`、`density` 等）能满足的，不再额外加样式
2. **Vuetify Utility Classes（Vuetify4 / MD3 语法）**：`d-flex`、`align-center`、`ga-2`、`text-body-medium`、`bg-surface-variant`、`pa-3`、`rounded-lg`、`mt-4` 等；注意颜色只能用 MD3 角色名（如 `surface-variant`、`on-surface-variant`、`primary`、`secondary`），**禁止使用 Vuetify3 的 `grey-lighten-4` / `grey-darken-1` 等中性色 step 命名，以及 `text-body-2` / `text-caption` / `text-subtitle-1` 等 MD2 排版类（应改用 `text-body-medium` / `text-body-small` / `text-title-medium`）**
3. **Tailwind CSS 4**：本项目 v4 采用 **CSS 优先配置**，默认**没有 `tailwind.config.js`**；以 `vite.config.ts` 是否含 `@tailwindcss/vite` 插件为准（本项目已启用）
4. **项目自定义样式方案**：`src/style.scss`、SCSS Mixins、CSS Variables 等
5. **自定义样式（最后手段）**：只有以上均不满足时才用 `<style lang="scss" scoped>`

## 4. 常见反模式

- 无意义的包裹 div（多个纯布局 div 嵌套）
- 过度使用 class 命名（应优先 Tailwind / Vuetify 工具类）
- 用 div 模拟组件行为（本项目用 Vuetify，优先用 `v-btn` 等组件）

## 5. SFC 块顺序（`<script>` → `<template>` → `<style>`）

三个顶层块**必须按此顺序排列**；`<script>` 用 `<script setup lang="ts">`。

```vue
<script setup lang="ts">
const msg = 'hello';
</script>

<template>
  <div>内容</div>
</template>

<style lang="scss" scoped>
.container {}
</style>
```

## 6. `<style>` 标签属性顺序

`lang` 在前，`scoped` 在后：`<style lang="scss" scoped>`（禁止 `<style scoped lang="scss">`）。

## 检查清单

- [ ] SFC 块顺序是否为 `<script>` → `<template>` → `<style>`
- [ ] 是否存在仅因 Vue 2 习惯添加的无意义包裹层
- [ ] 是否存在无意义的嵌套元素
- [ ] 元素上的 `id`、`class` 属性是否都有明确用途
- [ ] 是否优先使用了 Vuetify 组件自带样式与 Utility Classes
- [ ] 是否确认项目已启用 Tailwind v4（而非查找不存在的 `tailwind.config.js`）
- [ ] 自定义样式是否确实无法用以上方案替代
- [ ] `<style>` 标签属性顺序是否为 `lang` 在前、`scoped` 在后
