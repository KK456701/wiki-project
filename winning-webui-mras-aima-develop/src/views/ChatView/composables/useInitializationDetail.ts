import { computed, ref } from 'vue';
import type { InitializationOutput, ValidationItem, ImpactLevel, TraceNode } from '@/types/chat';

const CATEGORY_LABELS: Record<string, string> = {
  MISSING_TABLE: '缺少数据表',
  MISSING_COLUMN: '缺少字段',
  NO_DATA: '无数据',
  NULL_RATE: '字段存在空值',
  JOIN_COVERAGE: '关联未完全匹配',
  UNSUPPORTED: '检查未完成',
  NOT_IMPLEMENTED: '口径未实现',
  UPSTREAM_NOT_REGISTERED: '上游同步链路未登记',
  DATABASE_CONNECTION: '数据库连接异常',
};

const SEVERITY_RANK: Record<string, number> = {
  BLOCKED: 0,
  NO_SAMPLE: 1,
  WARNING: 2,
  NORMAL: 3,
};

export function useInitializationDetail(
  outputData: () => InitializationOutput | null,
  allTraceNodes: () => TraceNode[],
) {
  const searchText = ref('');
  const dbFilter = ref<string>('all');
  const typeFilter = ref<string>('all');
  const openGroups = ref<Set<string>>(new Set(['CONFIRMED']));
  const realDetailsOpen = ref(false);

  const items = computed<ValidationItem[]>(() => {
    const raw = outputData()?.items ?? [];
    const filtered = raw
      .filter((item) => {
        if (dbFilter.value !== 'all' && item.databaseRole !== dbFilter.value) return false;
        if (typeFilter.value !== 'all' && item.category !== typeFilter.value) return false;
        if (searchText.value) {
          const q = searchText.value.toLowerCase();
          const haystack = [
            item.ruleId,
            item.ruleName,
            item.profileId,
            item.tableName,
            item.fieldName,
          ]
            .filter(Boolean)
            .join(' ')
            .toLowerCase();
          if (!haystack.includes(q)) return false;
        }
        return true;
      })
      .sort((a, b) => (SEVERITY_RANK[a.severity] ?? 99) - (SEVERITY_RANK[b.severity] ?? 99));
    return filtered;
  });

  const impactLevels: ImpactLevel[] = ['CONFIRMED', 'POSSIBLE', 'DISPLAY_ONLY', 'UNKNOWN'];

  function impactLevelItems(level: ImpactLevel): ValidationItem[] {
    return items.value.filter((item) => computeImpactLevel(item) === level);
  }

  function impactProfileCount(level: ImpactLevel): number {
    const profiles = new Set(impactLevelItems(level).map((i) => i.profileId || i.ruleId));
    return profiles.size;
  }

  const groups = computed(() => {
    const map = new Map<string, ValidationItem[]>();
    if (!outputData()) return [];
    for (const item of items.value) {
      const key = item.ruleId;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(item);
    }
    return Array.from(map.entries()).map(([ruleId, grp]) => ({
      ruleId,
      ruleName: grp[0].ruleName,
      profileLabel: grp[0].profileLabel,
      profileId: grp[0].profileId,
      items: grp,
    }));
  });

  function profileWindowCount(profileId: string): number | null {
    const profiles = outputData()?.profiles;
    if (!profiles) return null;
    const match = profiles.find((p) => p.profileId === profileId);
    return match?.businessSourceCount ?? null;
  }

  const evidenceTotalInner = (items: ValidationItem[]) =>
    items.reduce((sum, i) => sum + Math.max(i.evidenceCount ?? 0, 1), 0);

  function toggleGroup(key: string) {
    const next = new Set(openGroups.value);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    openGroups.value = next;
  }

  function isGroupOpen(key: string): boolean {
    return openGroups.value.has(key);
  }

  const realSnapshotNodes = computed<TraceNode[]>(() => {
    return (allTraceNodes() ?? [])
      .filter((n) => n.nodeName === 'real_snapshot_data_validation')
      .sort((a, b) => (SEVERITY_RANK[a.status] ?? 99) - (SEVERITY_RANK[b.status] ?? 99));
  });

  const realSnapshotSummary = computed(() => {
    const nodes = realSnapshotNodes.value;
    const total = outputData()?.runnableCount ?? nodes.length;
    const completed = nodes.length;
    const failed = nodes.filter((n) => n.status === 'failed' || n.status === 'error').length;
    return {
      total,
      completed,
      failed,
      success: completed - failed,
      waiting: Math.max(0, total - completed),
    };
  });

  function focusedExecutionNode(profileId: string): TraceNode | null {
    return (
      allTraceNodes().find(
        (n) => n.nodeName === 'batch_indicator' && extractProfileId(n) === profileId,
      ) ?? null
    );
  }

  return {
    searchText,
    dbFilter,
    typeFilter,
    openGroups,
    realDetailsOpen,
    items,
    impactLevels,
    impactLevelItems,
    impactProfileCount,
    groupedByRule: groups,
    profileWindowCount,
    evidenceTotal: evidenceTotalInner,
    toggleGroup,
    isGroupOpen,
    realSnapshotNodes,
    realSnapshotSummary,
    focusedExecutionNode,
    CATEGORY_LABELS,
    SEVERITY_RANK,
  };
}

export function computeImpactLevel(item: ValidationItem): ImpactLevel {
  if (
    item.impactLevel &&
    ['CONFIRMED', 'POSSIBLE', 'DISPLAY_ONLY', 'UNKNOWN', 'NO_IMPACT'].includes(item.impactLevel)
  ) {
    return item.impactLevel as ImpactLevel;
  }
  if (item.affectsCalculation) return 'CONFIRMED';
  if (item.severity === 'BLOCKED' || item.severity === 'NO_SAMPLE') return 'CONFIRMED';
  if (item.category === 'UNSUPPORTED') return 'UNKNOWN';
  if (['NULL_RATE', 'JOIN_COVERAGE', 'NO_DATA'].includes(item.category)) return 'POSSIBLE';
  return 'NO_IMPACT';
}

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category;
}

export function databaseLabel(role: string): string {
  return role === 'business' ? '业务库' : role === 'real' ? '真实库' : role;
}

export function scopeLabel(scope: string | undefined): string {
  if (scope === 'STAT_WINDOW') return '本次统计窗口';
  if (scope === 'FULL_TABLE') return '全表基础质量';
  return scope ?? '—';
}

export function fieldRolesLabel(roles: string[] | undefined): string {
  if (!roles || roles.length === 0) return '—';
  const map: Record<string, string> = {
    TIME_FILTER: '统计时间',
    NUMERATOR_CONDITION: '分子判定',
    DENOMINATOR_SCOPE: '分母范围',
    JOIN_KEY: '关联键',
    GROUP_KEY: '分组键',
    DISTINCT_KEY: '去重键',
    SELECT_ONLY: '仅展示',
  };
  return roles.map((r) => map[r] ?? r).join('、');
}

export function impactText(item: ValidationItem): string {
  if (item.severity === 'BLOCKED') return '确定影响当前计算（阻断）';
  if (item.severity === 'NO_SAMPLE') return '确定影响当前计算（无样本）';
  if (item.category === 'UNSUPPORTED') return '暂无法判断';
  if (['NULL_RATE', 'JOIN_COVERAGE'].includes(item.category)) return '可能影响计算结果';
  if (item.category === 'NO_DATA') return '可能影响计算结果（无数据）';
  return '无明显影响';
}

export function qualityText(status: string): string {
  if (status === 'ALL_BLOCKED') return '全部阻断';
  if (status === 'PARTIAL_BLOCKED') return '部分阻断';
  if (status === 'WARNING') return '有警告';
  return '正常';
}

function extractProfileId(node: TraceNode): string {
  const input = node.inputData;
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    return String((input as Record<string, unknown>).profileId ?? '');
  }
  return '';
}

export function parseSnapshotOutput(node: TraceNode): Record<string, unknown> {
  const data = node.outputData;
  if (typeof data === 'string') {
    try {
      return JSON.parse(data);
    } catch {
      return {};
    }
  }
  return (data as Record<string, unknown>) ?? {};
}

/** 计算校验条目组的证据总数（独立工具函数，可在子组件中调用） */
export function evidenceTotal(items: ValidationItem[]): number {
  return items.reduce((sum, i) => sum + Math.max(i.evidenceCount ?? 0, 1), 0);
}

/** 根据 profileId 查找窗口数据量（独立工具函数） */
export function profileWindowCount(
  profileId: string,
  profiles: { profileId: string; businessSourceCount?: number }[] | undefined,
): number | null {
  if (!profiles) return null;
  const match = profiles.find((p) => p.profileId === profileId);
  return match?.businessSourceCount ?? null;
}
