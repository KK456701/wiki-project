<script setup lang="ts">
import { computed } from 'vue';
import type { BatchResultItem } from '@/types/chat';
import { renderMarkdown } from '@/utils/markdown';
import { useBatchResults } from '../composables/useBatchResults';
import { BATCH_ANALYSIS_UI_ACTION, useBatchAnalysis } from '../composables/useBatchAnalysis';

const EMIT = defineEmits<{
  inspect: [item: BatchResultItem];
  quickAction: [action: 'checklist' | 'quality_review'];
  viewReport: [batchRunId: string];
  inspectSteps: [traceId: string | null, batchRunId: string | null];
}>();

const props = defineProps<{
  batchResults: BatchResultItem[];
}>();

const resultsRef = computed(() => props.batchResults);

const { summary, visibleAttention, attentionTotal } = useBatchResults(resultsRef);
const { runningKey, title, answer, error, runBatch, inspect } = useBatchAnalysis(resultsRef);
const renderedAnswer = computed(() => renderMarkdown(answer.value));

function handleViewReport() {
  if (summary.value.batchRunId) {
    EMIT('viewReport', summary.value.batchRunId);
  }
}

const STAT_TEXTS = [
  '达标、未达标和待确认数量闭合。',
  '质量与达标独立判断。',
  '明细和报告绑定当前 batchRunId。',
] as const;
</script>

<template>
  <div class="batch-executive-summary">
    <!-- 核心结论 -->
    <v-card variant="outlined" class="mb-3">
      <v-card-title class="d-flex align-center text-body-large">
        <v-icon icon="mdi-chart-box-outline" color="primary" class="mr-2" />
        本次指标核算结果
        <v-spacer />
        <v-tooltip text="检查本次核算执行步骤" location="top">
          <template #activator="{ props: tip }">
            <v-btn
              v-bind="tip"
              size="small"
              variant="text"
              color="primary"
              icon="mdi-clipboard-search-outline"
              @click="EMIT('inspectSteps', null, summary.batchRunId)"
            />
          </template>
        </v-tooltip>
      </v-card-title>
      <v-card-text>
        <div class="d-flex flex-wrap ga-4 mb-2">
          <div>
            <span class="text-body-small text-medium-emphasis">覆盖指标：</span>
            <span class="font-weight-bold">{{ summary.indicatorCount }}</span>
          </div>
          <div>
            <span class="text-body-small text-medium-emphasis">达标：</span>
            <span class="font-weight-bold text-success">{{ summary.reached }}</span>
          </div>
          <div>
            <span class="text-body-small text-medium-emphasis">未达标：</span>
            <span class="font-weight-bold text-error">{{ summary.notReached }}</span>
          </div>
          <div>
            <span class="text-body-small text-medium-emphasis">待确认：</span>
            <span class="font-weight-bold text-warning">{{ summary.pending }}</span>
          </div>
        </div>
        <div class="d-flex flex-wrap ga-4 mb-2">
          <div>
            <span class="text-body-small text-medium-emphasis">数据质量：</span>
            <span class="font-weight-bold"
              >正常 {{ summary.qualityNormal }} / 异常 {{ summary.qualityAbnormal }}</span
            >
          </div>
          <div>
            <span class="text-body-small text-medium-emphasis">口径数：</span>
            <span class="font-weight-bold">{{ summary.profileCount }}</span>
          </div>
        </div>
        <div
          v-if="summary.statStart && summary.statEnd"
          class="text-body-small text-medium-emphasis mb-2"
        >
          统计周期：{{ summary.statStart }} 至 {{ summary.statEnd }}
        </div>
        <div class="text-body-small text-medium-emphasis bg-surface rounded pa-2">
          <div v-for="text in STAT_TEXTS" :key="text" class="mb-1">{{ text }}</div>
        </div>
      </v-card-text>

      <!-- 快捷操作按钮 -->
      <v-card-actions class="px-4 pb-4">
        <v-btn
          size="small"
          variant="tonal"
          color="primary"
          prepend-icon="mdi-clipboard-check-outline"
          class="mr-2"
          :loading="runningKey === BATCH_ANALYSIS_UI_ACTION.CHECKLIST"
          :disabled="!!runningKey"
          @click="runBatch(BATCH_ANALYSIS_UI_ACTION.CHECKLIST)"
        >
          生成待确认清单
        </v-btn>
        <v-btn
          size="small"
          variant="tonal"
          color="warning"
          prepend-icon="mdi-alert-circle-outline"
          :loading="runningKey === BATCH_ANALYSIS_UI_ACTION.QUALITY_REVIEW"
          :disabled="!!runningKey"
          @click="runBatch(BATCH_ANALYSIS_UI_ACTION.QUALITY_REVIEW)"
        >
          哪些未达标可能是数据问题
        </v-btn>
      </v-card-actions>
      <v-card-text v-if="answer || error" class="pt-0">
        <v-alert
          :type="error ? 'error' : 'info'"
          variant="tonal"
          density="comfortable"
          closable
          @click:close="
            error = '';
            answer = '';
          "
        >
          <div class="font-weight-medium mb-1">{{ title || '分析结果' }}</div>
          <div v-if="error">{{ error }}</div>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div v-else class="markdown-body" v-html="renderedAnswer" />
        </v-alert>
      </v-card-text>
    </v-card>

    <!-- 需重点关注 -->
    <v-card v-if="attentionTotal > 0" variant="outlined" class="mb-3">
      <v-card-title class="d-flex align-center text-label-large">
        <v-icon icon="mdi-alert-octagon-outline" color="warning" class="mr-2" />
        需重点关注（{{ visibleAttention.length }}/{{ attentionTotal }}）
      </v-card-title>
      <v-list density="compact">
        <v-list-item
          v-for="item in visibleAttention"
          :key="item.batchResult.ruleId + (item.batchResult.profileId ?? '')"
          class="attention-item"
          :disabled="!!runningKey"
          @click="inspect(item.batchResult)"
        >
          <template #prepend>
            <v-chip
              size="small"
              class="justify-center mr-2"
              style="width: 80px"
              :color="
                item.category === 'failure'
                  ? 'error'
                  : item.category === 'quality'
                    ? 'warning'
                    : item.category === 'pending'
                      ? 'info'
                      : 'error'
              "
            >
              {{ item.badge }}
            </v-chip>
          </template>
          <v-list-item-title class="text-body-medium">
            {{ item.batchResult.ruleName }}
          </v-list-item-title>
          <v-list-item-subtitle class="text-body-small">
            {{ item.reason }}
          </v-list-item-subtitle>
        </v-list-item>
      </v-list>
    </v-card>

    <!-- 查看完整报告 -->
    <div class="d-flex justify-center mb-3">
      <v-btn
        variant="tonal"
        color="primary"
        prepend-icon="mdi-file-document-outline"
        @click="handleViewReport"
      >
        查看完整报告
      </v-btn>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.attention-item {
  cursor: pointer;

  &:hover {
    background: rgba(var(--v-theme-primary), 0.04);
  }
}
</style>
