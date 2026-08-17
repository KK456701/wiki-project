<script setup lang="ts">
import { computed } from 'vue';
import {
  ASSISTANT_PICKER,
  type AssistantPicker,
  type PatientOption,
} from '@/views/DiagnosisWorkspace/assistant';
import type { DiagnosisDataScreening } from '@/types/diagnosis';
import AssistantPatientCandidatePicker from '@/views/DiagnosisWorkspace/components/AssistantPatientCandidatePicker.vue';

type DepartmentOption = DiagnosisDataScreening['departmentOptions'][number];

const props = defineProps<{
  caseId: string;
  picker: AssistantPicker;
  patientSearch: string;
  patientOptions: PatientOption[];
  patientLoading: boolean;
  departmentOptions: DepartmentOption[];
  departmentLoading: boolean;
  busy: boolean;
}>();

const emit = defineEmits<{
  'update:patient-search': [value: string];
  'search-patients': [];
  'select-patient': [option: PatientOption];
  'select-department': [value: string];
  close: [];
}>();

const isClarification = computed(() => props.picker === ASSISTANT_PICKER.CLARIFY_PATIENT);
const isPatient = computed(() => props.picker === ASSISTANT_PICKER.EXCLUDE_PATIENT);
const title = computed(() => {
  if (props.picker === ASSISTANT_PICKER.CLARIFY_PATIENT) return '选择需要澄清的患者';
  if (props.picker === ASSISTANT_PICKER.EXCLUDE_PATIENT) return '选择需要排除的患者';
  return '选择需要排除的科室';
});
const departmentItems = computed(() =>
  props.departmentOptions.map((item) => ({
    title: item.label,
    value: item.value,
    subtitle: `分母 ${item.denominatorCount} / 分子 ${item.numeratorCount}`,
  })),
);
</script>

<template>
  <div v-if="picker" class="assistant-picker pa-3">
    <div class="d-flex align-center justify-space-between mb-2">
      <strong class="text-body-medium">{{ title }}</strong>
      <v-btn
        icon="mdi-close"
        size="x-small"
        variant="text"
        aria-label="关闭选择"
        @click="emit('close')"
      />
    </div>
    <AssistantPatientCandidatePicker
      v-if="isClarification"
      :case-id="caseId"
      :busy="busy"
      @select="emit('select-patient', $event)"
    />
    <v-autocomplete
      v-else-if="isPatient"
      :model-value="null"
      :search="patientSearch"
      :items="patientOptions"
      item-title="title"
      item-value="value"
      label="搜索患者姓名、就诊号或住院号"
      variant="outlined"
      density="compact"
      :loading="patientLoading"
      :disabled="busy"
      no-data-text="没有找到匹配患者"
      return-object
      hide-details
      @update:search="
        emit('update:patient-search', $event ?? '');
        emit('search-patients');
      "
      @update:model-value="$event && emit('select-patient', $event)"
    >
      <template #item="{ props: itemProps, item }">
        <v-list-item v-bind="itemProps" :subtitle="item.subtitle" />
      </template>
    </v-autocomplete>
    <v-autocomplete
      v-else
      :model-value="null"
      :items="departmentItems"
      item-title="title"
      item-value="value"
      label="搜索并选择科室"
      variant="outlined"
      density="compact"
      :loading="departmentLoading"
      :disabled="busy"
      no-data-text="没有可选择的科室"
      hide-details
      @update:model-value="$event && emit('select-department', $event)"
    >
      <template #item="{ props: itemProps, item }">
        <v-list-item v-bind="itemProps" :subtitle="item.subtitle" />
      </template>
    </v-autocomplete>
  </div>
</template>

<style lang="scss" scoped>
.assistant-picker {
  background: rgba(var(--v-theme-on-surface), 0.025);
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 6px;
}
</style>
