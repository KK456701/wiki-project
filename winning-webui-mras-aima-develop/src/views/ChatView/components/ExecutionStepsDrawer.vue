<script setup lang="ts">
import {
  useExecutionSteps,
  statusText,
  statusColor,
  durationStr,
} from '../composables/useExecutionSteps';
import ExecutionStepDetail from './ExecutionStepDetail.vue';

const props = defineProps<{
  open: boolean;
  traceId: string | null;
  batchRunId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const {
  loading,
  error,
  steps,
  selectedNode,
  selectedNodeId,
  initOutputData,
  traceNodes,
  selectNode,
  backToList,
  EXECUTION_STEP_LABELS,
  EXECUTION_STEP_ICONS,
} = useExecutionSteps(
  () => props.open,
  () => props.traceId,
  () => props.batchRunId,
);

function nodeLabel(node: { nodeName: string; nodeTitle?: string }): string {
  return EXECUTION_STEP_LABELS[node.nodeName] ?? node.nodeTitle ?? node.nodeName;
}

function nodeIcon(node: { nodeName: string }): string {
  return EXECUTION_STEP_ICONS[node.nodeName] ?? 'mdi-circle-small';
}

function close() {
  emit('update:open', false);
}
</script>

<template>
  <v-navigation-drawer
    :model-value="open"
    temporary
    location="right"
    width="700"
    @update:model-value="emit('update:open', $event)"
  >
    <template #prepend>
      <div class="d-flex align-center justify-space-between pa-4 border-b flex-shrink-0">
        <div class="d-flex align-center">
          <v-btn
            v-if="selectedNodeId"
            icon="mdi-arrow-left"
            variant="text"
            size="small"
            class="mr-2"
            @click="backToList"
          />
          <h2 class="text-headline-small mb-0">
            {{ selectedNodeId && selectedNode ? nodeLabel(selectedNode) : '执行步骤检查' }}
          </h2>
        </div>
        <v-btn icon="mdi-close" variant="text" size="small" @click="close" />
      </div>
    </template>

    <div class="pa-4 overflow-y-auto flex-1-1-0">
      <!-- 加载中 -->
      <div v-if="loading" class="d-flex flex-column align-center justify-center py-8">
        <v-progress-circular indeterminate color="primary" size="48" class="mb-4" />
        <span class="text-body-medium text-medium-emphasis">正在加载执行链路...</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="d-flex flex-column align-center py-8">
        <v-icon icon="mdi-alert-circle-outline" color="error" size="48" class="mb-3" />
        <span class="text-body-medium text-error mb-3">{{ error }}</span>
        <v-btn variant="tonal" size="small" @click="close">关闭</v-btn>
      </div>

      <!-- 步骤列表 -->
      <template v-else-if="!selectedNodeId">
        <p class="text-body-medium text-medium-emphasis mb-4">
          本次批量指标核算共执行 {{ steps.length }} 个步骤，点击可查看详情
        </p>
        <v-list class="rounded">
          <v-list-item
            v-for="(step, idx) in steps"
            :key="step.nodeId"
            border
            class="cursor-pointer mb-1 rounded"
            :class="step.status === 'failed' || step.status === 'error' ? 'bg-error-light' : ''"
            @click="selectNode(step.nodeId)"
          >
            <template #prepend>
              <v-avatar :color="statusColor(step)" size="32" variant="tonal">
                <v-icon :icon="nodeIcon(step)" size="18" />
              </v-avatar>
            </template>
            <v-list-item-title class="d-flex align-center">
              <span class="text-body-medium font-weight-medium mr-2">步骤 {{ idx + 1 }}</span>
              <span class="mr-2">{{ nodeLabel(step) }}</span>
              <!-- <v-chip :color="statusColor(step)" size="x-small" variant="tonal" label>
                {{ statusText(step) }}
              </v-chip> -->
            </v-list-item-title>
            <v-list-item-subtitle class="text-body-small">
              <span v-if="step.durationMs != null">耗时 {{ durationStr(step.durationMs) }}</span>
              <span v-else class="text-medium-emphasis">等待中</span>
              <span v-if="step.errorCode" class="ml-2 text-error">{{ step.errorCode }}</span>
            </v-list-item-subtitle>
            <template #append>
              <v-chip :color="statusColor(step)" size="x-small" variant="tonal" label>
                {{ statusText(step) }}
              </v-chip>
              <v-icon icon="mdi-chevron-right" color="medium-emphasis" />
            </template>
          </v-list-item>
        </v-list>

        <v-card v-if="steps.length === 0" variant="outlined" class="text-center py-6 mt-4">
          <v-icon icon="mdi-information-outline" size="40" color="medium-emphasis" class="mb-2" />
          <p class="text-body-medium text-medium-emphasis mb-0">
            未在链路中找到执行步骤节点，可能该批次尚未完成或链路数据已过期
          </p>
        </v-card>
      </template>

      <!-- 步骤详情（委托给子组件） -->
      <ExecutionStepDetail
        v-else-if="selectedNode"
        :node="selectedNode"
        :init-output-data="initOutputData"
        :all-trace-nodes="traceNodes"
      />
    </div>
  </v-navigation-drawer>
</template>

<style lang="scss" scoped>
.bg-error-light {
  background-color: rgba(var(--v-theme-error), 0.06);
}

.cursor-pointer {
  cursor: pointer;
}
</style>
