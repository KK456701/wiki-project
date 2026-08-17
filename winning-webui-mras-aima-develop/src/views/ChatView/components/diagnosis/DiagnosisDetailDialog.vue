<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { detailFieldLabel } from '@/components/details/detail-fields';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type { DiagnosisDetailsResponse, DiagnosisDetailRow } from '@/types/diagnosis';
import type { DetailGroup } from '@/types/chat';

const props = defineProps<{
  caseId: string;
  modelValue: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

const diagnosisStore = useDiagnosisStore();

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

const activeGroup = ref<DetailGroup>('numerator');
const page = ref(1);
const pageSize = 50;
const data = ref<DiagnosisDetailsResponse | null>(null);
const loading = ref(false);
const error = ref('');

const totalPages = computed(() =>
  data.value ? Math.max(1, Math.ceil(data.value.rowCount / pageSize)) : 1,
);

const headers = computed(() => {
  const rows = data.value?.rows ?? [];
  if (!rows.length) return [];
  return Object.keys(rows[0]).map((k) => ({ key: k, title: detailFieldLabel(k) }));
});

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

async function load(group: DetailGroup) {
  loading.value = true;
  error.value = '';
  try {
    data.value = await diagnosisStore.loadDetails(props.caseId, group, page.value, pageSize);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载明细失败';
    data.value = null;
  } finally {
    loading.value = false;
  }
}

function changeGroup(group: DetailGroup) {
  activeGroup.value = group;
  page.value = 1;
  load(group);
}

function prevPage() {
  if (page.value > 1) {
    page.value -= 1;
    load(activeGroup.value);
  }
}

function nextPage() {
  if (page.value < totalPages.value) {
    page.value += 1;
    load(activeGroup.value);
  }
}

watch(open, (v) => {
  if (v) {
    page.value = 1;
    load(activeGroup.value);
  }
});
</script>

<template>
  <v-dialog v-model="open" max-width="960">
    <v-card>
      <v-card-title class="d-flex align-center ga-2">
        <v-icon icon="mdi-table-eye" color="primary" />
        分子/分母明细
        <v-spacer />
        <v-btn icon="mdi-close" variant="text" size="small" @click="open = false" />
      </v-card-title>

      <v-card-text>
        <v-tabs v-model="activeGroup" density="comfortable" @update:model-value="changeGroup">
          <v-tab value="numerator">分子</v-tab>
          <v-tab value="denominator">分母</v-tab>
        </v-tabs>

        <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mt-3">
          {{ error }}
        </v-alert>

        <div v-else-if="loading" class="d-flex justify-center py-6">
          <v-progress-circular indeterminate color="primary" />
        </div>

        <template v-else-if="data">
          <div class="d-flex flex-wrap ga-4 mt-3 text-body-small text-medium-emphasis">
            <span>总行数：{{ data.rowCount }}</span>
            <span>本页：{{ data.rows.length }}</span>
            <span v-if="data.truncated" class="text-warning">（已截断）</span>
            <span class="ml-auto">
              卡片计数 分子/分母：{{ data.cardNumerator }} / {{ data.cardDenominator }}；
              明细计数：{{ data.detailNumerator }} / {{ data.detailDenominator }}
            </span>
          </div>

          <v-table density="compact" class="mt-2 detail-table">
            <thead>
              <tr>
                <th v-for="h in headers" :key="h.key" class="text-no-wrap">{{ h.title }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in data.rows as DiagnosisDetailRow[]" :key="i">
                <td v-for="h in headers" :key="h.key" class="text-no-wrap">
                  {{ formatCell(row[h.key]) }}
                </td>
              </tr>
              <tr v-if="data.rows.length === 0">
                <td :colspan="Math.max(headers.length, 1)" class="text-center text-medium-emphasis">
                  无明细数据
                </td>
              </tr>
            </tbody>
          </v-table>

          <div class="d-flex align-center justify-center ga-3 mt-3">
            <v-btn
              size="small"
              variant="tonal"
              :disabled="page <= 1"
              prepend-icon="mdi-chevron-left"
              @click="prevPage"
            >
              上一页
            </v-btn>
            <span class="text-body-small">第 {{ page }} / {{ totalPages }} 页</span>
            <v-btn
              size="small"
              variant="tonal"
              :disabled="page >= totalPages"
              append-icon="mdi-chevron-right"
              @click="nextPage"
            >
              下一页
            </v-btn>
          </div>
        </template>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
.detail-table {
  max-height: 50vh;
  overflow: auto;
}
</style>
