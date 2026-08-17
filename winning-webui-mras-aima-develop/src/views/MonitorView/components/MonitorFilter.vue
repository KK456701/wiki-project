<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import type { ErrorType } from '@/monitor/constants';
import { ALL_ERROR_TYPES } from '@/monitor/constants';
import {
  ERROR_TYPE_LABEL,
  ERROR_TYPE_COLOR,
  ERROR_TYPE_ICON,
  KEYWORD_DEBOUNCE_MS,
} from '../constants';

const props = defineProps<{
  loading: boolean;
  hasActiveFilter: boolean;
  resultCount: number;
}>();

const emit = defineEmits<{ (e: 'reset'): void }>();

// 双向绑定，筛选结果由上层 computed 派生，无需再手动触发查询
const selectedTypes = defineModel<ErrorType[]>('selectedTypes', { required: true });
const keyword = defineModel<string>('keyword', { required: true });
const startTime = defineModel<number | undefined>('startTime', { required: true });
const endTime = defineModel<number | undefined>('endTime', { required: true });

const showDateFilter = ref(false);

/** 输入框本地值，防抖后再同步给上层，避免每次按键都重算筛选 */
const keywordInput = ref(keyword.value);

const applyKeyword = useDebounceFn((val: string) => {
  keyword.value = val;
}, KEYWORD_DEBOUNCE_MS);

function onKeywordInput(val: string): void {
  keywordInput.value = val;
  applyKeyword(val);
}

function clearKeyword(): void {
  keywordInput.value = '';
  keyword.value = '';
}

// ── 日期范围 ────────────────────────────────────────────────────

/** 当天 00:00:00.000 */
function dayStart(date: Date): number {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** 当天 23:59:59.999 */
function dayEnd(date: Date): number {
  const d = new Date(date);
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

/**
 * 时间戳 ↔ Date 的双向转换。
 * 直接以上层状态为唯一数据源，不再维护本地副本，杜绝父子状态不同步。
 */
const startDate = computed<Date | null>({
  get: () => (startTime.value !== undefined ? new Date(startTime.value) : null),
  set: (val) => {
    startTime.value = val ? dayStart(val) : undefined;
  },
});

const endDate = computed<Date | null>({
  get: () => (endTime.value !== undefined ? new Date(endTime.value) : null),
  set: (val) => {
    endTime.value = val ? dayEnd(val) : undefined;
  },
});

/**
 * 起止校验。
 * 比较的是「开始当天 00:00」与「结束当天 23:59」，因此选择同一天属于合法区间。
 */
const dateRangeError = computed(() =>
  startTime.value !== undefined && endTime.value !== undefined && startTime.value > endTime.value
    ? '开始日期不能晚于结束日期'
    : '',
);

/** 快捷时间区间 */
const datePresets = [
  { label: '今天', days: 0 },
  { label: '近 7 天', days: 6 },
  { label: '近 30 天', days: 29 },
];

function applyPreset(days: number): void {
  const now = new Date();
  const from = new Date(now);
  from.setDate(from.getDate() - days);
  startTime.value = dayStart(from);
  endTime.value = dayEnd(now);
  showDateFilter.value = true;
}

function clearDateRange(): void {
  startTime.value = undefined;
  endTime.value = undefined;
}

// ── 错误类型 ────────────────────────────────────────────────────

const isAllTypesSelected = computed(() => selectedTypes.value.length === ALL_ERROR_TYPES.length);

function toggleAllTypes(): void {
  selectedTypes.value = isAllTypesSelected.value ? [] : [...ALL_ERROR_TYPES];
}

/** 切换单个类型，确保一次性原子更新 selectedTypes，避免 v-chip-group 默认的多次事件 */
function toggleType(type: ErrorType): void {
  const idx = selectedTypes.value.indexOf(type);
  if (idx === -1) {
    selectedTypes.value = [...selectedTypes.value, type];
  } else {
    const next = [...selectedTypes.value];
    next.splice(idx, 1);
    selectedTypes.value = next;
  }
}

// ── 生效条件摘要 ────────────────────────────────────────────────

const dateSummary = computed(() => {
  const fmt = (ts: number) => new Date(ts).toLocaleDateString('zh-CN');
  if (startTime.value !== undefined && endTime.value !== undefined) {
    return `${fmt(startTime.value)} ~ ${fmt(endTime.value)}`;
  }
  if (startTime.value !== undefined) return `${fmt(startTime.value)} 起`;
  if (endTime.value !== undefined) return `截至 ${fmt(endTime.value)}`;
  return '';
});

const resultSummary = computed(() =>
  props.hasActiveFilter ? `命中 ${props.resultCount} 条` : `共 ${props.resultCount} 条`,
);
</script>

<template>
  <v-card variant="outlined" class="mb-4" rounded="lg">
    <v-card-text class="d-flex flex-column ga-3 py-3">
      <!-- 错误类型 -->
      <div class="d-flex align-center flex-wrap ga-2">
        <span id="type-filter-label" class="text-body-small text-medium-emphasis">错误类型</span>

        <v-chip
          size="small"
          variant="outlined"
          :color="isAllTypesSelected ? 'primary' : undefined"
          :prepend-icon="isAllTypesSelected ? 'mdi-check-all' : 'mdi-select-all'"
          :aria-pressed="isAllTypesSelected"
          @click="toggleAllTypes"
        >
          {{ isAllTypesSelected ? '取消全选' : '全选' }}
        </v-chip>

        <v-divider vertical class="mx-1" />

        <div class="d-flex flex-wrap ga-2" role="group" aria-labelledby="type-filter-label">
          <v-chip
            v-for="type in ALL_ERROR_TYPES"
            :key="type"
            size="small"
            :variant="selectedTypes.includes(type) ? 'tonal' : 'outlined'"
            :color="selectedTypes.includes(type) ? ERROR_TYPE_COLOR[type] : undefined"
            :prepend-icon="ERROR_TYPE_ICON[type]"
            :aria-pressed="selectedTypes.includes(type)"
            role="button"
            tabindex="0"
            @click="toggleType(type)"
            @keydown.enter.prevent="toggleType(type)"
            @keydown.space.prevent="toggleType(type)"
          >
            {{ ERROR_TYPE_LABEL[type] }}
          </v-chip>
        </div>
      </div>

      <!-- 搜索 + 操作 -->
      <div class="d-flex align-center flex-wrap ga-2">
        <v-text-field
          :model-value="keywordInput"
          density="compact"
          variant="outlined"
          label="搜索"
          placeholder="错误信息 / URL / 用户"
          hide-details
          clearable
          single-line
          prepend-inner-icon="mdi-magnify"
          class="search-field"
          @update:model-value="onKeywordInput"
          @click:clear="clearKeyword"
        />

        <v-btn
          variant="tonal"
          size="small"
          :color="startTime !== undefined || endTime !== undefined ? 'primary' : undefined"
          prepend-icon="mdi-calendar-range-outline"
          :append-icon="showDateFilter ? 'mdi-chevron-up' : 'mdi-chevron-down'"
          :aria-expanded="showDateFilter"
          aria-controls="date-filter-panel"
          @click="showDateFilter = !showDateFilter"
        >
          时间范围
        </v-btn>

        <v-btn
          variant="text"
          size="small"
          prepend-icon="mdi-filter-remove-outline"
          :disabled="!hasActiveFilter"
          @click="emit('reset')"
        >
          重置筛选
        </v-btn>

        <v-spacer />

        <v-progress-circular v-if="loading" indeterminate color="primary" size="18" width="2" />
        <span v-else class="text-body-small text-medium-emphasis">{{ resultSummary }}</span>
      </div>

      <!-- 时间范围（折叠） -->
      <v-expand-transition>
        <div v-show="showDateFilter" id="date-filter-panel" class="d-flex flex-column ga-2 pt-1">
          <div class="d-flex align-center flex-wrap ga-2">
            <v-chip
              v-for="preset in datePresets"
              :key="preset.label"
              size="small"
              variant="outlined"
              prepend-icon="mdi-clock-fast"
              @click="applyPreset(preset.days)"
            >
              {{ preset.label }}
            </v-chip>
          </div>

          <div class="d-flex align-start flex-wrap ga-3">
            <v-date-input
              v-model="startDate"
              label="开始日期"
              density="compact"
              variant="outlined"
              prepend-icon=""
              prepend-inner-icon="mdi-calendar-start-outline"
              clearable
              hide-details="auto"
              :error-messages="dateRangeError"
              class="date-field"
            />
            <v-date-input
              v-model="endDate"
              label="结束日期"
              density="compact"
              variant="outlined"
              prepend-icon=""
              prepend-inner-icon="mdi-calendar-end-outline"
              clearable
              hide-details="auto"
              :error="dateRangeError !== ''"
              class="date-field"
            />
            <v-btn
              variant="text"
              size="small"
              class="mt-1"
              :disabled="startTime === undefined && endTime === undefined"
              @click="clearDateRange"
            >
              清除时间
            </v-btn>
          </div>
        </div>
      </v-expand-transition>

      <!-- 生效条件摘要 -->
      <div v-if="hasActiveFilter" class="d-flex align-center flex-wrap ga-2 pt-1">
        <span class="text-body-small text-medium-emphasis">生效条件</span>
        <v-chip
          v-if="keyword"
          size="x-small"
          variant="tonal"
          color="primary"
          closable
          prepend-icon="mdi-magnify"
          @click:close="clearKeyword"
        >
          {{ keyword }}
        </v-chip>
        <v-chip
          v-if="dateSummary"
          size="x-small"
          variant="tonal"
          color="primary"
          closable
          prepend-icon="mdi-calendar-range-outline"
          @click:close="clearDateRange"
        >
          {{ dateSummary }}
        </v-chip>
        <v-chip
          v-if="selectedTypes.length > 0 && !isAllTypesSelected"
          size="x-small"
          variant="tonal"
          color="primary"
          closable
          prepend-icon="mdi-shape-outline"
          @click:close="selectedTypes = [...ALL_ERROR_TYPES]"
        >
          {{ selectedTypes.length }} 种类型
        </v-chip>
      </div>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
.search-field {
  max-width: 320px;
  min-width: 200px;
}

.date-field {
  width: 200px;
}
</style>
