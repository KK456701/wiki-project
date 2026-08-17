<script setup lang="ts">
import { NODE_TYPE_COLOR } from '../constants';
import type { TimingSummary } from '../types';

import type { TraceNodeType } from '../types';

defineProps<{
  summary: TimingSummary;
  totalDuration: number;
}>();

const timingItems: { key: keyof TimingSummary; label: string; nodeType: TraceNodeType }[] = [
  { key: 'llmMs', label: 'LLM', nodeType: 'llm' },
  { key: 'toolMs', label: 'Tool/DB', nodeType: 'tool' },
  { key: 'codeMs', label: 'Code', nodeType: 'code' },
  { key: 'storageMs', label: 'Storage', nodeType: 'storage' },
];

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
</script>

<template>
  <div class="trace-summary mb-4">
    <div class="d-flex align-center mb-2">
      <span class="text-label-large font-weight-medium">耗时分布</span>
      <v-chip size="x-small" variant="tonal" color="primary" class="ml-2">
        {{ formatMs(totalDuration) }}
      </v-chip>
    </div>

    <div class="d-flex flex-column gap-2">
      <div v-for="item in timingItems" :key="item.key" class="d-flex align-center">
        <span class="text-body-small label-width">{{ item.label }}</span>
        <v-progress-linear
          :model-value="totalDuration > 0 ? (summary[item.key] / totalDuration) * 100 : 0"
          :height="12"
          rounded
          :color="NODE_TYPE_COLOR[item.nodeType]"
          class="flex-grow-1 mx-2"
        />
        <span class="text-body-small font-monospace value-width">
          {{ formatMs(summary[item.key]) }}
        </span>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.label-width {
  width: 72px;
  flex-shrink: 0;
}

.value-width {
  width: 64px;
  text-align: right;
  flex-shrink: 0;
}
</style>
