<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useClipboard } from '@vueuse/core';
import { detailFieldLabel } from '@/components/details/detail-fields';
import type { SqlPreviewResult } from '@/services/sql-preview';

const props = defineProps<{
  open: boolean;
  result: SqlPreviewResult | null;
  error: string;
}>();

const emit = defineEmits<{ close: [] }>();
const page = ref(1);
const pageSize = 20;
const pageCount = computed(() => Math.max(1, Math.ceil((props.result?.rowCount ?? 0) / pageSize)));
const visibleRows = computed(() => {
  const start = (page.value - 1) * pageSize;
  return props.result?.rows.slice(start, start + pageSize) ?? [];
});
const { copy, copied } = useClipboard({ legacy: true });

watch(
  () => props.result,
  () => {
    page.value = 1;
  },
);

function displayValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
</script>

<template>
  <v-dialog
    :model-value="open"
    max-width="1180"
    scrollable
    @update:model-value="!$event && emit('close')"
  >
    <v-card rounded="xl">
      <v-toolbar color="surface" density="comfortable">
        <v-avatar color="primary" variant="tonal" size="36" class="ml-4">
          <v-icon icon="mdi-database-eye-outline" size="20" />
        </v-avatar>
        <v-toolbar-title class="text-body-large font-weight-bold">SQL 只读执行结果</v-toolbar-title>
        <v-btn icon="mdi-close" variant="text" @click="emit('close')" />
      </v-toolbar>
      <v-divider />

      <v-card-text class="pa-5">
        <v-alert v-if="error" type="error" variant="tonal" title="执行失败" class="mb-0">
          {{ error }}
        </v-alert>

        <template v-else-if="result">
          <div class="d-flex flex-wrap ga-2 mb-4">
            <v-chip color="success" variant="tonal" prepend-icon="mdi-check-circle-outline">
              查询完成
            </v-chip>
            <v-chip variant="outlined" prepend-icon="mdi-database">{{
              result.databaseLabel
            }}</v-chip>
            <v-chip variant="outlined" prepend-icon="mdi-timer-outline"
              >{{ result.durationMs }} ms</v-chip
            >
            <v-chip variant="outlined" prepend-icon="mdi-table-row"
              >{{ result.rowCount }} 行</v-chip
            >
          </div>

          <v-alert
            v-if="result.truncated"
            type="info"
            density="compact"
            variant="tonal"
            class="mb-3"
          >
            为保护业务库，本次只展示前 200 行。
          </v-alert>

          <div class="result-table border rounded-lg">
            <v-table density="compact" fixed-header height="420">
              <thead>
                <tr>
                  <th class="index-column">#</th>
                  <th v-for="column in result.columns" :key="column">
                    {{ detailFieldLabel(column) }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="visibleRows.length === 0">
                  <td
                    :colspan="result.columns.length + 1"
                    class="text-center text-medium-emphasis py-8"
                  >
                    查询成功，结果为空
                  </td>
                </tr>
                <tr v-for="(row, index) in visibleRows" :key="`${page}-${index}`">
                  <td class="text-medium-emphasis">{{ (page - 1) * pageSize + index + 1 }}</td>
                  <td v-for="column in result.columns" :key="column" class="text-no-wrap">
                    {{ displayValue(row[column]) }}
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>

          <div v-if="pageCount > 1" class="d-flex justify-center mt-3">
            <v-pagination v-model="page" :length="pageCount" density="comfortable" />
          </div>

          <v-expansion-panels variant="accordion" class="mt-4">
            <v-expansion-panel>
              <v-expansion-panel-title>查看实际执行 SQL</v-expansion-panel-title>
              <v-expansion-panel-text>
                <div class="d-flex justify-end mb-1">
                  <v-btn
                    size="small"
                    variant="text"
                    prepend-icon="mdi-content-copy"
                    @click="copy(result.executedSql)"
                  >
                    {{ copied ? '已复制' : '复制 SQL' }}
                  </v-btn>
                </div>
                <pre class="sql-block">{{ result.executedSql }}</pre>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
        </template>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
.result-table {
  overflow: hidden;
}

.index-column {
  width: 56px;
}

.sql-block {
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(var(--v-theme-on-surface), 0.04);
  border-radius: 8px;
  padding: 12px;
  max-height: 300px;
  overflow: auto;
  font-size: 12px;
}
</style>
