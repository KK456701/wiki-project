# 改后验证门禁（Verification Gate，常驻）

> AI 在完成任何代码新增/修改后，必须运行 `npm run lint` 与 `npm run typecheck`（vue-tsc 类型检查）验证，**未通过校验不得宣称"完成/通过"**。

## 核心规则

1. 任何代码改动（新增/编辑/删除文件、修改配置）完成后，**必须**执行：
   ```bash
   npm run lint       # ESLint，0 warning（--max-warnings 0）
   npm run typecheck  # vue-tsc 类型检查
   ```
2. **两项都通过**才算完成。任一项报错/警告未消，不得向用户宣称"已完成/已修复/已通过"。
3. 若改动涉及格式化（如新建/大改文件），先 `npx prettier --write <file>` 再 lint（见 `B04-code-formatting.md`）。
4. 若改动 Vue 组件，确认行数 ≤ 250（硬上限，见 `A03-vue-component-size.md`）。
5. 若 lint/typecheck 因环境问题无法运行（如依赖未装），必须明确告知用户"未验证"，不得默认成功。

## 为什么必须门禁

- CI 卡零警告（`npm run lint --max-warnings 0`），本地不过等于 CI 必红。
- `noUnusedLocals` / `noUnusedParameters` 已开，未使用变量直接报错。
- 类型错误在运行时才暴露，typecheck 是前置拦截。

## 检查清单

- [ ] 是否运行了 `npm run lint` 且 0 warning？
- [ ] 是否运行了 `npm run typecheck` 且无类型错误？
- [ ] 若有格式化需求，是否先 prettier 再 lint？
- [ ] 若改了 Vue 组件，行数是否 ≤ 250？
- [ ] 是否两项都通过后才向用户报告完成？
