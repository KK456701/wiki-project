import { computed, type Ref } from 'vue';
import type { BatchResultItem } from '@/types/chat';

/**
 * 批量结果计算逻辑（文档 batchResults 第二篇 §5-§9）
 *
 * 提供指标分组、达标判断、数据质量、重点关注等前端推导计算。
 * 所有逻辑基于 batchResults 数组，不依赖后端 Markdown。
 */

/** 单口径达标结论 */
export type Outcome = 'reached' | 'not_reached' | 'failed' | 'no_sample' | 'pending';

/** 重点关注项 */
export interface AttentionItem {
  batchResult: BatchResultItem;
  badge: string;
  category: 'failure' | 'quality' | 'pending' | 'not_reached';
  priority: number;
  reason: string;
}

/** 摘要统计 */
export interface BatchSummary {
  indicatorCount: number;
  profileCount: number;
  reached: number;
  notReached: number;
  pending: number;
  qualityNormal: number;
  qualityAbnormal: number;
  batchRunId: string | null;
  statStart: string | null;
  statEnd: string | null;
}

const OFFICIAL_KEYWORDS = ['公版', '推荐方案', '默认'] as const;

export function useBatchResults(batchResults: Ref<BatchResultItem[]>) {
  // ============ 工具函数 ============

  /** 将 resultValue/targetValue 转换为数字（文档 §6.1） */
  function toNumber(val: number | string | undefined | null): number | null {
    if (val == null) return null;
    if (typeof val === 'number') {
      return Number.isFinite(val) ? val : null;
    }
    // 删除常见单位后缀
    const cleaned = val.replace(/[%倍分钟小时天]/g, '').trim();
    const num = Number(cleaned);
    return Number.isFinite(num) ? num : null;
  }

  /** 判断是否正式口径（文档 §5.3）：profileId 为空 或 名称含关键词 */
  function isOfficial(item: BatchResultItem): boolean {
    if (!item.profileId) return true;
    return OFFICIAL_KEYWORDS.some((kw) => item.profileLabel?.includes(kw) ?? false);
  }

  /** 取指标组的第一条正式口径（文档 §5.4） */
  function getFormal(group: BatchResultItem[]): BatchResultItem {
    const official = group.find(isOfficial);
    return official ?? group[0];
  }

  /** 单口径达标结论（文档 §6.2） */
  function calculateOutcome(item: BatchResultItem): Outcome {
    if (item.status === 'FAILED') return 'failed';
    if (item.status === 'NO_SAMPLE') return 'no_sample';

    const result = toNumber(item.resultValue);
    const target = toNumber(item.targetValue);
    const direction = item.targetDirection;

    if (result == null || target == null || !direction) return 'pending';

    // 兼容 up/down（后端 API 文档格式）
    if (direction === 'up') {
      return result >= target ? 'reached' : 'not_reached';
    }
    if (direction === 'down') {
      return result <= target ? 'reached' : 'not_reached';
    }

    // 兼容 >=/<=/>/</= （SSE 实际传递格式）
    const dirStr = direction as string;
    if (dirStr.includes('<')) {
      const met = dirStr.includes('=') ? result <= target : result < target;
      return met ? 'reached' : 'not_reached';
    }
    if (dirStr.includes('>')) {
      const met = dirStr.includes('=') ? result >= target : result > target;
      return met ? 'reached' : 'not_reached';
    }

    return result === target ? 'reached' : 'not_reached';
  }

  /** 指标级达标状态（文档 §6.3） */
  function calculateIndicatorOutcome(group: BatchResultItem[]): Outcome {
    const formal = getFormal(group);
    if (group.length > 1 && !group.some(isOfficial)) return 'pending';

    const outcome = calculateOutcome(formal);
    if (outcome === 'reached') return 'reached';
    if (outcome === 'not_reached') return 'not_reached';
    return 'pending';
  }

  /** 摘要级数据质量判断（文档 §7，不读取 qualityStatus） */
  function calculateSummaryQuality(item: BatchResultItem): 'normal' | 'abnormal' {
    if (item.errorCode === 'PROFILE_NOT_IMPLEMENTED') return 'normal';
    if (item.status === 'SUCCESS' && item.dataFreshness !== 'extraction_failed_stale') {
      return 'normal';
    }
    return 'abnormal';
  }

  /** 单口径数据质量展示（文档 §13.5，读取 qualityStatus） */
  function calculateQuality(item: BatchResultItem): string {
    if (item.status === 'FAILED') return '异常';
    if (item.status === 'NO_SAMPLE') return '无可用样本';
    if (item.dataFreshness === 'extraction_failed_stale') return '旧快照';
    const qs = item.qualityStatus;
    if (qs && !['NORMAL', 'OK', 'PASS', 'SUCCESS', '正常'].includes(qs)) {
      return qs;
    }
    return '正常';
  }

  // ============ 分组（文档 §5.1） ============

  /** 按 ruleId 分组的指标组 Map */
  const indicatorGroups = computed<Map<string, BatchResultItem[]>>(() => {
    const map = new Map<string, BatchResultItem[]>();
    for (const item of batchResults.value) {
      const group = map.get(item.ruleId);
      if (group) {
        group.push(item);
      } else {
        map.set(item.ruleId, [item]);
      }
    }
    return map;
  });

  /** 指标组数组（保持 batchResults 原始顺序，按 ruleId 首次出现排序） */
  const indicatorGroupList = computed<BatchResultItem[][]>(() => {
    const seen = new Set<string>();
    const list: BatchResultItem[][] = [];
    for (const item of batchResults.value) {
      if (seen.has(item.ruleId)) continue;
      seen.add(item.ruleId);
      list.push(indicatorGroups.value.get(item.ruleId)!);
    }
    return list;
  });

  // ============ 摘要统计（文档 §8） ============

  const summary = computed<BatchSummary>(() => {
    const groups = indicatorGroupList.value;
    let reached = 0;
    let notReached = 0;
    let pending = 0;
    let qualityNormal = 0;
    let qualityAbnormal = 0;

    for (const group of groups) {
      const formal = getFormal(group);
      const outcome = calculateIndicatorOutcome(group);

      if (outcome === 'reached') reached++;
      else if (outcome === 'not_reached') notReached++;
      else pending++;

      const quality = calculateSummaryQuality(formal);
      if (quality === 'normal') qualityNormal++;
      else qualityAbnormal++;
    }

    const firstWithBatchRunId = batchResults.value.find((r) => r.batchRunId);
    const firstWithStatRange = batchResults.value.find((r) => r.statStart && r.statEnd);

    return {
      indicatorCount: groups.length,
      profileCount: batchResults.value.length,
      reached,
      notReached,
      pending,
      qualityNormal,
      qualityAbnormal,
      batchRunId: firstWithBatchRunId?.batchRunId ?? null,
      statStart: firstWithStatRange?.statStart ?? null,
      statEnd: firstWithStatRange?.statEnd ?? null,
    };
  });

  // ============ 重点关注（文档 §9） ============

  /** 全部需重点关注的项（按优先级 + ruleId 排序） */
  const attentionItems = computed<AttentionItem[]>(() => {
    const mapCategory = (item: BatchResultItem): AttentionItem | null => {
      if (item.status === 'FAILED') {
        return {
          batchResult: item,
          badge: '计算异常',
          category: 'failure',
          priority: 0,
          reason: item.errorMessage || '数据源或执行链路未能完成',
        };
      }
      if (item.status === 'NO_SAMPLE') {
        return {
          batchResult: item,
          badge: '无可用样本',
          category: 'quality',
          priority: 1,
          reason: '统计窗口内没有可核算记录',
        };
      }
      if (item.dataFreshness === 'extraction_failed_stale') {
        return {
          batchResult: item,
          badge: '数据质量',
          category: 'quality',
          priority: 1,
          reason: '本次抽取失败，结果使用了旧快照',
        };
      }

      const outcome = calculateOutcome(item);
      if (outcome === 'pending') {
        return {
          batchResult: item,
          badge: '待确认',
          category: 'pending',
          priority: 2,
          reason: '缺少可判定的目标值或指标方向',
        };
      }
      if (outcome === 'not_reached') {
        const resultStr = item.resultValue != null ? String(item.resultValue) : '—';
        const targetStr = item.targetValue != null ? String(item.targetValue) : '—';
        return {
          batchResult: item,
          badge: '未达标',
          category: 'not_reached',
          priority: 3,
          reason: `结果值 ${resultStr}，目标值 ${targetStr}`,
        };
      }
      return null;
    };

    return batchResults.value
      .map(mapCategory)
      .filter((a): a is AttentionItem => a !== null)
      .sort((a, b) => {
        if (a.priority !== b.priority) return a.priority - b.priority;
        return a.batchResult.ruleId.localeCompare(b.batchResult.ruleId);
      });
  });

  /** 可见重点关注（最多 5 项，跨类别各取一项后补足，文档 §9.3） */
  const visibleAttention = computed<AttentionItem[]>(() => {
    const all = attentionItems.value;
    if (all.length <= 5) return all;

    const picked: AttentionItem[] = [];
    const used = new Set<number>();

    const categories = ['failure', 'quality', 'pending', 'not_reached'] as const;
    for (const cat of categories) {
      const idx = all.findIndex((a, i) => a.category === cat && !used.has(i));
      if (idx >= 0 && picked.length < 5) {
        picked.push(all[idx]);
        used.add(idx);
      }
    }

    // 不足 5 项时从头部补足
    for (let i = 0; i < all.length && picked.length < 5; i++) {
      if (!used.has(i)) {
        picked.push(all[i]);
        used.add(i);
      }
    }

    return picked;
  });

  /** 所有需要关注的总数 */
  const attentionTotal = computed(() => attentionItems.value.length);

  // ============ 指标卡片辅助 ============

  /** 卡片推荐口径选择规则（文档 §13.3） */
  function getRecommendedProfile(group: BatchResultItem[]): BatchResultItem {
    const successfulOfficial = group.find((r) => r.status === 'SUCCESS' && isOfficial(r));
    if (successfulOfficial) return successfulOfficial;

    const firstSuccess = group.find((r) => r.status === 'SUCCESS');
    if (firstSuccess) return firstSuccess;

    const official = group.find(isOfficial);
    if (official) return official;

    return group[0];
  }

  /** 卡片头部状态（文档 §13.2） */
  function getCardStatus(group: BatchResultItem[]): string {
    if (group.some((r) => r.status === 'SUCCESS')) return '计算成功';
    if (group.every((r) => r.status === 'NO_SAMPLE')) return '无样本';
    return group[0].status;
  }

  /** 卡片系统建议生成（文档 §13.6） */
  function getCardAdvice(group: BatchResultItem[]): string {
    const recommended = getRecommendedProfile(group);
    const outcome = calculateOutcome(recommended);

    if (outcome === 'failed') return '核查依赖表、概览 SQL 和采集模块';
    if (outcome === 'no_sample') return '核查时间窗口、采集覆盖和业务模块是否启用';
    if (recommended.dataFreshness === 'extraction_failed_stale') return '恢复抽取后重跑';
    if (outcome === 'not_reached') return '先核对绑定明细，再制定改善措施';
    if (outcome === 'pending') return '业务负责人确认目标口径';

    // 多口径推荐判断
    if (group.length > 1) {
      const profiles = group.filter((r) => r !== recommended);
      if (profiles.some((r) => r.status === 'SUCCESS')) {
        return '正式口径已达标，保存报告持续观察';
      }
    }

    return '保存报告和快照，持续观察';
  }

  return {
    indicatorGroups,
    indicatorGroupList,
    summary,
    attentionItems,
    visibleAttention,
    attentionTotal,
    // 工具函数
    isOfficial,
    getFormal,
    calculateOutcome,
    calculateIndicatorOutcome,
    calculateSummaryQuality,
    calculateQuality,
    // 卡片辅助
    getRecommendedProfile,
    getCardStatus,
    getCardAdvice,
  };
}
