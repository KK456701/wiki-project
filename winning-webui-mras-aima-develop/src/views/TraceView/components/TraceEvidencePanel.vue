<script setup lang="ts">
import { format } from 'date-fns';
import type { TraceEvidenceFull } from '../types';

defineProps<{
  evidence: TraceEvidenceFull[];
}>();

function formatFullTime(isoString: string): string {
  return format(new Date(isoString), 'yyyy-MM-dd HH:mm:ss');
}
</script>

<template>
  <div class="trace-evidence">
    <div class="d-flex align-center mb-2">
      <span class="text-label-large font-weight-medium">Evidence 证据链</span>
      <v-chip size="x-small" variant="tonal" color="primary" class="ml-2">
        {{ evidence.length }} 条
      </v-chip>
    </div>

    <div v-if="evidence.length === 0" class="text-body-small text-medium-emphasis py-4 text-center">
      暂无 Evidence 数据
    </div>

    <div v-else class="d-flex flex-column gap-2">
      <v-card v-for="item in evidence" :key="item.evidenceId" variant="outlined">
        <v-card-text class="pa-3">
          <div class="text-body-medium font-weight-medium font-monospace mb-2">
            {{ item.evidenceId }}
          </div>

          <div class="d-flex flex-wrap gap-3 text-body-small text-medium-emphasis">
            <div>
              <strong>事实类型:</strong>
              <v-chip size="x-small" variant="tonal" class="ml-1">{{ item.factType }}</v-chip>
            </div>
            <div>
              <strong>规则:</strong>
              {{ item.ruleId }}
              <span class="ml-1 text-medium-emphasis">({{ item.ruleVersion }})</span>
            </div>
            <div v-if="item.statStart && item.statEnd">
              <strong>统计周期:</strong>
              {{ item.statStart }} ~ {{ item.statEnd }}
            </div>
          </div>

          <div class="d-flex flex-wrap gap-3 text-body-small text-medium-emphasis mt-1">
            <div><strong>来源工具:</strong> {{ item.sourceTool }}</div>
            <div>
              <strong>来源对象:</strong>
              <span class="font-monospace">{{ item.sourceObjectId }}</span>
            </div>
          </div>

          <div class="d-flex flex-wrap gap-3 text-body-small text-medium-emphasis mt-1">
            <div><strong>创建:</strong> {{ formatFullTime(item.createdAt) }}</div>
            <div><strong>过期:</strong> {{ formatFullTime(item.expiresAt) }}</div>
          </div>
        </v-card-text>
      </v-card>
    </div>
  </div>
</template>
