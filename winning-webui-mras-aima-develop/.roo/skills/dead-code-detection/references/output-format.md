# 输出格式规范

> 本文档定义死代码检测报告的结构化输出格式，支持 Markdown、JSON 和控制台三种输出模式。

## 目录

1. [输出格式概览](#1-输出格式概览)
2. [Markdown 格式](#2-markdown-格式)
3. [JSON 格式](#3-json-格式)
4. [控制台格式](#4-控制台格式)
5. [字段说明](#5-字段说明)
6. [示例报告](#6-示例报告)

---

## 1. 输出格式概览

| 格式 | 适用场景 | 特点 |
|------|----------|------|
| **Markdown** | 文档归档、PR 评论 | 可读性强，支持链接 |
| **JSON** | CI/CD 集成、程序化处理 | 结构化，易于解析 |
| **Console** | 终端查看、快速检查 | 简洁直观，支持颜色 |

---

## 2. Markdown 格式

### 2.1 报告结构

```markdown
# 死代码检测报告

**生成时间**: 2026-07-24 09:55:00  
**检测范围**: src/  
**检测文件数**: 128  
**发现问题数**: 23

---

## 摘要

| 类型 | 数量 | 风险等级 |
|------|------|----------|
| 未使用导入 | 8 | 低 |
| 未使用导出 | 6 | 中 |
| 未使用依赖 | 3 | 高 |
| 不可达代码 | 4 | 低 |
| 未使用变量 | 2 | 低 |

---

## 详细结果

### 2.1 未使用导入 (8)

#### [UNUSED-001] 未使用的导入: `formatDate`

- **文件**: [`src/utils/date.ts`](src/utils/date.ts:15)
- **行号**: 15
- **置信度**: HIGH
- **代码片段**:
  ```typescript
  import { formatDate } from '@/utils/date';
  ```
- **判定依据**: 该导入在当前模块中未被引用
- **重构建议**: 删除此导入语句

---

#### [UNUSED-002] 未使用的导入: `UserService`

- **文件**: [`src/services/api.ts`](src/services/api.ts:3)
- **行号**: 3
- **置信度**: MEDIUM
- **代码片段**:
  ```typescript
  import { UserService } from './UserService';
  ```
- **判定依据**: 该导入在当前模块中未被直接引用，但可能存在动态使用
- **重构建议**: 确认无动态调用后删除

---

### 2.2 未使用导出 (6)

#### [UNUSED-009] 未使用的导出函数: `calculateDiscount`

- **文件**: [`src/utils/pricing.ts`](src/utils/pricing.ts:42)
- **行号**: 42-58
- **置信度**: HIGH
- **代码片段**:
  ```typescript
  export function calculateDiscount(price: number, rate: number): number {
    return price * rate;
  }
  ```
- **判定依据**: 该函数已导出但在整个项目中未被导入或调用
- **重构建议**: 
  1. 确认是否为公共 API
  2. 如非公共 API，删除该函数
  3. 如可能未来使用，添加 `// TODO: 待使用` 注释

---

### 2.3 未使用依赖 (3)

#### [UNUSED-015] 未使用的依赖: `lodash`

- **文件**: [`package.json`](package.json:28)
- **行号**: 28
- **置信度**: HIGH
- **代码片段**:
  ```json
  "dependencies": {
    "lodash": "^4.17.21"
  }
  ```
- **判定依据**: 该依赖在代码中未被 import 或 require
- **重构建议**: 
  1. 运行 `npm uninstall lodash`
  2. 确认无动态 require 后移除

---

### 2.4 不可达代码 (4)

#### [UNUSED-019] return 后的不可达代码

- **文件**: [`src/controllers/userController.ts`](src/controllers/userController.ts:87)
- **行号**: 87-92
- **置信度**: HIGH
- **代码片段**:
  ```typescript
  return response;
  console.log('User fetched:', userId); // 不可达
  const metrics = collectMetrics();     // 不可达
  ```
- **判定依据**: return 语句后的代码永远不会执行
- **重构建议**: 删除 return 后的语句，或将日志移至 return 前

---

### 2.5 未使用变量 (2)

#### [UNUSED-023] 未使用的变量: `tempData`

- **文件**: [`src/components/DataGrid.vue`](src/components/DataGrid.vue:156)
- **行号**: 156
- **置信度**: HIGH
- **代码片段**:
  ```typescript
  const tempData = ref([]);
  ```
- **判定依据**: 该变量声明后未被读取或写入
- **重构建议**: 删除该变量声明

---

## 重构优先级建议

### 高优先级（建议立即处理）

1. **未使用依赖** - 减少打包体积，降低安全风险
2. **不可达代码** - 代码质量问题，可能隐藏 bug

### 中优先级（建议近期处理）

3. **未使用导出** - 确认是否为公共 API 后清理
4. **未使用变量** - 清理代码，提升可读性

### 低优先级（可计划处理）

5. **未使用导入** - 使用 ESLint 自动修复

---

## 附录

### A. 检测工具版本

- knip: v5.0.0
- ts-prune: v0.10.3
- ESLint: v8.57.0

### B. 检测配置

```json
{
  "exclude": ["**/*.test.ts", "**/*.spec.ts"],
  "ignoreDynamic": true,
  "confidenceThreshold": "MEDIUM"
}
```

### C. 术语说明

- **置信度 HIGH**: 静态分析确认无引用，可安全删除
- **置信度 MEDIUM**: 可能存在动态引用，建议人工确认
- **置信度 LOW**: 存在间接引用可能，仅标记不自动清理
```

### 2.2 生成命令

```bash
# 生成 Markdown 报告
npx knip --reporter markdown > dead-code-report.md

# 自定义脚本生成
node scripts/generate-markdown-report.js --output report.md
```

---

## 3. JSON 格式

### 3.1 Schema 定义

```typescript
interface DeadCodeReport {
  // 元信息
  metadata: {
    generatedAt: string;          // ISO 8601 时间戳
    toolVersion: string;          // 检测工具版本
    configHash: string;           // 配置哈希
  };
  
  // 摘要统计
  summary: {
    totalFiles: number;           // 检测文件总数
    totalIssues: number;          // 问题总数
    byType: Record<IssueType, number>;
    byConfidence: Record<Confidence, number>;
  };
  
  // 详细问题列表
  issues: Issue[];
}

interface Issue {
  // 唯一标识
  id: string;                     // 如 "UNUSED-001"
  
  // 问题类型
  type: IssueType;                // 见下方枚举
  
  // 位置信息
  location: {
    file: string;                 // 相对路径
    line: number;                 // 起始行号
    column?: number;              // 起始列号
    endLine?: number;             // 结束行号
    endColumn?: number;           // 结束列号
  };
  
  // 代码信息
  code: {
    snippet: string;              // 代码片段
    symbol?: string;              // 相关符号名
  };
  
  // 分析信息
  analysis: {
    confidence: Confidence;       // 置信度
    reason: string;               // 判定依据
    references?: Reference[];     // 引用关系
  };
  
  // 建议
  suggestion: {
    action: SuggestionAction;     // 建议操作
    description: string;          // 详细说明
    autoFixable: boolean;         // 是否可自动修复
  };
}

type IssueType = 
  | 'unused-import'               // 未使用导入
  | 'unused-export'               // 未使用导出
  | 'unused-variable'             // 未使用变量
  | 'unused-function'             // 未调用函数
  | 'unused-dependency'           // 未使用依赖
  | 'unreachable-code'            // 不可达代码
  | 'unused-file';                // 未使用文件

type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

type SuggestionAction = 
  | 'delete'                      // 删除
  | 'review'                      // 人工审查
  | 'ignore'                      // 忽略
  | 'refactor';                   // 重构

interface Reference {
  file: string;
  line: number;
  type: 'import' | 'call' | 'type';
}
```

### 3.2 示例 JSON

```json
{
  "metadata": {
    "generatedAt": "2026-07-24T09:55:00.000Z",
    "toolVersion": "1.0.0",
    "configHash": "abc123def456"
  },
  "summary": {
    "totalFiles": 128,
    "totalIssues": 23,
    "byType": {
      "unused-import": 8,
      "unused-export": 6,
      "unused-dependency": 3,
      "unreachable-code": 4,
      "unused-variable": 2
    },
    "byConfidence": {
      "HIGH": 15,
      "MEDIUM": 6,
      "LOW": 2
    }
  },
  "issues": [
    {
      "id": "UNUSED-001",
      "type": "unused-import",
      "location": {
        "file": "src/utils/date.ts",
        "line": 15,
        "column": 1
      },
      "code": {
        "snippet": "import { formatDate } from '@/utils/date';",
        "symbol": "formatDate"
      },
      "analysis": {
        "confidence": "HIGH",
        "reason": "该导入在当前模块中未被引用",
        "references": []
      },
      "suggestion": {
        "action": "delete",
        "description": "删除此导入语句",
        "autoFixable": true
      }
    },
    {
      "id": "UNUSED-009",
      "type": "unused-export",
      "location": {
        "file": "src/utils/pricing.ts",
        "line": 42,
        "endLine": 58
      },
      "code": {
        "snippet": "export function calculateDiscount(price: number, rate: number): number {\n  return price * rate;\n}",
        "symbol": "calculateDiscount"
      },
      "analysis": {
        "confidence": "HIGH",
        "reason": "该函数已导出但在整个项目中未被导入或调用",
        "references": []
      },
      "suggestion": {
        "action": "review",
        "description": "确认是否为公共 API，如非公共 API 则删除",
        "autoFixable": false
      }
    }
  ]
}
```

### 3.3 生成命令

```bash
# JSON 输出
npx knip --reporter json > dead-code-report.json

# 格式化 JSON
cat dead-code-report.json | jq '.' > formatted-report.json
```

---

## 4. 控制台格式

### 4.1 默认输出

```
🔍 死代码检测报告
==================

📊 摘要
------
检测文件: 128
发现问题: 23

按类型:
  ⚠️  未使用导入:     8
  ⚠️  未使用导出:     6
  🔴 未使用依赖:     3
  ⚠️  不可达代码:     4
  ⚠️  未使用变量:     2

📋 详细结果
----------

[UNUSED-001] src/utils/date.ts:15
  类型: 未使用导入
  符号: formatDate
  置信度: HIGH
  代码: import { formatDate } from '@/utils/date';
  建议: 删除此导入语句

[UNUSED-009] src/utils/pricing.ts:42-58
  类型: 未使用导出函数
  符号: calculateDiscount
  置信度: HIGH
  代码: export function calculateDiscount(...) { ... }
  建议: 确认是否为公共 API，如非公共 API 则删除

...

✅ 检测完成
```

### 4.2 颜色编码

| 颜色 | 含义 |
|------|------|
| 🔴 红色 | 高风险（未使用依赖） |
| 🟡 黄色 | 中风险（未使用导出） |
| 🟢 绿色 | 低风险（未使用导入） |
| 🔵 蓝色 | 信息（检测统计） |

### 4.3 简洁模式

```bash
# 仅输出问题文件列表
npx knip --reporter compact

# 输出:
src/utils/date.ts:15 - unused import: formatDate
src/utils/pricing.ts:42 - unused export: calculateDiscount
...
```

---

## 5. 字段说明

### 5.1 问题类型 (type)

| 类型 | 说明 | 风险等级 |
|------|------|----------|
| `unused-import` | 未使用的导入语句 | 低 |
| `unused-export` | 未被外部使用的导出 | 中 |
| `unused-variable` | 未使用的变量声明 | 低 |
| `unused-function` | 未被调用的函数 | 中 |
| `unused-dependency` | package.json 中未使用的依赖 | 高 |
| `unreachable-code` | 控制流中不可达的代码 | 低 |
| `unused-file` | 未被引用的文件 | 中 |

### 5.2 置信度 (confidence)

| 等级 | 说明 | 处理建议 |
|------|------|----------|
| `HIGH` | 静态分析确认无引用 | 可直接删除 |
| `MEDIUM` | 可能存在动态引用 | 人工确认后删除 |
| `LOW` | 存在间接引用可能 | 仅标记，不自动清理 |

### 5.3 建议操作 (action)

| 操作 | 说明 |
|------|------|
| `delete` | 安全删除 |
| `review` | 需要人工审查 |
| `ignore` | 建议忽略（可能有特殊用途） |
| `refactor` | 建议重构而非删除 |

---

## 6. 示例报告

### 6.1 完整 Markdown 报告示例

见 [示例报告](#2-markdown-格式) 章节。

### 6.2 完整 JSON 报告示例

见 [示例 JSON](#32-示例-json) 章节。

### 6.3 报告对比

支持对比两次检测报告，追踪问题修复进度：

```bash
# 对比报告
node scripts/compare-reports.js \
  reports/dead-code-20260701.json \
  reports/dead-code-20260724.json

# 输出:
问题变化: -5 (从 28 减少到 23)
新增问题: 2
已修复: 7
```

---

## 7. 自定义输出

### 7.1 自定义模板

支持使用 Handlebars 模板自定义输出格式：

```handlebars
<!-- templates/report.hbs -->
# 死代码检测报告

生成时间: {{metadata.generatedAt}}

## 问题列表
{{#each issues}}
### [{{id}}] {{type}}

- 文件: {{location.file}}:{{location.line}}
- 置信度: {{analysis.confidence}}
- 建议: {{suggestion.description}}

{{/each}}
```

---

## 8. CSS/SCSS 检测结果输出

### 8.1 Markdown 格式示例

```markdown
### 2.6 未使用 CSS/SCSS 样式 (12)

#### [UNUSED-025] 未使用的 CSS 类: `.btn-primary`

- **文件**: [`src/styles/components.css`](src/styles/components.css:45)
- **行号**: 45-52
- **置信度**: HIGH
- **代码片段**:
  ```css
  .btn-primary {
    background-color: #007bff;
    border-color: #007bff;
    color: #fff;
  }
  ```
- **判定依据**: 该类名在所有模板和脚本中均未被引用
- **重构建议**: 删除该 CSS 规则

---

#### [UNUSED-026] 未使用的 SCSS 变量: `$primary-color`

- **文件**: [`src/styles/variables.scss`](src/styles/variables.scss:12)
- **行号**: 12
- **置信度**: HIGH
- **代码片段**:
  ```scss
  $primary-color: #007bff;
  ```
- **判定依据**: 该变量定义后未被任何文件引用
- **重构建议**: 删除该变量定义

---

#### [UNUSED-027] 未使用的 SCSS Mixin: `flex-center`

- **文件**: [`src/styles/mixins.scss`](src/styles/mixins.scss:23-28)
- **行号**: 23-28
- **置信度**: MEDIUM
- **代码片段**:
  ```scss
  @mixin flex-center {
    display: flex;
    align-items: center;
    justify-content: center;
  }
  ```
- **判定依据**: 该 Mixin 定义后未被 `@include` 调用，但可能存在动态使用
- **重构建议**: 确认无动态使用后删除

---

#### [UNUSED-028] Vue 组件中未使用的样式: `.unused-class`

- **文件**: [`src/components/UserCard.vue`](src/components/UserCard.vue:89-92)
- **行号**: 89-92
- **置信度**: HIGH
- **代码片段**:
  ```vue
  <style scoped>
  .unused-class {
    color: red;
  }
  </style>
  ```
- **判定依据**: 该样式在当前组件的 `<template>` 中未被使用
- **重构建议**: 删除该样式规则
```

### 8.2 JSON Schema 扩展

```typescript
// 扩展 IssueType
type IssueType =
  | 'unused-import'
  | 'unused-export'
  | 'unused-variable'
  | 'unused-function'
  | 'unused-dependency'
  | 'unreachable-code'
  | 'unused-file'
  | 'unused-css-class'        // 新增：未使用的 CSS 类
  | 'unused-css-id'           // 新增：未使用的 CSS ID
  | 'unused-scss-variable'    // 新增：未使用的 SCSS 变量
  | 'unused-scss-mixin'       // 新增：未使用的 SCSS Mixin
  | 'unused-scss-function';   // 新增：未使用的 SCSS 函数

// CSS/SCSS 问题详情
interface CSSIssue extends Issue {
  type: 'unused-css-class' | 'unused-css-id' | 'unused-scss-variable' | 'unused-scss-mixin' | 'unused-scss-function';
  
  // CSS 特有字段
  css: {
    selector?: string;        // 选择器（如 '.btn-primary'）
    variable?: string;        // 变量名（如 '$primary-color'）
    mixin?: string;           // Mixin 名
    function?: string;        // 函数名
    scope: 'global' | 'scoped' | 'module';  // 样式作用域
    component?: string;       // 所属组件（Vue SFC）
  };
}
```

### 8.3 JSON 示例

```json
{
  "metadata": {
    "generatedAt": "2026-07-24T09:55:00.000Z",
    "toolVersion": "1.0.0",
    "configHash": "abc123def456"
  },
  "summary": {
    "totalFiles": 128,
    "totalIssues": 35,
    "byType": {
      "unused-import": 8,
      "unused-export": 6,
      "unused-dependency": 3,
      "unreachable-code": 4,
      "unused-variable": 2,
      "unused-css-class": 7,
      "unused-scss-variable": 3,
      "unused-scss-mixin": 2
    },
    "byConfidence": {
      "HIGH": 25,
      "MEDIUM": 8,
      "LOW": 2
    }
  },
  "issues": [
    {
      "id": "UNUSED-025",
      "type": "unused-css-class",
      "location": {
        "file": "src/styles/components.css",
        "line": 45,
        "endLine": 52
      },
      "code": {
        "snippet": ".btn-primary {\n  background-color: #007bff;\n  border-color: #007bff;\n  color: #fff;\n}"
      },
      "css": {
        "selector": ".btn-primary",
        "scope": "global"
      },
      "analysis": {
        "confidence": "HIGH",
        "reason": "该类名在所有模板和脚本中均未被引用",
        "references": []
      },
      "suggestion": {
        "action": "delete",
        "description": "删除该 CSS 规则",
        "autoFixable": true
      }
    },
    {
      "id": "UNUSED-026",
      "type": "unused-scss-variable",
      "location": {
        "file": "src/styles/variables.scss",
        "line": 12
      },
      "code": {
        "snippet": "$primary-color: #007bff;",
        "symbol": "$primary-color"
      },
      "css": {
        "variable": "$primary-color",
        "scope": "global"
      },
      "analysis": {
        "confidence": "HIGH",
        "reason": "该变量定义后未被任何文件引用",
        "references": []
      },
      "suggestion": {
        "action": "delete",
        "description": "删除该变量定义",
        "autoFixable": true
      }
    },
    {
      "id": "UNUSED-028",
      "type": "unused-css-class",
      "location": {
        "file": "src/components/UserCard.vue",
        "line": 89,
        "endLine": 92
      },
      "code": {
        "snippet": ".unused-class {\n  color: red;\n}"
      },
      "css": {
        "selector": ".unused-class",
        "scope": "scoped",
        "component": "UserCard"
      },
      "analysis": {
        "confidence": "HIGH",
        "reason": "该样式在当前组件的 <template> 中未被使用",
        "references": []
      },
      "suggestion": {
        "action": "delete",
        "description": "删除该样式规则",
        "autoFixable": true
      }
    }
  ]
}
```

### 8.4 控制台输出示例

```
🔍 死代码检测报告（含 CSS/SCSS）
==================

📊 摘要
------
检测文件: 128
发现问题: 35

按类型:
  ⚠️  未使用导入:        8
  ⚠️  未使用导出:        6
  🔴 未使用依赖:        3
  ⚠️  不可达代码:        4
  ⚠️  未使用变量:        2
  🎨 未使用 CSS 类:      7
  🎨 未使用 SCSS 变量:   3
  🎨 未使用 SCSS Mixin:  2

📋 详细结果
----------

[UNUSED-025] src/styles/components.css:45-52
  类型: 未使用 CSS 类
  选择器: .btn-primary
  作用域: global
  置信度: HIGH
  代码: .btn-primary { background-color: #007bff; ... }
  建议: 删除该 CSS 规则

[UNUSED-026] src/styles/variables.scss:12
  类型: 未使用 SCSS 变量
  变量: $primary-color
  置信度: HIGH
  代码: $primary-color: #007bff;
  建议: 删除该变量定义

[UNUSED-028] src/components/UserCard.vue:89-92
  类型: 未使用 CSS 类
  选择器: .unused-class
  作用域: scoped
  组件: UserCard
  置信度: HIGH
  代码: .unused-class { color: red; }
  建议: 删除该样式规则

...

✅ 检测完成
```

### 8.5 字段说明（CSS/SCSS 扩展）

| 字段 | 类型 | 说明 |
|------|------|------|
| `css.selector` | string | CSS 选择器（如 `.btn-primary`、`#main-title`） |
| `css.variable` | string | SCSS 变量名（如 `$primary-color`） |
| `css.mixin` | string | SCSS Mixin 名（如 `flex-center`） |
| `css.function` | string | SCSS 函数名（如 `calculate-rem`） |
| `css.scope` | string | 样式作用域：`global`（全局）、`scoped`（Vue scoped）、`module`（CSS Modules） |
| `css.component` | string | 所属组件名（仅 Vue SFC） |

### 8.6 风险等级说明（CSS/SCSS）

| 类型 | 风险等级 | 说明 |
|------|----------|------|
| `unused-css-class` | 中 | 可能存在动态类名引用，需确认 |
| `unused-css-id` | 中 | ID 选择器通常唯一，但可能被 JS 动态引用 |
| `unused-scss-variable` | 低 | 变量未被引用通常可安全删除 |
| `unused-scss-mixin` | 中 | Mixin 可能被动态 `@include`，需确认 |
| `unused-scss-function` | 中 | 函数可能被动态调用，需确认 |

### 8.7 清理建议（CSS/SCSS）

| 类型 | 建议操作 | 注意事项 |
|------|----------|----------|
| `unused-css-class` | 确认后删除 | 检查是否存在动态类名（`:class`、`classList.add`） |
| `unused-css-id` | 确认后删除 | 检查 `document.getElementById` 等 JS 引用 |
| `unused-scss-variable` | 直接删除 | 通常可安全删除 |
| `unused-scss-mixin` | 确认后删除 | 检查是否存在动态 `@include` |
| `unused-scss-function` | 确认后删除 | 检查是否存在动态调用 |

### 7.2 输出命令

```bash
# 使用自定义模板
node scripts/generate-report.js \
  --template templates/report.hbs \
  --output custom-report.md
```
