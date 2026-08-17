# 禁止魔法字符串规则（按需：编辑 src/**/*.ts / src/**/*.vue 时）

> 代码中**严禁使用魔法字符串（magic strings）**。所有用于条件判断、比较、映射的字符串字面量，**必须提取为命名常量**。

## 什么是魔法字符串

魔法字符串是指在代码中直接硬编码的字符串字面量，用于：

- 条件判断（`if`、`v-if`、`switch`）
- 对象映射的键
- 函数参数的特殊值
- 状态、类型等枚举值

## 禁止的写法

```typescript
// ❌ 模板中直接使用字符串字面量
<template v-if="row.status === '未下发'">
// ❌ 脚本中直接使用字符串字面量
if (status === 'pending') { ... }
// ❌ 映射对象中使用字符串字面量作为键
const map: Record<string, string> = { 未下发: 'info', 待整改: 'warning' };
```

## 允许的写法

```typescript
// 1. 在模块的 constants.ts 中定义常量
export const RECTIFY_STATUS_NAME = {
  NOT_ISSUED: '未下发',
  PENDING: '待整改',
  DONE: '已整改',
  EVALUATED: '已评价',
} as const;

// 2. 在组件中导入并使用常量
import { RECTIFY_STATUS_NAME } from '../constants';
<template v-if="row.status === RECTIFY_STATUS_NAME.NOT_ISSUED">

// 3. 映射对象中使用常量作为键
const map: Record<string, string> = {
  [RECTIFY_STATUS_NAME.NOT_ISSUED]: 'info',
  [RECTIFY_STATUS_NAME.PENDING]: 'warning',
};
```

## 常量定义规范

### 文件位置

- **模块级常量**：定义在模块目录下的 `constants.ts` 中（如 `src/views/{Module}/constants.ts`）
- **全局常量**：定义在 `src/types/` 或 `src/constants/` 中

### 命名规范

- 常量对象使用 **UPPER_SNAKE_CASE** 命名
- 属性名使用 **UPPER_SNAKE_CASE** 命名，语义清晰
- 使用 `as const` 断言确保类型收窄

```typescript
// ✅ 正确
export const ORDER_STATUS = {
  PENDING: 'pending',
  ACTIVE: 'active',
  COMPLETED: 'completed',
} as const;
```

### 与类型定义配合

如果模块的 `types.ts` 中已有对应的联合类型，常量应与类型保持一致：

```typescript
// types.ts
export type RectifyStatus = '未下发' | '待整改' | '已整改' | '已评价';
// constants.ts
export const RECTIFY_STATUS_NAME = {
  NOT_ISSUED: '未下发', PENDING: '待整改', DONE: '已整改', EVALUATED: '已评价',
} as const;
```

## 豁免情况

以下场景**可以**使用字符串字面量，无需提取常量：

1. 用户可见的展示文本（按钮文字、标签文字）
2. CSS 类名（Tailwind 类名等）
3. `console.log` 调试信息（开发调试用，生产代码不应保留）
4. 唯一性标识（如 `v-for` 的 key、组件 name 等不影响逻辑的标识）
5. 已有类型约束的函数参数（参数类型已通过 TypeScript 类型严格约束时）

## 检查清单

- [ ] 条件判断中是否有硬编码的字符串字面量
- [ ] 映射对象的键是否有硬编码的字符串字面量
- [ ] 状态、类型等枚举值是否已提取为常量
- [ ] 常量是否定义在 `constants.ts` 中
- [ ] 常量对象是否使用了 `as const` 断言
- [ ] 常量命名是否语义清晰
