<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue';
import { format } from 'date-fns';
import { useClipboard } from '@vueuse/core';
import type { ChatMessage as ChatMessageType, BatchResultItem } from '@/types/chat';
import { MESSAGE_ROLE, MESSAGE_STATUS, CLARIFICATION_STATUS } from '@/types/chat';
import { renderMarkdown, parseExportMarkers, type ExportMarker } from '@/utils/markdown';
import {
  createIndicatorExport,
  createUploadComparisonExport,
  createDiagnosisReportExport,
  downloadIndicatorExport,
} from '@/services/export';
import BatchExecutiveSummary from './BatchExecutiveSummary.vue';

/**
 * 异步懒加载链路详情弹窗
 * @description TraceDetailDialog 仅在用户点击"链路详情"按钮时才加载，
 * 避免将其打包进 ChatMessage 的首屏 chunk。
 */
const TraceDetailDialog = defineAsyncComponent(() => import('./TraceDetailDialog.vue'));
const BatchReportDrawer = defineAsyncComponent(() => import('./BatchReportDrawer.vue'));
const ExecutionStepsDrawer = defineAsyncComponent(() => import('./ExecutionStepsDrawer.vue'));
const DiagnosisCaseCard = defineAsyncComponent(() => import('./diagnosis/DiagnosisCaseCard.vue'));

const props = defineProps<{
  message: ChatMessageType;
}>();

const isUser = computed(() => props.message.role === MESSAGE_ROLE.USER);
const isStreaming = computed(() => props.message.status === MESSAGE_STATUS.STREAMING);
const isError = computed(() => props.message.status === MESSAGE_STATUS.ERROR);
const isStopped = computed(() => props.message.status === MESSAGE_STATUS.STOPPED);

/** 排查任务锚点：content 形如 {{diagnosis_case:<caseId>}} 时渲染排查卡片 */
const DIAGNOSIS_CASE_RE = /^\{\{diagnosis_case:(.+)\}\}$/;
const diagnosisCaseId = computed(() => {
  if (isUser.value || !props.message.content) return null;
  const m = props.message.content.match(DIAGNOSIS_CASE_RE);
  return m ? m[1] : null;
});

// 澄清信息展示
const hasClarification = computed(
  () => !isUser.value && props.message.clarification && props.message.clarificationStatus,
);
const clarificationStatusText = computed(() => {
  const status = props.message.clarificationStatus;
  if (status === CLARIFICATION_STATUS.CONFIRMED) return '已确认';
  if (status === CLARIFICATION_STATUS.DISMISSED) return '已忽略';
  return '';
});

// 链路详情弹窗
const showTraceDetail = ref(false);
/** 有 traceId（流式过程）或有 runId（历史消息，可间接解析）时显示链路入口 */
const hasTraceId = computed(
  () => !isUser.value && (!!props.message.traceId || !!props.message.runId),
);

/** 导出标记（解析后从正文移除） */
const exportMarkers = computed<ExportMarker[]>(() => {
  if (isUser.value || !props.message.content) return [];
  return parseExportMarkers(props.message.content).markers;
});

/** 清洗后的正文（导出标记已移除） */
const cleanContent = computed(() => {
  if (isUser.value || !props.message.content) return props.message.content;
  return parseExportMarkers(props.message.content).cleanContent;
});

const renderedContent = computed(() => {
  if (isUser.value) return props.message.content;
  return renderMarkdown(cleanContent.value);
});

const statusText = computed(() => {
  if (isError.value) return props.message.errorMessage || '发生错误';
  if (isStreaming.value) return props.message.currentStage || '思考中...';
  if (isStopped.value) return '已停止生成';
  return '';
});

// 格式化时间
const formattedTime = computed(() => {
  if (!props.message.createdAt) return '';
  return format(new Date(props.message.createdAt), 'yyyy-MM-dd HH:mm');
});

// copied 状态直接驱动按钮反馈，无需额外 snackbar 提示（参考 MonitorLogDetail 复制交互）
const { copy, copied, isSupported } = useClipboard({ legacy: true });

/** 是否有批量结果需要渲染（优先于 Markdown，文档 batchResults §4） */
const hasBatchResults = computed(
  () => !isUser.value && props.message.batchResults && props.message.batchResults.length > 0,
);

/** 导出动作处理器：按标记类型调用对应导出接口并触发浏览器下载（A4 实装） */
const exporting = ref<string | null>(null);
const exportError = ref('');

async function handleExportAction(marker: ExportMarker) {
  if (exporting.value) return;
  // 患者级明细导出二次确认（对齐 readonly 防护）
  if (!window.confirm('导出可能包含患者级业务明细。确认仅在授权范围内使用并立即下载吗？')) return;
  exporting.value = marker.runId;
  exportError.value = '';
  try {
    let created;
    if (marker.type === 'diagnosis_export') {
      created = await createDiagnosisReportExport(marker.runId, true);
    } else if (marker.type === 'detail_export') {
      created = await createIndicatorExport(marker.runId, true);
    } else {
      created = await createUploadComparisonExport(marker.runId, marker.fileToken ?? '', true);
    }
    await downloadIndicatorExport(created);
  } catch (e) {
    exportError.value = e instanceof Error ? e.message : '导出失败，请重试';
  } finally {
    exporting.value = null;
  }
}

function handleQuickAction(_action: 'checklist' | 'quality_review') {
  // 后端动作端点（POST /api/agent/actions/analyze-batch）尚未接入，
  // 暂时以「功能建设中」反馈占位，避免按钮点击无任何响应（E 系列清理：移除原 console.log 占位）
  notifyFeatureBuilding();
}

/** 报告抽屉状态 */
const reportDrawerOpen = ref(false);
const reportBatchRunId = ref<string | null>(null);

/** 执行步骤抽屉状态 */
const stepsDrawerOpen = ref(false);
const stepsTraceId = ref<string | null>(null);
const stepsBatchRunId = ref<string | null>(null);

function handleViewReport(batchRunId: string) {
  reportBatchRunId.value = batchRunId;
  reportDrawerOpen.value = true;
}

function handleInspectSteps(traceId: string | null, batchRunId: string | null) {
  stepsTraceId.value = traceId ?? props.message.traceId ?? null;
  stepsBatchRunId.value = batchRunId ?? props.message.batchResults?.[0]?.batchRunId ?? null;
  stepsDrawerOpen.value = true;
}

function handleInspect(_item: BatchResultItem) {
  // 后端动作端点（POST /api/agent/actions/inspect-indicator）尚未接入，
  // 暂时以「功能建设中」反馈占位（E 系列清理：移除原 console.log 占位）
  notifyFeatureBuilding();
}

/** 轻量功能占位提示（未接入后端动作时给出明确反馈，而非静默无响应） */
const featureNoticeText = ref('');
const featureNoticeOpen = ref(false);
function notifyFeatureBuilding() {
  featureNoticeText.value = '该功能正在建设中，敬请期待';
  featureNoticeOpen.value = true;
}

// 代码块复制按钮（事件委托）
function onMarkdownClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  const btn = target.closest('.code-block-copy-btn') as HTMLButtonElement | null;
  if (!btn) return;

  const code = btn.dataset.code || '';
  if (!code) return;

  copy(code);
}
</script>

<template>
  <div
    class="message-wrapper"
    :class="{
      'message-wrapper--user': isUser,
      'message-wrapper--assistant': !isUser,
      'message-wrapper--stopped': isStopped,
    }"
  >
    <!-- 助手头像 -->
    <v-avatar v-if="!isUser" color="primary" size="36" class="message-avatar">
      <v-icon size="20">mdi-robot-happy</v-icon>
    </v-avatar>

    <!-- 消息内容 -->
    <div class="message-content">
      <template v-if="diagnosisCaseId">
        <DiagnosisCaseCard :case-id="diagnosisCaseId" />
      </template>
      <template v-else>
        <!-- 消息气泡 -->
        <div
          class="message-bubble w-100 overflow-auto"
          :class="{
            'message-bubble--user': isUser,
            'message-bubble--assistant': !isUser,
            'message-bubble--error': isError,
          }"
        >
          <!-- 用户消息：纯文本 -->
          <div v-if="isUser" class="message-text">{{ message.content }}</div>

          <!-- 批量结果卡片（优先级高于 Markdown 文本，文档 batchResults §4） -->
          <BatchExecutiveSummary
            v-if="hasBatchResults"
            :batch-results="message.batchResults!"
            @quick-action="handleQuickAction"
            @view-report="handleViewReport"
            @inspect="handleInspect"
            @inspect-steps="handleInspectSteps"
          />

          <!--
          助手消息：Markdown 渲染（事件委托处理代码块复制按钮）
          v-html 在此处安全：markdown-it 配置了 html: false，原始 HTML 标签会被转义
        -->
          <!-- eslint-disable vue/no-v-html -->
          <div
            v-else-if="!isUser && !hasBatchResults"
            class="message-text markdown-body"
            @click="onMarkdownClick"
            v-html="renderedContent"
          />
          <!-- eslint-enable vue/no-v-html -->

          <!-- 导出操作按钮（从 Markdown 中解析的内部标记） -->
          <div v-if="exportMarkers.length > 0" class="d-flex flex-column ga-1 mt-2">
            <div class="d-flex flex-wrap ga-1">
              <v-btn
                v-for="marker in exportMarkers"
                :key="marker.runId"
                size="x-small"
                variant="tonal"
                color="primary"
                :loading="exporting === marker.runId"
                :disabled="exporting !== null"
                @click="handleExportAction(marker)"
              >
                <v-icon start icon="mdi-file-export-outline" size="14" />
                {{
                  marker.type === 'detail_export'
                    ? '导出明细'
                    : marker.type === 'diagnosis_export'
                      ? '导出诊断报告'
                      : '导出对比'
                }}
              </v-btn>
            </div>
            <v-alert
              v-if="exportError"
              type="error"
              variant="tonal"
              density="comfortable"
              closable
              @click:close="exportError = ''"
            >
              {{ exportError }}
            </v-alert>
          </div>

          <!-- 流式状态指示 -->
          <div v-if="isStreaming" class="message-status">
            <v-progress-linear indeterminate color="primary" height="2" class="mb-1" />
            <span class="text-body-small text-medium-emphasis">{{ statusText }}</span>
          </div>

          <!-- 停止状态指示 -->
          <div v-if="isStopped" class="message-stopped">
            <v-icon icon="mdi-stop-circle" size="16" color="warning" class="mr-1" />
            <span class="text-body-small text-warning">{{ statusText }}</span>
          </div>

          <!-- 错误信息 -->
          <div v-if="isError" class="message-error">
            <v-icon icon="mdi-alert-circle" size="16" color="error" class="mr-1" />
            <span>{{ statusText }}</span>
          </div>

          <!-- 已处理的澄清信息 -->
          <div
            v-if="hasClarification"
            class="message-clarification d-flex align-center text-body-small text-info"
          >
            <v-icon icon="mdi-help-circle" size="16" color="info" class="mr-1" />
            <span class="clarification-text">
              {{ message.clarification?.title }}：{{ clarificationStatusText }}
            </span>
          </div>
        </div>

        <!-- 消息元信息 -->
        <div class="message-meta">
          <span class="message-time">{{ formattedTime }}</span>
          <!-- 链路详情入口 -->
          <v-btn
            v-if="hasTraceId"
            variant="text"
            size="x-small"
            color="primary"
            prepend-icon="mdi-timeline-clock-outline"
            class="ml-2"
            @click="showTraceDetail = true"
          >
            查看链路
          </v-btn>
          <v-btn
            v-if="!isUser && message.content && isSupported"
            icon
            variant="text"
            size="x-small"
            :color="copied ? 'success' : undefined"
            class="copy-btn"
            :title="copied ? '已复制' : '复制'"
            @click="copy(message.content)"
          >
            <v-icon size="14">
              {{ copied ? 'mdi-check-circle-outline' : 'mdi-content-copy' }}
            </v-icon>
          </v-btn>
        </div>

        <!-- 工具调用阶段 -->
        <div v-if="message.stages.length > 0" class="message-stages">
          <div v-for="stage in message.stages" :key="stage.step" class="stage-item">
            <v-icon
              :icon="
                stage.status === 'success'
                  ? 'mdi-check-circle'
                  : stage.status === 'failed'
                    ? 'mdi-close-circle'
                    : 'mdi-loading'
              "
              :color="
                stage.status === 'success'
                  ? 'success'
                  : stage.status === 'failed'
                    ? 'error'
                    : 'primary'
              "
              :class="{ 'mdi-spin': stage.status === 'running' }"
              size="14"
            />
            <span class="text-body-small text-medium-emphasis">
              {{ stage.message || stage.toolName }}
              <span v-if="stage.durationMs"> ({{ stage.durationMs }}ms)</span>
            </span>
          </div>
        </div>
      </template>
    </div>

    <!-- 链路详情弹窗 -->
    <TraceDetailDialog
      v-model="showTraceDetail"
      :trace-id="message.traceId"
      :run-id="message.runId"
    />
  </div>

  <!-- 完整报告抽屉（右侧弹出） -->
  <BatchReportDrawer v-model:open="reportDrawerOpen" :batch-run-id="reportBatchRunId" />

  <!-- 执行步骤检查抽屉（右侧弹出） -->
  <ExecutionStepsDrawer
    v-model:open="stepsDrawerOpen"
    :trace-id="stepsTraceId"
    :batch-run-id="stepsBatchRunId"
  />

  <!-- 功能占位提示（未接入后端动作时给出明确反馈） -->
  <v-snackbar v-model="featureNoticeOpen" color="info" timeout="3000" location="bottom">
    {{ featureNoticeText }}
  </v-snackbar>
</template>

<style lang="scss" scoped>
@use './styles/ChatMessage.scss';
@use './styles/markdown-body';

.message-clarification {
  padding: 6px 10px;
  background: rgba(var(--v-theme-info), 0.08);
  border-radius: 4px;

  .clarification-text {
    flex: 1;
  }
}
</style>
