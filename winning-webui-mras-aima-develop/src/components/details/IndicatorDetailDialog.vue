<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  detailFieldLabel,
  formatDetailCell,
  visibleDetailKeys,
} from '@/components/details/detail-fields';
import type { DetailGroup, DetailGroupDescriptor, RuleDetailPage } from '@/types/chat';

type DetailPage = Pick<
  RuleDetailPage,
  | 'detailKind'
  | 'group'
  | 'groups'
  | 'rows'
  | 'page'
  | 'pageSize'
  | 'rowCount'
  | 'snapshotId'
  | 'snapshotReused'
  | 'summary'
  | 'truncated'
>;

const props = defineProps<{
  modelValue: boolean;
  title: string;
  initialGroup?: DetailGroup;
  loadPage: (group: DetailGroup | undefined, page: number, pageSize: number) => Promise<DetailPage>;
  exportDetails?: () => Promise<void>;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
const activeGroup = ref<DetailGroup>();
const page = ref(1);
const pageSize = 20;
const data = ref<DetailPage>();
const loading = ref(false);
const exporting = ref(false);
const error = ref('');
const search = ref('');
const confirmExport = ref(false);

const open = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});
const groups = computed<DetailGroupDescriptor[]>(() => data.value?.groups ?? []);
const totalPages = computed(() =>
  data.value ? Math.max(1, Math.ceil(data.value.rowCount / data.value.pageSize)) : 1,
);
const visibleKeys = computed(() => visibleDetailKeys(data.value?.rows[0]));
const visibleRows = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  const rows = data.value?.rows ?? [];
  if (!keyword) return rows;
  return rows.filter((row) =>
    visibleKeys.value.some((key) =>
      String(row[key] ?? '')
        .toLocaleLowerCase()
        .includes(keyword),
    ),
  );
});

async function load(group: DetailGroup | undefined, targetPage = 1) {
  loading.value = true;
  error.value = '';
  try {
    const result = await props.loadPage(group, targetPage, pageSize);
    data.value = result;
    activeGroup.value = result.group;
    page.value = result.page;
    search.value = '';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载明细失败';
  } finally {
    loading.value = false;
  }
}

function changeGroup(group: unknown) {
  void load(group as DetailGroup, 1);
}

async function exportWorkbook() {
  if (!props.exportDetails) return;
  exporting.value = true;
  error.value = '';
  try {
    await props.exportDetails();
    confirmExport.value = false;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '导出明细失败';
  } finally {
    exporting.value = false;
  }
}

watch(open, (value) => {
  if (value) void load(props.initialGroup, 1);
  else {
    data.value = undefined;
    error.value = '';
  }
});
</script>

<template>
  <v-dialog v-model="open" max-width="1120" scrollable>
    <v-card rounded="lg">
      <v-card-title class="d-flex align-center ga-2 px-5 py-3">
        <v-icon icon="mdi-table-eye" color="primary" size="22" />
        <span class="text-title-medium">{{ title }}</span>
        <v-spacer />
        <v-btn
          v-if="exportDetails"
          prepend-icon="mdi-microsoft-excel"
          variant="tonal"
          size="small"
          @click="confirmExport = true"
        >
          导出明细 Excel
        </v-btn>
        <v-btn icon="mdi-close" variant="text" size="small" @click="open = false" />
      </v-card-title>
      <v-divider />

      <v-tabs
        v-if="groups.length"
        :model-value="activeGroup"
        density="compact"
        class="px-4"
        show-arrows
        @update:model-value="changeGroup"
      >
        <v-tab v-for="item in groups" :key="item.key" :value="item.key">
          {{ item.label }}
          <v-chip size="x-small" variant="tonal" class="ml-2">{{ item.rowCount }}</v-chip>
        </v-tab>
      </v-tabs>

      <v-card-text class="pa-5">
        <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">
          {{ error }}
        </v-alert>
        <div v-if="loading" class="d-flex flex-column align-center py-10 ga-3">
          <v-progress-circular indeterminate color="primary" />
          <span class="text-body-small text-medium-emphasis">正在加载并核对指标明细…</span>
          <span class="text-label-small text-disabled">
            首次打开会保存与本次指标结果一致的明细，后续查看和导出将直接复用
          </span>
        </div>
        <template v-else-if="data">
          <div class="d-flex flex-wrap align-center ga-3 mb-3">
            <span class="text-body-small text-medium-emphasis">共 {{ data.rowCount }} 条</span>
            <v-chip v-if="data.snapshotReused" size="x-small" variant="tonal">已复用快照</v-chip>
            <v-spacer />
            <v-text-field
              v-model="search"
              prepend-inner-icon="mdi-magnify"
              placeholder="搜索本页患者、就诊号或科室"
              density="compact"
              variant="outlined"
              hide-details
              clearable
              style="max-width: 320px"
            />
          </div>
          <div class="detail-table-wrap">
            <v-table density="compact" class="detail-table">
              <thead>
                <tr>
                  <th v-for="key in visibleKeys" :key="key">{{ detailFieldLabel(key) }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, index) in visibleRows"
                  :key="String(row.__detail_id ?? index)"
                  :class="{
                    'median-row': row.__is_median_sample === 1 || row.__is_median_sample === true,
                  }"
                >
                  <td v-for="key in visibleKeys" :key="key">
                    {{ formatDetailCell(key, row[key]) }}
                  </td>
                </tr>
                <tr v-if="visibleRows.length === 0">
                  <td
                    :colspan="Math.max(visibleKeys.length, 1)"
                    class="text-center text-medium-emphasis py-8"
                  >
                    {{ data.rows.length ? '本页没有匹配记录' : '该分组暂无明细数据' }}
                  </td>
                </tr>
              </tbody>
            </v-table>
          </div>
          <div v-if="totalPages > 1" class="d-flex justify-center align-center ga-3 mt-4">
            <v-btn
              size="small"
              variant="tonal"
              :disabled="page <= 1"
              @click="load(activeGroup, page - 1)"
              >上一页</v-btn
            >
            <span class="text-body-small">第 {{ page }} / {{ totalPages }} 页</span>
            <v-btn
              size="small"
              variant="tonal"
              :disabled="page >= totalPages"
              @click="load(activeGroup, page + 1)"
              >下一页</v-btn
            >
          </div>
        </template>
      </v-card-text>
    </v-card>

    <v-dialog v-model="confirmExport" max-width="480" persistent>
      <v-card title="确认导出患者级明细">
        <v-card-text
          >文件包含患者级业务数据，请确认仅用于授权范围内的数据核对。导出将覆盖当前冻结快照的全部分组，不受本页搜索条件影响。</v-card-text
        >
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="exporting" @click="confirmExport = false">取消</v-btn>
          <v-btn color="primary" variant="flat" :loading="exporting" @click="exportWorkbook"
            >确认并导出</v-btn
          >
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-dialog>
</template>

<style lang="scss" scoped src="./IndicatorDetailDialog.scss"></style>
