<script setup lang="ts">
import { ref, watch } from 'vue';
import type { BatchReportSnapshot, ReportDownloadFormat } from '@/types/chat';
import { createReportSnapshot, downloadReport } from '@/services/chat';
import ReportTaskTable from './ReportTaskTable.vue';

const props = defineProps<{
  open: boolean;
  batchRunId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

/** 报告数据 */
const report = ref<BatchReportSnapshot | null>(null);
/** 加载中 */
const loading = ref(false);
/** 错误信息 */
const errorMessage = ref('');
/** 下载中格式 */
const downloadingFormat = ref<string | null>(null);

/** 下载格式选项 */
const DOWNLOAD_FORMATS: { format: ReportDownloadFormat; label: string; icon: string }[] = [
  { format: 'docx', label: 'Word', icon: 'mdi-file-word-outline' },
  { format: 'pdf', label: 'PDF', icon: 'mdi-file-pdf-box' },
  { format: 'xlsx', label: 'Excel', icon: 'mdi-file-excel-outline' },
];

/** 当抽屉打开且 batchRunId 有效时，创建报告快照 */
watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen || !props.batchRunId) {
      report.value = null;
      errorMessage.value = '';
      return;
    }

    loading.value = true;
    errorMessage.value = '';
    report.value = null;
    try {
      report.value = await createReportSnapshot(props.batchRunId);
    } catch (err) {
      errorMessage.value = err instanceof Error ? err.message : '创建报告快照失败';
    } finally {
      loading.value = false;
    }
  },
);

/** 下载报告 */
async function handleDownload(format: ReportDownloadFormat) {
  if (!report.value) return;
  downloadingFormat.value = format;
  try {
    await downloadReport(report.value.reportId, format);
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '下载失败';
  } finally {
    downloadingFormat.value = null;
  }
}
</script>

<template>
  <v-navigation-drawer
    :model-value="open"
    temporary
    location="right"
    width="800"
    @update:model-value="emit('update:open', $event)"
  >
    <template #prepend>
      <div class="d-flex align-center justify-space-between pa-4 border-b flex-shrink-0">
        <div>
          <h2 class="text-headline-small mb-1">📄 完整报告</h2>
          <p class="text-body-medium text-medium-emphasis mb-0">批次指标核算报告详情</p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="small" @click="emit('update:open', false)" />
      </div>
    </template>

    <div class="pa-4 overflow-y-auto flex-1-1-0">
      <!-- 加载中 -->
      <div v-if="loading" class="d-flex flex-column align-center justify-center py-8">
        <v-progress-circular indeterminate color="primary" size="48" class="mb-4" />
        <span class="text-body-medium text-medium-emphasis">正在生成报告快照...</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="errorMessage" class="d-flex flex-column align-center py-8">
        <v-icon icon="mdi-alert-circle-outline" color="error" size="48" class="mb-3" />
        <span class="text-body-large text-error mb-4">{{ errorMessage }}</span>
        <v-btn
          variant="tonal"
          color="primary"
          @click="
            report = null;
            loading = false;
            errorMessage = '';
          "
        >
          重试
        </v-btn>
      </div>

      <!-- 报告内容 -->
      <template v-else-if="report">
        <!-- 草稿标签 -->
        <v-alert
          v-if="report.reportStatus === 'DRAFT'"
          type="warning"
          variant="tonal"
          density="compact"
          class="mb-4"
        >
          此报告为草稿版本，包含非成功指标或质量异常，不建议直接对外分发
        </v-alert>

        <!-- 报告元信息 -->
        <v-card variant="outlined" class="mb-4">
          <v-card-text class="pa-3">
            <div class="d-flex flex-wrap ga-4 text-body-medium">
              <div>
                <span class="text-medium-emphasis">报告版本：</span>
                <v-chip
                  :color="report.reportStatus === 'FORMAL' ? 'success' : 'warning'"
                  size="x-small"
                  label
                  class="ml-1"
                >
                  V{{ report.version }} · {{ report.reportStatus === 'FORMAL' ? '正式' : '草稿' }}
                </v-chip>
              </div>
              <div>
                <span class="text-medium-emphasis">批次：</span>
                <code class="text-body-small">{{ report.batchRunId }}</code>
              </div>
              <div>
                <span class="text-medium-emphasis">统计周期：</span>
                <span>{{ report.statStart }} 至 {{ report.statEnd }}</span>
              </div>
              <div>
                <span class="text-medium-emphasis">生成时间：</span>
                <span>{{ report.generatedAt }}</span>
              </div>
            </div>
          </v-card-text>
        </v-card>

        <!-- 计数汇总 -->
        <v-card variant="outlined" class="mb-4">
          <v-card-title class="text-label-large py-2 px-3">
            指标汇总（共 {{ report.total }} 项）
          </v-card-title>
          <v-card-text class="pa-3 pt-0">
            <div class="d-flex ga-4">
              <v-chip color="success" size="small" variant="tonal">
                成功 {{ report.counts.success }}
              </v-chip>
              <v-chip color="warning" size="small" variant="tonal">
                无样本 {{ report.counts.noSample }}
              </v-chip>
              <v-chip color="error" size="small" variant="tonal">
                失败 {{ report.counts.failed }}
              </v-chip>
            </div>
          </v-card-text>
        </v-card>

        <!-- 任务列表（含口径/链路/明细弹窗） -->
        <ReportTaskTable :report="report" />

        <!-- 免责声明 -->
        <v-card variant="outlined" class="bg-surface">
          <v-card-text class="text-body-small text-medium-emphasis pa-3">
            {{ report.statement }}
          </v-card-text>
        </v-card>
      </template>

      <!-- 空状态（未请求但无 batchRunId） -->
      <div v-else class="d-flex flex-column align-center py-8">
        <v-icon
          icon="mdi-file-document-outline"
          size="48"
          color="on-surface-variant"
          class="mb-3"
        />
        <span class="text-body-medium text-medium-emphasis">暂无报告数据</span>
      </div>
    </div>

    <!-- 下载按钮（固定在抽屉底部） -->
    <template #append>
      <div v-if="report" class="d-flex justify-center ga-2 pa-3 border-t bg-surface flex-shrink-0">
        <v-btn
          v-for="fmt in DOWNLOAD_FORMATS"
          :key="fmt.format"
          variant="tonal"
          color="primary"
          :prepend-icon="fmt.icon"
          :loading="downloadingFormat === fmt.format"
          @click="handleDownload(fmt.format)"
        >
          下载 {{ fmt.label }}
        </v-btn>
      </div>
    </template>
  </v-navigation-drawer>
</template>
