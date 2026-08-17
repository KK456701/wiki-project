<script setup lang="ts">
import { format } from 'date-fns';
import type { ErrorLog } from '@/monitor/types';
import type { ExportFormat } from '../types';
import {
  ERROR_TYPE_LABEL,
  ERROR_TYPE_COLOR,
  ERROR_TYPE_ICON,
  LOG_TABLE_HEADERS,
  PAGE_SIZE_OPTIONS,
  MESSAGE_MAX_WIDTH,
  EXPORT_FORMAT_OPTIONS,
} from '../constants';

defineProps<{
  logs: ErrorLog[];
  loading: boolean;
  hasActiveFilter: boolean;
}>();

const emit = defineEmits<{
  (e: 'view-detail', log: ErrorLog): void;
  (e: 'export-logs', format: ExportFormat): void;
  (e: 'reset-filter'): void;
}>();

const page = defineModel<number>('page', { required: true });
const pageSize = defineModel<number>('pageSize', { required: true });

function formatTime(ts: number): string {
  return format(ts, 'yyyy-MM-dd HH:mm:ss');
}

function onRowClick(_event: Event, { item }: { item: ErrorLog }): void {
  emit('view-detail', item);
}

/** 让表格行支持键盘聚焦与 Enter/Space 触发，补齐可访问性 */
function rowProps({ item }: { item: ErrorLog }) {
  return {
    tabindex: 0,
    class: 'log-row',
    'aria-label': `查看日志 #${item.id} 的详情`,
    onKeydown: (event: KeyboardEvent) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        emit('view-detail', item);
      }
    },
  };
}
</script>

<template>
  <v-card variant="outlined" rounded="lg">
    <v-toolbar density="comfortable" color="transparent">
      <v-toolbar-title class="text-body-large font-weight-medium">错误日志列表</v-toolbar-title>

      <v-menu location="bottom end">
        <template #activator="{ props: menuProps }">
          <v-btn
            v-bind="menuProps"
            variant="text"
            size="small"
            prepend-icon="mdi-tray-arrow-down"
            append-icon="mdi-menu-down"
            :disabled="logs.length === 0"
          >
            导出
          </v-btn>
        </template>
        <v-list density="compact" min-width="200">
          <v-list-subheader>导出当前筛选结果（{{ logs.length }} 条）</v-list-subheader>
          <v-list-item
            v-for="option in EXPORT_FORMAT_OPTIONS"
            :key="option.value"
            :prepend-icon="option.icon"
            :title="option.label"
            @click="emit('export-logs', option.value)"
          />
        </v-list>
      </v-menu>
    </v-toolbar>

    <v-divider />

    <v-data-table
      v-model:page="page"
      v-model:items-per-page="pageSize"
      :headers="LOG_TABLE_HEADERS"
      :items="logs"
      :loading="loading"
      :items-per-page-options="PAGE_SIZE_OPTIONS"
      :sort-by="[{ key: 'timestamp', order: 'desc' }]"
      :row-props="rowProps"
      item-value="id"
      hover
      density="comfortable"
      items-per-page-text="每页条数"
      @click:row="onRowClick"
    >
      <!-- 类型 -->
      <template #item.type="{ item }">
        <v-chip
          size="small"
          variant="tonal"
          :color="ERROR_TYPE_COLOR[item.type]"
          :prepend-icon="ERROR_TYPE_ICON[item.type]"
        >
          {{ ERROR_TYPE_LABEL[item.type] }}
        </v-chip>
      </template>

      <!-- 错误信息 + URL -->
      <template #item.message="{ item }">
        <div class="py-1" :style="{ maxWidth: MESSAGE_MAX_WIDTH }">
          <div class="text-body-medium text-truncate" :title="item.message">{{ item.message }}</div>
          <div class="text-body-small text-medium-emphasis text-truncate" :title="item.url">
            {{ item.url }}
          </div>
        </div>
      </template>

      <!-- 时间 -->
      <template #item.timestamp="{ item }">
        <span class="text-body-small text-medium-emphasis tabular-nums">
          {{ formatTime(item.timestamp) }}
        </span>
      </template>

      <!-- ID -->
      <template #item.id="{ item }">
        <span class="text-body-small text-medium-emphasis tabular-nums">{{ item.id }}</span>
      </template>

      <!-- 操作 -->
      <template #item.actions="{ item }">
        <v-tooltip text="查看详情" location="top">
          <template #activator="{ props: tooltipProps }">
            <v-btn
              v-bind="tooltipProps"
              variant="text"
              size="small"
              icon="mdi-eye-outline"
              density="comfortable"
              :aria-label="`查看日志 #${item.id} 的详情`"
              @click.stop="emit('view-detail', item)"
            />
          </template>
        </v-tooltip>
      </template>

      <!-- 加载中：骨架屏，避免整块替换导致的布局跳动 -->
      <template #loading>
        <v-skeleton-loader type="table-row@6" />
      </template>

      <!-- 空态 -->
      <template #no-data>
        <v-empty-state
          :icon="hasActiveFilter ? 'mdi-filter-off-outline' : 'mdi-shield-check-outline'"
          :title="hasActiveFilter ? '没有匹配的日志' : '暂无错误日志'"
          :text="
            hasActiveFilter
              ? '当前筛选条件下没有找到任何记录，试试调整条件。'
              : '应用运行正常，尚未捕获到任何前端错误。'
          "
          class="py-8"
        >
          <template v-if="hasActiveFilter" #actions>
            <v-btn variant="tonal" size="small" @click="emit('reset-filter')">重置筛选</v-btn>
          </template>
        </v-empty-state>
      </template>
    </v-data-table>
  </v-card>
</template>

<style lang="scss" scoped>
.tabular-nums {
  font-variant-numeric: tabular-nums;
}

:deep(.log-row) {
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid rgb(var(--v-theme-primary));
    outline-offset: -2px;
  }
}
</style>
