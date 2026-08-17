<script setup lang="ts">
import type { TraceNode } from '@/types/chat';
import {
  statusText,
  statusColor,
  durationStr,
  formatJsonValue,
} from '../composables/useExecutionSteps';
import InitializationDetail from './InitializationDetail.vue';

const props = defineProps<{
  node: TraceNode;
  initOutputData: Record<string, unknown> | null;
  allTraceNodes: TraceNode[];
}>();

const isInitNode = props.node.nodeName === 'batch_data_initialization_validation';
</script>

<template>
  <!-- 初始化节点：渲染专用详情组件 -->
  <InitializationDetail
    v-if="isInitNode"
    :node="node"
    :init-output-data="initOutputData"
    :all-trace-nodes="allTraceNodes"
  />

  <!-- 非初始化节点：通用详情 -->
  <template v-else>
    <!-- 基本信息 -->
    <v-card variant="outlined" class="mb-4">
      <v-card-title class="text-label-large py-2 px-3">基本信息</v-card-title>
      <v-card-text class="pa-3 pt-0">
        <div class="d-flex flex-wrap ga-3 text-body-medium">
          <div>
            <span class="text-medium-emphasis">状态：</span>
            <v-chip :color="statusColor(node)" size="x-small" variant="tonal" label>
              {{ statusText(node) }}
            </v-chip>
          </div>
          <div>
            <span class="text-medium-emphasis">耗时：</span>
            <span>{{ durationStr(node.durationMs) }}</span>
          </div>
          <div v-if="node.nodeType">
            <span class="text-medium-emphasis">类型：</span>
            <span>{{ node.nodeType }}</span>
          </div>
          <div v-if="node.modelId">
            <span class="text-medium-emphasis">模型：</span>
            <code class="text-body-small">{{ node.modelId }}</code>
          </div>
        </div>
      </v-card-text>
    </v-card>

    <!-- 处理概览 -->
    <v-card variant="outlined" class="mb-4">
      <v-card-title class="text-label-large py-2 px-3">处理概览</v-card-title>
      <v-card-text class="pa-3 pt-0">
        <p class="text-body-medium mb-1">
          {{ node.processingSummary ?? '暂无处理说明' }}
        </p>
        <div class="text-body-small text-medium-emphasis">
          节点标识：<code>{{ node.nodeName }}</code>
          <span class="ml-2"
            >节点 ID：<code>{{ node.nodeId }}</code></span
          >
        </div>
      </v-card-text>
    </v-card>

    <!-- 错误信息 -->
    <v-card v-if="node.errorCode || node.errorMessage" variant="outlined" class="mb-4 border-error">
      <v-card-title class="text-label-large py-2 px-3 text-error">异常信息</v-card-title>
      <v-card-text class="pa-3 pt-0">
        <div v-if="node.errorCode" class="text-body-medium">
          <span class="text-medium-emphasis">错误码：</span>
          <code>{{ node.errorCode }}</code>
        </div>
        <div v-if="node.errorMessage" class="text-body-medium mt-1">
          <span class="text-medium-emphasis">原因：</span>
          {{ node.errorMessage }}
        </div>
      </v-card-text>
    </v-card>

    <!-- 输入参数 -->
    <v-card variant="outlined" class="mb-4">
      <v-expansion-panels variant="accordion" class="v-card--flat">
        <v-expansion-panel>
          <v-expansion-panel-title class="text-label-large py-2 px-3">
            输入参数
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <pre
              class="text-body-small font-monospace bg-surface rounded pa-2 overflow-auto"
              style="max-height: 300px"
              >{{ formatJsonValue(node.inputData) }}</pre>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </v-card>

    <!-- 输出参数 -->
    <v-card variant="outlined" class="mb-4">
      <v-expansion-panels variant="accordion" class="v-card--flat">
        <v-expansion-panel>
          <v-expansion-panel-title class="text-label-large py-2 px-3">
            输出参数
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <pre
              class="text-body-small font-monospace bg-surface rounded pa-2 overflow-auto"
              style="max-height: 300px"
              >{{ formatJsonValue(node.outputData) }}</pre>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </v-card>
  </template>
</template>

<style lang="scss" scoped>
.border-error {
  border-color: rgba(var(--v-theme-error), 0.3);
  border-width: 1px;
}
</style>
