# 查故障功能 × 后端 API 文档变更 · 影响分析报告

> 分析对象：`winning-winex-mras-aima/docs/api/` 下刚变更的 4 份后端契约
> （`README.md`、`diagnosis-cases.md`、`diagnosis-case-actions.md`、`hospital-drafts.md`）
> 及其引用的 `../diagnosis-troubleshooting-flow.md`。
>
> 比对目标：当前前端实现（`winning-webui-mras-aima/src/.../diagnosis/` 下的 types/services/store/组件）。
>
> 结论：**接口路径、动作矩阵、快照结构、自动连跑三门校验核心逻辑均与权威契约一致；**
> 偏差集中在「证据提交字段/形态」与「两处 UI 门控」，均为我此前参照旧的「前端参考文档」
> （`异常排查-*.md`）实现、与现在这份权威后端契约不一致所致。

---

## 0. 已确认一致（无需改动）

| 项 | 实现位置 | 契约依据 |
| --- | --- | --- |
| 动作矩阵 11 步按钮全覆盖（含 SHADOW_TRIAL / DRAFT_SAVE / COMPLETED） | `DiagnosisActionBar.vue` `buildActions` | actions §2 |
| `CONFIRM_CALIBER` / `CONFIRM_CASE_CALIBER` / `SAVE_HOSPITAL_DRAFT` 均带 `payload:{confirmed:true}` | `ActionBar` L37/59/82 | actions §3.1 |
| `SUBMIT_CASE` 字段：`recordField`+`recordIds`+`symptom` | `DiagnosisActionForm.vue` L52-61 | actions §3.3 |
| `BUILD_CANDIDATE` 字段：`type/layer/sql/requirements/expectedCaseEffect` | `DiagnosisActionForm.vue` L65-76 | actions §3.5 |
| `advanceGates` 自动连跑 1→2→3 直到首个 BLOCKED（含死循环保护） | `stores/diagnosis.ts` L110-126 | flow §4 + 用户确认的方案 A |
| gate 编号 `SCHEMA=1/EVENT=2/VALUE=3` | `diagnosis-constants.ts` GATE | cases §4 备注 |
| 快照类型含 `caseExpectedClassification` 等全字段 | `types/diagnosis.ts` | cases §4 |
| 明细响应类型字段全部匹配（含 `detailContractVersion`/`card*`/`detail*`） | `types/diagnosis.ts` `DiagnosisDetailsResponse` | cases §3 |
| 错误解析兼容 `{detail}` / `{detail:{code,message}}` / 顶层 `code` | `services/diagnosis.ts` `parseApiError` | README §5 |
| `CONFIRM_CALIBER`/`RUN_BASE_CHECKS`/`RECHECK_GATE` 后触发自动连跑 | `DiagnosisCaseCard.vue` L85-100 | 用户确认方案 A |

---

## 1. 偏差清单（需适配）

### ① 证据表单发送了契约不存在的字段 `treatment` 与 `expectedCaseEffect`
- **位置**：`DiagnosisEvidenceForm.vue` L20（`treatment` 控件）、L60（`treatment` 入参）、L65（`expectedCaseEffect` 入参）；`types/diagnosis.ts` L51/L53/L59-67。
- **契约**：`actions.md` §3.4 的 `SUBMIT_EVIDENCE` 合法字段为
  `summary / type / runAutomatic / requestAiAnalysis / suspectedLayer / requirement / validationSql / candidateSql / patchConditions`。
  **没有 `treatment` 和 `expectedCaseEffect`**（`expectedCaseEffect` 是 `BUILD_CANDIDATE` 的字段，不是证据的）。
- **影响**：后端 `@RequestBody` 反序列化会忽略未知字段，**不报错、不破坏**，但属于契约外脏字段，且类型定义把 `expectedCaseEffect` 错误地放在了 `EvidenceItem` 上。
- **建议适配**：
  - 证据表单移除「处置方向(treatment)」控件与提交；
  - 证据表单移除 `expectedCaseEffect` 控件与提交（该字段在 `BUILD_CANDIDATE` 表单已正确存在）；
  - 从 `EvidenceItem` 类型删除 `treatment` 与 `expectedCaseEffect`（保留 `requirement/validationSql/candidateSql/patchConditions` 等契约字段）。

### ② 证据类型被硬编码为 `IMPLEMENTER_SQL_REQUIREMENT`，过度收窄形态
- **位置**：`DiagnosisEvidenceForm.vue` L17 默认 `type='IMPLEMENTER_SQL_REQUIREMENT'`，且无其它类型入口；L25 `runAutomatic` 默认 `true`。
- **契约**：`actions.md` §4 前端要点 #2 明确支持 **4 种证据形态**：人工摘要 / 自动取证 / SQL 要求 / AI 解释；
  `SUBMIT_EVIDENCE` 的 `type` 是**可选**的（仅 `IMPLEMENTER_SQL_REQUIREMENT` 这一种才会触发「自动生成候选 SQL + 立即跑影子试跑」的重副作用）。
- **影响（中）**：当前每次提交证据都带 `type=IMPLEMENTER_SQL_REQUIREMENT` 且 `runAutomatic` 默认 `true`、
  `suspectedLayer=SOURCE_EXTRACT` → 后端会**对每一条证据都自动生成候选 SQL 并跑影子试跑**（§3.4）。
  一条简单的「我核对了 X」人工摘要也会被当成 SQL 改写要求去跑影子试跑，副作用过重，且无法提交「轻量人工摘要证据」。
- **建议适配（前端已给出推荐方案 X，待用户最终确认是否采用）**：
  - **方案 X（推荐）**：表单增加 `v-radio-group` 切换 ——
    - **轻量人工摘要（默认）**：仅 `summary` 必填，可选 `requestAiAnalysis`（AI 解释文字）；`type` 不传、`runAutomatic=false` → 后端不跑影子试跑、不生成候选；
    - **SQL 改写要求**：`type=IMPLEMENTER_SQL_REQUIREMENT`，展示 `suspectedLayer / requirement / patchConditions / candidateSql / validationSql` + `runAutomatic`(默认开) / `requestAiAnalysis` → 触发自动取证+候选+影子。
    - 该形态切换可保证「记一条发现」与「提一个 SQL 修改要求」两种语义清晰分离，避免轻量记录误触发重流程。
  - 方案 Y：维持现状（所有证据都走 SQL 要求形态，由后端统一自动取证）—— 唯一好处是统一省事，但每条都跑影子试跑（重操作）不划算。
  - **当前推荐 X**：更贴合契约 4 形态语义、避免重副作用误触发、UX 更清晰，且改动量小（独立表单组件内加一处切换 + 条件渲染，不破 A09 行数上限）。

### ③ 自动取证证据的渲染**入口写错**（已核对后端源码，结构已确知，非臆测）
- **位置**：`DiagnosisEvidenceItem.vue` L58-75（自动取证区块挂在 `item.type === 'AUTOMATIC_DATA_FLOW'` 分支）。
- **后端真实机制（已读 `DiagnosisCaseService.submitEvidence` L422-424 + `DiagnosisCaseEvidenceService.collect` L60-116 确认）**：
  - 后端**根本不存在 `AUTOMATIC_DATA_FLOW` 这个 type**。证据 `type` 只有 `'IMPLEMENTER_SQL_REQUIREMENT'`（触发自动生成候选+影子试跑）与「未填 type」两种特殊路径，其余 type 后端不枚举、不强制。
  - 自动取证由证据提交的 **`runAutomatic=true`** 触发：后端直接 `evidence.putAll(collect(...))`，把取证结果**扁平合并进这条证据对象的顶层**（与 type 无关）。
  - 合并进来的真实字段（即前端渲染要用到的）：
    - `summary`：自动取证结论文本（= `display.conclusion`）；
    - `stages`：数组，每项 `{stage, databaseRole, sql, status(COMPLETED|NEEDS_MANUAL_EVIDENCE), rowCount?, rows?, meaning?, error?, reason?}`；
    - `display`：`{found:[], notFound:[], unfinished:[], conclusion, nextAction}`；
    - `identifierMapping`：`{recordType, recordIds, businessSourceField, realTargetField}`；
    - `allStagesCompleted`：boolean。
  - 此外，仅当 `type==='IMPLEMENTER_SQL_REQUIREMENT'` 时，后端会在同一证据上**自动附加** `requirementAnalysis`（`{judgement, requirement, nextAction, sqlGeneration}`）与 `sqlContext`（`{available, layer?, layerLabel?, executableSql?, templateSqlHash?, currentResult?}`）——这两个字段前端此前渲染方向正确（L7-56），但 `sqlContext` 未渲染 `executableSql`/`currentResult` 增强项。
- **影响（中，非破坏）**：当前自动取证区块挂在**永远不命中的 `type==='AUTOMATIC_DATA_FLOW'`** 上 → 后端回流的 `display`/`stages` **永远不渲染**，自动取证结果在前端完全不可见。
- **说明（自我纠错）**：我此前报告写「契约未定义 `display`/`stages`、是臆测的」系**误判**——`collect()` 方法第 105-115 行明确定义了这两个字段。误判根源是旧的 `异常排查-*.md` 前端参考文档把证据形态写成「含 `AUTOMATIC_DATA_FLOW` 的 4 种枚举」，后端实际没有该枚举值，是我据此把渲染入口写错。现已通过直接核对后端 Java 源码纠正，**无需后端样例**。
- **建议适配**：自动取证区块的渲染条件从 `item.type === 'AUTOMATIC_DATA_FLOW'` 改为「证据对象是否含 `display`/`stages`（即后端经 `runAutomatic` 回流了取证结果）」；`IMPLEMENTER_SQL_REQUIREMENT` 的 `requirementAnalysis`/`sqlContext` 渲染保留，并可增强 `sqlContext.executableSql`/`currentResult` 展示。`EvidenceItem` 类型需补 `stages`/`display`/`identifierMapping`/`allStagesCompleted` 真实字段（保留 `Record<string,unknown>` 兜底以防其它未知回流）。

### ④ `SAVE_HOSPITAL_DRAFT` 按钮未按权限门控 —— **已决策：暂不控制**
- **位置**：`DiagnosisActionBar.vue` L75-84（DRAFT_SAVE 步骤直接渲染「保存医院草稿」按钮，**无权限判断**）。
- **契约**：`actions.md` §4 前端要点 #4 —— 「按钮仅在当前账号 `permissions` 含 `indicator_diagnosis_release` 且 `currentStep==='DRAFT_SAVE'` 时可用」，否则点按会得 `403 DRAFT_SAVE_PERMISSION_DENIED`。
- **用户决策（2026-08-06）**：`indicator_diagnosis_release` 权限**暂不控制**——前端不做权限门控，按钮始终可见；无权限用户点击后拿到 403 时，现有错误提示已优雅兜底（不崩），可接受。
- **结论**：该项**不纳入本次代码改动**，从修改清单移除。若后续需要前置隐藏，再单独评估权限来源（鉴权 store / 后端快照补充）。

### ⑤ 明细入口仅按 gate2 状态门控，未校验 `overviewSqlHash` 是否存在
- **位置**：`DiagnosisCaseCard.vue` L54（仅 `gate2Passed = status==='PASSED'`）、L187（`:disabled="!gate2Passed"`）。
- **契约**：`cases.md` §3 / §5 —— 「明细按钮仅在 **gate2 PASSED 且存在 `overviewSqlHash`** 时开放」；
  否则明细查询返回 `409 DIAGNOSIS_DETAIL_CONTEXT_MISSING`。
- **影响（低）**：正常情况下 gate2 通过即已产出 `overviewSqlHash`，实际触发 409 的概率极低；但严格按契约应双条件门控。
- **建议适配**：从 `gateResults`（gate=2）的 `facts.executionEvidence` 中提取 `overviewSqlHash`，并在按钮禁用条件中追加「该 hash 存在」判断（缺失时给出「需先执行第 2 关基础校验」提示）。

### ⑥ （无动作）`BASE_CHECKS_RESULT` 步骤
- `actions.md` 动作矩阵行 2 提到 `RUN_BASE_CHECKS` 允许步骤含 `BASE_CHECKS_RESULT`，但该步骤**未出现在 `flow.md` §3 的步骤对照表（11 步）中**，应为内部瞬时态、不会落到前端 UI。当前实现仅在 `GATE_*` 步骤显示「重跑基础检查」，无影响。仅记录，无需改动。

---

## 2. 待你确认/决策的点（阻塞或影响方案）

1. **② 证据形态**：当前**推荐方案 X（轻量摘要 / SQL 要求 双形态切换，默认轻量摘要）**（详见第②节建议适配段）。是否采用 X？还是维持 Y（全 SQL 要求）？
2. **③ 自动取证渲染**：~~能否由后端提供样例~~ **已自行核对后端源码确认真实结构**，无需样例；改动即「修正渲染入口」，无阻塞。
3. ~~④ 权限来源~~ **已决策：暂不控制 `indicator_diagnosis_release`，前端不改**（见第④节）。

---

## 3. 拟执行的修改清单（确认后落地）

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `src/types/diagnosis.ts` | 从 `EvidenceItem` 删除 `treatment`（证据无此字段）；保留契约字段 `requirement/validationSql/candidateSql/patchConditions`，并补 `stages`/`display`/`identifierMapping`/`allStagesCompleted` 真实回流字段（保留 `Record<string,unknown>` 兜底） |
| 2 | `DiagnosisEvidenceForm.vue` | 移除 `treatment` 控件与提交；按决策②增加「轻量摘要 / SQL 要求」双形态切换（`v-radio-group`，默认轻量；轻量形态 `type` 不传、`runAutomatic=false`，SQL 要求形态携带 `IMPLEMENTER_SQL_REQUIREMENT` + 全套字段） |
| 3 | `DiagnosisEvidenceItem.vue` | 自动取证区块渲染条件从 `type==='AUTOMATIC_DATA_FLOW'` 改为「证据含 `display`/`stages`」；`IMPLEMENTER_SQL_REQUIREMENT` 分支保留并增强 `sqlContext.executableSql`/`currentResult` 展示 |
| 4 | `DiagnosisCaseCard.vue` | 明细按钮禁用条件追加 `overviewSqlHash` 存在性校验 |

> 上述修改均不涉及接口路径/字段重命名，属于「契约贴合度修补」，改动量小、风险低；
> 完成后照例跑 `npm run typecheck` + `npm run lint` 校验门禁（A11）。
