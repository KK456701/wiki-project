<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { getDiagnosisDataScreening } from '@/services/diagnosis';
import { DIAGNOSIS_ACTION } from '@/constants/diagnosis';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type {
  DataScreeningFinding,
  DiagnosisCaseSnapshot,
  DiagnosisDataScreening,
} from '@/types/diagnosis';
import DiagnosisSqlExecuteButton from './DiagnosisSqlExecuteButton.vue';
import {
  clearScreeningCache,
  getScreeningCache,
  screeningCacheKey,
  setScreeningCache,
} from '@/views/DiagnosisWorkspace/data-screening-cache';

const props = defineProps<{
  caseId: string;
  /** 任务已结束时只读 */
  readonly?: boolean;
}>();

const diagnosis = useDiagnosisStore();

/* ---- 初筛数据 ---- */
const loading = ref(false);
const error = ref('');
const screening = ref<DiagnosisDataScreening | null>(null);
const expanded = ref(false);

/* ---- 修复流程 ---- */
const repairDialog = ref(false);
const repairRuleId = ref('');
const repairPreviewLoading = ref(false);
const repairRunLoading = ref(false);
const repairError = ref('');
const repairSqlExpanded = ref(false);
const repairSnapshot = ref<DiagnosisCaseSnapshot | null>(null);
const showRepairResult = ref(false);

/* ---- helpers ---- */
function record(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

function displayMetric(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(6);
  return String(v);
}

/* ---- SQL diff（简化 LCS） ---- */
type DiffLine = { kind: 'same' | 'added' | 'removed'; text: string };

function buildSqlDiff(original: string, candidate: string): DiffLine[] {
  const o = original.replace(/\r\n/g, '\n').split('\n');
  const c = candidate.replace(/\r\n/g, '\n').split('\n');
  if (!original) return c.map((t) => ({ kind: 'added' as const, text: t }));
  if (!candidate) return o.map((t) => ({ kind: 'removed' as const, text: t }));
  if (o.length * c.length > 500_000) {
    const set = new Set(o);
    return c.map((t) => ({ kind: set.has(t) ? ('same' as const) : ('added' as const), text: t }));
  }
  const m = Array.from({ length: o.length + 1 }, () => new Uint32Array(c.length + 1));
  for (let i = o.length - 1; i >= 0; i--)
    for (let j = c.length - 1; j >= 0; j--)
      m[i][j] = o[i] === c[j] ? m[i + 1][j + 1] + 1 : Math.max(m[i + 1][j], m[i][j + 1]);
  const r: DiffLine[] = [];
  let i = 0,
    j = 0;
  while (i < o.length || j < c.length) {
    if (i < o.length && j < c.length && o[i] === c[j]) {
      r.push({ kind: 'same', text: o[i] });
      i++;
      j++;
    } else if (j < c.length && (i >= o.length || m[i][j + 1] >= m[i + 1][j])) {
      r.push({ kind: 'added', text: c[j] });
      j++;
    } else {
      r.push({ kind: 'removed', text: o[i] });
      i++;
    }
  }
  return r;
}

/* ---- 初筛加载 ---- */
function cacheKey() {
  const snapshot = diagnosis.getCase(props.caseId);
  const gate = snapshot?.gateResults.find((item) => Number(item.gate) === 2);
  const facts = record(gate?.facts);
  const execution = record(facts.executionEvidence);
  return screeningCacheKey(
    props.caseId,
    execution.overviewSqlHash,
    snapshot?.caliberSnapshot.timeRange.start,
    snapshot?.caliberSnapshot.timeRange.end,
  );
}

function load(force = false) {
  if (force) clearScreeningCache(props.caseId);
  const key = cacheKey();
  const cached = getScreeningCache(key);
  if (cached) {
    screening.value = cached;
    error.value = '';
    return;
  }
  loading.value = true;
  error.value = '';
  getDiagnosisDataScreening(props.caseId)
    .then((d) => {
      screening.value = d;
      setScreeningCache(key, d);
    })
    .catch((e: unknown) => {
      error.value = e instanceof Error ? e.message : '加载初筛结果失败';
      screening.value = null;
    })
    .finally(() => {
      loading.value = false;
    });
}

onMounted(() => load());

/* ---- 表格取值 ---- */
function findingPatientName(f: DataScreeningFinding): string {
  const row = f.row || {};
  return String(row.PERSON_NAME || row.FULL_NAME || row.personName || '未登记姓名');
}

function findingRecordId(f: DataScreeningFinding): string {
  const row = f.row || {};
  return String(row.ENCOUNTER_ID || row.encounterId || row.BIZ_ID || row.bizId || f.rowKey);
}

function findingDepartment(f: DataScreeningFinding): string {
  const row = f.row || {};
  return String(
    row.CURRENT_DEPT_NAME ||
      row.currentDeptName ||
      row.CURRENT_WARD_NAME ||
      row.currentWardName ||
      '未登记科室',
  );
}

function findingReason(f: DataScreeningFinding): string {
  return f.reason;
}

function findingRuleLabel(f: DataScreeningFinding): string {
  const map: Record<string, string> = {
    PUBLIC_001: '测试患者',
    PUBLIC_002: '测试/透析门诊科室',
    PUBLIC_003: '重复业务编号',
  };
  return map[f.ruleCode] || f.ruleCode;
}

/* ---- 修复规则 ---- */
function publicRuleRepairable(ruleId: string): boolean {
  return ruleId === 'PUBLIC_001' || ruleId === 'PUBLIC_002';
}

function publicRuleLabel(ruleId: string): string {
  if (ruleId === 'PUBLIC_001') return '排除测试患者';
  if (ruleId === 'PUBLIC_002') return '排除测试及血液透析门诊科室';
  return '检查重复明细与事件启用情况';
}

function publicRuleRepairDescription(ruleId: string): string {
  if (ruleId === 'PUBLIC_001')
    return '在当前指标源表抽取 SQL 的患者姓名字段上追加"测试 / test"排除条件。';
  if (ruleId === 'PUBLIC_002')
    return '在当前指标源表抽取 SQL 的科室名称字段上追加"测试 / test / 血液透析门诊"排除条件。';
  return '该规则只提示人工检查相关事件是否重复启用，不自动修改 SQL。';
}

/* ---- 修复弹窗 ---- */
async function openRepairDialog(f: DataScreeningFinding) {
  const ruleId = String(f.ruleCode || '');
  repairRuleId.value = ruleId;
  repairError.value = '';
  repairSqlExpanded.value = false;
  showRepairResult.value = false;
  repairSnapshot.value = null;
  repairDialog.value = true;

  if (!publicRuleRepairable(ruleId)) return;

  repairPreviewLoading.value = true;
  try {
    const snap = await diagnosis.submitAction(
      props.caseId,
      DIAGNOSIS_ACTION.PREVIEW_PUBLIC_RULE_FIX,
      { publicRuleIds: [ruleId] },
    );
    repairSnapshot.value = snap;
    if (!Object.keys(snap.candidateSql || {}).length) {
      repairError.value = '未能生成公共规则候选 SQL。';
    }
  } catch (e: unknown) {
    repairError.value = e instanceof Error ? e.message : '生成修复方案失败。';
  } finally {
    repairPreviewLoading.value = false;
  }
}

function closeRepairDialog() {
  repairDialog.value = false;
  repairRuleId.value = '';
  repairError.value = '';
  repairSqlExpanded.value = false;
  showRepairResult.value = false;
}

async function runRuleRepair() {
  repairError.value = '';
  repairRunLoading.value = true;
  try {
    const snap = await diagnosis.submitAction(
      props.caseId,
      DIAGNOSIS_ACTION.RUN_PUBLIC_RULE_FIX,
      {},
    );
    repairSnapshot.value = snap;
    showRepairResult.value = true;
    const trial = record(snap.shadowTrial);
    if (String(trial.status || '') === 'FAILED' || trial.passed === false) {
      repairError.value = String(trial.message || '影子试跑未通过');
    }
  } catch (e: unknown) {
    repairError.value = e instanceof Error ? e.message : '修复执行失败。';
  } finally {
    repairRunLoading.value = false;
  }
}

async function toggleRepairSql() {
  repairSqlExpanded.value = !repairSqlExpanded.value;
  if (repairSqlExpanded.value) {
    await nextTick();
    document.querySelector('.repair-sql-body')?.scrollIntoView({ block: 'nearest' });
  }
}

/* ---- computed（修复结果展示） ---- */
const candidateSql = computed(() => record(repairSnapshot.value?.candidateSql));
const candidateExecutable = computed(() =>
  String(candidateSql.value.candidateSqlExecutable || candidateSql.value.sql || ''),
);
const candidateOriginal = computed(() =>
  String(candidateSql.value.originalSqlExecutable || candidateSql.value.originalSql || ''),
);
const diffLines = computed(() => buildSqlDiff(candidateOriginal.value, candidateExecutable.value));
const changedLines = computed(() => diffLines.value.filter((l) => l.kind !== 'same'));
const hasCandidate = computed(() => candidateExecutable.value.trim().length > 0);

const shadowTrial = computed(() => record(repairSnapshot.value?.shadowTrial));
const trialPassed = computed(() => Boolean(shadowTrial.value.passed));
const trialHasResult = computed(() => Boolean(shadowTrial.value.publicRuleFix));

function firstResult(value: unknown): Record<string, unknown> {
  return Array.isArray(value) ? record(value[0]) : record(value);
}

function aggregateValue(row: Record<string, unknown>, kind: string): unknown {
  const marker = kind === 'numerator' ? '分子' : kind === 'denominator' ? '分母' : '监测情况';
  const key = Object.keys(row).find((k) => k.includes(marker));
  return key ? row[key] : '—';
}

const originalResult = computed(() => firstResult(shadowTrial.value.originalResult));
const candidateResult = computed(() => firstResult(shadowTrial.value.candidateResult));

const hasFindings = computed(() => (screening.value?.findingCount ?? 0) > 0);
</script>

<template>
  <v-card variant="outlined" :border="hasFindings ? 'warning' : undefined" class="pa-4">
    <!-- 头部 -->
    <div
      class="d-flex align-center ga-2 mb-1 screening-header"
      role="button"
      tabindex="0"
      @click="expanded = !expanded"
      @keydown.enter="expanded = !expanded"
      @keydown.space.prevent="expanded = !expanded"
    >
      <v-icon icon="mdi-shield-search" color="primary" />
      <span class="text-body-large font-weight-medium">AI初步筛查</span>
      <v-spacer />
      <v-chip
        v-if="screening"
        size="x-small"
        :color="hasFindings ? 'warning' : 'success'"
        variant="tonal"
        label
      >
        {{ hasFindings ? `${screening.findingCount} 条疑似问题` : '未发现' }}
      </v-chip>
      <v-icon
        :icon="expanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
        size="20"
        class="text-medium-emphasis"
      />
    </div>
    <!-- 加载 / 错误 -->
    <div v-if="loading" class="d-flex align-center ga-2 text-medium-emphasis py-4">
      <v-progress-circular indeterminate size="20" width="3" color="primary" />
      <span class="text-body-medium">正在初筛明细数据…</span>
    </div>
    <v-alert v-else-if="error" type="error" variant="tonal" density="compact">
      {{ error }}
      <template #append>
        <v-btn size="x-small" variant="text" prepend-icon="mdi-refresh" @click.stop="load(true)"
          >重试</v-btn
        >
      </template>
    </v-alert>

    <!-- 初筛表格 -->
    <v-expand-transition>
      <div v-if="expanded && screening">
        <v-alert
          v-if="hasFindings"
          type="warning"
          variant="tonal"
          density="comfortable"
          class="mb-3"
          :text="`发现 ${screening.findingCount} 条疑似测试或重复数据（已扫描 ${screening.scannedRows} 行）`"
        />
        <v-alert
          v-else
          type="success"
          variant="tonal"
          density="comfortable"
          class="mb-3"
          text="当前明细未命中测试患者、测试/血液透析门诊科室或重复业务编号规则。"
        />

        <v-table v-if="hasFindings && screening.findings.length" density="compact">
          <thead>
            <tr>
              <th>患者姓名</th>
              <th>患者标识</th>
              <th>科室</th>
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in screening.findings" :key="f.findingId">
              <td>{{ findingPatientName(f) }}</td>
              <td>{{ findingRecordId(f) }}</td>
              <td>{{ findingDepartment(f) }}</td>
              <td>
                <v-chip size="x-small" variant="flat" color="warning" label class="mr-1">
                  {{ findingRuleLabel(f) }}
                </v-chip>
                {{ findingReason(f) }}
              </td>
              <td>
                <v-btn
                  size="x-small"
                  variant="text"
                  color="warning"
                  prepend-icon="mdi-wrench-outline"
                  :disabled="props.readonly"
                  @click.stop="openRepairDialog(f)"
                >
                  {{ publicRuleRepairable(f.ruleCode) ? '一键修复' : '人工检查' }}
                </v-btn>
              </td>
            </tr>
          </tbody>
        </v-table>
      </div>
    </v-expand-transition>

    <!-- ====== 修复弹窗 ====== -->
    <v-dialog v-model="repairDialog" :max-width="trialHasResult ? 640 : 520">
      <v-card>
        <v-card-title class="d-flex align-center ga-2">
          <v-icon icon="mdi-wrench-outline" color="warning" />
          {{
            trialHasResult
              ? trialPassed
                ? '候选结果已生成'
                : '候选执行未通过'
              : '公共规则修复方案'
          }}
        </v-card-title>

        <v-card-text>
          <!-- 规则说明 -->
          <p v-if="!trialHasResult" class="text-body-medium mb-3">
            <strong>{{ publicRuleLabel(repairRuleId) }}：</strong>
            {{ publicRuleRepairDescription(repairRuleId) }}
          </p>

          <!-- 不可修复规则 -->
          <v-alert
            v-if="!publicRuleRepairable(repairRuleId)"
            type="info"
            variant="tonal"
            density="compact"
            text="该规则只提示人工检查相关事件是否重复启用，不自动修改 SQL。"
          />

          <!-- 预览 loading -->
          <div
            v-if="repairPreviewLoading"
            class="d-flex align-center ga-2 py-4 text-medium-emphasis"
          >
            <v-progress-circular indeterminate size="18" width="3" color="primary" />
            <span class="text-body-medium">正在解析当前指标抽取 SQL，生成修复方案…</span>
          </div>

          <!-- 候选 SQL 预览 -->
          <template v-if="hasCandidate && !trialHasResult && !repairPreviewLoading">
            <v-alert
              type="info"
              variant="tonal"
              density="compact"
              class="mb-3"
              text="以下是程序根据当前抽取 SQL 生成的候选语句，仅预览，尚未执行。"
            />
            <div class="text-body-small text-medium-emphasis mb-2">
              本次修改 <strong>{{ changedLines.length }} 行</strong>
            </div>
            <div class="d-flex justify-end mb-2">
              <DiagnosisSqlExecuteButton
                :sql="candidateExecutable"
                :role-hint="String(candidateSql.layer ?? '')"
                :snapshot="repairSnapshot"
              />
            </div>
            <div class="repair-changed-lines pa-2 rounded bg-surface-variant mb-2">
              <code
                v-for="(line, idx) in changedLines.slice(0, 10)"
                :key="`ch-${idx}`"
                :class="`diff-line diff-${line.kind}`"
                style="display: block; font-size: 11px; line-height: 1.5"
              >
                <span class="text-body-small mr-1">{{ line.kind === 'added' ? '+' : '−' }}</span
                >{{ line.text }}
              </code>
              <div v-if="changedLines.length > 10" class="text-body-small text-medium-emphasis">
                …还有 {{ changedLines.length - 10 }} 行
              </div>
            </div>
            <v-btn
              size="x-small"
              variant="text"
              :append-icon="repairSqlExpanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
              @click="toggleRepairSql"
            >
              {{ repairSqlExpanded ? '收起' : '查看完整候选 SQL' }}
            </v-btn>
            <div v-show="repairSqlExpanded" class="repair-sql-body mt-2">
              <div class="d-flex ga-2 mb-1 text-body-small">
                <span class="diff-added-example">■ 新增</span>
                <span class="diff-removed-example">■ 删除</span>
              </div>
              <pre class="repair-sql-pre"><code
                v-for="(line, idx) in diffLines"
                :key="`full-${idx}`"
                :class="`diff-line diff-${line.kind}`"
              ><span class="text-body-small mr-2">{{ line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' ' }}</span>{{ line.text }}</code></pre>
            </div>
          </template>

          <!-- 执行结果 -->
          <template v-if="trialHasResult">
            <v-alert
              :type="trialPassed ? 'success' : 'error'"
              variant="tonal"
              density="compact"
              class="mb-3"
              :text="
                String(
                  shadowTrial.message ||
                    (trialPassed
                      ? '候选数据已完成隔离试跑，正式数据保持不变。'
                      : '请根据执行结果调整候选条件。'),
                )
              "
            />
            <div v-if="trialPassed" class="d-flex flex-wrap ga-3 mb-3">
              <div class="repair-metric">
                <div class="text-body-small text-medium-emphasis">候选指标结果</div>
                <div class="text-headline-small font-weight-bold">
                  {{ displayMetric(aggregateValue(candidateResult, 'result')) }}
                </div>
                <div class="text-body-small text-medium-emphasis">
                  正式值 {{ displayMetric(aggregateValue(originalResult, 'result')) }}
                </div>
              </div>
              <div class="repair-metric">
                <div class="text-body-small text-medium-emphasis">候选分子</div>
                <div class="text-headline-small font-weight-bold">
                  {{ displayMetric(aggregateValue(candidateResult, 'numerator')) }}
                </div>
                <div class="text-body-small text-medium-emphasis">
                  正式分子 {{ displayMetric(aggregateValue(originalResult, 'numerator')) }}
                </div>
              </div>
              <div class="repair-metric">
                <div class="text-body-small text-medium-emphasis">候选分母</div>
                <div class="text-headline-small font-weight-bold">
                  {{ displayMetric(aggregateValue(candidateResult, 'denominator')) }}
                </div>
                <div class="text-body-small text-medium-emphasis">
                  正式分母 {{ displayMetric(aggregateValue(originalResult, 'denominator')) }}
                </div>
              </div>
            </div>
          </template>

          <!-- 错误 -->
          <v-alert
            v-if="repairError"
            type="error"
            variant="tonal"
            density="compact"
            :text="repairError"
          />
        </v-card-text>

        <v-divider />
        <v-card-actions class="pa-3">
          <v-spacer />
          <v-btn variant="text" @click="closeRepairDialog">关闭</v-btn>
          <v-btn
            v-if="hasCandidate && !trialHasResult && publicRuleRepairable(repairRuleId)"
            color="warning"
            variant="tonal"
            :loading="repairRunLoading"
            prepend-icon="mdi-play-circle-outline"
            @click="runRuleRepair"
          >
            用该 SQL 整体执行
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>

<style lang="scss" scoped>
.screening-header {
  cursor: pointer;
  user-select: none;
  border-radius: 8px;
  padding: 4px 8px;
  margin: -4px -8px;
  transition: background 0.15s ease;

  &:hover {
    background: rgba(var(--v-theme-on-surface), 0.04);
  }
}

.diff-added {
  color: rgb(var(--v-theme-success));
}

.diff-removed {
  color: rgb(var(--v-theme-error));
}

.diff-same {
  color: rgba(var(--v-theme-on-surface), 0.6);
}

.diff-added-example {
  color: rgb(var(--v-theme-success));
  font-size: 11px;
}

.diff-removed-example {
  color: rgb(var(--v-theme-error));
  font-size: 11px;
}

.repair-sql-pre {
  max-height: 320px;
  overflow: auto;
  background: rgba(var(--v-theme-on-surface), 0.04);
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre;
  tab-size: 2;

  code {
    display: block;
  }
}

.repair-metric {
  flex: 1;
  min-width: 140px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  border-radius: 8px;
  padding: 10px 14px;
}

.repair-changed-lines {
  max-height: 240px;
  overflow-y: auto;
}
</style>
