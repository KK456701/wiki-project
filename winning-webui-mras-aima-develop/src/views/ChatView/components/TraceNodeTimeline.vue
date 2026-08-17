<script setup lang="ts">
import type { TraceNode } from '@/types/chat';
import {
  formatTime,
  formatJson,
  nodeTypeIcon,
  nodeTypeColor,
  statusIcon,
  statusColor,
} from '../composables/useTraceDetail';

defineProps<{
  nodes: TraceNode[];
}>();
</script>

<template>
  <div class="pa-4">
    <v-timeline density="compact" side="end" align="start" class="ps-0">
      <v-timeline-item
        v-for="(node, index) in nodes"
        :key="node.nodeId"
        :dot-color="statusColor(node.status)"
        :icon="statusIcon(node.status)"
        size="small"
      >
        <v-card :color="statusColor(node.status)" variant="outlined" class="mb-2">
          <v-card-title class="text-body-large d-flex align-center py-2">
            <span class="step-num mr-2">{{ String(index + 1).padStart(2, '0') }}</span>
            <v-icon
              :icon="nodeTypeIcon(node.nodeType)"
              :color="nodeTypeColor(node.nodeType)"
              size="24"
              class="mr-2"
            />
            <span class="font-weight-medium">{{ node.nodeTitle }}</span>
            <span class="text-title-small ml-2">{{ node.nodeName }}</span>
          </v-card-title>
          <v-card-subtitle v-if="node.processingSummary">
            {{ node.processingSummary }}
          </v-card-subtitle>

          <v-card-text class="py-2 px-3">
            <div class="text-body-small text-medium-emphasis mb-2">
              {{ formatTime(node.startedAt) }} · {{ node.durationMs }}ms
            </div>

            <v-expansion-panels class="mb-1">
              <v-expansion-panel>
                <v-expansion-panel-title class="text-body-small py-1">
                  <v-icon icon="mdi-import" size="14" class="mr-1" />
                  输入参数
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <pre class="code-block text-body-small pa-2">{{
                    formatJson(node.inputData)
                  }}</pre>
                </v-expansion-panel-text>
              </v-expansion-panel>

              <v-expansion-panel>
                <v-expansion-panel-title class="text-body-small py-1">
                  <v-icon icon="mdi-export" size="14" class="mr-1" />
                  输出结果
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <pre class="code-block text-body-small pa-2">{{
                    formatJson(node.outputData)
                  }}</pre>
                </v-expansion-panel-text>
              </v-expansion-panel>

              <v-expansion-panel v-if="node.errorCode || node.errorMessage">
                <v-expansion-panel-title class="text-body-small py-1 text-error">
                  <v-icon icon="mdi-alert-circle" size="14" class="mr-1" />
                  错误信息
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <div class="text-body-small">
                    <div v-if="node.errorCode"><strong>错误码:</strong> {{ node.errorCode }}</div>
                    <div v-if="node.errorMessage">
                      <strong>描述:</strong> {{ node.errorMessage }}
                    </div>
                  </div>
                </v-expansion-panel-text>
              </v-expansion-panel>
            </v-expansion-panels>
          </v-card-text>
        </v-card>
      </v-timeline-item>
    </v-timeline>
  </div>
</template>

<style lang="scss" scoped>
:deep(.v-timeline-item__body) {
  width: 100%;
}

.code-block {
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  font-family: 'Roboto Mono', 'Courier New', monospace;
}

.step-num {
  font-size: 24px;
  color: rgba(var(--v-theme-on-surface), 0.45);
}
</style>
