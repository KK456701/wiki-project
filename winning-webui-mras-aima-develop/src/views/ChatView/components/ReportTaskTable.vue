<script setup lang="ts">
import type { BatchReportSnapshot, BatchTaskSnapshot } from '@/types/chat';
import type { RuleEffectiveQuery, RuleDetailQuery } from '@/types/chat';
import { ref } from 'vue';
import DataFlowDialog from './DataFlowDialog.vue';
import RuleCaliberDialog from './RuleCaliberDialog.vue';
import RuleDetailDialog from './RuleDetailDialog.vue';

const props = defineProps<{
  report: BatchReportSnapshot;
}>();

/** 格式化结果值 */
function formatResult(task: BatchTaskSnapshot): string {
  if (task.resultValue == null) return '—';
  const rounded = Math.round(task.resultValue * 100) / 100;
  const value = rounded.toFixed(2);
  return task.unit === 'percentage' ? `${value}%` : `${value}${task.unit || ''}`;
}

/** 指标状态列映射 */
const STATUS_MAP: Record<string, { label: string; color: string }> = {
  SUCCESS: { label: '成功', color: 'success' },
  NO_SAMPLE: { label: '无样本', color: 'warning' },
  FAILED: { label: '失败', color: 'error' },
};

/** 口径弹窗 */
const caliberOpen = ref(false);
const caliberQuery = ref<RuleEffectiveQuery | null>(null);

/** 链路弹窗 */
const dataFlowOpen = ref(false);
const dataFlowQuery = ref<RuleEffectiveQuery | null>(null);

/** 明细弹窗 */
const detailOpen = ref(false);
const detailQuery = ref<RuleDetailQuery | null>(null);

function buildEffectiveQuery(task: BatchTaskSnapshot): RuleEffectiveQuery {
  return {
    ruleId: task.ruleId,
    profileId: task.profileId,
    statStart: props.report.statStart,
    statEnd: props.report.statEnd,
  };
}

function buildDetailQuery(task: BatchTaskSnapshot): RuleDetailQuery {
  return {
    ruleId: task.ruleId,
    batchRunId: props.report.batchRunId,
    profileId: task.profileId,
    start: props.report.statStart,
    end: props.report.statEnd,
    page: 1,
    pageSize: 50,
  };
}

function handleCaliber(task: BatchTaskSnapshot) {
  caliberQuery.value = buildEffectiveQuery(task);
  caliberOpen.value = true;
}

function handleLink(task: BatchTaskSnapshot) {
  dataFlowQuery.value = buildEffectiveQuery(task);
  dataFlowOpen.value = true;
}

function handleDetail(task: BatchTaskSnapshot) {
  detailQuery.value = buildDetailQuery(task);
  detailOpen.value = true;
}
</script>

<template>
  <v-card variant="outlined" class="mb-4">
    <v-card-title class="text-label-large py-2 px-3">指标明细</v-card-title>
    <div class="overflow-x-auto">
      <v-table density="compact" class="report-table">
        <thead>
          <tr>
            <th class="text-left text-body-small">序号</th>
            <th class="text-left text-body-small">指标</th>
            <th class="text-left text-body-small">口径</th>
            <th class="text-left text-body-small">状态</th>
            <th class="text-right text-body-small">结果</th>
            <th class="text-right text-body-small">分子</th>
            <th class="text-right text-body-small">分母</th>
            <th class="text-right text-body-small">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in report.tasks" :key="`${task.ruleId}::${task.profileId ?? ''}`">
            <td class="text-body-small">{{ task.position + 1 }}</td>
            <td>
              <div class="text-body-medium">{{ task.ruleName }}</div>
              <div class="text-body-small text-medium-emphasis">
                <code>{{ task.ruleId }}</code>
              </div>
            </td>
            <td class="text-body-small text-medium-emphasis">
              {{ task.profileName || '正式口径' }}
            </td>
            <td>
              <v-chip :color="STATUS_MAP[task.status]?.color ?? 'default'" size="x-small" label>
                {{ STATUS_MAP[task.status]?.label ?? task.status }}
              </v-chip>
            </td>
            <td class="text-right text-body-medium font-weight-medium">
              {{ formatResult(task) }}
            </td>
            <td class="text-right text-body-small">{{ task.numeratorCount ?? '—' }}</td>
            <td class="text-right text-body-small">{{ task.denominatorCount ?? '—' }}</td>
            <td class="text-right text-no-wrap">
              <v-btn
                variant="text"
                size="x-small"
                color="primary"
                class="text-body-small"
                @click="handleCaliber(task)"
              >
                口径
              </v-btn>
              <v-btn
                variant="text"
                size="x-small"
                color="primary"
                class="text-body-small"
                @click="handleLink(task)"
              >
                链路
              </v-btn>
              <v-btn
                variant="text"
                size="x-small"
                color="primary"
                class="text-body-small"
                @click="handleDetail(task)"
              >
                明细
              </v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>
    </div>
  </v-card>

  <RuleCaliberDialog v-model:open="caliberOpen" :query="caliberQuery" />
  <DataFlowDialog v-model:open="dataFlowOpen" :query="dataFlowQuery" />
  <RuleDetailDialog v-model:open="detailOpen" :task-info="detailQuery" />
</template>

<style lang="scss" scoped>
.report-table {
  font-size: 13px;

  :deep(th) {
    white-space: nowrap;
    font-weight: 500;
    padding: 6px 8px !important;
  }

  :deep(td) {
    padding: 4px 8px !important;
    vertical-align: middle;
  }

  :deep(tbody tr:hover) {
    background: rgba(var(--v-theme-on-surface), 0.03);
  }
}
</style>
