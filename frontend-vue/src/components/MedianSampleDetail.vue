<script setup lang="ts">
import type { IndicatorDetailResult } from '../api/agent'
import SpecialDetailPager from './SpecialDetailPager.vue'
defineProps<{ detail: IndicatorDetailResult }>()
const emit = defineEmits<{ page: [value: number] }>()
</script>

<template>
  <div class="special-detail-heading">
    <div><span>危急值报告时间中位数</span><strong>{{ detail.medianValue ?? '—' }}<small> 分钟</small></strong></div>
    <div><span>有效计算样本</span><strong>{{ detail.sampleCount ?? 0 }}</strong></div>
    <p>样本已按时间差排序；<code>__is_median_sample=1</code> 标出奇数中点或偶数时参与平均的两个样本。</p>
  </div>
  <SpecialDetailPager
    :rows="detail.rows || []"
    :total="detail.rowCount"
    :page="detail.page"
    :page-size="detail.pageSize"
    @page="emit('page', $event)"
  />
</template>
