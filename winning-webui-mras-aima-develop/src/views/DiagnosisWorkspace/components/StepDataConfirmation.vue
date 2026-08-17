<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { GATE } from '@/constants/diagnosis';
import { useDiagnosisStore } from '@/stores/diagnosis';
import { DETAIL_GROUP, type DetailGroup } from '@/types/chat';
import AiScreeningPanel from '@/views/DiagnosisWorkspace/components/AiScreeningPanel.vue';
import DiagnosisAssistantPanel from '@/views/DiagnosisWorkspace/components/DiagnosisAssistantPanel.vue';
import DiagnosisDetailDialog from '@/views/DiagnosisWorkspace/components/DiagnosisDetailDialog.vue';
import GateChecksPanel from '@/views/DiagnosisWorkspace/components/GateChecksPanel.vue';
import type { UploadSqlEntry } from '@/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue';
import type { AiSqlRepairEntry } from '@/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue';
import { useAttainmentDisplay } from '@/views/DiagnosisWorkspace/composables/useAttainmentDisplay';
import { useDataConfirmation } from '@/views/DiagnosisWorkspace/composables/useDataConfirmation';

const props = defineProps<{
  caseId: string;
  gatesRunning: boolean;
  retry: () => void;
  next: () => void;
  readonly?: boolean;
}>();

const diagnosis = useDiagnosisStore();
const {
  departmentOptions,
  departmentsLoading,
  submitting,
  submitError,
  finishing,
  loadDepartmentOptions,
  clarifyPatient,
  cancelPatientClarification,
  proceedToLineage,
  finishAsCorrect,
} = useDataConfirmation(props.caseId);

const detailOpen = ref(false);
const detailGroup = ref<DetailGroup>();
const finishDialogOpen = ref(false);
const snapshot = computed(() => diagnosis.getCase(props.caseId));
const gateResults = computed(() => snapshot.value?.gateResults ?? []);

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

function gateResult(gate: number) {
  return gateResults.value.find((result) => Number(result.gate) === gate);
}

const gatesPassed = computed(() =>
  [GATE.SCHEMA, GATE.EVENT, GATE.VALUE].every((gate) => gateResult(gate)?.status === 'PASSED'),
);
const executionEvidence = computed(() =>
  record(record(gateResult(GATE.EVENT)?.facts).executionEvidence),
);
const displayEvidence = computed(() => {
  const evidence = executionEvidence.value;
  const originalAttainment = record(record(snapshot.value?.shadowTrial).originalAttainment);
  return {
    ...evidence,
    targetValue: evidence.targetValue ?? originalAttainment.targetValue,
    targetDirection: evidence.targetDirection || originalAttainment.targetDirection,
    attainmentLabel: evidence.attainmentLabel || originalAttainment.attainmentLabel,
  };
});
const {
  resultValue,
  numeratorCount,
  denominatorCount,
  attainmentLabel,
  attainmentClass,
  targetText,
} = useAttainmentDisplay(displayEvidence);
const detailKind = computed(() => String(executionEvidence.value.detailKind ?? 'COUNT_RATIO'));
const detailCards = computed(() => {
  switch (detailKind.value) {
    case 'SUM_CONTRIBUTION':
      return [{ group: DETAIL_GROUP.CONTRIBUTIONS, label: '贡献明细', value: resultValue.value }];
    case 'MEDIAN_SAMPLE':
      return [
        { group: DETAIL_GROUP.SAMPLES, label: '有效样本明细', value: denominatorCount.value },
      ];
    case 'DUAL_SOURCE':
      return [
        { group: DETAIL_GROUP.ACTUAL, label: '实际开展明细', value: numeratorCount.value },
        { group: DETAIL_GROUP.REGISTERED, label: '备案目录明细', value: denominatorCount.value },
      ];
    case 'RATE_COMPARISON':
      return [{ group: DETAIL_GROUP.LEVEL4_HIT, label: '两率计算明细', value: resultValue.value }];
    default:
      return [
        { group: DETAIL_GROUP.NUMERATOR, label: '分子', value: numeratorCount.value },
        { group: DETAIL_GROUP.DENOMINATOR, label: '分母', value: denominatorCount.value },
      ];
  }
});

function openDetail(group: DetailGroup | undefined) {
  detailGroup.value = group;
  detailOpen.value = true;
}

async function handleProceed() {
  const ok = await proceedToLineage({
    overIncludedRows: [],
    overIncludedNote: '',
    underIncludedNote: '',
    selectedDepartments: [],
    departmentOptions: departmentOptions.value,
  });
  if (ok) props.next();
}

function startSqlRepair(value: UploadSqlEntry | AiSqlRepairEntry) {
  sessionStorage.setItem(`diagnosis-sql-repair:${props.caseId}`, JSON.stringify(value));
  props.next();
}

async function confirmFinish() {
  finishDialogOpen.value = false;
  await finishAsCorrect();
}

watch(
  gatesPassed,
  (passed) => {
    if (passed) void loadDepartmentOptions();
  },
  { immediate: true },
);
</script>

<template>
  <div class="confirmation-workspace mx-auto">
    <div class="confirmation-heading mb-3">
      <span class="text-body-large font-weight-medium">查看指标详情与AI初步排查</span>
    </div>

    <GateChecksPanel
      :gate-results="gateResults"
      :gates-running="props.gatesRunning"
      :retry="props.retry"
    />

    <template v-if="!props.gatesRunning && gatesPassed">
      <v-card variant="outlined" class="result-card mb-3">
        <div class="result-summary">
          <v-icon icon="mdi-sigma" color="primary" size="20" />
          <span class="text-body-small text-medium-emphasis">指标结果</span>
          <strong class="result-value">
            {{ resultValue
            }}<span class="attainment-suffix">
              （<span :class="attainmentClass">{{ attainmentLabel }}</span
              >，{{ targetText }}）
            </span>
          </strong>
        </div>
        <button
          v-for="card in detailCards"
          :key="card.group"
          type="button"
          class="result-metric"
          @click="openDetail(card.group)"
        >
          <span class="text-body-small text-medium-emphasis">{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small><v-icon icon="mdi-table-eye" size="14" /> 查看明细</small>
        </button>
      </v-card>

      <AiScreeningPanel
        :case-id="props.caseId"
        :readonly="props.readonly"
        class="compact-screening mb-3"
      />

      <v-alert
        v-if="submitError"
        type="error"
        variant="tonal"
        density="compact"
        class="mb-3"
        :text="submitError"
      />

      <DiagnosisAssistantPanel
        :case-id="props.caseId"
        :department-options="departmentOptions"
        :departments-loading="departmentsLoading"
        :operation-busy="submitting"
        :readonly="props.readonly"
        :clarify-patient="clarifyPatient"
        :cancel-patient-clarification="cancelPatientClarification"
        class="mb-3"
        @start-sql-repair="startSqlRepair"
      />
    </template>

    <DiagnosisDetailDialog
      v-model="detailOpen"
      :case-id="props.caseId"
      :group="detailGroup"
      :selected-keys="new Set()"
      readonly
    />

    <v-dialog v-model="finishDialogOpen" max-width="440" persistent>
      <v-card>
        <v-card-title class="text-headline-small">确认无异议</v-card-title>
        <v-card-text> 确认当前结果正确并结束本次排查？结束后本任务将变为只读。 </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="finishing" @click="finishDialogOpen = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="finishing" @click="confirmFinish">
            确认结束
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <div v-if="!props.gatesRunning && gatesPassed" class="d-flex ga-3 mb-4">
      <v-btn
        variant="tonal"
        size="large"
        class="flex-1"
        rounded="lg"
        prepend-icon="mdi-check-circle-outline"
        :disabled="props.readonly || finishing"
        :loading="finishing"
        @click="finishDialogOpen = true"
      >
        确认无异议
      </v-btn>
      <v-btn
        color="primary"
        variant="flat"
        size="large"
        class="flex-1"
        rounded="lg"
        prepend-icon="mdi-graph-outline"
        :disabled="props.readonly || submitting"
        :loading="submitting"
        @click="handleProceed"
      >
        进入SQL脚本核查
      </v-btn>
    </div>
  </div>
</template>

<style lang="scss" scoped src="./StepDataConfirmation.scss"></style>
