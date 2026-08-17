<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { ErrorType } from '@/monitor/constants';
import { ALL_ERROR_TYPES } from '@/monitor/constants';
import { useMonitorData } from './composables/useMonitorData';
import { useLogExport } from './composables/useLogExport';
import type { ErrorLog, ExportFormat, FeedbackMessage } from './types';
import { SNACKBAR_TIMEOUT } from './constants';
import MonitorStats from './components/MonitorStats.vue';
import MonitorFilter from './components/MonitorFilter.vue';
import MonitorLogTable from './components/MonitorLogTable.vue';
import MonitorLogDetail from './components/MonitorLogDetail.vue';

const {
  filteredLogs,
  stats,
  loading,
  error,
  sdkEnabled,
  totalCount,
  filteredCount,
  hasActiveFilter,
  page,
  pageSize,
  selectedTypes,
  keyword,
  startTime,
  endTime,
  loadLogs,
  refreshSDKStatus,
  toggleSDK,
  clearAllLogs,
  resetFilter,
} = useMonitorData();

const { exportLogs } = useLogExport();

const selectedLog = ref<ErrorLog | null>(null);
const clearDialogVisible = ref(false);
const clearing = ref(false);

// ── 反馈提示 ────────────────────────────────────────────────────

const feedbackVisible = ref(false);
const feedback = ref<FeedbackMessage>({ text: '', color: 'success', icon: 'mdi-check-circle' });

function notify(text: string, color: FeedbackMessage['color'] = 'success'): void {
  const iconMap: Record<FeedbackMessage['color'], string> = {
    success: 'mdi-check-circle-outline',
    error: 'mdi-alert-circle-outline',
    info: 'mdi-information-outline',
  };
  feedback.value = { text, color, icon: iconMap[color] };
  feedbackVisible.value = true;
}

// ── 交互 ────────────────────────────────────────────────────────

function onToggleSDK(): void {
  const enabled = toggleSDK();
  notify(enabled ? '监控已开启' : '监控已暂停', enabled ? 'success' : 'info');
}

async function onRefresh(): Promise<void> {
  refreshSDKStatus();
  await loadLogs();
  if (!error.value) notify('数据已刷新', 'info');
}

/** 从统计卡片点选类型：单独查看该类型，再次点击恢复全部 */
function onSelectType(type: ErrorType): void {
  const isOnlyThisType = selectedTypes.value.length === 1 && selectedTypes.value[0] === type;
  selectedTypes.value = isOnlyThisType ? [...ALL_ERROR_TYPES] : [type];
}

async function onConfirmClear(): Promise<void> {
  clearing.value = true;
  const ok = await clearAllLogs();
  clearing.value = false;
  clearDialogVisible.value = false;
  notify(ok ? '日志已清空' : '清空失败，请重试', ok ? 'success' : 'error');
}

function onExport(exportFormat: ExportFormat): void {
  try {
    const count = exportLogs(filteredLogs.value, exportFormat);
    notify(`已导出 ${count} 条日志（${exportFormat.toUpperCase()}）`);
  } catch {
    notify('导出失败，请重试', 'error');
  }
}

onMounted(loadLogs);
</script>

<template>
  <v-container fluid class="monitor-view pa-4">
    <!-- 页头 -->
    <header class="d-flex align-center flex-wrap ga-3 mb-4">
      <div>
        <h1 class="text-headline-medium font-weight-bold">前端监控</h1>
        <p class="text-body-small text-medium-emphasis mb-0">
          自动捕获运行时错误，数据仅保存在当前浏览器本地
        </p>
      </div>

      <v-spacer />

      <v-btn
        variant="outlined"
        size="small"
        color="error"
        prepend-icon="mdi-delete-sweep-outline"
        :disabled="totalCount === 0 || loading"
        @click="clearDialogVisible = true"
      >
        清空日志
      </v-btn>
    </header>

    <MonitorStats
      :stats="stats"
      :total-count="totalCount"
      :filtered-count="filteredCount"
      :has-active-filter="hasActiveFilter"
      :selected-types="selectedTypes"
      :sdk-enabled="sdkEnabled"
      :loading="loading"
      @toggle-sdk="onToggleSDK"
      @refresh="onRefresh"
      @select-type="onSelectType"
    />

    <MonitorFilter
      v-model:selected-types="selectedTypes"
      v-model:keyword="keyword"
      v-model:start-time="startTime"
      v-model:end-time="endTime"
      :loading="loading"
      :has-active-filter="hasActiveFilter"
      :result-count="filteredCount"
      @reset="resetFilter"
    />

    <v-alert
      v-if="error"
      type="error"
      variant="tonal"
      closable
      class="mb-4"
      rounded="lg"
      @click:close="error = null"
    >
      {{ error }}
    </v-alert>

    <MonitorLogTable
      v-model:page="page"
      v-model:page-size="pageSize"
      :logs="filteredLogs"
      :loading="loading"
      :has-active-filter="hasActiveFilter"
      @view-detail="selectedLog = $event"
      @export-logs="onExport"
      @reset-filter="resetFilter"
    />

    <MonitorLogDetail v-model:log="selectedLog" />

    <!-- 清空确认：破坏性操作需二次确认 -->
    <v-dialog v-model="clearDialogVisible" max-width="420" persistent>
      <v-card rounded="lg">
        <v-card-item>
          <template #prepend>
            <v-avatar color="error" variant="tonal" size="40">
              <v-icon icon="mdi-alert-outline" />
            </v-avatar>
          </template>
          <v-card-title class="text-headline-small">清空全部日志？</v-card-title>
        </v-card-item>

        <v-card-text class="text-body-medium text-medium-emphasis">
          将永久删除本地留存的 {{ totalCount }} 条错误日志，该操作无法撤销。
          建议先导出备份后再清空。
        </v-card-text>

        <v-card-actions class="px-4 pb-3">
          <v-spacer />
          <v-btn variant="text" :disabled="clearing" @click="clearDialogVisible = false">
            取消
          </v-btn>
          <v-btn color="error" variant="flat" :loading="clearing" @click="onConfirmClear">
            确认清空
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar
      v-model="feedbackVisible"
      :timeout="SNACKBAR_TIMEOUT"
      :color="feedback.color"
      location="bottom right"
      rounded="lg"
    >
      <div class="d-flex align-center ga-2">
        <v-icon :icon="feedback.icon" size="18" />
        <span>{{ feedback.text }}</span>
      </div>
    </v-snackbar>
  </v-container>
</template>

<style lang="scss" scoped>
.monitor-view {
  max-width: 1400px;
}
</style>
