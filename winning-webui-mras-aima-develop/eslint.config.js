/**
 * ESLint 配置文件（Flat Config 格式，适用于 ESLint 9+）
 *
 * 配置结构说明：
 * - 数组中的每个对象称为一个"配置块"，按顺序依次应用
 * - 后面的配置块会覆盖前面的同名规则
 * - `files` 字段指定该配置块作用的文件范围
 * - `ignores` 字段指定排除的文件范围
 *
 * @see https://eslint.org/docs/latest/use/configure/configuration-files
 */

// ─── 插件与预设导入 ───────────────────────────────────────────────
import js from '@eslint/js'; // ESLint 内置的推荐规则集
import pluginVue from 'eslint-plugin-vue'; // Vue 单文件组件 lint 规则插件
import tseslint from '@typescript-eslint/eslint-plugin'; // TypeScript lint 规则插件
import tsparser from '@typescript-eslint/parser'; // TypeScript 代码解析器
import vueParser from 'vue-eslint-parser'; // Vue SFC 模板解析器（解析 <template> 部分）
import eslintConfigPrettier from 'eslint-config-prettier'; // 关闭与 Prettier 冲突的 ESLint 规则
import globals from 'globals'; // 预定义的环境全局变量集合（浏览器、Node.js 等）
import vuetify from 'eslint-plugin-vuetify'; // 官方 Vuetify lint 插件，提供 Vuetify4 规范校验

export default [
  // ─── 1. 基础 JS 推荐规则 ─────────────────────────────────────────
  // 来自 @eslint/js 的 recommended 预设，包含 ESLint 官方推荐的基础规则
  // 如：禁止未声明变量、禁止重复参数名等
  js.configs.recommended,

  // ─── 2. Vue 推荐规则 ─────────────────────────────────────────────
  // 来自 eslint-plugin-vue 的 flat/recommended 预设
  // 包含 Vue 模板和 SFC 的推荐 lint 规则，如：
  // - 模板中禁止使用 v-html（防 XSS）
  // - 组件名必须多单词
  // - 指令缩写一致性等
  ...pluginVue.configs['flat/recommended'],

  // ─── 3. TypeScript 文件配置 ──────────────────────────────────────
  // 仅作用于 .ts / .tsx 文件
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: {
      // 使用 TypeScript 专用解析器，替代默认的 espree
      parser: tsparser,
      parserOptions: {
        ecmaVersion: 'latest', // 支持最新的 ECMAScript 语法
        sourceType: 'module', // 代码为 ES Module（import/export）
      },
      globals: {
        ...globals.browser, // 浏览器环境全局变量（window、document、fetch 等）
        // RequestInit 为 TypeScript DOM 类型全局（无运行时值），globals.browser 未收录，
        // 用于 request.ts 的 RequestOptions 继承 fetch 原生配置，需在此显式声明以免 no-undef 误报
        RequestInit: 'readonly',
      },
    },
    plugins: {
      // 注册 @typescript-eslint 插件，提供 TS 专属规则
      '@typescript-eslint': tseslint,
    },
    rules: {
      // 继承 @typescript-eslint 的推荐规则
      ...tseslint.configs.recommended.rules,
      // 禁止未使用的变量，但以 _ 开头的参数除外（常用于回调函数的占位参数）
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      // 警告使用 any 类型（鼓励使用更精确的类型定义）
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },

  // ─── 4. Vue 单文件组件配置 ──────────────────────────────────────
  // 仅作用于 .vue 文件
  {
    files: ['**/*.vue'],
    languageOptions: {
      // 主解析器使用 vue-eslint-parser，负责解析 <template>、<style> 等 Vue 特有语法
      parser: vueParser,
      parserOptions: {
        // <script> 块内的 TypeScript 代码交由 @typescript-eslint/parser 解析
        parser: tsparser,
        ecmaVersion: 'latest',
        sourceType: 'module',
        // 告知解析器 .vue 是合法的文件扩展名，避免解析报错
        extraFileExtensions: ['.vue'],
      },
      globals: {
        ...globals.browser, // 浏览器环境全局变量（window、document、navigator 等）
        // RequestInit 为 TypeScript DOM 类型全局（无运行时值），globals.browser 未收录，
        // 与 .ts 配置保持一致，避免 <script> 块内类型引用被 no-undef 误报
        RequestInit: 'readonly',
      },
    },
    plugins: {
      '@typescript-eslint': tseslint,
    },
    rules: {
      // ── TypeScript 规则（与 .ts 文件保持一致）──
      ...tseslint.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',

      // ── Vue 专属规则 ──
      // 注意：vue/multi-word-component-names、vue/no-v-html、vue/require-default-prop
      // 已在 flat/recommended 预设中配置为 error 级别，遵循 Vue 官方风格指南 Priority A
      // 如需覆盖，请谨慎考虑后再修改

      // 强制模板中的属性名使用 kebab-case（如 my-prop 而非 myProp）
      'vue/attribute-hyphenation': ['error', 'always'],
      // 强制模板中的事件名使用 kebab-case（如 @my-event 而非 @myEvent）
      'vue/v-on-event-hyphenation': ['error', 'always'],
      // 强制组件选项按固定顺序排列（如 name → props → computed → methods 等）
      'vue/order-in-components': 'error',
      // 允许带点号的具名插槽 —— Vuetify 数据表格的列插槽命名为 #item.xxx / #header.xxx，
      // 默认规则会把点号误判为指令修饰符
      'vue/valid-v-slot': ['error', { allowModifiers: true }],
    },
  },

  // ─── 4.1 Vuetify4 规范强制（长效护栏）───────────────────────────
  // 注册官方 eslint-plugin-vuetify 的 recommended-v4 预设，防止后续代码混入 Vuetify3 废弃写法。
  // 覆盖的校验维度：
  // - vuetify/no-deprecated-typography：禁止 MD2 排版类（text-caption / text-subtitle-1 / text-h5 等），自动修复为 MD3 token
  // - vuetify/no-legacy-grid-props：禁止 v3 栅格旧 props（如 row/col、grid-list-*）
  // - vuetify/no-elevation-overflow：禁止超出 Vuetify4 支持范围的 elevation 值
  // - vuetify/no-deprecated-snackbar：禁止废弃的 snackbar 写法
  // 说明：recommended-v4 预设即 eslint-plugin-vuetify 官方提供的 Vuetify4 质量保障方案，
  // 配合 `npm run lint -- --fix` 可自动迁移绝大多数 typography 废弃类。
  {
    files: ['**/*.vue'],
    plugins: {
      vuetify,
    },
    rules: {
      ...vuetify.configs['recommended-v4'].rules,
    },
  },

  // ─── 4.2 index.vue 文件特殊配置 ──────────────────────────────────
  // 对 index.vue 文件豁免 multi-word-component-names 规则
  // 因为 index.vue 作为目录入口文件，使用单词命名是合理的约定
  {
    files: ['**/index.vue'],
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },

  // ─── 5. 通用规则（作用于所有文件）─────────────────────────────────
  {
    rules: {
      // 警告使用 console（生产代码中应避免遗留调试日志）
      'no-console': 'warn',
      // 警告使用 debugger（生产代码中不应遗留断点）
      'no-debugger': 'warn',
      // 关闭基础 no-unused-vars（由 @typescript-eslint/no-unused-vars 接管，避免重复检查）
      'no-unused-vars': 'off',
      // 强制使用 const 声明不会被重新赋值的变量
      'prefer-const': 'error',
      // 禁止使用 var（统一使用 let/const，避免变量提升带来的问题）
      'no-var': 'error',
    },
  },

  // ─── 6. Prettier 集成 ───────────────────────────────────────────
  // 必须放在配置数组的最后，确保能覆盖前面所有与 Prettier 冲突的 ESLint 规则
  // 这样 ESLint 和 Prettier 各司其职：ESLint 管代码质量，Prettier 管代码格式
  eslintConfigPrettier,

  // ─── 7. 全局忽略 ────────────────────────────────────────────────
  // 以下文件和目录不参与 ESLint 检查
  {
    ignores: [
      'node_modules/**', // 依赖包
      'dist/**', // 构建产物
      'dist-ssr/**', // SSR 构建产物
      'eslint.config.js', // 配置文件本身
      'create-build-info.js', // 构建信息生成脚本（Node.js 环境）
      '.vscode/**', // VSCode 配置
      'public/**', // 静态资源目录
      'prototype/**', // 原型目录，不参与任何代码校验（eslint/prettier/rules 等）
    ],
  },
];
