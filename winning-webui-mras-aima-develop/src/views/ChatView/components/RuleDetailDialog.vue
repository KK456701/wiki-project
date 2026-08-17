<script setup lang="ts">
import { computed } from 'vue';
import IndicatorDetailDialog from '@/components/details/IndicatorDetailDialog.vue';
import { getRuleDetails } from '@/services/chat';
import { createBatchDetailExport, downloadIndicatorExport } from '@/services/export';
import type { DetailGroup, RuleDetailQuery } from '@/types/chat';

const props = defineProps<{ open: boolean; taskInfo: RuleDetailQuery | null }>();
const emit = defineEmits<{ 'update:open': [value: boolean] }>();
const model = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
});

async function loadPage(group: DetailGroup | undefined, page: number, pageSize: number) {
  if (!props.taskInfo) throw new Error('缺少指标明细上下文');
  return getRuleDetails({ ...props.taskInfo, group, page, pageSize });
}

async function exportDetails() {
  if (!props.taskInfo) throw new Error('缺少指标明细上下文');
  const task = await createBatchDetailExport(
    props.taskInfo.batchRunId,
    props.taskInfo.ruleId,
    props.taskInfo.profileId,
  );
  await downloadIndicatorExport(task);
}
</script>

<template>
  <IndicatorDetailDialog
    v-model="model"
    :title="`指标明细 — ${taskInfo?.ruleId ?? ''}`"
    :initial-group="taskInfo?.group"
    :load-page="loadPage"
    :export-details="exportDetails"
  />
</template>
