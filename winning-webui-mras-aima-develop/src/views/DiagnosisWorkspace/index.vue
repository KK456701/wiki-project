<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useDiagnosisWorkspace } from './composables/useDiagnosisWorkspace';
import DiagnosisStepper from './components/DiagnosisStepper.vue';
import DiagnosisSummaryHeader from './components/DiagnosisSummaryHeader.vue';
import StepSelection from './components/StepSelection.vue';
import StepDataConfirmation from './components/StepDataConfirmation.vue';
import StepLineage from './components/StepLineage.vue';

const {
  step,
  caseId,
  snapshot,
  prefill,
  candidateRuleIds,
  rules,
  rulesLoading,
  loadRules,
  getProfiles,
  loadError,
  creating,
  gatesRunning,
  goStep,
  loadCase,
  ensureGatesRan,
  createCase,
  close,
  clearError,
  runGates,
  caseCompleted,
} = useDiagnosisWorkspace();

const selectionSummary = ref<{ ruleName: string; profileName: string; dateText: string } | null>(
  null,
);

// 摘要：优先用快照（建案后），否则用选择态
const summary = computed(() => {
  const s = snapshot.value;
  if (s) {
    const cal = s.caliberSnapshot;
    const start = cal.timeRange?.start || (s.caseInput?.statStart as string | undefined) || '';
    const end = cal.timeRange?.end || (s.caseInput?.statEnd as string | undefined) || '';
    const dateText =
      start && end ? `${String(start).slice(0, 10)} ~ ${String(end).slice(0, 10)}` : '';
    return { ruleName: cal.ruleName || '', profileName: cal.profileName || '', dateText };
  }
  return selectionSummary.value;
});

// 链路核查所需参数（来自快照）
const lineageQuery = computed(() => {
  const s = snapshot.value;
  const cal = s?.caliberSnapshot;
  const start = (cal?.timeRange?.start ||
    (s?.caseInput?.statStart as string | undefined) ||
    '') as string;
  const end = (cal?.timeRange?.end ||
    (s?.caseInput?.statEnd as string | undefined) ||
    '') as string;
  return {
    ruleId: cal?.ruleId || '',
    profileId: cal?.profileId || '',
    statStart: start,
    statEnd: end,
  };
});

function handleSubmit(payload: {
  ruleId: string;
  profileId: string;
  statStart: string;
  statEnd: string;
}) {
  void createCase(payload).catch(() => undefined);
}

onMounted(async () => {
  // 进入页面即加载指标列表（整页仅一次，缓存于 composable）
  void loadRules();
  if (caseId.value && !snapshot.value) await loadCase(caseId.value);
  if (step.value === 'data') ensureGatesRan();
});

watch(step, (s) => {
  if (s === 'data' && caseId.value) ensureGatesRan();
});

watch(snapshot, () => {
  if (step.value === 'data' && caseId.value) ensureGatesRan();
});
</script>

<template>
  <div class="dw-page d-flex flex-column">
    <header class="dw-appbar d-flex align-center pa-2">
      <!-- 左侧：返回 + 标题 + 当前指标摘要 -->
      <div class="flex-1-1-auto min-width-0">
        <div class="d-flex align-center">
          <v-btn
            icon="mdi-arrow-left"
            variant="text"
            density="comfortable"
            aria-label="返回"
            @click="close"
          />
          <div class="text-body-large font-weight-medium ml-1">指标异常排查</div>
          <DiagnosisSummaryHeader
            class="ml-4 flex-1-1-auto min-width-0"
            :rule-name="summary?.ruleName"
            :profile-name="summary?.profileName"
            :date-text="summary?.dateText"
          />
        </div>
      </div>
      <v-spacer />
      <!-- 右侧：步骤导航 -->
      <div class="flex-0-1-auto min-width-0 ml-2">
        <DiagnosisStepper :current-step="step" :has-case="!!caseId" @navigate="goStep" />
      </div>
    </header>
    <v-divider />

    <v-alert
      v-if="caseCompleted"
      type="warning"
      variant="tonal"
      class="dw-readonly-alert ma-0 flex-0-0 py-1"
      density="compact"
      text="本任务已经结束，当前内容只读。如需继续修改，请新建异常排查任务。"
      border
    />

    <v-divider />

    <div class="dw-content flex-grow-1 overflow-y-auto">
      <v-alert
        v-if="loadError"
        type="error"
        variant="tonal"
        class="ma-4"
        :text="loadError"
        closable
        @click:close="clearError"
      />

      <StepSelection
        v-if="step === 'selection'"
        :creating="creating"
        :rules="rules"
        :rules-loading="rulesLoading"
        :get-profiles="getProfiles"
        :prefill="prefill"
        :candidate-rule-ids="candidateRuleIds"
        :readonly="caseCompleted"
        class="pa-4"
        @submit="handleSubmit"
        @summary="selectionSummary = $event"
      />

      <template v-else-if="caseId">
        <StepDataConfirmation
          v-if="step === 'data'"
          :case-id="caseId"
          :gates-running="gatesRunning"
          :retry="() => caseId && runGates(caseId)"
          :next="() => goStep('lineage')"
          :readonly="caseCompleted"
          class="pa-4"
        />

        <StepLineage
          v-else-if="step === 'lineage'"
          :rule-id="lineageQuery.ruleId"
          :profile-id="lineageQuery.profileId"
          :stat-start="lineageQuery.statStart"
          :stat-end="lineageQuery.statEnd"
          :case-id="caseId"
        />
      </template>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dw-page {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgb(var(--v-theme-background));
}

.dw-appbar {
  flex-shrink: 0;
}

.dw-content {
  min-height: 0;
}
</style>
