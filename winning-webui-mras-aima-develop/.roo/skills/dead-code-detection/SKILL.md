---
name: dead-code-detection
description: 检测项目中的死代码（未调用函数、未引用变量、不可达代码、冗余导入、废弃依赖、未使用 CSS/SCSS 样式），支持多语言环境，内置智能过滤机制排除动态反射、框架路由、元编程及测试代码的误报。当用户需要清理代码库、优化打包体积、提升代码质量或进行代码审查时使用。
---

# 死代码检测 Skill

## 何时使用

- 用户请求检测项目中的死代码
- 用户需要清理未使用的代码以提升代码库健康度
- 用户希望优化打包体积，移除冗余代码
- 用户进行代码审查，需要识别潜在的可删除代码
- 用户准备重构项目，需要先了解代码依赖关系

## 何时不使用

- 用户只需要格式化代码（使用 Prettier）
- 用户只需要类型检查（使用 TypeScript 编译器）
- 用户只需要 lint 检查（使用 ESLint）
- 用户需要性能分析（使用性能分析工具）

## 本项目提示

- 本仓库（winning-webui-mras-aima，Vue 3 + Vuetify 前端）**已配置 Knip**：`knip.config.ts`（入口 `src/main.ts`、`src/router/index.ts`、`vite.config.ts`）。
- 优先执行 `npx knip`（默认 `dependencies/files/exports` 全检），无需另写配置；如只看某类：`npx knip --include dependencies`。
- 项目 lint：`npm run lint`；格式化：`npm run format`。本项目样式由 Tailwind CSS 4 + Vuetify 接管，PurgeCSS 仅作补充核查。

## 输入参数

执行死代码检测前，需要确认以下参数：

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `targetPath` | 是 | `src/` | 检测目标目录路径 |
| `fileExtensions` | 否 | `['.ts', '.vue', '.js', '.css', '.scss']` | 检测的文件扩展名 |
| `excludePatterns` | 否 | `['**/*.test.*', '**/*.spec.*', '**/dist/**']` | 排除的文件/目录模式 |
| `outputFormat` | 否 | `markdown` | 输出格式：`markdown` / `json` / `console` |
| `includeTests` | 否 | `false` | 是否检测测试代码中的死代码 |
| `strictMode` | 否 | `false` | 严格模式：启用更激进的检测（可能增加误报） |

## 工作流程

### 步骤 1：环境准备与工具选择

根据项目类型选择合适的检测工具组合：

1. **读取项目配置**：检查 `package.json`、`tsconfig.json` 等配置文件
2. **识别项目类型**：TypeScript / JavaScript / Vue / React 等
3. **选择检测工具**：根据项目类型选择最合适的工具组合

详细工具选择策略见 [references/tool-integration.md](references/tool-integration.md)。

### 步骤 2：执行静态分析

按以下顺序执行检测：

1. **冗余导入检测**：使用 ESLint 或 knip 检测未使用的 import
2. **未引用变量检测**：使用 ts-prune 或 knip 检测未使用的导出
3. **未调用函数检测**：使用 AST 分析函数调用图
4. **不可达代码检测**：使用 ESLint 检测 return/throw 后的代码
5. **废弃依赖检测**：对比 package.json 与实际使用情况
6. **未使用样式检测**：使用 PurgeCSS 或 CSS 分析工具检测未使用的 CSS/SCSS 类

详细检测逻辑见 [references/detection-logic.md](references/detection-logic.md)。

### 步骤 3：智能过滤误报

应用过滤规则排除以下场景的误报：

- **动态反射**：`window[key]`、`obj[prop]` 等动态访问
- **框架路由**：Vue Router 路由配置、React Router 配置
- **元编程**：装饰器、Reflect.metadata 等
- **测试代码**：测试文件中的导出函数
- **类型导出**：TypeScript 类型/接口导出
- **生命周期钩子**：Vue/React 生命周期函数
- **动态类名**：`:class="{ active: isActive }"` 等动态绑定的类名
- **全局样式**：`global.css` 或 `index.css` 中的全局样式
- **第三方组件覆盖样式**：用于覆盖 UI 库默认样式的类

### 步骤 4：生成分析报告

按照指定格式输出检测结果：

详细输出格式见 [references/output-format.md](references/output-format.md)。

### 步骤 5：提供重构建议

针对每类死代码提供安全的清理建议：

- **冗余导入**：直接删除 import 语句
- **未引用变量**：确认无副作用后删除
- **未调用函数**：检查是否有动态调用，确认后删除
- **不可达代码**：删除 return/throw 后的代码块
- **废弃依赖**：从 package.json 移除并执行 npm uninstall
- **未使用样式**：确认无动态类名引用后删除 CSS/SCSS 规则

## 检测类型说明

| 类型 | 说明 | 风险等级 | 清理建议 |
|------|------|----------|----------|
| `unused-import` | 未使用的导入 | 低 | 直接删除 |
| `unused-variable` | 未引用的变量 | 低 | 确认后删除 |
| `unused-function` | 未调用的函数 | 中 | 检查动态调用后删除 |
| `unreachable-code` | 不可达代码块 | 低 | 直接删除 |
| `deprecated-dependency` | 废弃的依赖 | 高 | 评估替代方案后移除 |
| `unused-export` | 未使用的导出 | 中 | 检查外部引用后删除 |
| `unused-css` | 未使用的 CSS/SCSS 样式 | 中 | 检查动态类名后删除 |

## 工具执行示例

### 使用 knip 检测

```bash
# 安装 knip
npm install -D knip

# 执行检测
npx knip --include dependencies,files,exports
```

### 使用 ts-prune 检测 TypeScript

```bash
# 安装 ts-prune
npm install -D ts-prune

# 执行检测
npx ts-prune --error
```

### 使用 ESLint 检测

```bash
# 确保配置了 unused-imports 插件
npx eslint src/ --ext .ts,.vue,.js --rule '{"unused-imports/no-unused-imports": "error"}'
```

### 使用 PurgeCSS 检测未使用样式

```bash
# 安装 PurgeCSS
npm install -D @fullhuman/postcss-purgecss

# 执行检测（仅报告，不删除）
npx purgecss --css src/**/*.css --content src/**/*.vue --output report/
```

## 注意事项

1. **备份代码**：执行清理前建议提交当前代码或创建分支
2. **渐进式清理**：建议按风险等级从低到高逐步清理
3. **验证功能**：每次清理后运行测试确保功能正常
4. **动态调用**：对于可能通过字符串动态调用的函数，标记为"待确认"而非直接删除
5. **第三方库**：某些导出可能被第三方库通过反射调用，需谨慎处理

## 故障排除

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| 误报过多 | 未正确配置排除规则 | 检查 excludePatterns 配置 |
| 漏报 | 工具覆盖范围有限 | 结合多种工具交叉验证 |
| 检测速度慢 | 项目规模大 | 缩小检测范围，排除 dist/node_modules |
| 类型文件报错 | 类型导出被误判 | 添加类型文件排除规则 |
