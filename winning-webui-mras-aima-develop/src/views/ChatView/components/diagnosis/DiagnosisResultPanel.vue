<script setup lang="ts">
/**
 * 排查「方案与结论」结构化展示（C1 / C2）
 *
 * 将 snapshot 中的 candidateSql / shadowTrial / draftResult / causeConclusion
 * 从「原始 JSON dump」升级为 Material Design 结构化呈现：
 * - 候选修改方案：类型 / 作用层 / SQL / 摘要
 * - 影子试跑：状态 + 三层对比（抽取数据变化 / 最终指标结果变化 / 案例编号验收）
 * - 医院草稿：保存结果回显（draftId / 医院 / 作用层 / 复核状态）
 *
 * 设计说明（对照 readonly 参考实现）：
 * readonly 用自定义 CSS 类（.diagnosis-compare-table 等）+ 嵌套折叠面板实现；
 * 此处改用 Vuetify 4 官方组件（v-table / v-chip / v-expansion-panel）并按当前项目
 * 设计规范重新组织，未照搬其结构与样式。技术对账原文仅折叠在单层面板中备查。
 */
import { computed } from 'vue';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

const props = defineProps<{ snapshot: DiagnosisCaseSnapshot }>();

// ---- 安全取值工具（shadowTrial / candidateSql 在类型中为 Record<string, unknown>） ----
function asRec(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
}
function num(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) return Number(v);
  return null;
}
function str(v: unknown): string | null {
  if (v == null) return null;
  if (typeof v === 'object') return null; // 对象/数组不转为 "[object Object]"，交由调用方兜底
  const s = String(v).trim();
  return s || null;
}
function fmt(n: number | null, sign = ''): string {
  if (n == null) return '—';
  return `${sign}${n.toLocaleString()}`;
}
/**
 * 变化量展示文本：增加显示 `+x`，减少显示 `-x`（x 为绝对值），无数据/无变化显示占位。
 * 颜色语义见 deltaClass：增加=红、减少=绿（对齐「变化项/指标变化」列业务约定）。
 */
function deltaText(diff: number | null): string {
  if (diff == null) return '—';
  if (diff === 0) return '0';
  return `${diff > 0 ? '+' : '-'}${Math.abs(diff).toLocaleString()}`;
}
/** 变化量颜色：增加=红(text-error)、减少=绿(text-success)、无变化/无数据=灰 */
function deltaClass(diff: number | null): string {
  if (diff == null || diff === 0) return 'text-medium-emphasis';
  return diff > 0 ? 'text-error' : 'text-success';
}

// ---- 候选修改方案 ----
const candidate = computed(() => asRec(props.snapshot.candidateSql));
const candidateType = computed(() =>
  str(candidate.value['type'] ?? candidate.value['candidateType']),
);
const candidateLayer = computed(() => str(candidate.value['layer']));
const candidateSqlText = computed(
  () =>
    str(candidate.value['sql'] ?? candidate.value['candidateSql']) ??
    str(candidate.value['baselineSql']),
);
const candidateDiff = computed(() => str(candidate.value['diffSummary']));
const candidateValidation = computed(() => {
  const raw = candidate.value['validation'];
  // 后端可能返回字符串，也可能返回 { message, ok } 结构，统一取可读文本
  if (raw && typeof raw === 'object') return str((raw as Record<string, unknown>)['message']);
  return str(raw);
});
const hasCandidate = computed(() => Object.keys(candidate.value).length > 0);

// ---- 影子试跑 ----
const shadow = computed(() => asRec(props.snapshot.shadowTrial));
const shadowRawStatus = computed(() => String(shadow.value['status'] ?? '').toUpperCase());
const shadowPassed = computed(
  () =>
    shadow.value['passed'] === true ||
    shadowRawStatus.value === 'PASSED' ||
    shadowRawStatus.value === 'SHADOW_PASSED',
);
const shadowFailed = computed(
  () =>
    shadow.value['passed'] === false ||
    shadowRawStatus.value === 'FAILED' ||
    shadowRawStatus.value === 'SHADOW_FAILED',
);
const shadowMessage = computed(() => str(shadow.value['message']));
const hasShadow = computed(() => Object.keys(shadow.value).length > 0);

// 第一层：抽取数据变化
const extractionRows = computed(() => {
  const d = asRec(shadow.value['recordSetDiff']);
  const formal = num(shadow.value['formalRows']);
  const shadowRows = num(shadow.value['shadowRows']);
  const build = (
    label: string,
    formal: number | null,
    shadow: number | null,
    sign: string,
  ): {
    label: string;
    formal: number | null;
    shadow: number | null;
    sign: string;
    delta: number | null;
  } => {
    // 变化列 = 新（候选）相对老（正式）的差值；仅存在派生计数（如候选抽取减少/新增）时，按语义符号返回
    let delta: number | null = null;
    if (formal != null && shadow != null) delta = shadow - formal;
    else if (shadow != null) delta = sign === '-' ? -shadow : shadow;
    return { label, formal, shadow, sign, delta };
  };
  return [
    build('中间表总记录数', formal, shadowRows, ''),
    build('去重业务编号数', num(d['originalCount']), num(d['candidateCount']), ''),
    build('候选抽取减少', null, num(d['removedCount']), '-'),
    build('候选抽取新增', null, num(d['addedCount']), '+'),
  ].filter((r) => r.formal != null || r.shadow != null);
});
const hasExtraction = computed(() => extractionRows.value.length > 0);

// 第二层：最终指标结果变化
function resultMetrics(v: unknown): {
  numerator: number | null;
  denominator: number | null;
  result: number | null;
} {
  const r = asRec(v);
  return {
    numerator: num(r['numerator']),
    denominator: num(r['denominator']),
    result: num(r['resultValue'] ?? r['result']),
  };
}
const originalResult = computed(() =>
  resultMetrics(shadow.value['originalResult'] ?? candidate.value['baselineResult']),
);
const candidateResult = computed(() => resultMetrics(shadow.value['candidateResult']));
const resultRows = computed(() =>
  [
    { label: '分子', o: originalResult.value.numerator, c: candidateResult.value.numerator },
    { label: '分母', o: originalResult.value.denominator, c: candidateResult.value.denominator },
    { label: '结果值', o: originalResult.value.result, c: candidateResult.value.result },
  ].map((r) => ({ ...r, delta: r.o != null && r.c != null ? r.c - r.o : null })),
);
const hasResultCompare = computed(() => resultRows.value.some((r) => r.o != null || r.c != null));

// 第三层：案例编号验收
const caseValidationRows = computed<
  Array<{
    id: string | null;
    baseline: number | null;
    candidate: number | null;
    delta: number | null;
    expected: string | null;
    passed: boolean | null;
  }>
>(() => {
  const cv = asRec(shadow.value['caseValidation']);
  if (Object.keys(cv).length === 0) return [];
  if (Array.isArray(cv['rows'])) {
    return (cv['rows'] as unknown[]).map((item) => {
      const r = asRec(item);
      const baseline = num(r['baseline'] ?? r['before'] ?? r['baselineCount']);
      const candidate = num(r['candidate'] ?? r['after'] ?? r['candidateCount']);
      return {
        id: str(r['id'] ?? r['caseId'] ?? r['businessNo']),
        baseline,
        candidate,
        delta: baseline != null && candidate != null ? candidate - baseline : null,
        expected: str(r['expectedAction'] ?? r['expected']),
        passed: r['passed'] === undefined ? null : r['passed'] === true || r['passed'] === 'true',
      };
    });
  }
  const base = Array.isArray(cv['baselineCounts']) ? (cv['baselineCounts'] as unknown[]) : [];
  const cand = Array.isArray(cv['candidateCounts']) ? (cv['candidateCounts'] as unknown[]) : [];
  const n = Math.max(base.length, cand.length);
  const rows: Array<{
    id: string | null;
    baseline: number | null;
    candidate: number | null;
    delta: number | null;
    expected: string | null;
    passed: boolean | null;
  }> = [];
  for (let i = 0; i < n; i++) {
    const b = asRec(base[i]);
    const c = asRec(cand[i]);
    const baseline = num(b['count'] ?? b);
    const candidate = num(c['count'] ?? c);
    rows.push({
      id: str(b['id'] ?? c['id']),
      baseline,
      candidate,
      delta: baseline != null && candidate != null ? candidate - baseline : null,
      expected: str(cv['expectedAction']),
      passed: null,
    });
  }
  return rows;
});
const hasCaseValidation = computed(() => caseValidationRows.value.length > 0);

// ---- 医院草稿（C2：保存后回显） ----
const draft = computed(() => asRec(props.snapshot.draftResult));
const hasDraft = computed(() => Object.keys(draft.value).length > 0);
const draftId = computed(() => str(draft.value['draftId']));
const draftHospital = computed(() => str(draft.value['hospitalId']));
const draftLayer = computed(() => str(draft.value['changeLayer']));
const draftRevalidated = computed(() => draft.value['revalidationPassed'] === true);

// ---- 原因结论 ----
const cause = computed(() => asRec(props.snapshot.causeConclusion));
const hasCause = computed(() => Object.keys(cause.value).length > 0);

function pretty(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
</script>

<template>
  <div class="result-panel">
    <!-- 候选修改方案 -->
    <section v-if="hasCandidate" class="result-block">
      <header class="result-block__head">
        <v-icon icon="mdi-file-edit-outline" size="18" color="warning" />
        <span class="text-label-large font-weight-medium">候选修改方案</span>
        <v-chip v-if="candidateType" size="x-small" label variant="tonal" class="ml-2">{{
          candidateType
        }}</v-chip>
        <v-chip
          v-if="candidateLayer"
          size="x-small"
          label
          variant="tonal"
          color="info"
          class="ml-1"
          >{{ candidateLayer }}</v-chip
        >
      </header>

      <v-expansion-panels v-if="candidateSqlText" variant="accordion" class="mb-2">
        <v-expansion-panel>
          <v-expansion-panel-title class="text-body-small">SQL 明细</v-expansion-panel-title>
          <v-expansion-panel-text>
            <pre class="sql-block">{{ candidateSqlText }}</pre>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>

      <v-list density="compact" nav class="pa-0">
        <v-list-item v-if="candidateDiff">
          <template #prepend><v-icon icon="mdi-text-box-outline" size="16" /></template>
          <v-list-item-title class="text-body-medium">{{ candidateDiff }}</v-list-item-title>
        </v-list-item>
        <v-list-item v-if="candidateValidation">
          <template #prepend
            ><v-icon icon="mdi-checkbox-marked-circle-outline" size="16"
          /></template>
          <v-list-item-title class="text-body-medium">{{ candidateValidation }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </section>

    <!-- 影子试跑 -->
    <section v-if="hasShadow" class="result-block">
      <header class="result-block__head">
        <v-icon icon="mdi-test-tube-off" size="18" :color="shadowFailed ? 'error' : 'success'" />
        <span class="text-label-large font-weight-medium">影子试跑</span>
        <v-chip
          :color="shadowFailed ? 'error' : shadowPassed ? 'success' : 'grey'"
          size="x-small"
          label
          variant="flat"
          class="ml-2"
          >{{ shadowFailed ? '未通过' : shadowPassed ? '通过' : '未知' }}</v-chip
        >
      </header>

      <p v-if="shadowMessage" class="text-body-medium text-medium-emphasis mb-2">
        {{ shadowMessage }}
      </p>

      <!-- 第一层：抽取数据变化 -->
      <template v-if="hasExtraction">
        <div class="result-subtitle">抽取数据变化</div>
        <v-table density="compact" class="compare-table mb-3">
          <thead>
            <tr>
              <th>变化项</th>
              <th class="text-right">正式（原）</th>
              <th class="text-right">候选（新）</th>
              <th class="text-right">变化</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in extractionRows" :key="row.label">
              <td>{{ row.label }}</td>
              <td class="text-right">{{ fmt(row.formal) }}</td>
              <td class="text-right">{{ fmt(row.shadow) }}</td>
              <td class="text-right font-weight-medium" :class="deltaClass(row.delta)">
                {{ deltaText(row.delta) }}
              </td>
            </tr>
          </tbody>
        </v-table>
      </template>

      <!-- 第二层：最终指标结果变化 -->
      <template v-if="hasResultCompare">
        <div class="result-subtitle">最终指标结果变化</div>
        <v-table density="compact" class="compare-table mb-3">
          <thead>
            <tr>
              <th>指标</th>
              <th class="text-right">正式（原）</th>
              <th class="text-right">候选（新）</th>
              <th class="text-right">变化</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in resultRows" :key="row.label">
              <td>{{ row.label }}</td>
              <td class="text-right">{{ fmt(row.o) }}</td>
              <td class="text-right">{{ fmt(row.c) }}</td>
              <td class="text-right font-weight-medium" :class="deltaClass(row.delta)">
                {{ deltaText(row.delta) }}
              </td>
            </tr>
          </tbody>
        </v-table>
      </template>

      <!-- 第三层：案例编号验收 -->
      <template v-if="hasCaseValidation">
        <div class="result-subtitle">案例编号验收</div>
        <v-table density="compact" class="compare-table mb-3">
          <thead>
            <tr>
              <th>案例编号</th>
              <th class="text-right">正式记录数</th>
              <th class="text-right">候选记录数</th>
              <th class="text-right">变化</th>
              <th>预期</th>
              <th class="text-center">验收</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in caseValidationRows" :key="i">
              <td>{{ row.id ?? '—' }}</td>
              <td class="text-right">{{ fmt(row.baseline) }}</td>
              <td class="text-right">{{ fmt(row.candidate) }}</td>
              <td class="text-right font-weight-medium" :class="deltaClass(row.delta)">
                {{ deltaText(row.delta) }}
              </td>
              <td>{{ row.expected ?? '—' }}</td>
              <td class="text-center">
                <v-chip
                  v-if="row.passed !== null"
                  :color="row.passed ? 'success' : 'error'"
                  size="x-small"
                  label
                  variant="flat"
                  >{{ row.passed ? '符合预期' : '未符合' }}</v-chip
                >
                <span v-else class="text-medium-emphasis">—</span>
              </td>
            </tr>
          </tbody>
        </v-table>
      </template>

      <!-- 技术对账原文（单层折叠，仅供实施排查） -->
      <v-expansion-panels variant="accordion" class="tech-panel">
        <v-expansion-panel>
          <v-expansion-panel-title class="text-body-small"
            >技术对账明细（实施排查用）</v-expansion-panel-title
          >
          <v-expansion-panel-text>
            <pre class="tech-pre">{{ pretty(shadow) }}</pre>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </section>

    <!-- 医院草稿（C2：保存后回显） -->
    <section v-if="hasDraft" class="result-block">
      <header class="result-block__head">
        <v-icon icon="mdi-content-save-outline" size="18" color="success" />
        <span class="text-label-large font-weight-medium">医院草稿</span>
        <v-chip
          :color="draftRevalidated ? 'success' : 'success'"
          size="x-small"
          label
          variant="flat"
          class="ml-2"
          >已保存</v-chip
        >
        <v-chip
          v-if="draftRevalidated"
          size="x-small"
          label
          variant="tonal"
          color="info"
          class="ml-1"
          >已复核</v-chip
        >
      </header>
      <v-list density="compact" nav class="pa-0">
        <v-list-item v-if="draftId"
          ><template #prepend><v-icon icon="mdi-identifier" size="16" /></template
          ><v-list-item-title class="text-body-medium"
            >草稿 ID：{{ draftId }}</v-list-item-title
          ></v-list-item
        >
        <v-list-item v-if="draftHospital"
          ><template #prepend><v-icon icon="mdi-hospital-building" size="16" /></template
          ><v-list-item-title class="text-body-medium"
            >医院：{{ draftHospital }}</v-list-item-title
          ></v-list-item
        >
        <v-list-item v-if="draftLayer"
          ><template #prepend><v-icon icon="mdi-layers-outline" size="16" /></template
          ><v-list-item-title class="text-body-medium"
            >作用层：{{ draftLayer }}</v-list-item-title
          ></v-list-item
        >
      </v-list>
    </section>

    <!-- 原因结论 -->
    <section v-if="hasCause" class="result-block">
      <header class="result-block__head">
        <v-icon icon="mdi-magnify-scan" size="18" color="warning" />
        <span class="text-label-large font-weight-medium">原因结论</span>
      </header>
      <v-list density="compact" nav class="pa-0">
        <v-list-item v-for="(val, key) in cause" :key="key">
          <v-list-item-title class="text-body-medium">
            <span class="text-medium-emphasis">{{ key }}：</span>{{ str(val) ?? pretty(val) }}
          </v-list-item-title>
        </v-list-item>
      </v-list>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.result-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-block {
  border: 1px solid rgb(var(--v-theme-outline, 200));
  border-radius: 10px;
  padding: 10px 12px;
  background: rgb(var(--v-theme-surface));
}

.result-block__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.result-subtitle {
  font-size: 12px;
  font-weight: 600;
  color: rgb(var(--v-theme-primary));
  margin-bottom: 4px;
}

.sql-block {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  font-family: 'Fira Code', monospace;
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 6px;
  padding: 10px;
  margin: 0;
}

.tech-pre {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 6px;
  padding: 10px;
  margin: 0;
}

.compare-table {
  background: transparent;
}
</style>
