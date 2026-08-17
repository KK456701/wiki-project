<script setup lang="ts">
import { computed } from 'vue';
import { useClipboard } from '@vueuse/core';
import { useTraceDetail } from '../composables/useTraceDetail';
import TraceNodeTimeline from './TraceNodeTimeline.vue';

const props = defineProps<{
  modelValue: boolean;
  traceId?: string;
  /** 批量作业 ID（历史消息加载时通过 runId→batch API→traceId 间接获取通路） */
  runId?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

const { traceData, loading, loadError, sortedNodes, jsonView, resolvedTraceId } = useTraceDetail(
  () => props.modelValue,
  () => props.traceId,
  () => props.runId,
);

const displayTraceId = computed(() => props.traceId ?? resolvedTraceId.value);

const { copy, copied, isSupported } = useClipboard({ legacy: true });
</script>

<template>
  <v-dialog v-model="visible" max-width="800" scrollable aria-label="链路详情">
    <v-card rounded="lg">
      <!-- 头部工具栏（所有状态统一） -->
      <v-toolbar density="comfortable" color="surface">
        <v-avatar color="primary" size="32" class="ml-4" variant="tonal">
          <v-icon icon="mdi-timeline-clock" size="18" />
        </v-avatar>
        <v-toolbar-title class="text-body-large font-weight-medium">
          链路详情
          <template v-if="!loadError && displayTraceId">
            （TraceId: {{ displayTraceId }}）
          </template>
        </v-toolbar-title>
        <v-chip v-if="traceData" size="small" variant="tonal" color="primary" class="mr-2">
          {{ sortedNodes.length }} 个节点
        </v-chip>
        <v-btn variant="text" icon="mdi-close" aria-label="关闭" @click="visible = false" />
      </v-toolbar>

      <v-divider />

      <!-- 中间内容区（按状态切换） -->

      <!-- 加载中 -->
      <v-card-text v-if="loading" class="text-center py-8">
        <v-progress-circular indeterminate color="primary" />
        <div class="text-medium-emphasis mt-3">加载链路数据中...</div>
      </v-card-text>

      <!-- 加载失败 -->
      <v-card-text v-else-if="loadError" class="text-center py-8">
        <v-icon icon="mdi-alert-circle" color="error" size="48" />
        <div class="text-error mt-3">{{ loadError }}</div>
      </v-card-text>

      <!-- 正常内容 -->
      <v-card-text v-else-if="traceData" class="pa-0">
        <TraceNodeTimeline :nodes="sortedNodes" />
      </v-card-text>

      <v-divider />

      <!-- 底部操作栏（所有状态统一） -->
      <v-card-actions class="px-4">
        <v-btn
          v-if="traceData && isSupported"
          variant="text"
          size="small"
          :color="copied ? 'success' : undefined"
          :prepend-icon="copied ? 'mdi-check-circle-outline' : 'mdi-content-copy'"
          @click="copy(jsonView)"
        >
          {{ copied ? '已复制' : '复制 JSON' }}
        </v-btn>
        <v-spacer />
        <v-btn variant="tonal" size="small" @click="visible = false">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
