<script setup lang="ts">
import type { BatchResultItem } from '@/types/chat';
import { useBatchResults } from '../composables/useBatchResults';
import { computed } from 'vue';

const props = defineProps<{
  item: BatchResultItem;
  isRecommended: boolean;
}>();

const { calculateOutcome, calculateQuality } = useBatchResults(computed(() => [props.item]));

const outcome = computed(() => calculateOutcome(props.item));
const qualityLabel = computed(() => calculateQuality(props.item));

const outcomeColor = computed(() => {
  switch (outcome.value) {
    case 'reached':
      return 'success';
    case 'not_reached':
      return 'error';
    case 'failed':
      return 'error';
    case 'no_sample':
      return 'grey';
    case 'pending':
      return 'info';
    default:
      return 'grey';
  }
});

const outcomeText = computed(() => {
  switch (outcome.value) {
    case 'reached':
      return '达标';
    case 'not_reached':
      return '未达标';
    case 'failed':
      return '计算失败';
    case 'no_sample':
      return '无样本';
    case 'pending':
      return '待确认';
    default:
      return '-';
  }
});

/** 根据 detailKind 获取分子/分母/结果的展示标签（文档 §14） */
const displayLabels = computed(() => {
  const kind = props.item.detailKind;
  if (kind === 'MEDIAN_SAMPLE') {
    return { leftLabel: '中位数', rightLabel: '有效样本', resultLabel: '结果' };
  }
  if (kind === 'SUM_CONTRIBUTION') {
    return { leftLabel: '成功贡献值', rightLabel: '抢救总量', resultLabel: '结果' };
  }
  if (kind === 'DUAL_SOURCE') {
    return { leftLabel: '实际开展', rightLabel: '备案目录', resultLabel: '开展率' };
  }
  if (kind === 'RATE_COMPARISON') {
    return { leftLabel: '两率对比', rightLabel: '', resultLabel: '' };
  }
  // COUNT_RATIO 或其他
  return { leftLabel: '分子', rightLabel: '分母', resultLabel: '结果' };
});

/** 口径名称：profileLabel → profileId → "推荐方案（公版）" */
const profileName = computed(() => {
  return props.item.profileLabel || props.item.profileId || '推荐方案（公版）';
});
</script>

<template>
  <div
    class="indicator-row d-flex align-center ga-3 py-1 px-2 rounded"
    :class="{ 'indicator-row--recommended': isRecommended }"
  >
    <!-- 推荐标识 -->
    <v-icon v-if="isRecommended" icon="mdi-star" size="14" color="primary" />

    <!-- 口径名 -->
    <span class="text-body-medium flex-shrink-0" style="min-width: 120px">
      {{ profileName }}
    </span>

    <!-- 达标状态 -->
    <v-chip size="x-small" :color="outcomeColor" variant="tonal" class="flex-shrink-0">
      {{ outcomeText }}
    </v-chip>

    <!-- 数值（含分子/分母） -->
    <template v-if="item.status === 'SUCCESS'">
      <span v-if="item.resultValue != null" class="font-weight-bold text-body-medium">
        {{ item.resultValue }}{{ item.unit || '' }}
      </span>
      <template
        v-if="
          displayLabels.leftLabel && item.numeratorCount != null && item.denominatorCount != null
        "
      >
        <span class="text-body-small text-medium-emphasis">
          {{ displayLabels.leftLabel }} {{ item.numeratorCount }} / {{ displayLabels.rightLabel }}
          {{ item.denominatorCount }}
        </span>
      </template>
    </template>

    <!-- 目标 -->
    <span v-if="item.targetValue" class="text-body-small text-medium-emphasis flex-shrink-0">
      目标 {{ item.targetDirection === 'up' ? '≥' : item.targetDirection === 'down' ? '≤' : ''
      }}{{ item.targetValue }}
    </span>

    <!-- 质量 -->
    <span
      class="text-body-small flex-shrink-0"
      :class="qualityLabel === '正常' ? 'text-success' : 'text-warning'"
    >
      {{ qualityLabel }}
    </span>
  </div>
</template>

<style lang="scss" scoped>
.indicator-row {
  &--recommended {
    background: rgba(var(--v-theme-primary), 0.04);
  }
}
</style>
