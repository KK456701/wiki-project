<script setup lang="ts">
import { computed, watch } from 'vue';
import { format } from 'date-fns';
import { useScrollLock } from '@vueuse/core';
import type { TraceNodeFull } from '../types';
import {
  NODE_TYPE_ICON,
  NODE_TYPE_COLOR,
  NODE_STATUS_ICON,
  NODE_STATUS_COLOR,
  FLOW_STAGE_CONFIG,
} from '../constants';

const props = defineProps<{
  modelValue: TraceNodeFull | null;
}>();

const visible = computed(() => !!props.modelValue);
const locked = useScrollLock(document.documentElement);
watch(visible, (v) => {
  locked.value = v;
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: TraceNodeFull | null): void;
}>();

function close() {
  emit('update:modelValue', null);
}

function formatFullTime(isoString: string | null): string {
  if (!isoString) return '-';
  return format(new Date(isoString), 'yyyy-MM-dd HH:mm:ss.SSS');
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatJson(data: Record<string, unknown> | null): string {
  if (!data) return '无数据';
  return JSON.stringify(data, null, 2);
}
</script>

<template>
  <v-navigation-drawer
    :model-value="!!modelValue"
    location="right"
    temporary
    width="480"
    @update:model-value="close"
  >
    <template v-if="modelValue">
      <v-toolbar density="compact">
        <v-toolbar-title class="text-body-large">节点详情</v-toolbar-title>
        <v-spacer />
        <v-btn icon="mdi-close" variant="text" size="small" @click="close" />
      </v-toolbar>

      <div class="pa-4">
        <!-- Basic info -->
        <div class="mb-3">
          <div class="d-flex align-center mb-2">
            <v-icon
              :icon="NODE_TYPE_ICON[modelValue.nodeType]"
              :color="NODE_TYPE_COLOR[modelValue.nodeType]"
              size="20"
              class="mr-1"
            />
            <span class="text-body-large font-weight-medium">{{ modelValue.nodeTitle }}</span>
            <v-icon
              :icon="NODE_STATUS_ICON[modelValue.status]"
              :color="NODE_STATUS_COLOR[modelValue.status]"
              size="18"
              class="ml-2"
            />
          </div>
          <div class="text-body-small text-medium-emphasis">{{ modelValue.nodeName }}</div>
          <div class="text-body-small mt-1">
            <v-chip
              :color="FLOW_STAGE_CONFIG[modelValue.flowStage]?.color ?? '#888'"
              size="x-small"
              variant="tonal"
            >
              {{ modelValue.flowStageTitle }}
            </v-chip>
            <v-chip size="x-small" class="ml-1">{{ modelValue.nodeType }}</v-chip>
          </div>
        </div>

        <v-divider class="mb-3" />

        <!-- Timing -->
        <div class="text-label-large font-weight-medium mb-2">耗时信息</div>
        <div class="d-flex flex-wrap gap-2 text-body-small mb-3">
          <v-chip size="x-small" variant="tonal" color="primary">
            总耗时: {{ formatMs(modelValue.durationMs) }}
          </v-chip>
          <v-chip size="x-small" variant="tonal">
            偏移: {{ formatMs(modelValue.startedOffsetMs) }}
          </v-chip>
          <v-chip size="x-small" variant="tonal">
            排他: {{ formatMs(modelValue.exclusiveDurationMs) }}
          </v-chip>
        </div>
        <div class="text-body-small text-medium-emphasis mb-3">
          <div>开始: {{ formatFullTime(modelValue.startedAt) }}</div>
          <div>结束: {{ formatFullTime(modelValue.endedAt) }}</div>
        </div>

        <v-divider class="mb-3" />

        <!-- LLM / Model info -->
        <div
          v-if="
            modelValue.modelId ||
            modelValue.inputTokens !== null ||
            modelValue.outputTokens !== null
          "
          class="mb-3"
        >
          <div class="text-label-large font-weight-medium mb-2">LLM 信息</div>
          <div class="text-body-small">
            <div v-if="modelValue.modelId"><strong>模型:</strong> {{ modelValue.modelId }}</div>
            <div v-if="modelValue.llmModel">
              <strong>LLM (已弃用):</strong> {{ modelValue.llmModel }}
            </div>
            <div v-if="modelValue.inputTokens !== null || modelValue.outputTokens !== null">
              <strong>Tokens:</strong>
              输入 {{ modelValue.inputTokens ?? '-' }} / 输出 {{ modelValue.outputTokens ?? '-' }}
            </div>
            <div>
              <strong>Cache:</strong> {{ modelValue.cacheReused ? '命中' : '未命中' }}
              <span class="ml-2"><strong>重试:</strong> {{ modelValue.retryCount }}</span>
            </div>
          </div>
        </div>

        <!-- Capability / Tool / DB -->
        <div class="mb-3">
          <div class="text-label-large font-weight-medium mb-2">上下文信息</div>
          <div class="d-flex flex-wrap gap-1 text-body-small">
            <v-chip v-if="modelValue.capability" size="x-small" variant="tonal">
              capability: {{ modelValue.capability }}
            </v-chip>
            <v-chip v-if="modelValue.toolName" size="x-small" variant="tonal">
              tool: {{ modelValue.toolName }}
            </v-chip>
            <v-chip v-if="modelValue.dbSource" size="x-small" variant="tonal">
              db: {{ modelValue.dbSource }}
            </v-chip>
            <v-chip v-if="modelValue.ruleId" size="x-small" variant="tonal">
              rule: {{ modelValue.ruleId }}
            </v-chip>
            <v-chip v-if="modelValue.failureClass" size="x-small" variant="tonal" color="error">
              failure: {{ modelValue.failureClass }}
            </v-chip>
          </div>
        </div>

        <v-divider class="mb-3" />

        <!-- Processing summary -->
        <div class="mb-3">
          <div class="text-label-large font-weight-medium mb-2">处理说明</div>
          <div class="text-body-small text-medium-emphasis">{{ modelValue.processingSummary }}</div>
        </div>

        <!-- Error -->
        <div v-if="modelValue.errorCode || modelValue.errorMessage" class="mb-3">
          <div class="text-label-large font-weight-medium mb-2 text-error">错误信息</div>
          <div class="text-body-small">
            <div v-if="modelValue.errorCode">
              <strong>错误码:</strong> {{ modelValue.errorCode }}
            </div>
            <div v-if="modelValue.errorMessage">
              <strong>描述:</strong> {{ modelValue.errorMessage }}
            </div>
          </div>
        </div>

        <!-- capability_readiness -->
        <div v-if="modelValue.capabilityReadiness" class="mb-3">
          <div class="text-label-large font-weight-medium mb-2">能力就绪状态</div>
          <div class="text-body-small">
            <div v-for="(val, key) in modelValue.capabilityReadiness" :key="key" class="mb-1">
              <strong>{{ key }}:</strong>
              <v-icon
                v-if="typeof val === 'boolean'"
                :icon="val ? 'mdi-check' : 'mdi-close'"
                :color="val ? 'success' : 'error'"
                size="14"
              />
              <span v-else>{{ val }}</span>
            </div>
          </div>
        </div>

        <v-divider class="mb-3" />

        <!-- Input / Output data -->
        <v-expansion-panels>
          <v-expansion-panel>
            <v-expansion-panel-title class="text-body-small py-2">
              <v-icon icon="mdi-import" size="14" class="mr-1" />
              输入数据
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <pre class="json-content font-monospace">{{ formatJson(modelValue.inputData) }}</pre>
            </v-expansion-panel-text>
          </v-expansion-panel>

          <v-expansion-panel>
            <v-expansion-panel-title class="text-body-small py-2">
              <v-icon icon="mdi-export" size="14" class="mr-1" />
              输出数据
            </v-expansion-panel-title>
            <v-expansion-panel-text>
              <pre class="json-content font-monospace">{{ formatJson(modelValue.outputData) }}</pre>
            </v-expansion-panel-text>
          </v-expansion-panel>
        </v-expansion-panels>
      </div>
    </template>
  </v-navigation-drawer>
</template>

<style lang="scss" scoped>
.json-content {
  background: rgba(var(--v-theme-on-surface), 0.05);
  padding: 8px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1.4;
  max-height: 300px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
