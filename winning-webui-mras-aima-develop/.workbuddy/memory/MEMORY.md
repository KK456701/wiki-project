# 项目长期记忆 · winning-webui-mras-aima

## 技术栈（已核实，2026-08-06，2026-08-12 更新）
- Vue 3.5 + vue-router 4 + **Vuetify ^4.1.6** + Vite 8 + TypeScript。
- 查故障功能现已拆分为两处：
  - **入口/卡片**：`src/views/ChatView/components/diagnosis/DiagnosisCaseCard.vue`（轻量入口卡，点「打开排查工作区」→ 跳 `/diagnosis`）+ `QuickActions.vue`（「查故障」卡片直接 `router.push` 到 `/diagnosis`）。
  - **全屏排查工作区**：`src/views/DiagnosisWorkspace/`（独立路由页 `/diagnosis`，含 Stepper/SummaryHeader/StepSelection/StepDataConfirmation/StepLineage + 编排 composable `useDiagnosisWorkspace.ts`）。
- 算指标仍为指标多选（inline 表单，由 `QuickActions.vue` 的 `multipleRule` 控制）；查故障=指标单选。

## 关键业务约定（用户确认）
- **`profileId` = 所选口径的 profileId，未必等于 `ruleId`**（2026-08-12 重构推翻早期「profileId 恒等于 ruleId」约定）。一个指标可能有多个口径，仅一个时自动选中；口径列表走 `GET /api/kb/rules/{ruleId}/profiles`。
- 查故障：指标单选；算指标：指标多选。
- 全屏工作区靠 URL 持久化（`/diagnosis?step=&caseId=`），可抗手动刷新；刷新时若无显式 `step` 但有 `caseId`，按 3 个 gate 结果推导（全部 PASSED → `lineage`，否则 `data`）。

## 实施约束（重要 · 跨会话适用）
- **readonly 仓库（`readonly/winning-webui-mras-aima`）仅作功能/数据流参考，其 UI 与交互不可直接照搬**。readonly 用大量自定义 CSS 类（`.diagnosis-compare-table`、`.diagnosis-case-table`、`data-state` 徽章等）实现对比表格。
- **移植到本项目的任何 readonly 能力，必须用 Vuetify 4 官方组件 + Material Design 规范重新实现**：对比表格用 `v-table`/`v-data-table`；状态用 `v-chip` 语义色（success/error/warning）替代 `data-state`；折叠区用 `v-expansion-panel`；卡片用 `v-card`+elevation；加载态用 `v-progress-linear`/`v-progress-circular`。不复制 readonly 自定义 class。
- 组件 API 以 Vuetify 4 为准（`v-date-input`/`v-checkbox-btn`/`v-autocomplete` 等已在本项目使用）。

## 审计文档
- 查故障功能审计清单：`查故障功能审计清单.md`（项目根目录）。含 A/B/C/D/E 级问题、readonly 三边界对照、决策项与建议、实施约束。
