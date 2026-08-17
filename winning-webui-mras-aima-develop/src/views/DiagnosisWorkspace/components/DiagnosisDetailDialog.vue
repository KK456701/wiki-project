<script setup lang="ts">
import { computed } from 'vue';
import IndicatorDetailDialog from '@/components/details/IndicatorDetailDialog.vue';
import { createDiagnosisDetailExport, downloadIndicatorExport } from '@/services/export';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type { DetailGroup } from '@/types/chat';
import type { DiagnosisDetailRow } from '@/types/diagnosis';

const props = defineProps<{
  caseId: string;
  modelValue: boolean;
  group?: DetailGroup;
  selectedKeys?: Set<string>;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  'update:selectedKeys': [value: Set<string>];
  'update:selectedRows': [value: DiagnosisDetailRow[]];
}>();
const diagnosisStore = useDiagnosisStore();
const model = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

async function loadPage(group: DetailGroup | undefined, page: number, pageSize: number) {
  return diagnosisStore.loadDetails(props.caseId, group, page, pageSize);
}

async function exportDetails() {
  const task = await createDiagnosisDetailExport(props.caseId);
  await downloadIndicatorExport(task);
}
</script>

<template>
  <IndicatorDetailDialog
    v-model="model"
    title="指标计算明细"
    :initial-group="group"
    :load-page="loadPage"
    :export-details="exportDetails"
  />
</template>
