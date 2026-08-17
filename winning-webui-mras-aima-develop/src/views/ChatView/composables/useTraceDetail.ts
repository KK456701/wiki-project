import { compareAsc, format } from 'date-fns';
import { computed, ref, watch } from 'vue';
import type { TraceDetailResponse, TraceNode } from '@/types/chat';
import { getTraceDetail, getBatchDetail } from '@/services/chat';

const NODE_TYPE_MAP: Record<string, { icon: string; color: string }> = {
  llm: { icon: 'mdi-brain', color: 'primary' },
  code: { icon: 'mdi-code-braces', color: 'info' },
  tool: { icon: 'mdi-wrench', color: 'success' },
  storage: { icon: 'mdi-database', color: 'warning' },
};

const STATUS_MAP: Record<string, { icon: string; color: string }> = {
  success: { icon: 'mdi-check', color: 'success' },
  failed: { icon: 'mdi-close', color: 'error' },
  warning: { icon: 'mdi-alert', color: 'warning' },
};

export interface TraceDetailField {
  label: string;
  value: string;
  breakAll?: boolean;
}

export interface TraceDetailSection {
  title: string;
  icon: string;
  fields: TraceDetailField[];
}

export type TraceDetailTab = 'detail' | 'nodes' | 'json';

export function useTraceDetail(
  modelValue: () => boolean,
  traceId: () => string | undefined,
  runId: () => string | undefined,
) {
  const traceData = ref<TraceDetailResponse | null>(null);
  const loading = ref(false);
  const loadError = ref<string | null>(null);
  const tab = ref<TraceDetailTab>('detail');
  /** 经过解析后最终使用的 traceId（通过直传或 runId→batch→traceId 解析得到） */
  const resolvedTraceId = ref<string | null>(null);

  // 切换 traceId/runId 时回到详情页签并清空旧数据
  watch([traceId, runId], () => {
    tab.value = 'detail';
    traceData.value = null;
    resolvedTraceId.value = null;
  });

  /**
   * 解析 traceId：优先使用直接传入的 traceId，
   * 否则通过 runId → batch API → job.traceId 间接获取
   */
  async function resolveTraceId(): Promise<string | null> {
    const directId = traceId();
    if (directId) return directId;

    const batchRunId = runId();
    if (!batchRunId) return null;

    const batch = await getBatchDetail(batchRunId);
    return batch.job?.traceId ?? null;
  }

  // 监听弹窗打开，加载数据
  watch(modelValue, async (isOpen) => {
    if (!isOpen || traceData.value) return;

    const id = traceId();
    const bid = runId();
    if (!id && !bid) return;

    loading.value = true;
    loadError.value = null;
    try {
      const finalTraceId = await resolveTraceId();
      if (!finalTraceId) {
        loadError.value = '未找到关联的链路追踪 ID';
        return;
      }
      resolvedTraceId.value = finalTraceId;
      traceData.value = await getTraceDetail(finalTraceId);
    } catch (error) {
      loadError.value = error instanceof Error ? error.message : '加载链路数据失败';
    } finally {
      loading.value = false;
    }
  });

  const sortedNodes = computed<TraceNode[]>(() => {
    if (!traceData.value?.nodes) return [];
    return [...traceData.value.nodes].sort((a, b) =>
      compareAsc(new Date(a.startedAt), new Date(b.startedAt)),
    );
  });

  const jsonView = computed(() =>
    traceData.value ? JSON.stringify(traceData.value, null, 2) : '',
  );

  /** 详情页签的分组字段 */
  const detailSections = computed<TraceDetailSection[]>(() => {
    const data = traceData.value;
    if (!data) return [];

    const baseSection: TraceDetailSection = {
      title: '基础信息',
      icon: 'mdi-information-outline',
      fields: [
        { label: 'Trace ID', value: data.traceId },
        { label: '用户问题', value: data.userQuery ?? '-', breakAll: true },
        { label: '意图识别', value: data.intent ?? '-' },
        { label: '最终状态', value: data.finalStatus },
        { label: '总耗时', value: data.durationMs != null ? `${data.durationMs} ms` : '-' },
        { label: '开始时间', value: formatTime(data.startedAt) },
        { label: '结束时间', value: data.endedAt ? formatTime(data.endedAt) : '-' },
      ],
    };

    const result: TraceDetailSection[] = [baseSection];

    if (data.timingSummary) {
      result.push({
        title: '耗时分布',
        icon: 'mdi-chart-bar',
        fields: [
          { label: 'LLM 耗时', value: `${data.timingSummary.llmMs} ms` },
          { label: '工具调用耗时', value: `${data.timingSummary.toolMs} ms` },
          { label: '代码执行耗时', value: `${data.timingSummary.codeMs} ms` },
          { label: '存储操作耗时', value: `${data.timingSummary.storageMs} ms` },
        ],
      });
    }

    result.push({
      title: '执行统计',
      icon: 'mdi-chart-box-outline',
      fields: [
        { label: '节点数量', value: `${data.nodes?.length ?? 0}` },
        { label: '错误次数', value: `${data.errorCount}` },
        { label: '回退次数', value: `${data.fallbackCount}` },
        { label: '链路版本', value: data.traceVersion },
      ],
    });

    return result;
  });

  return {
    traceData,
    loading,
    loadError,
    tab,
    sortedNodes,
    jsonView,
    detailSections,
    resolvedTraceId,
  };
}

export function nodeTypeIcon(type: string): string {
  return NODE_TYPE_MAP[type]?.icon ?? 'mdi-circle-small';
}

export function nodeTypeColor(type: string): string {
  return NODE_TYPE_MAP[type]?.color ?? 'grey';
}

export function statusIcon(status: string): string {
  return STATUS_MAP[status]?.icon ?? '';
}

export function statusColor(status: string): string {
  return STATUS_MAP[status]?.color ?? 'grey';
}

export function formatTime(isoString: string): string {
  return format(new Date(isoString), 'HH:mm:ss');
}

export function formatJson(obj: Record<string, unknown> | string): string {
  if (typeof obj === 'string') {
    try {
      return JSON.stringify(JSON.parse(obj), null, 2);
    } catch {
      return obj;
    }
  }
  return JSON.stringify(obj, null, 2);
}
