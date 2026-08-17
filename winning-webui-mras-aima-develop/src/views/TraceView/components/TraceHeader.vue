<script setup lang="ts">
import { format } from 'date-fns';
import type { TraceDetailFull } from '../types';
import { FINAL_STATUS_MAP } from '../constants';

defineProps<{
  data: TraceDetailFull;
}>();

function formatFullTime(isoString: string | null): string {
  if (!isoString) return '-';
  return format(new Date(isoString), 'yyyy-MM-dd HH:mm:ss');
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
</script>

<template>
  <div class="trace-header mb-4">
    <div class="d-flex align-center mb-2">
      <span class="text-headline-small font-monospace">{{ data.traceId }}</span>
      <v-chip :color="FINAL_STATUS_MAP[data.finalStatus].color" size="small" class="ml-3">
        {{ FINAL_STATUS_MAP[data.finalStatus].label }}
      </v-chip>
      <v-chip v-if="data.errorCount > 0" size="small" color="error" class="ml-2">
        错误: {{ data.errorCount }}
      </v-chip>
      <v-chip v-if="data.fallbackCount > 0" size="small" color="warning" class="ml-2">
        降级: {{ data.fallbackCount }}
      </v-chip>
    </div>

    <div class="text-body-medium text-medium-emphasis ml-10">
      <div>
        <strong>用户问题:</strong>
        <span class="ml-1">{{ data.userQuery }}</span>
      </div>
      <div class="mt-1">
        <strong>意图:</strong>
        <v-chip size="x-small" variant="tonal" class="ml-1">{{ data.intent }}</v-chip>
        <span class="ml-3"><strong>会话:</strong> {{ data.sessionId ?? '-' }}</span>
      </div>
      <div class="mt-1">
        <strong>时间:</strong>
        {{ formatFullTime(data.startedAt) }}
        <span v-if="data.endedAt"> ~ {{ formatFullTime(data.endedAt) }} </span>
        <span class="ml-1">({{ formatDuration(data.durationMs) }})</span>
      </div>
    </div>
  </div>
</template>
