<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { detailFieldLabel, formatDetailCell } from '@/components/details/detail-fields';
import { getDiagnosisShadowDiffs } from '@/services/diagnosis';
import type { ShadowDiffItem, ShadowDiffType } from '@/types/diagnosis';

const props = defineProps<{ caseId: string; trialId: string }>();
const open = ref(false);
const type = ref<ShadowDiffType>('ADDED');
const loading = ref(false);
const error = ref('');
const items = ref<ShadowDiffItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const types: Array<{ value: ShadowDiffType; label: string }> = [
  { value: 'ADDED', label: '新增记录' },
  { value: 'REMOVED', label: '减少记录' },
  { value: 'CHANGED', label: '字段变化' },
  { value: 'DUPLICATE', label: '重复记录' },
];

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));

function rows(item: ShadowDiffItem) {
  return [...item.beforeRows, ...item.afterRows];
}

function columns(item: ShadowDiffItem) {
  const result = new Set<string>();
  rows(item).forEach((row) =>
    Object.keys(row).forEach((key) => !key.startsWith('__') && result.add(key)),
  );
  return Array.from(result);
}

async function load() {
  if (!open.value || !props.caseId || !props.trialId) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await getDiagnosisShadowDiffs(
      props.caseId,
      props.trialId,
      type.value,
      page.value,
      pageSize,
    );
    items.value = result.items;
    total.value = result.total;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载差异明细失败';
  } finally {
    loading.value = false;
  }
}

watch([open, type, page, () => props.trialId], () => void load());
watch(type, () => (page.value = 1));
</script>

<template>
  <v-expansion-panels v-model="open" class="mt-4">
    <v-expansion-panel :value="true">
      <v-expansion-panel-title>差异明细</v-expansion-panel-title>
      <v-expansion-panel-text>
        <div class="d-flex flex-wrap ga-2 mb-3">
          <v-btn
            v-for="entry in types"
            :key="entry.value"
            size="small"
            :variant="type === entry.value ? 'flat' : 'outlined'"
            :color="type === entry.value ? 'primary' : undefined"
            @click="type = entry.value"
          >
            {{ entry.label }}
          </v-btn>
        </div>
        <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-3" />
        <v-alert v-if="error" type="error" variant="tonal" density="compact" :text="error" />
        <div
          v-else-if="!loading && !items.length"
          class="text-body-small text-medium-emphasis py-4"
        >
          当前正式链路与候选链路没有{{ types.find((entry) => entry.value === type)?.label }}差异。
        </div>
        <div v-else class="difference-list">
          <article v-for="item in items" :key="item.businessKey" class="difference-item">
            <div class="text-label-medium mb-2">业务标识：{{ item.businessKey }}</div>
            <div v-if="item.changedFields.length" class="text-body-small text-medium-emphasis mb-2">
              变化字段：{{ item.changedFields.map(detailFieldLabel).join('、') }}
            </div>
            <div class="difference-table-wrap">
              <table class="difference-table">
                <thead>
                  <tr>
                    <th>数据版本</th>
                    <th v-for="field in columns(item)" :key="field">
                      {{ detailFieldLabel(field) }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, index) in item.beforeRows" :key="`before:${index}`">
                    <td>原记录</td>
                    <td v-for="field in columns(item)" :key="field">
                      {{ formatDetailCell(field, row[field]) }}
                    </td>
                  </tr>
                  <tr v-for="(row, index) in item.afterRows" :key="`after:${index}`">
                    <td>当前记录</td>
                    <td v-for="field in columns(item)" :key="field">
                      {{ formatDetailCell(field, row[field]) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </article>
        </div>
        <v-pagination v-if="pageCount > 1" v-model="page" :length="pageCount" density="compact" />
      </v-expansion-panel-text>
    </v-expansion-panel>
  </v-expansion-panels>
</template>

<style scoped>
.difference-list {
  display: grid;
  min-width: 0;
  gap: 12px;
}
.difference-item {
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(var(--v-theme-outline), 0.18);
  border-radius: 8px;
}
.difference-table-wrap {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  scrollbar-gutter: stable;
}
.difference-table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.difference-table th,
.difference-table td {
  min-width: 130px;
  padding: 7px 9px;
  white-space: nowrap;
  text-align: left;
  border-bottom: 1px solid rgba(var(--v-theme-outline), 0.14);
}
.difference-table th {
  font-weight: 500;
  color: rgba(var(--v-theme-on-surface), 0.62);
}
</style>
