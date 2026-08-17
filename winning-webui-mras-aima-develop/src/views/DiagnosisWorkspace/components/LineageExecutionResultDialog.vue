<script setup lang="ts">
import { computed } from 'vue';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import {
  displayExecutionMetric,
  executionMetric,
  resultRecord,
} from '@/views/DiagnosisWorkspace/execution-result';
import DraftSavePanel from '@/views/DiagnosisWorkspace/components/DraftSavePanel.vue';
import ShadowDifferenceDetails from '@/views/DiagnosisWorkspace/components/ShadowDifferenceDetails.vue';
import { TARGET_DIRECTION_SYMBOL } from '@/constants/diagnosis';

const props = defineProps<{
  open: boolean;
  running: boolean;
  stages: readonly string[];
  currentStage: string;
  resultMessage: string;
  error: string;
  snapshot: DiagnosisCaseSnapshot | null;
  caseId: string | null;
}>();

const emit = defineEmits<{ close: [] }>();

const shadow = computed(() => resultRecord(props.snapshot?.shadowTrial));
const originalResult = computed(() => resultRecord(shadow.value.originalResult));
const candidateResult = computed(() => resultRecord(shadow.value.candidateResult));
const hasCandidateResult = computed(() => Object.keys(candidateResult.value).length > 0);
const currentResult = computed(() =>
  hasCandidateResult.value ? candidateResult.value : originalResult.value,
);
const originalAttainment = computed(() => resultRecord(shadow.value.originalAttainment));
const candidateAttainment = computed(() => resultRecord(shadow.value.candidateAttainment));
const currentAttainment = computed(() =>
  hasCandidateResult.value ? candidateAttainment.value : originalAttainment.value,
);
const trialId = computed(() => String(shadow.value.trialId ?? ''));
const passed = computed(() => shadow.value.passed === true && !props.error);
const hasResult = computed(
  () =>
    Boolean(props.resultMessage || props.error) ||
    shadow.value.originalResult !== undefined ||
    shadow.value.candidateResult !== undefined,
);

function stageState(stage: string): 'active' | 'done' | 'pending' {
  if (!props.running) return props.error ? 'pending' : 'done';
  const activeIndex = props.stages.indexOf(props.currentStage);
  const stageIndex = props.stages.indexOf(stage);
  if (stageIndex < activeIndex) return 'done';
  if (stageIndex === activeIndex) return 'active';
  return 'pending';
}

function metric(row: Record<string, unknown>, kind: 'numerator' | 'denominator' | 'result') {
  return displayExecutionMetric(executionMetric(row, kind));
}

function attainmentLabel(value: Record<string, unknown>) {
  return String(value.attainmentLabel ?? '待判定');
}

function targetText(value: Record<string, unknown>) {
  const direction = String(value.targetDirection ?? '').toLowerCase();
  const symbol = TARGET_DIRECTION_SYMBOL[direction] ?? '';
  const target = value.targetValue;
  return target == null || target === '' || !symbol ? '目标值—' : `目标值 ${symbol} ${target}`;
}

function attainmentClass(value: Record<string, unknown>) {
  const label = String(value.attainmentLabel ?? '待判定');
  return label === '达标'
    ? 'text-success'
    : label === '未达标'
      ? 'text-error'
      : 'text-medium-emphasis';
}
</script>

<template>
  <v-dialog :model-value="open" max-width="920" scrollable persistent>
    <v-card rounded="lg">
      <v-card-title class="d-flex align-center ga-2 py-3">
        <v-icon icon="mdi-play-circle-outline" color="primary" />
        <span class="text-title-medium">当前指标结果</span>
        <v-spacer />
        <v-btn
          icon="mdi-close"
          size="small"
          variant="text"
          :disabled="running"
          aria-label="关闭执行结果"
          @click="emit('close')"
        />
      </v-card-title>
      <v-divider />

      <v-card-text>
        <div class="execution-stages mb-4">
          <div v-for="stage in stages" :key="stage" class="execution-stage">
            <v-progress-circular
              v-if="stageState(stage) === 'active'"
              indeterminate
              size="18"
              width="2"
              color="primary"
            />
            <v-icon
              v-else
              :icon="stageState(stage) === 'done' ? 'mdi-check-circle' : 'mdi-circle-outline'"
              :color="stageState(stage) === 'done' ? 'success' : 'on-surface-variant'"
              size="18"
            />
            <span
              class="text-body-small"
              :class="stageState(stage) === 'pending' ? 'text-medium-emphasis' : ''"
            >
              {{ stage }}
            </span>
          </div>
        </div>

        <v-alert
          v-if="running"
          type="info"
          variant="tonal"
          density="compact"
          class="mb-4"
          :text="currentStage || '正在准备执行…'"
        />
        <v-alert
          v-else-if="error"
          type="error"
          variant="tonal"
          density="compact"
          class="mb-4"
          :text="error"
        />
        <v-alert
          v-else-if="hasResult"
          :type="passed ? 'success' : 'info'"
          variant="tonal"
          density="compact"
          class="mb-4"
          :text="resultMessage || String(shadow.message || '整体执行完成。')"
        />

        <div v-if="hasResult && !error" class="result-grid">
          <div class="result-card pa-3 rounded-lg">
            <span class="text-body-small text-medium-emphasis">指标结果</span>
            <strong class="text-title-large">
              {{ metric(currentResult, 'result') }}
              <small class="attainment-result">
                （<span :class="attainmentClass(currentAttainment)">{{
                  attainmentLabel(currentAttainment)
                }}</span
                >，{{ targetText(currentAttainment) }}）
              </small>
            </strong>
            <span v-if="hasCandidateResult" class="text-body-small text-medium-emphasis">
              原 SQL：{{ metric(originalResult, 'result') }} （<span
                :class="attainmentClass(originalAttainment)"
                >{{ attainmentLabel(originalAttainment) }}</span
              >，{{ targetText(originalAttainment) }}）
            </span>
          </div>
          <div class="result-card pa-3 rounded-lg">
            <span class="text-body-small text-medium-emphasis">分子</span>
            <strong class="text-title-large">{{ metric(currentResult, 'numerator') }}</strong>
            <span v-if="hasCandidateResult" class="text-body-small text-medium-emphasis">
              原 SQL：{{ metric(originalResult, 'numerator') }}
            </span>
          </div>
          <div class="result-card pa-3 rounded-lg">
            <span class="text-body-small text-medium-emphasis">分母</span>
            <strong class="text-title-large">{{ metric(currentResult, 'denominator') }}</strong>
            <span v-if="hasCandidateResult" class="text-body-small text-medium-emphasis">
              原 SQL：{{ metric(originalResult, 'denominator') }}
            </span>
          </div>
        </div>

        <ShadowDifferenceDetails
          v-if="hasCandidateResult && caseId && trialId"
          :case-id="caseId"
          :trial-id="trialId"
        />

        <DraftSavePanel v-if="hasResult && !running" :snapshot="snapshot" :case-id="caseId" />
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
.execution-stages {
  display: grid;
  gap: 8px;
}

.execution-stage {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.result-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: rgba(var(--v-theme-on-surface), 0.035);
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}

.attainment-result {
  font-size: 12px;
  font-weight: 400;
}

@media (max-width: 700px) {
  .result-grid {
    grid-template-columns: 1fr;
  }
}
</style>
