---
name: cb-code-commit
description: 代码提交工作流规则，包含代码检查、评审和 Conventional Commits 规范。当用户请求提交代码、git commit 或类似表达时使用此技能。
---

# CB Code Commit Workflow

## Overview

规范代码提交流程，包含代码质量检查、代码评审和 Conventional Commits 提交信息格式规范。确保每次提交都符合本项目（Vue 3 + Vuetify + TypeScript + Vite）规范，提高代码质量和可维护性。

> 本 skill 由 `code-commit` 转换而来，检查项已适配当前项目技术栈（详见 `.roo/rules/A00-project-conventions.md`）。

## Workflow

### Step 1: 代码检查阶段

**自动检测项目环境**：

1. **检测包管理器**（按优先级）：
   - 存在 `pnpm-lock.yaml` → 使用 `pnpm`
   - 存在 `yarn.lock` → 使用 `yarn`
   - 存在 `package-lock.json` → 使用 `npm`
   - 都不存在 → 询问用户

2. **检测项目类型**（按优先级匹配）：
   - 存在 `.sparkrc.ts` → Spark 项目
   - 存在 `src-tauri/tauri.conf.json` 或 `src-tauri/Cargo.toml` → Tauri 项目（可能同时是 TypeScript 项目）
   - 存在 `tsconfig.json` 且包含 TypeScript 文件 → TypeScript 项目
   - 其他 → 普通 JavaScript 项目

   > 注意：Tauri 项目可以同时是 TypeScript 项目，此时需要同时执行前端和 Rust 的检查。

**执行检查命令**：

```bash
# 1. 检查前端 linter 错误（根据检测到的包管理器）
{packageManager} lint

# 2. 检查 TypeScript 类型（仅 TypeScript 项目）
{packageManager} type-check  # 或 tsc --noEmit

# 3. Rust 代码检查（仅 Tauri 项目）
cd src-tauri && cargo fmt --check    # Rust 格式检查
cd src-tauri && cargo clippy -- -D warnings  # Rust lint 检查
```

如果有错误，必须先修复再继续。

> **注意**：如果 `package.json` 中没有 `lint` 脚本，跳过前端 lint 检查并提醒用户。Rust 检查命令如果 `cargo` 未安装则跳过并提醒。

### Step 2: 代码评审阶段

在提交前，必须对变更的文件进行评审。根据 Step 1 检测到的项目类型，选择对应的检查项。

#### 通用检查项（所有项目）
- [ ] 是否有 `any` 类型（TypeScript 项目禁止随意使用，优先精确类型）
- [ ] 是否有硬编码文本 / 魔法字符串（应提取常量或 i18n，见 `B03-no-magic-strings.md`）
- [ ] 是否修改了禁止修改的文件（根据项目配置）

#### 当前项目专属检查项（Vue 3 + Vuetify + TS + Vite，依据 `A00-project-conventions.md`）
- [ ] 是否使用了正确的导入方式（Vue/vue-router/pinia 从原生包引入）
- [ ] 是否只使用了 Vuetify 组件（`<v-` 标签）；非必要不引第三方组件库
- [ ] 是否使用 `@/` 别名而非相对路径 `../../`
- [ ] 调后端是否走 `@/utils/request`（禁止裸写 fetch，见 `B10-api-network-layer.md`）
- [ ] 浏览器存储是否走 `@/storage`（禁止裸写 localStorage/sessionStorage，见 `B07-storage-guidelines.md`）
- [ ] 新建/编辑的 Vue 组件是否超出 250 行硬上限（见 `A09-vue-component-size-limit.md`）

#### Spark 项目专属检查项（仅当检测到 `.sparkrc.ts` 时）
- [ ] 是否使用了正确的导入方式（Vue/vue-router/pinia 从原生包，其他从 spark）
- [ ] 是否只使用了 win-design 组件（`<w-` 标签）
- [ ] 是否遵守模块隔离规范（禁止跨模块 import）

#### Tauri 项目专属检查项（仅当检测到 Tauri 项目时）

**前端代码检查**（对照项目规则 `tauri-vue-development-guide.md`）：
- [ ] 是否使用了 `localStorage`/`sessionStorage` 进行持久化（应使用 `tauri-plugin-store` 或 `@tauri-store/pinia`）
- [ ] 是否使用了原生 `fetch` 调用外部 API（应使用 `@tauri-apps/plugin-http` 或 Tauri Command）
- [ ] vue-router 是否使用 History 模式（必须使用 `createWebHashHistory()`）
- [ ] 是否硬编码文件路径（应使用 `@tauri-apps/api/path` API）
- [ ] Pinia 持久化是否使用了 `pinia-plugin-persistedstate`（应使用 `@tauri-store/pinia`）
- [ ] 是否使用了 SSR（Tauri 禁止 SSR，必须用 SPA 模式）
- [ ] 敏感数据（Token/密钥）是否存储在 `localStorage`（应使用 `tauri-plugin-stronghold`）

**Rust 代码检查**：
- [ ] 是否遵循 Rust 命名规范（snake_case 函数名，CamelCase 类型名）
- [ ] 是否有未处理的 `unwrap()`/`expect()`（生产代码应使用错误处理）
- [ ] 是否有 `unsafe` 代码块（如有需说明理由）

#### 文件权限检查（根据项目实际配置调整）

**Spark 项目**：
- ✅ 允许修改：`src/views/**/*`, `src/pages/**/*`, `src/rdf-views/**/*`, `mock/**/*`
- 🔒 公共目录（只能引用）：`src/components/`, `src/composables/`, `src/stores/`, `src/utils/`, `src/assets/`
- 🚫 禁止修改：`.sparkrc.ts`, `tsconfig.json`, `package.json`, `src/app.ts`, `src/global.ts`, `src/global.scss`, `src/App.vue`

**Tauri 项目**：
- ✅ 允许修改：`src/**/*`, `src-tauri/src/**/*`
- 🔒 谨慎修改（需确认）：`src-tauri/tauri.conf.json`（CSP/权限配置）, `src-tauri/Cargo.toml`（Rust 依赖）, `src-tauri/capabilities/**/*`（权限配置）
- 🚫 禁止修改：`src-tauri/icons/**/*`（图标资源）, `src-tauri/gen/**/*`（自动生成文件）

**当前项目（非 Spark 非 Tauri）**：
- 以 `.roo/rules/A00-project-conventions.md` 的「分层职责」与目录约定为准
- 关注是否存在修改了不应变更的配置文件的情况

### Step 3: 变更分析

分析 git 变更，识别变更类型：

```
feat     - 新功能
fix      - 修复 bug
docs     - 文档变更
style    - 代码格式（不影响功能）
refactor - 重构
perf     - 性能优化
test     - 测试相关
chore    - 构建/工具变动
```

### Step 4: 关联工作项

**必须**在生成提交信息之前询问用户关联的工作项ID。

**执行流程**：
1. 使用 `ask_followup_question` 工具询问用户关联的工作项ID
2. 等待用户输入工作项ID（支持单个或多个，多个用逗号分隔，例如：123456 或 123456,789012）
3. 记录用户输入的工作项ID，供 Step 5 生成提交信息时使用

**示例**：
```typescript
// 询问用户
ask_followup_question({
  question: "请输入关联的工作项ID（支持多个，用逗号分隔，例如：123456 或 123456,789012）",
  follow_up: [
    { text: "123456", mode: null },
    { text: "123456,789012", mode: null },
    { text: "跳过（不关联工作项）", mode: null }
  ]
})
```

**注意事项**：
- 支持关联多个工作项，用逗号分隔
- 如果用户选择"跳过"或输入空值，则提交信息中不添加工作项引用
- 工作项ID必须是数字格式

### Step 5: 生成提交信息

根据 Step 3 的变更分析结果和 Step 4 获取的工作项ID，按照 Conventional Commits 规范生成提交信息：

```
<type>[optional scope]: <description>

[optional body]

Refs #<工作项ID>

Co-authored-by: {MODEL}({AI_ENV})
```

格式要求：
- type: 必须是上述类型之一
- scope: 可选，表示影响范围
- description: 简短描述（不超过 50 字符）
- body: 详细描述变更内容（每行不超过 72 字符）。当变更项较多时，使用无序列表组织：
  ```
  变更摘要描述

  - 变更项 1
  - 变更项 2
  - 变更项 3
  ```
- footer: 包含工作项引用（`Refs`，使用 Step 4 获取的工作项ID）和 AI 协作标识（`Co-authored-by`，见 Step 6）

工作项引用格式：
- 单个工作项：`Refs #123456`
- 多个工作项：`Refs #123456, #789012`
- 用户跳过时：不添加 `Refs` 行

> **注意**：使用 `Refs #<ID>` 格式，符合 Conventional Commits 规范。`Refs` 表示引用工作项，不会暗示会关闭工作项（不同于 `Closes`/`Fixes`）。

### Step 6: 追加 AI 协作标识

在提交信息的 footer 最末尾，**必须**追加一行 `Co-authored-by` 标识，记录本次提交中 AI 的参与信息。

**格式**：
```
Co-authored-by: {MODEL}({AI_ENV})
```

**输出示例**：
- `Co-authored-by: GLM-5.1(Roo Code)`
- `Co-authored-by: Claude Sonnet 4(Claude Code)`
- `Co-authored-by: DeepSeek-V3(Cline)`

**检测方式**：

1. **获取模型名称（{MODEL}）**：
   - 优先从当前环境上下文中获取模型标识（如环境详情中的模型名称字段）
   - 将模型标识格式化为人类可读名称，遵循以下规则：
     - 去除版本日期后缀（如 `-20250514`）
     - 首字母大写，连字符后的字母也大写（如 `glm-5.1` → `GLM-5.1`，`deepseek-v3` → `DeepSeek-V3`）
     - 已有品牌大小写的保持原样（如 `Claude Sonnet 4`）

2. **获取环境名称（{AI_ENV}）**：
   - 根据当前运行环境的工具链和上下文推断宿主环境名称
   - 常见环境映射：

     | 环境线索 | 环境名称 |
     |---------|---------|
     | 工具包含 `ask_followup_question`、`execute_command`、`apply_diff` 等，系统提示包含 "Zoo" | Zoo Code |
     | 工具包含 `ask_followup_question`、`execute_command`、`apply_diff` 等，系统提示包含 "Roo" 且不含 "Zoo" | Roo Code |
     | 工具包含 `TodoWrite`、`Read`、`Write` 等，系统提示包含 "Claude Code" | Claude Code |
     | 工具前缀为 `kilo_` 或系统提示包含 "Kilo" | Kilo Code |
     | 工具前缀为 `cline_` 或系统提示包含 "Cline" | Cline |
     | 工具包含 CodeBuddy 专属工具 `mcp_call_tool`/`mcp_get_tool_description`、`automation_update`、`connect_cloud_service`、`use_skill` 中的任意一个（这些工具在其他编码助手中不存在），或系统提示包含 "CodeBuddy" | CodeBuddy |

     > **CodeBuddy 识别说明**：CodeBuddy 没有公开的"环境标识"环境变量或系统提示固定字段，但其工具集中 `mcp_call_tool`、`mcp_get_tool_description`、`automation_update`、`connect_cloud_service`、`use_skill` 为 CodeBuddy 独有（其他助手如 ROO/Cline/Claude Code 不具备），因此优先以工具集作为确定性判定依据，比依赖系统提示文本更稳定。

3. **回退策略**：
   - 如果无法可靠获取模型名称或环境名称中的**任意一个**，则整行统一回退为：
     ```
     Co-authored-by: AI 大模型
     ```
   - 回退条件包括：环境上下文中无模型信息、模型名称无法解析、环境名称无法推断

**完整提交信息示例**：
```
feat(template): 添加模板配置功能

- 添加模板配置面板，支持动态配置模板属性
- 新增模板变量解析引擎
- 优化模板渲染性能

Refs #123456, #789012

Co-authored-by: GLM-5.1(Roo Code)
```

**无工作项时的提交信息示例**：
```
fix(auth): 修复登录态过期未正确跳转的问题

- 修复 Token 过期后未清除本地缓存导致请求循环
- 统一 Axios 拦截器中的 401 处理逻辑
- 新增登录态过期提示

Co-authored-by: GLM-5.1(Roo Code)
```

**注意事项**：
- `Co-authored-by` 必须放在提交信息的**最末尾**（在 `Refs` 之后）
- `Co-authored-by` 前必须有一个空行，与上方 footer 内容分隔，符合 Git trailer 格式
- 即使没有关联工作项（用户跳过 Step 4），`Co-authored-by` 仍必须追加在 body 之后
- 此步骤**不可跳过**，每次提交都必须包含

### Step 7: 执行提交

> ⚠️ **强制要求（Windows 环境）**
>
> **绝对禁止**使用 `git commit -m "多行内容"`，PowerShell 会丢失换行符导致提交信息只有标题没有 body。
>
> **必须使用临时文件方式**：
> ```bash
> write_to_file(".git/COMMIT_MSG_TEMP", commitMessage)
> git add .
> git commit -F .git/COMMIT_MSG_TEMP
> delete_files(".git/COMMIT_MSG_TEMP")
> ```
>
> 违反此规则将导致提交信息格式错误，必须 amend 修正。

## Usage Examples

**触发关键词**:
- "提交代码"
- "git commit"
- "帮我提交代码"
- "commit my changes"

**执行流程**:
1. 自动检测包管理器和项目类型，执行对应的 lint 和类型检查
2. 根据项目类型选择对应的评审检查项，对变更文件进行代码评审
3. 分析变更类型，确定 type 和 scope
4. 询问用户关联的工作项ID
5. 结合变更类型和工作项ID，生成符合 Conventional Commits 规范的完整提交信息
6. 检测当前 AI 模型名称和宿主环境，追加 `Co-authored-by` 标识到提交信息末尾
7. 使用临时文件方式执行 git commit（Windows 环境）
8. 输出评审结果和提交信息

## Output Format

提交完成后，输出以下信息：

```
## 代码评审结果
- 检查项: 通过/未通过
- 发现的问题: 列表（如果有）

## 关联工作项
#<工作项ID> 或 未关联

## 提交信息
<生成的提交信息，包含工作项信息和 Co-authored-by 标识>

## 变更统计
- 文件变更数
- 新增行数
- 删除行数
```

## Notes

1. 如果 lint 检查失败，询问用户是否继续提交
2. 如果发现规范问题，提醒用户并提供修复建议
3. 用户明确要求跳过检查时，可以跳过代码评审阶段
4. Windows 环境下必须使用临时文件方式处理多行提交信息
5. `Co-authored-by` 标识为强制追加项，不可跳过
