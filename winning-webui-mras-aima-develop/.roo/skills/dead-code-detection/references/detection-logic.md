# 检测逻辑详解

> 本文档详细描述死代码检测的 AST 分析逻辑、各类死代码识别规则及智能过滤机制。

## 目录

1. [AST 分析基础](#1-ast-分析基础)
2. [冗余导入检测](#2-冗余导入检测)
3. [未引用变量检测](#3-未引用变量检测)
4. [未调用函数检测](#4-未调用函数检测)
5. [不可达代码检测](#5-不可达代码检测)
6. [废弃依赖检测](#6-废弃依赖检测)
7. [智能过滤机制](#7-智能过滤机制)
8. [多语言适配](#8-多语言适配)

---

## 1. AST 分析基础

### 1.1 解析器选择

| 语言 | 推荐解析器 | 输出格式 |
|------|-----------|----------|
| TypeScript | `@typescript-eslint/parser` | ESTree + TS 扩展 |
| JavaScript | `@babel/parser` 或 `acorn` | ESTree |
| Vue SFC | `vue-eslint-parser` + `@typescript-eslint/parser` | ESTree（template + script） |

### 1.2 核心分析流程

```
源代码 → 解析为 AST → 构建符号表 → 构建引用图 → 分析未引用节点 → 过滤误报 → 输出结果
```

### 1.3 符号表构建

符号表记录所有声明的标识符及其元信息：

```typescript
interface SymbolEntry {
  name: string;           // 标识符名称
  kind: 'function' | 'variable' | 'class' | 'type' | 'interface' | 'enum';
  scope: 'module' | 'block' | 'function';
  isExported: boolean;    // 是否被导出
  isDefault: boolean;     // 是否为默认导出
  node: ASTNode;          // AST 节点引用
  location: SourceLocation; // 源码位置
  references: Reference[]; // 引用列表
}

interface Reference {
  node: ASTNode;          // 引用节点
  type: 'read' | 'write' | 'type'; // 引用类型
  location: SourceLocation;
}
```

### 1.4 引用图构建

遍历 AST，收集所有标识符引用：

- **Identifier 节点**：变量读取/写入
- **MemberExpression**：属性访问（需特殊处理动态访问）
- **CallExpression**：函数调用
- **JSX/Template**：组件引用（Vue/React 特有）

---

## 2. 冗余导入检测

### 2.1 检测规则

遍历所有 `ImportDeclaration` 节点，检查每个导入的标识符是否在模块内被引用。

```
ImportDeclaration
├── ImportSpecifier (具名导入) → 检查每个 imported name 的本地引用
├── ImportDefaultSpecifier (默认导入) → 检查默认名称的引用
└── ImportNamespaceSpecifier (命名空间导入) → 检查 namespace.xxx 的引用
```

### 2.2 判定逻辑

```typescript
function isImportUsed(importName: string, ast: ASTNode): boolean {
  // 1. 搜索所有 Identifier 节点
  const identifiers = findAllIdentifiers(ast);
  
  // 2. 过滤出与 importName 同名的标识符
  const matches = identifiers.filter(id => id.name === importName);
  
  // 3. 排除导入声明本身的引用
  const nonImportRefs = matches.filter(id => !isInImportDeclaration(id));
  
  // 4. 排除作为对象属性的引用（obj.importName 不算使用）
  const directRefs = nonImportRefs.filter(id => !isPropertyAccess(id));
  
  return directRefs.length > 0;
}
```

### 2.3 特殊处理

- **类型导入**：`import type { Foo }` 仅在类型位置被引用时算使用
- **副作用导入**：`import 'module'` 无绑定标识，不检测（可能有副作用）
- **重导出**：`export { Foo } from 'module'` 算作使用
- **Vue 组件注册**：在 `<template>` 中使用的组件算作导入使用

---

## 3. 未引用变量检测

### 3.1 检测规则

检查所有变量声明（`VariableDeclaration`）中标识符的引用情况。

### 3.2 作用域分析

```typescript
function analyzeVariableUsage(ast: ASTNode): UnusedVariable[] {
  const scopeManager = buildScopeTree(ast);
  const unused: UnusedVariable[] = [];
  
  for (const scope of scopeManager.getAllScopes()) {
    for (const variable of scope.variables) {
      // 跳过全局变量和参数
      if (variable.scope.type === 'global') continue;
      if (variable.defs.some(d => d.type === 'Parameter')) continue;
      
      // 检查引用数
      if (variable.references.length === 0) {
        unused.push({
          name: variable.name,
          location: variable.defs[0].name.loc,
          kind: getVariableKind(variable.defs[0].node),
        });
      }
    }
  }
  
  return unused;
}
```

### 3.3 解构赋值处理

```typescript
// 部分解构：只检测未使用的解构变量
const { used, unused } = data; // 如果 unused 未被引用，标记为死代码

// 建议：使用 rest 忽略不需要的属性
const { used, ...rest } = data;
```

### 3.4 特殊处理

- **下划线前缀变量**：`_unused` 约定表示有意忽略，不报告
- **解构重命名**：`const { a: b } = obj` 检测 `b` 的使用情况
- **for-in/for-of 变量**：循环变量通常有副作用，需谨慎标记
- **catch 参数**：`catch (e)` 中 `e` 未使用是常见模式，可配置是否报告

---

## 4. 未调用函数检测

### 4.1 检测规则

构建函数调用图（Call Graph），识别未被任何代码路径调用的函数。

### 4.2 调用图构建

```typescript
interface CallGraphNode {
  caller: string;       // 调用者标识
  callee: string;       // 被调用者标识
  callType: 'direct' | 'indirect' | 'dynamic';
  location: SourceLocation;
}

function buildCallGraph(ast: ASTNode): CallGraphNode[] {
  const edges: CallGraphNode[] = [];
  
  // 遍历所有 CallExpression 节点
  visit(ast, {
    CallExpression(node) {
      const callee = resolveCallee(node.callee);
      const caller = getCurrentFunctionName();
      
      edges.push({
        caller,
        callee,
        callType: classifyCallType(node.callee),
        location: node.loc,
      });
    }
  });
  
  return edges;
}
```

### 4.3 导出函数分析

对于导出的函数，需要跨文件分析：

```
1. 收集所有 export 声明
2. 在整个项目中搜索导入引用
3. 检查是否有文件 import 了该导出
4. 对于默认导出，检查默认导入和命名空间导入
```

### 4.4 特殊处理

- **回调函数**：作为参数传递的函数算作使用
- **事件处理器**：`onClick={handler}` 算作使用
- **Vue 组件方法**：`defineExpose` 暴露的方法算作使用
- **生命周期钩子**：框架自动调用的方法不算死代码
- **递归函数**：函数内部调用自身算作使用

---

## 5. 不可达代码检测

### 5.1 检测规则

识别在控制流中永远不会执行到的代码块。

### 5.2 检测场景

| 场景 | 示例 | 检测方式 |
|------|------|----------|
| return 后的语句 | `return x; console.log();` | 检查 ReturnStatement 后的兄弟节点 |
| throw 后的语句 | `throw err; doSomething();` | 检查 ThrowStatement 后的兄弟节点 |
| break/continue 后的语句 | `break; x = 1;` | 检查 BreakStatement 后的兄弟节点 |
| 条件恒假 | `if (false) { ... }` | 常量条件折叠分析 |
| process.exit 后的代码 | `process.exit(1); cleanup();` | 检查终止函数后的语句 |

### 5.3 实现逻辑

```typescript
function findUnreachableCode(ast: ASTNode): UnreachableCode[] {
  const results: UnreachableCode[] = [];
  
  visit(ast, {
    BlockStatement(node) {
      const body = node.body;
      for (let i = 0; i < body.length - 1; i++) {
        if (isTerminatingStatement(body[i])) {
          // 收集后续所有语句作为不可达代码
          const unreachable = body.slice(i + 1);
          if (unreachable.length > 0) {
            results.push({
              terminator: body[i].type,
              unreachableNodes: unreachable,
              location: {
                start: unreachable[0].loc.start,
                end: unreachable[unreachable.length - 1].loc.end,
              },
            });
          }
          break; // 只报告一次
        }
      }
    }
  });
  
  return results;
}

function isTerminatingStatement(node: ASTNode): boolean {
  return ['ReturnStatement', 'ThrowStatement', 'BreakStatement', 'ContinueStatement']
    .includes(node.type) ||
    isProcessExit(node) ||
    isProcessAbort(node);
}
```

---

## 6. 废弃依赖检测

### 6.1 检测规则

对比 `package.json` 中声明的依赖与实际代码中的使用情况。

### 6.2 检测流程

```
1. 解析 package.json，提取 dependencies 和 devDependencies
2. 扫描所有源文件，收集所有 import/require 的模块名
3. 对比：声明但未使用的依赖 → 废弃依赖
4. 排除：已知副作用包（如 polyfill）
```

### 6.3 模块名解析

```typescript
function resolveModuleName(importPath: string): string {
  // 处理 scoped packages: @scope/package/file → @scope/package
  if (importPath.startsWith('@')) {
    const parts = importPath.split('/');
    return parts.slice(0, 2).join('/');
  }
  
  // 处理普通包: package/file → package
  return importPath.split('/')[0];
}
```

### 6.4 排除列表

以下依赖即使未直接 import 也不应标记为废弃：

- **构建工具插件**：`vite-plugin-*`、`webpack-*`（在配置文件中使用）
- **Polyfill**：`core-js`、`regenerator-runtime`
- **类型包**：`@types/*`（可能被 TypeScript 隐式引用）
- **Peer 依赖**：被其他依赖间接需要的包
- **CSS 框架**：`tailwindcss`、`postcss`（在配置中使用）

---

## 7. 智能过滤机制

### 7.1 动态反射过滤

```typescript
// 以下模式应排除误报：

// 1. 动态属性访问
obj[methodName]();        // methodName 可能是动态计算的
window[globalVar];        // 全局变量动态访问

// 2. Reflect API
Reflect.get(obj, key);
Reflect.has(obj, prop);

// 3. 字符串模板动态调用
const fn = `handle${Type}`;
this[fn]();
```

**过滤规则**：当检测到 `Computed MemberExpression` 或 `Reflect.*` 调用时，将相关标识符标记为"可能动态使用"，不报告为死代码。

### 7.2 框架路由过滤

```typescript
// Vue Router 路由配置中的组件引用
const routes = [
  { path: '/', component: HomeView },      // HomeView 被路由引用
  { path: '/about', component: () => import('./AboutView.vue') }, // 动态导入
];

// React Router 配置
<Route path="/dashboard" element={<Dashboard />} />
```

**过滤规则**：
- 扫描路由配置文件，提取所有组件引用
- 将路由中引用的组件标记为"框架使用"

### 7.3 元编程过滤

```typescript
// 装饰器
@Component
class MyComponent { }

// Reflect.metadata
Reflect.metadata('design:type', String)

// Proxy 拦截
new Proxy(target, { get: (obj, prop) => ... })
```

**过滤规则**：
- 被装饰器引用的类/方法标记为"元编程使用"
- Proxy handler 中的属性访问标记为"动态使用"

### 7.4 测试代码过滤

```typescript
// 测试文件中的导出函数
export function helperFn() { } // 可能被测试文件引用

// describe/it 中的引用
describe('MyComponent', () => {
  it('should work', () => {
    myFunction(); // 测试引用
  });
});
```

**过滤规则**：
- 默认排除测试文件（`*.test.*`、`*.spec.*`）
- 如果启用 `includeTests`，则检测测试文件中的死代码
- 非测试文件中被测试文件引用的函数不标记为死代码

### 7.5 Vue 特有过滤

```vue
<!-- template 中使用的组件 -->
<script setup>
import MyComponent from './MyComponent.vue'; // 在 template 中使用
</script>
<template>
  <MyComponent />
</template>

<!-- defineProps/defineEmits 的类型引用 -->
<script setup lang="ts">
interface Props { title: string } // 被 defineProps 引用
const props = defineProps<Props>();
</script>

<!-- defineExpose 暴露的方法 -->
<script setup>
function resetForm() { } // 被 defineExpose 暴露
defineExpose({ resetForm });
</script>
```

### 7.6 置信度分级

根据检测的确定性，将结果分为三个置信度等级：

| 等级 | 标签 | 说明 | 建议操作 |
|------|------|------|----------|
| 高 | `HIGH` | 静态分析确认无引用 | 可直接清理 |
| 中 | `MEDIUM` | 可能存在动态引用 | 人工确认后清理 |
| 低 | `LOW` | 存在间接引用可能 | 仅标记，不自动清理 |

---

## 8. 多语言适配

### 8.1 TypeScript 特有

- **类型导出**：`export type/interface` 仅在类型位置引用时算使用
- **枚举成员**：未使用的枚举成员需要单独检测
- **声明合并**：namespace 与 class/function 的声明合并
- **模块增强**：`declare module` 中的类型扩展

### 8.2 Vue SFC 特有

- **template 引用**：`<script setup>` 中顶层声明自动在 template 中可用
- **props 定义**：`defineProps` 的参数类型引用
- **emit 定义**：`defineEmits` 中的事件名引用
- **slot 使用**：`<slot>` 相关的类型定义

### 8.3 JavaScript 特有

- **CommonJS**：`module.exports` / `require()` 的引用分析
- **JSDoc 类型**：`@typedef` / `@type` 中的类型引用
- **全局变量**：`window.xxx` 的赋值和读取

### 8.4 配置文件适配

| 文件类型 | 特殊处理 |
|----------|----------|
| `vite.config.ts` | 插件引用、环境变量引用 |
| `tsconfig.json` | path alias 映射 |
| `eslint.config.js` | 插件和规则引用 |
| `tailwind.config.js` | 内容扫描路径配置 |

---

## 9. CSS/SCSS 死代码检测

### 9.1 检测范围

| 类型 | 说明 | 检测方式 |
|------|------|----------|
| 未使用类选择器 | `.unused-class { }` | 扫描模板/JS 中的类名引用 |
| 未使用 ID 选择器 | `#unused-id { }` | 扫描模板/JS 中的 ID 引用 |
| 未使用变量 | `$unused-var: value;` (SCSS) | 分析 SCSS 变量引用 |
| 未使用 Mixin | `@mixin unused { }` | 分析 `@include` 调用 |
| 未使用函数 | `@function unused() { }` | 分析函数调用 |
| 重复样式规则 | 相同选择器的重复定义 | 比较选择器哈希 |

### 9.2 检测流程

```
1. 解析 CSS/SCSS 文件 → 提取所有选择器、变量、Mixin
2. 扫描模板文件（.vue/.html）→ 提取 class/id 引用
3. 扫描 JS/TS 文件 → 提取动态类名引用
4. 对比：定义的样式 vs 引用的样式 → 未使用的样式
5. 过滤误报 → 输出结果
```

### 9.3 选择器引用分析

```typescript
interface CSSSelector {
  name: string;           // 选择器名称（如 '.btn-primary'）
  type: 'class' | 'id' | 'element' | 'attribute';
  file: string;           // 定义文件
  line: number;           // 行号
  specificity: number;    // 特异性
}

interface CSSReference {
  selector: string;       // 被引用的选择器
  file: string;           // 引用文件
  line: number;           // 行号
  context: 'template' | 'script' | 'dynamic';
}

function findUnusedSelectors(
  cssFiles: string[],
  templateFiles: string[],
  scriptFiles: string[]
): UnusedCSS[] {
  // 1. 提取所有 CSS 选择器
  const definedSelectors = extractSelectors(cssFiles);
  
  // 2. 提取所有引用
  const templateRefs = extractClassRefsFromTemplates(templateFiles);
  const scriptRefs = extractClassRefsFromScripts(scriptFiles);
  const allRefs = [...templateRefs, ...scriptRefs];
  
  // 3. 对比找出未使用的
  return definedSelectors.filter(sel =>
    !allRefs.some(ref => ref.selector === sel.name)
  );
}
```

### 9.4 SCSS 变量/Mixin 分析

```typescript
// SCSS 变量检测
interface SCSSVariable {
  name: string;           // 变量名（如 '$primary-color'）
  value: string;          // 变量值
  file: string;
  line: number;
  references: number;     // 引用次数
}

// 检测未使用的 SCSS 变量
function findUnusedSCSSVariables(scssFiles: string[]): UnusedSCSSVar[] {
  const variables = extractSCSSVariables(scssFiles);
  
  return variables.filter(v => {
    // 统计变量在文件中的引用次数
    const refCount = countVariableReferences(v.name, scssFiles);
    return refCount === 0;
  });
}

// 检测未使用的 Mixin
function findUnusedMixins(scssFiles: string[]): UnusedMixin[] {
  const mixins = extractMixins(scssFiles);
  
  return mixins.filter(m => {
    // 统计 @include 调用次数
    const includeCount = countMixinIncludes(m.name, scssFiles);
    return includeCount === 0;
  });
}
```

### 9.5 智能过滤（CSS/SCSS）

#### 动态类名过滤

```vue
<!-- 以下模式应排除误报 -->

<!-- 1. 动态类绑定 -->
<div :class="{ 'is-active': isActive }"></div>
<div :class="`status-${status}`"></div>

<!-- 2. 计算属性类名 -->
<div :class="computedClass"></div>

<!-- 3. 数组语法 -->
<div :class="[baseClass, { 'is-disabled': disabled }]"></div>
```

**过滤规则**：
- 提取 `:class` 绑定中的所有字符串字面量
- 对于模板字符串 `` `status-${status}` ``，提取前缀 `status-` 并匹配所有 `status-*` 类
- 对于变量引用，标记为"可能动态使用"，不报告

#### 全局样式过滤

```css
/* 以下样式应排除 */

/* 1. 全局重置样式 */
* { box-sizing: border-box; }
body { margin: 0; }

/* 2. 标签选择器 */
h1, h2, h3 { font-weight: normal; }

/* 3. 全局工具类 */
.container { max-width: 1200px; }
```

**过滤规则**：
- 标签选择器（`div`、`span`、`h1` 等）不检测
- 通配符选择器（`*`）不检测
- 全局样式文件（`global.css`、`index.css`）可选择性排除

#### 第三方组件覆盖样式

```scss
// 覆盖 UI 库默认样式
.v-btn {
  &.custom-theme {
    background: $custom-color;
  }
}

// 深度选择器
::v-deep(.el-input__inner) {
  border-color: $primary;
}
```

**过滤规则**：
- 包含 `::v-deep`、`/deep/`、`:deep()` 的选择器不检测
- 覆盖第三方组件类名（如 `.v-*`）的选择器需谨慎处理

### 9.6 Vue SFC 样式分析

```vue
<!-- Vue 单文件组件样式 -->
<style scoped>
.btn-primary { }  <!-- 仅在组件内生效 -->
</style>

<style lang="scss" scoped>
$local-var: #fff;  <!-- 局部变量 -->
.btn-secondary { }
</style>
```

**分析要点**：
- `scoped` 样式仅在当前组件生效，需检查当前组件模板
- 非 `scoped` 样式可能影响其他组件，需全局检查
- `<style>` 块中的变量/Mixin 仅在当前块内有效

### 9.7 置信度分级（CSS/SCSS）

| 等级 | 场景 | 建议 |
|------|------|------|
| HIGH | 类名在模板和脚本中均无引用 | 可直接删除 |
| MEDIUM | 存在动态类名绑定（如 `:class`） | 人工确认后删除 |
| LOW | 可能是全局样式或覆盖样式 | 仅标记，不自动删除 |
