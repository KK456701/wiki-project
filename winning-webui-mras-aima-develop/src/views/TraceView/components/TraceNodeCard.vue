<script setup lang="ts">
import { format } from 'date-fns';
import type { TraceNodeFull } from '../types';
import { NODE_TYPE_ICON, NODE_TYPE_COLOR, NODE_STATUS_ICON, NODE_STATUS_COLOR } from '../constants';

defineProps<{
  node: TraceNodeFull;
}>();

defineEmits<{
  (e: 'click', node: TraceNodeFull): void;
}>();

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatTime(isoString: string): string {
  return format(new Date(isoString), 'HH:mm:ss.SSS');
}
</script>

<template>
  <v-card
    variant="outlined"
    class="node-card cursor-pointer"
    :class="{ 'border-error': node.status !== 'success' }"
    @click="$emit('click', node)"
  >
    <v-card-text class="pa-3">
      <div class="d-flex align-center mb-1">
        <v-icon
          :icon="NODE_TYPE_ICON[node.nodeType]"
          :color="NODE_TYPE_COLOR[node.nodeType]"
          size="18"
          class="mr-1"
        />
        <span class="text-body-medium font-weight-medium text-truncate flex-grow-1">
          {{ node.nodeTitle }}
        </span>
        <v-icon
          :icon="NODE_STATUS_ICON[node.status]"
          :color="NODE_STATUS_COLOR[node.status]"
          size="16"
        />
      </div>

      <div class="d-flex align-center text-body-small text-medium-emphasis gap-3">
        <span class="font-monospace">{{ node.nodeName }}</span>
        <span>·</span>
        <span>{{ formatDuration(node.durationMs) }}</span>
        <span>·</span>
        <span>{{ formatTime(node.startedAt) }}</span>
      </div>

      <div class="text-body-small text-medium-emphasis mt-1">
        {{ node.processingSummary }}
      </div>

      <!-- Extra badges row -->
      <div class="d-flex flex-wrap align-center gap-1 mt-2">
        <v-chip v-if="node.retryCount > 0" size="x-small" variant="tonal" color="warning">
          重试 {{ node.retryCount }}
        </v-chip>
        <v-chip v-if="node.cacheReused" size="x-small" variant="tonal" color="info"> cache </v-chip>
        <v-chip v-if="node.capability" size="x-small" variant="tonal">
          {{ node.capability }}
        </v-chip>
        <v-chip v-if="node.failureClass" size="x-small" variant="tonal" color="error">
          {{ node.failureClass }}
        </v-chip>
      </div>

      <!-- LLM tokens info -->
      <div
        v-if="node.inputTokens !== null || node.outputTokens !== null"
        class="text-body-small text-medium-emphasis mt-1"
      >
        tokens: in={{ node.inputTokens ?? '-' }} out={{ node.outputTokens ?? '-' }}
      </div>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
.node-card {
  transition: box-shadow 0.2s;

  &:hover {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  }
}
</style>
