import { computed, ref, shallowRef, watch } from 'vue';
import { monitorSDK } from '@/monitor';
import type { ErrorLog } from '@/monitor/types';
import type { ErrorType } from '@/monitor/constants';
import { ALL_ERROR_TYPES } from '@/monitor/constants';
import type { ErrorTypeStat } from '../types';
import { DEFAULT_PAGE_SIZE } from '../constants';

/**
 * 监控日志数据源
 *
 * 采用「一次性载入全量 + 前端派生筛选」策略：
 * SDK 侧 IndexedDB 有 maxLogCount 上限（默认 1000 条）且 queryLogs 内部本就是
 * 全量读取后再切片，因此这里直接取全量的成本与分页查询几乎一致，却能保证
 * 关键词搜索、统计数、分页总数三者始终基于同一份数据，不会互相矛盾。
 */
export function useMonitorData() {
  /** 全量日志（已按时间倒序，SDK 保证） */
  const allLogs = shallowRef<ErrorLog[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const sdkEnabled = ref(monitorSDK.isEnabled());

  // ── 分页状态（与 v-data-table 双向绑定）────────────────────────
  const page = ref(1);
  const pageSize = ref(DEFAULT_PAGE_SIZE);

  // ── 筛选状态 ──────────────────────────────────────────────────
  const selectedTypes = ref<ErrorType[]>([...ALL_ERROR_TYPES]);
  const keyword = ref('');
  const startTime = ref<number | undefined>(undefined);
  const endTime = ref<number | undefined>(undefined);

  /** 请求序号，用于丢弃过期响应，避免快速连续操作时旧结果覆盖新结果 */
  let requestSeq = 0;

  /** 日志总数（未筛选） */
  const totalCount = computed(() => allLogs.value.length);

  /** 各错误类型数量统计，直接由全量日志派生，无需额外请求 */
  const stats = computed<ErrorTypeStat[]>(() => {
    const typeMap = new Map<ErrorType, number>();
    for (const log of allLogs.value) {
      typeMap.set(log.type, (typeMap.get(log.type) ?? 0) + 1);
    }
    return ALL_ERROR_TYPES.map((type) => ({ type, count: typeMap.get(type) ?? 0 }));
  });

  /** 是否存在生效中的筛选条件 */
  const hasActiveFilter = computed(
    () =>
      keyword.value.trim() !== '' ||
      startTime.value !== undefined ||
      endTime.value !== undefined ||
      (selectedTypes.value.length > 0 && selectedTypes.value.length !== ALL_ERROR_TYPES.length),
  );

  /** 筛选后的日志 —— 关键词在全量数据上匹配，而非仅当前页 */
  const filteredLogs = computed<ErrorLog[]>(() => {
    const types = new Set(selectedTypes.value);
    const kw = keyword.value.trim().toLowerCase();
    const from = startTime.value;
    const to = endTime.value;

    return allLogs.value.filter((log) => {
      // 未选择任何类型时等同于全选，不做类型过滤
      if (selectedTypes.value.length > 0 && !types.has(log.type)) return false;
      if (from !== undefined && log.timestamp < from) return false;
      if (to !== undefined && log.timestamp > to) return false;
      if (kw) {
        const hit =
          log.message.toLowerCase().includes(kw) ||
          log.url.toLowerCase().includes(kw) ||
          (log.userId?.toLowerCase().includes(kw) ?? false);
        if (!hit) return false;
      }
      return true;
    });
  });

  /** 筛选后的条数 */
  const filteredCount = computed(() => filteredLogs.value.length);

  // 筛选条件变化时回到第一页，避免停留在已不存在的页码上导致空白
  watch([selectedTypes, keyword, startTime, endTime], () => {
    page.value = 1;
  });

  /** 同步 SDK 启用状态 */
  function refreshSDKStatus(): void {
    sdkEnabled.value = monitorSDK.isEnabled();
  }

  /** 载入全量日志 */
  async function loadLogs(): Promise<void> {
    const seq = ++requestSeq;
    loading.value = true;
    error.value = null;
    try {
      const result = await monitorSDK.exportLogs();
      // 仅接受最后一次请求的结果
      if (seq !== requestSeq) return;
      allLogs.value = result;
    } catch (err) {
      if (seq !== requestSeq) return;
      error.value = err instanceof Error ? err.message : '查询日志失败';
      allLogs.value = [];
    } finally {
      if (seq === requestSeq) loading.value = false;
    }
  }

  /** 切换 SDK 启用状态 */
  function toggleSDK(): boolean {
    if (sdkEnabled.value) {
      monitorSDK.disable();
    } else {
      monitorSDK.enable();
    }
    refreshSDKStatus();
    return sdkEnabled.value;
  }

  /** 清空全部日志 */
  async function clearAllLogs(): Promise<boolean> {
    try {
      await monitorSDK.clearLogs();
      allLogs.value = [];
      page.value = 1;
      return true;
    } catch (err) {
      error.value = err instanceof Error ? err.message : '清空日志失败';
      return false;
    }
  }

  /** 重置全部筛选条件 */
  function resetFilter(): void {
    selectedTypes.value = [...ALL_ERROR_TYPES];
    keyword.value = '';
    startTime.value = undefined;
    endTime.value = undefined;
    page.value = 1;
  }

  return {
    // 数据
    allLogs,
    filteredLogs,
    stats,
    loading,
    error,
    sdkEnabled,
    totalCount,
    filteredCount,
    hasActiveFilter,
    // 分页
    page,
    pageSize,
    // 筛选
    selectedTypes,
    keyword,
    startTime,
    endTime,
    // 行为
    loadLogs,
    refreshSDKStatus,
    toggleSDK,
    clearAllLogs,
    resetFilter,
  };
}
