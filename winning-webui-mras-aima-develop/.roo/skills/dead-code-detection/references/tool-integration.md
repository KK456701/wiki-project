# 工具集成策略

> 本文档描述主流静态分析工具的选择、配置及集成方案。

## 目录

1. [工具概览](#1-工具概览)
2. [工具选择策略](#2-工具选择策略)
3. [knip 集成](#3-knip-集成)
4. [ts-prune 集成](#4-ts-prune-集成)
5. [ESLint 插件集成](#5-eslint-插件集成)
6. [工具组合方案](#6-工具组合方案)
7. [CI/CD 集成](#7-cicd-集成)

---

## 1. 工具概览

| 工具 | 检测能力 | 语言支持 | 特点 |
|------|----------|----------|------|
| **knip** | 依赖、文件、导出、类型 | TS/JS/Vue/React | 功能最全面，支持 monorepo |
| **ts-prune** | 未使用导出 | TypeScript | 专注 TS 导出分析 |
| **eslint-plugin-unused-imports** | 未使用导入 | TS/JS | ESLint 生态集成 |
| **depcheck** | 废弃依赖 | JS/TS | 专注依赖分析 |
| **unimported** | 未使用文件和依赖 | TS/JS | 快速扫描 |
| **Madge** | 循环依赖、孤儿文件 | TS/JS | 依赖图可视化 |

---

## 2. 工具选择策略

### 2.1 按项目类型选择

| 项目类型 | 推荐工具组合 | 理由 |
|----------|-------------|------|
| **TypeScript 项目** | knip + ts-prune | knip 全面检测，ts-prune 补充导出分析 |
| **Vue 项目** | knip + eslint-plugin-unused-imports | knip 支持 Vue SFC，ESLint 处理导入 |
| **React 项目** | knip + eslint-plugin-unused-imports | knip 支持 JSX，ESLint 处理导入 |
| **纯 JavaScript** | depcheck + eslint-plugin-unused-imports | 无需 TS 分析，轻量快速 |
| **Monorepo** | knip | 原生支持 workspace 分析 |

### 2.2 按检测需求选择

| 检测目标 | 推荐工具 |
|----------|----------|
| 未使用依赖 | knip / depcheck |
| 未使用导出 | ts-prune / knip |
| 未使用导入 | eslint-plugin-unused-imports |
| 未使用文件 | knip / unimported |
| 不可达代码 | ESLint (no-unreachable) |
| 循环依赖 | Madge |

---

## 3. knip 集成

### 3.1 安装

```bash
npm install -D knip
```

### 3.2 配置文件

创建 `knip.config.ts` 或 `knip.config.json`：

```typescript
// knip.config.ts
import type { KnipConfig } from 'knip';

const config: KnipConfig = {
  // 入口文件
  entry: [
    'src/main.ts',
    'src/router/index.ts',
    'vite.config.ts',
  ],
  
  // 项目文件
  project: ['src/**/*.{ts,vue,js}'],
  
  // 排除模式
  ignore: [
    'src/**/*.test.ts',
    'src/**/*.spec.ts',
    'src/types/**/*.d.ts',
    'src/mocks/**',
  ],
  
  // 检测项配置
  dependencies: {
    enabled: true,
    ignore: ['@types/*'], // 忽略类型包
  },
  
  devDependencies: {
    enabled: true,
  },
  
  exports: {
    enabled: true,
    ignore: [
      // 忽略动态导出的模块
      'src/utils/dynamicExports.ts',
    ],
  },
  
  // 文件类型检测
  files: {
    enabled: true,
  },
  
  // 类型检测
  types: {
    enabled: true,
  },
  
  // 排除规则
  exclude: [
    'unlisted',      // 不排除未列出的依赖
    'unresolved',    // 不排除无法解析的导入
  ],
};

export default config;
```

### 3.3 执行命令

```bash
# 完整检测
npx knip

# 仅检测依赖
npx knip --include dependencies

# 仅检测导出
npx knip --include exports

# 仅检测文件
npx knip --include files

# JSON 输出
npx knip --reporter json

# 详细模式
npx knip --show-progress
```

### 3.4 package.json 脚本

```json
{
  "scripts": {
    "knip": "knip",
    "knip:ci": "knip --reporter json > knip-report.json",
    "knip:fix": "knip --fix"
  }
}
```

---

## 4. ts-prune 集成

### 4.1 安装

```bash
npm install -D ts-prune
```

### 4.2 配置文件

创建 `.tsprunerc` 或使用命令行参数：

```json
{
  "skip": ".*\\.test\\.ts|.*\\.spec\\.ts|.*\\.d\\.ts",
  "ignore": "src/types|src/mocks",
  "error": true
}
```

### 4.3 执行命令

```bash
# 基础检测
npx ts-prune

# 错误模式（有未使用导出时返回非零退出码）
npx ts-prune --error

# 指定目录
npx ts-prune -p src/

# JSON 输出
npx ts-prune --output json
```

### 4.4 局限性

- 仅支持 TypeScript
- 仅检测未使用导出，不检测未使用导入
- 不支持 Vue SFC 的 template 分析

---

## 5. ESLint 插件集成

### 5.1 eslint-plugin-unused-imports

**安装**：

```bash
npm install -D eslint-plugin-unused-imports
```

**配置** (`eslint.config.js`)：

```javascript
import unusedImports from 'eslint-plugin-unused-imports';

export default [
  {
    plugins: {
      'unused-imports': unusedImports,
    },
    rules: {
      // 移除未使用的导入
      'unused-imports/no-unused-imports': 'error',
      // 移除未使用的变量（可选，可能与 no-unused-vars 冲突）
      'unused-imports/no-unused-vars': [
        'warn',
        {
          vars: 'all',
          varsIgnorePattern: '^_',
          args: 'after-used',
          argsIgnorePattern: '^_',
        },
      ],
    },
  },
];
```

### 5.2 ESLint 内置规则

```javascript
export default [
  {
    rules: {
      // 不可达代码检测
      'no-unreachable': 'error',
      // 未使用变量
      'no-unused-vars': ['error', {
        vars: 'all',
        varsIgnorePattern: '^_',
        args: 'after-used',
        ignoreRestSiblings: true,
      }],
      // 未使用的标签（JSX）
      'no-unused-labels': 'error',
    },
  },
];
```

### 5.3 自动修复

```bash
# 自动移除未使用导入
npx eslint src/ --ext .ts,.vue,.js --rule '{"unused-imports/no-unused-imports": "error"}' --fix
```

---

## 6. 工具组合方案

### 6.1 推荐组合：全面检测

```bash
#!/bin/bash
# dead-code-check.sh

echo "=== 死代码检测开始 ==="

# 1. ESLint 检测未使用导入和不可达代码
echo "1. 检测未使用导入..."
npx eslint src/ --ext .ts,.vue,.js \
  --rule '{"unused-imports/no-unused-imports": "error"}' \
  --rule '{"no-unreachable": "error"}' \
  --format json > eslint-report.json

# 2. knip 检测依赖、导出、文件
echo "2. 检测未使用依赖和导出..."
npx knip --reporter json > knip-report.json

# 3. ts-prune 补充 TypeScript 导出检测
echo "3. 补充 TypeScript 导出检测..."
npx ts-prune --error > ts-prune-report.txt 2>&1 || true

echo "=== 检测完成 ==="
```

### 6.2 轻量组合：快速检测

```bash
# 仅使用 knip 进行快速全面检测
npx knip --include dependencies,exports,files
```

### 6.3 CI 集成组合

```yaml
# .github/workflows/dead-code.yml
name: Dead Code Detection

on: [push, pull_request]

jobs:
  dead-code:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      
      - run: npm ci
      
      - name: Run knip
        run: npx knip --reporter json > knip-report.json
      
      - name: Upload report
        uses: actions/upload-artifact@v4
        with:
          name: dead-code-report
          path: knip-report.json
```

---

## 7. CI/CD 集成

### 7.1 失败阈值配置

```json
// knip.config.ts
{
  "rules": {
    "dependencies": "error",      // 未使用依赖 → 错误
    "devDependencies": "warn",    // 未使用开发依赖 → 警告
    "exports": "warn",            // 未使用导出 → 警告
    "files": "warn"               // 未使用文件 → 警告
  }
}
```

### 7.2 渐进式集成策略

1. **第一阶段**：仅报告，不阻断 CI
   ```bash
   npx knip || echo "检测到死代码，但不阻断构建"
   ```

2. **第二阶段**：设置阈值，逐步降低
   ```bash
   # 允许最多 10 个未使用导出
   npx knip --max-issues 10
   ```

3. **第三阶段**：严格模式，阻断 CI
   ```bash
   npx knip --production
   ```

### 7.3 报告归档

```bash
# 生成带时间戳的报告
npx knip --reporter json > reports/dead-code-$(date +%Y%m%d).json

# 对比历史报告
node scripts/compare-reports.js reports/dead-code-latest.json reports/dead-code-previous.json
```

### 7.4 与现有工具链集成

| 工具 | 集成方式 |
|------|----------|
| **Husky** | pre-commit 钩子运行快速检测 |
| **lint-staged** | 仅检测暂存文件 |
| **GitHub Actions** | PR 检查自动运行 |
| **GitLab CI** | pipeline 阶段集成 |
| **Jenkins** | 构建步骤添加检测 |

---

## 8. 自定义脚本集成

### 8.1 统一报告生成脚本

```typescript
// scripts/generate-dead-code-report.ts
import { execSync } from 'child_process';
import { writeFileSync } from 'fs';

interface DeadCodeReport {
  timestamp: string;
  summary: {
    unusedImports: number;
    unusedExports: number;
    unusedDependencies: number;
    unreachableCode: number;
  };
  details: {
    type: string;
    file: string;
    line: number;
    message: string;
    confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  }[];
}

async function generateReport(): Promise<DeadCodeReport> {
  const report: DeadCodeReport = {
    timestamp: new Date().toISOString(),
    summary: {
      unusedImports: 0,
      unusedExports: 0,
      unusedDependencies: 0,
      unreachableCode: 0,
    },
    details: [],
  };

  // 1. 运行 knip
  try {
    const knipOutput = execSync('npx knip --reporter json', { encoding: 'utf-8' });
    const knipResult = JSON.parse(knipOutput);
    // 解析 knip 结果...
  } catch (e) {
    // knip 返回非零退出码表示发现问题
  }

  // 2. 运行 ESLint
  // ...

  // 3. 运行 ts-prune
  // ...

  return report;
}

// 执行并输出报告
generateReport().then(report => {
  writeFileSync('dead-code-report.json', JSON.stringify(report, null, 2));
  console.log(`报告已生成: dead-code-report.json`);
  console.log(`发现 ${report.details.length} 个问题`);
});
```

---

## 9. CSS/SCSS 死代码检测工具

### 9.1 工具概览

| 工具 | 检测能力 | 特点 |
|------|----------|------|
| **PurgeCSS** | 未使用 CSS 类 | 最流行，支持 Vue/React，可配置 safelist |
| **UnCSS** | 未使用 CSS | 基于浏览器渲染，准确但较慢 |
| **PurgeCSS (PostCSS)** | 构建时清理 | 集成到构建流程，自动移除 |
| **stylelint-no-unused-selectors** | 未使用选择器 | Stylelint 插件，实时检测 |
| **scss-variable-lint** | 未使用 SCSS 变量 | 专注 SCSS 变量分析 |

### 9.2 PurgeCSS 集成

#### 安装

```bash
npm install -D purgecss
```

> 注：历史上该包名为 `@fullhuman/postcss-purgecss`，现已统一为 `purgecss`。

#### PostCSS 配置

```javascript
// postcss.config.js
const purgecss = require('purgecss');

module.exports = {
  plugins: [
    purgecss({
      // 扫描的内容文件
      content: [
        './src/**/*.vue',      // Vue SFC
        './src/**/*.html',     // HTML 模板
        './src/**/*.jsx',      // JSX 文件
        './src/**/*.ts',       // TS 中的动态类名
        './src/**/*.js',       // JS 中的动态类名
      ],
      
      // CSS 文件
      css: ['./src/**/*.css', './src/**/*.scss'],
      
      // 默认提取器（处理 Vue/JSX）
      defaultExtractor: content => content.match(/[\w-/:]+(?<!:)/g) || [],
      
      // 安全列表（不删除的类名）
      safelist: {
        // 标准 safelist
        standard: [
          /^is-/,           // 保留所有 is-* 类（状态类）
          /^v-/,            // 保留 Vuetify 组件类（本项目组件库为 Vuetify 4）
          // Tailwind v4 为 CSS 优先配置（@tailwindcss/vite 插件，无 tailwind.config.js），
          // 其工具类在构建期由 Tailwind 自身扫描生成，无需在此 safelist 重复列举
          'active',
          'disabled',
          'hidden',
        ],
        // 深度 safelist（处理动态类名）
        deep: [
          /^status-/,       // 保留 status-* 系列
          /^type-/,         // 保留 type-* 系列
        ],
        // 贪婪匹配
        greedy: [
          /data-table-.*/,  // 保留 data-table-* 相关
        ],
      },
      
      // 关键 CSS（不删除）
      keyframes: false,     // 是否保留 @keyframes
      fontFace: false,      // 是否保留 @font-face
    }),
  ],
};
```

#### 仅检测（不删除）

```bash
# 使用 PurgeCSS CLI 生成报告
npx purgecss --css src/**/*.css --content src/**/*.vue --output report/

# 查看哪些类被移除
npx purgecss --css src/styles/main.css --content src/**/*.vue --rejected
```

### 9.3 Stylelint 插件集成

#### 安装

```bash
npm install -D stylelint stylelint-no-unused-selectors
```

#### 配置

```json
// .stylelintrc.json
{
  "plugins": ["stylelint-no-unused-selectors"],
  "rules": {
    "no-unused-selectors/selectors": [
      true,
      {
        "ignoreSelectors": [
          "/^is-/",
          "/^el-/",
          "/^w-/"
        ]
      }
    ]
  }
}
```

#### 执行

```bash
# 检测未使用选择器
npx stylelint "src/**/*.css" "src/**/*.scss" "src/**/*.vue"

# 自动修复（仅部分规则支持）
npx stylelint "src/**/*.scss" --fix
```

### 9.4 SCSS 变量检测脚本

```bash
#!/bin/bash
# scripts/find-unused-scss-vars.sh

# 提取所有 SCSS 变量
VARS=$(grep -roh '\$[a-zA-Z_][a-zA-Z0-9_-]*' src/ --include="*.scss" | sort -u)

echo "=== 未使用的 SCSS 变量 ==="

for var in $VARS; do
  # 统计引用次数（排除定义行）
  COUNT=$(grep -r "$var" src/ --include="*.scss" --include="*.vue" | wc -l)
  
  if [ "$COUNT" -le 1 ]; then
    # 仅出现 1 次（定义处），说明未被使用
    FILE=$(grep -rl "$var" src/ --include="*.scss")
    echo "  $var ($FILE)"
  fi
done
```

### 9.5 Vue SFC 样式检测

针对 Vue 单文件组件的 `<style>` 块，需要特殊处理：

```typescript
// 提取 Vue SFC 中的样式选择器
function extractVueStyleSelectors(vueFile: string): string[] {
  const content = readFileSync(vueFile, 'utf-8');
  
  // 匹配 <style> 块
  const styleBlocks = content.match(/<style[^>]*>([\s\S]*?)<\/style>/g) || [];
  
  const selectors: string[] = [];
  
  for (const block of styleBlocks) {
    // 提取 CSS 内容
    const cssContent = block.replace(/<style[^>]*>|<\/style>/g, '');
    
    // 提取选择器（简化版）
    const selectorMatches = cssContent.match(/^([^{}/]+)\{/gm) || [];
    for (const match of selectorMatches) {
      const selector = match.replace('{', '').trim();
      selectors.push(selector);
    }
  }
  
  return selectors;
}

// 检查选择器是否在 template 中被使用
function isSelectorUsedInTemplate(selector: string, vueFile: string): boolean {
  const content = readFileSync(vueFile, 'utf-8');
  
  // 提取 template 块
  const templateMatch = content.match(/<template>([\s\S]*?)<\/template>/);
  if (!templateMatch) return false;
  
  const template = templateMatch[1];
  
  // 检查类名引用
  if (selector.startsWith('.')) {
    const className = selector.slice(1);
    return template.includes(className);
  }
  
  // 检查 ID 引用
  if (selector.startsWith('#')) {
    const id = selector.slice(1);
    return template.includes(id);
  }
  
  return false;
}
```

### 9.6 工具组合方案（含 CSS/SCSS）

```bash
#!/bin/bash
# 完整死代码检测（含 CSS/SCSS）

echo "=== 死代码检测（完整版）==="

# 1. JS/TS 死代码
echo "1. 检测 JS/TS 死代码..."
npx knip --reporter json > js-report.json

# 2. CSS/SCSS 死代码
echo "2. 检测 CSS/SCSS 死代码..."
npx purgecss \
  --css 'src/**/*.css' 'src/**/*.scss' \
  --content 'src/**/*.vue' 'src/**/*.ts' 'src/**/*.js' \
  --rejected \
  > css-report.txt

# 3. 合并报告
echo "3. 生成合并报告..."
node scripts/merge-reports.js js-report.json css-report.txt > dead-code-report.md

echo "=== 检测完成 ==="
```

### 9.7 package.json 脚本

```json
{
  "scripts": {
    "dead-code": "npm run dead-code:js && npm run dead-code:css",
    "dead-code:js": "knip --reporter json > reports/js-dead-code.json",
    "dead-code:css": "purgecss --css 'src/**/*.css' 'src/**/*.scss' --content 'src/**/*.vue' --rejected > reports/css-dead-code.txt",
    "dead-code:report": "node scripts/merge-reports.js",
    "dead-code:fix": "npm run dead-code:js -- --fix && npm run dead-code:css"
  }
}
```

### 8.2 与 npm scripts 集成

```json
{
  "scripts": {
    "dead-code": "node scripts/generate-dead-code-report.js",
    "dead-code:fix": "npm run dead-code && npx eslint --fix src/",
    "dead-code:ci": "npm run dead-code && test $(jq '.details | length' dead-code-report.json) -lt 10"
  }
}
```
