<script setup lang="ts">
import type { IndicatorDetailResult } from '../api/agent'
import SpecialDetailPager from './SpecialDetailPager.vue'
defineProps<{ detail: IndicatorDetailResult }>()
const emit = defineEmits<{
  group: [value: string]
  page: [value: number]
}>()
function rate(value?: string): string {
  if (!value) return '—'
  return value.includes('%') || value.includes('无数据') ? value : `${value}%`
}
function rows(detail: IndicatorDetailResult): Record<string, unknown>[] {
  if (detail.group === 'level4Total') return detail.level4Total || []
  if (detail.group === 'level3Hit') return detail.level3Hit || []
  if (detail.group === 'level3Total') return detail.level3Total || []
  return detail.level4Hit || []
}
function count(detail: IndicatorDetailResult, key: string): number {
  return detail.groupCounts?.[key] ?? 0
}
function bothZero(detail: IndicatorDetailResult): boolean {
  return detail.level4Rate?.replace('%', '').trim() === '0.00'
    && detail.level3Rate?.replace('%', '').trim() === '0.00'
}
</script>

<template>
  <div class="rate-comparison-summary">
    <div>
      <span>四级手术率</span><strong>{{ rate(detail.level4Rate) }}</strong>
      <small>A {{ count(detail, 'level4Hit') }} / B {{ count(detail, 'level4Total') }}</small>
    </div>
    <b>:</b>
    <div>
      <span>三级手术率</span><strong>{{ rate(detail.level3Rate) }}</strong>
      <small>C {{ count(detail, 'level3Hit') }} / D {{ count(detail, 'level3Total') }}</small>
    </div>
    <p>最终结果 {{ detail.resultDisplay || '—' }}</p>
  </div>
  <p v-if="bothZero(detail)" class="rate-zero-explanation">
    两组总体均有记录，但死亡命中均为 0，因此两个死亡率都是 0%。
    这里展示的是“两率并列对照”，没有执行 0 ÷ 0。
  </p>
  <div class="indicator-detail-groups four-groups">
    <button type="button" :class="{ active: detail.group === 'level4Hit' }" @click="emit('group', 'level4Hit')">
      四级命中 A（{{ count(detail, 'level4Hit') }}）
    </button>
    <button type="button" :class="{ active: detail.group === 'level4Total' }" @click="emit('group', 'level4Total')">
      四级总体 B（{{ count(detail, 'level4Total') }}）
    </button>
    <button type="button" :class="{ active: detail.group === 'level3Hit' }" @click="emit('group', 'level3Hit')">
      三级命中 C（{{ count(detail, 'level3Hit') }}）
    </button>
    <button type="button" :class="{ active: detail.group === 'level3Total' }" @click="emit('group', 'level3Total')">
      三级总体 D（{{ count(detail, 'level3Total') }}）
    </button>
  </div>
  <SpecialDetailPager
    :rows="rows(detail)"
    :total="detail.rowCount"
    :page="detail.page"
    :page-size="detail.pageSize"
    @page="emit('page', $event)"
  />
</template>
