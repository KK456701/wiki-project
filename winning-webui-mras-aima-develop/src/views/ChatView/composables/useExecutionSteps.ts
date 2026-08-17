import { ref, watch, computed } from 'vue';
import type { TraceNode, TraceDetailResponse } from '@/types/chat';
import { getTraceDetail, getBatchDetail } from '@/services/chat';
import { EXECUTION_STEP_NAMES, EXECUTION_STEP_LABELS, EXECUTION_STEP_ICONS } from '../constants';

export function useExecutionSteps(
  open: () => boolean,
  traceId: () => string | null,
  batchRunId: () => string | null,
) {
  const loading = ref(false);
  const error = ref('');
  const trace = ref<TraceDetailResponse | null>(null);
  const selectedNodeId = ref<string | null>(null);

  const steps = computed<TraceNode[]>(() => {
    if (!trace.value?.nodes) return [];
    const map = new Map<string, TraceNode>();
    for (const node of trace.value.nodes) {
      const name = node.nodeName;
      if ((EXECUTION_STEP_NAMES as readonly string[]).includes(name) && !map.has(name)) {
        map.set(name, node);
      }
    }
    return EXECUTION_STEP_NAMES.map((n) => map.get(n)).filter((n): n is TraceNode => n != null);
  });

  const selectedNode = computed<TraceNode | null>(() => {
    if (!selectedNodeId.value || !trace.value) return null;
    return trace.value.nodes.find((n) => n.nodeId === selectedNodeId.value) ?? null;
  });

  const initOutputData = computed<Record<string, unknown> | null>(() => {
    if (selectedNode.value?.nodeName !== 'batch_data_initialization_validation') return null;
    const data = selectedNode.value?.outputData;
    if (typeof data === 'string') {
      try {
        return JSON.parse(data);
      } catch {
        return null;
      }
    }
    return (data as Record<string, unknown>) ?? null;
  });

  watch(open, async (isOpen) => {
    if (!isOpen) {
      selectedNodeId.value = null;
      return;
    }
    if (trace.value) return;
    loading.value = true;
    error.value = '';
    try {
      let tid = traceId();
      if (!tid && batchRunId()) {
        const batch = await getBatchDetail(batchRunId()!);
        tid = batch.job?.traceId ?? null;
      }
      if (!tid) {
        error.value = '未找到关联的执行链路 ID，可能已过期';
        return;
      }
      trace.value = await getTraceDetail(tid);
    } catch (err) {
      error.value = err instanceof Error ? err.message : '加载执行链路失败';
    } finally {
      loading.value = false;
    }
  });

  function selectNode(nodeId: string) {
    selectedNodeId.value = nodeId;
  }

  function backToList() {
    selectedNodeId.value = null;
  }

  return {
    loading,
    error,
    trace,
    steps,
    selectedNode,
    selectedNodeId,
    initOutputData,
    traceNodes: computed(() => trace.value?.nodes ?? []),
    selectNode,
    backToList,
    EXECUTION_STEP_LABELS,
    EXECUTION_STEP_ICONS,
  };
}

export function statusText(node: TraceNode): string {
  if (node.status === 'running') return '执行中';
  if (node.status === 'failed' || node.status === 'error') return '失败';
  if (node.status === 'warning' || node.status === 'incomplete') return '需关注';
  return '已完成';
}

export function statusColor(node: TraceNode): string {
  if (node.status === 'running') return 'info';
  if (node.status === 'failed' || node.status === 'error') return 'error';
  if (node.status === 'warning' || node.status === 'incomplete') return 'warning';
  return 'success';
}

export function durationStr(ms: number | undefined | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

export function formatJsonValue(val: unknown): string {
  if (val === null || val === undefined) return '—';
  if (typeof val === 'object') return JSON.stringify(val, null, 2);
  return String(val);
}
