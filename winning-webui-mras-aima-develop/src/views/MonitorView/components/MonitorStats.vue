<script setup lang="ts">
import { computed } from 'vue';
import type { ErrorType } from '@/monitor/constants';
import type { ErrorTypeStat } from '../types';
import {
  ERROR_TYPE_LABEL,
  ERROR_TYPE_COLOR,
  ERROR_TYPE_ICON,
  SDK_STATUS_LABEL,
} from '../constants';

const props = defineProps<{
  stats: ErrorTypeStat[];
  totalCount: number;
  filteredCount: number;
  hasActiveFilter: boolean;
  selectedTypes: ErrorType[];
  sdkEnabled: boolean;
  loading: boolean;
}>();

const emit = defineEmits<{
  (e: 'toggle-sdk'): void;
  (e: 'refresh'): void;
  (e: 'select-type', type: ErrorType): void;
}>();

/** 各类型占总数的百分比，用于分布条 */
const statsWithRatio = computed(() =>
  props.stats.map((stat) => ({
    ...stat,
    ratio: props.totalCount > 0 ? Math.round((stat.count / props.totalCount) * 100) : 0,
    active: props.selectedTypes.includes(stat.type),
  })),
);

const sdkStatusText = computed(() =>
  props.sdkEnabled ? SDK_STATUS_LABEL.ENABLED : SDK_STATUS_LABEL.DISABLED,
);
</script>

<template>
  <!-- 概览卡片：3 列均分，恰好填满 12 栅格 -->
  <v-row class="mb-2">
    <v-col cols="12" sm="6" md="4">
      <v-card
        variant="tonal"
        :color="sdkEnabled ? 'success' : 'surface-variant'"
        class="h-100"
        rounded="lg"
      >
        <v-card-item>
          <template #prepend>
            <v-avatar :color="sdkEnabled ? 'success' : 'surface-variant'" size="40" variant="flat">
              <v-icon icon="mdi-pulse" size="22" />
            </v-avatar>
          </template>
          <v-card-subtitle class="text-body-small pl-0">SDK 状态</v-card-subtitle>
          <v-card-title class="text-headline-small font-weight-bold pl-0 pt-0">
            {{ sdkStatusText }}
          </v-card-title>
        </v-card-item>

        <v-card-actions class="pt-0">
          <v-btn
            variant="text"
            size="small"
            :color="sdkEnabled ? 'error' : 'success'"
            :prepend-icon="sdkEnabled ? 'mdi-pause-circle-outline' : 'mdi-play-circle-outline'"
            @click="emit('toggle-sdk')"
          >
            {{ sdkEnabled ? '暂停监控' : '开启监控' }}
          </v-btn>
          <v-spacer />
          <v-tooltip text="刷新数据" location="top">
            <template #activator="{ props: tooltipProps }">
              <v-btn
                v-bind="tooltipProps"
                variant="text"
                size="small"
                icon="mdi-refresh"
                aria-label="刷新数据"
                :loading="loading"
                @click="emit('refresh')"
              />
            </template>
          </v-tooltip>
        </v-card-actions>
      </v-card>
    </v-col>

    <v-col cols="12" sm="6" md="4">
      <v-card variant="outlined" class="h-100" rounded="lg">
        <v-card-item>
          <template #prepend>
            <v-avatar color="primary" size="40" variant="flat">
              <v-icon icon="mdi-bug-outline" size="22" />
            </v-avatar>
          </template>
          <v-card-subtitle class="text-body-small pl-0">错误总数</v-card-subtitle>
          <v-card-title class="text-headline-medium font-weight-bold pl-0 pt-0 stat-number">
            <v-skeleton-loader v-if="loading" type="text" width="72" class="pa-0" />
            <template v-else>{{ totalCount }}</template>
          </v-card-title>
        </v-card-item>
        <v-card-text class="text-body-small text-medium-emphasis pt-0">
          本地库中已留存的全部日志
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" md="4">
      <v-card variant="outlined" class="h-100" rounded="lg">
        <v-card-item>
          <template #prepend>
            <v-avatar :color="hasActiveFilter ? 'primary' : 'surface-variant'" size="40">
              <v-icon icon="mdi-filter-check-outline" size="22" />
            </v-avatar>
          </template>
          <v-card-subtitle class="text-body-small pl-0">当前筛选命中</v-card-subtitle>
          <v-card-title class="text-headline-medium font-weight-bold pl-0 pt-0 stat-number">
            <v-skeleton-loader v-if="loading" type="text" width="72" class="pa-0" />
            <template v-else>{{ filteredCount }}</template>
          </v-card-title>
        </v-card-item>
        <v-card-text class="text-body-small text-medium-emphasis pt-0">
          {{ hasActiveFilter ? '已应用筛选条件' : '未应用筛选条件' }}
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>

  <!-- 类型分布：自适应网格，避免 5 个类型硬凑 12 栅格导致换行错位 -->
  <v-card variant="outlined" class="mb-4" rounded="lg">
    <v-card-item density="compact">
      <v-card-title class="text-body-large font-weight-medium">错误类型分布</v-card-title>
      <template #append>
        <span class="text-body-small text-medium-emphasis">点击可快速筛选</span>
      </template>
    </v-card-item>

    <v-card-text class="pt-0">
      <v-skeleton-loader v-if="loading" type="list-item-two-line@2" class="pa-0" />

      <div v-else class="stat-grid" role="group" aria-label="错误类型分布">
        <button
          v-for="stat in statsWithRatio"
          :key="stat.type"
          type="button"
          class="stat-item rounded-lg pa-3 text-left"
          :class="{ 'stat-item--active': stat.active }"
          :aria-pressed="stat.active"
          :aria-label="`${ERROR_TYPE_LABEL[stat.type]}，${stat.count} 条，占比 ${stat.ratio}%`"
          @click="emit('select-type', stat.type)"
        >
          <div class="d-flex align-center ga-2 mb-2">
            <v-icon
              :icon="ERROR_TYPE_ICON[stat.type]"
              :color="ERROR_TYPE_COLOR[stat.type]"
              size="18"
            />
            <span class="text-body-small text-medium-emphasis text-truncate">
              {{ ERROR_TYPE_LABEL[stat.type] }}
            </span>
            <v-spacer />
            <span class="text-label-large font-weight-bold stat-number">{{ stat.count }}</span>
          </div>
          <v-progress-linear
            :model-value="stat.ratio"
            :color="ERROR_TYPE_COLOR[stat.type]"
            height="4"
            rounded
            bg-opacity="0.12"
          />
        </button>
      </div>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
/* 数字等宽，避免数值变化时布局抖动 */
.stat-number {
  font-variant-numeric: tabular-nums;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.stat-item {
  width: 100%;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  background-color: transparent;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;

  &:hover {
    background-color: rgba(var(--v-theme-on-surface), var(--v-hover-opacity));
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--v-theme-primary));
    outline-offset: 2px;
  }

  &--active {
    border-color: rgb(var(--v-theme-primary));
    background-color: rgba(var(--v-theme-primary), 0.06);
  }
}
</style>
