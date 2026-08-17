<script setup lang="ts">
import { computed } from 'vue';
import {
  DIAGNOSIS_STEP_ORDER,
  DIAGNOSIS_STEP_LABELS,
  DIAGNOSIS_STEP_ICONS,
  DIAGNOSIS_STEP,
} from './diagnosis-constants';

const props = defineProps<{
  currentStep: string;
}>();

/** 当前步骤在主线中的索引；WAITING_EXTERNAL_FIX 视为停留在 CHANGE_PROPOSAL */
const activeIndex = computed(() => {
  const idx = DIAGNOSIS_STEP_ORDER.indexOf(
    props.currentStep as (typeof DIAGNOSIS_STEP_ORDER)[number],
  );
  if (idx >= 0) return idx;
  if (props.currentStep === DIAGNOSIS_STEP.WAITING_EXTERNAL_FIX) {
    return DIAGNOSIS_STEP_ORDER.indexOf(DIAGNOSIS_STEP.CHANGE_PROPOSAL);
  }
  return -1;
});

const isExternalFix = computed(() => props.currentStep === DIAGNOSIS_STEP.WAITING_EXTERNAL_FIX);

interface StepState {
  key: string;
  label: string;
  icon: string;
  state: 'done' | 'active' | 'pending';
}

const steps = computed<StepState[]>(() =>
  DIAGNOSIS_STEP_ORDER.map((key, idx) => {
    const state: StepState['state'] =
      activeIndex.value < 0
        ? 'pending'
        : idx < activeIndex.value
          ? 'done'
          : idx === activeIndex.value
            ? 'active'
            : 'pending';
    return {
      key,
      label: DIAGNOSIS_STEP_LABELS[key] ?? key,
      icon: DIAGNOSIS_STEP_ICONS[key] ?? 'mdi-circle-small',
      state,
    };
  }),
);
</script>

<template>
  <div class="step-timeline">
    <div class="d-flex align-center ga-1 flex-wrap">
      <template v-for="(step, idx) in steps" :key="step.key">
        <v-chip
          :color="
            step.state === 'done' ? 'success' : step.state === 'active' ? 'primary' : undefined
          "
          :variant="step.state === 'pending' ? 'outlined' : 'flat'"
          size="small"
          label
        >
          <v-icon :icon="step.state === 'done' ? 'mdi-check-circle' : step.icon" start size="16" />
          {{ step.label }}
        </v-chip>
        <v-icon
          v-if="idx < steps.length - 1"
          icon="mdi-chevron-right"
          size="16"
          color="on-surface-variant"
        />
      </template>
    </div>
    <v-chip
      v-if="activeIndex < 0"
      class="mt-2"
      color="error"
      variant="tonal"
      size="small"
      prepend-icon="mdi-alert-circle-outline"
    >
      未知步骤：{{ props.currentStep }}
    </v-chip>
    <v-chip
      v-if="isExternalFix"
      class="mt-2"
      color="warning"
      variant="tonal"
      size="small"
      prepend-icon="mdi-clock-outline"
    >
      需院方在系统外修复数据/配置，不在本流程内闭环
    </v-chip>
  </div>
</template>

<style lang="scss" scoped>
.step-timeline {
  width: 100%;
}
</style>
