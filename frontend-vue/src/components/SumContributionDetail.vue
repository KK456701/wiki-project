<script setup lang="ts">
import type { IndicatorDetailResult } from '../api/agent'
import SpecialDetailPager from './SpecialDetailPager.vue'
defineProps<{ detail: IndicatorDetailResult }>()
const emit = defineEmits<{ page: [value: number] }>()
</script>

<template>
  <div class="special-detail-heading">
    <div><span>抢救成功贡献合计</span><strong>{{ detail.numeratorContributionTotal ?? 0 }}</strong></div>
    <div><span>抢救总例次贡献合计</span><strong>{{ detail.denominatorContributionTotal ?? 0 }}</strong></div>
    <p>该指标按每条记录的贡献值求和，不使用返回行数冒充分子或分母。</p>
  </div>
  <SpecialDetailPager
    :rows="detail.rows || []"
    :total="detail.rowCount"
    :page="detail.page"
    :page-size="detail.pageSize"
    @page="emit('page', $event)"
  />
</template>
