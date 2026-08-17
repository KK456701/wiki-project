import { ref, watch } from 'vue';
import { getTraceDetail } from '@/services/chat';
import type { TraceDetailFull } from '../types';
import { TRACE_ERROR_CODE } from '../types';

export function useTraceData(traceId: () => string | undefined) {
  const data = ref<TraceDetailFull | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function fetchData(id: string) {
    loading.value = true;
    error.value = null;
    try {
      data.value = (await getTraceDetail(id)) as TraceDetailFull;
    } catch (err) {
      const message = err instanceof Error ? err.message : '加载链路数据失败';
      if (message.includes('404') || message.includes(TRACE_ERROR_CODE.NOT_FOUND)) {
        error.value = '未找到该链路数据，可能已被清理或 traceId 不正确';
      } else {
        error.value = message;
      }
    } finally {
      loading.value = false;
    }
  }

  watch(
    traceId,
    (id) => {
      if (id) {
        data.value = null;
        fetchData(id);
      }
    },
    { immediate: true },
  );

  function refresh() {
    const id = traceId();
    if (id) {
      fetchData(id);
    }
  }

  return { data, loading, error, refresh };
}
