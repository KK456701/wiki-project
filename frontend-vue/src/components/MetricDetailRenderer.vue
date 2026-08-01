<script setup lang="ts">
import type { IndicatorDetailResult } from '../api/agent'
import CountRatioDetail from './CountRatioDetail.vue'
import SumContributionDetail from './SumContributionDetail.vue'
import MedianSampleDetail from './MedianSampleDetail.vue'
import DualSourceDetail from './DualSourceDetail.vue'
import RateComparisonDetail from './RateComparisonDetail.vue'

defineProps<{
  kind?: string
  detail?: IndicatorDetailResult
  group: string
  loading: boolean
  error: string
}>()

const emit = defineEmits<{
  group: [value: string]
  page: [value: number]
}>()
</script>

<template>
  <p v-if="loading" class="indicator-loading">正在生成并核对本批次明细快照…</p>
  <p v-else-if="error" class="indicator-error">{{ error }}</p>
  <template v-else-if="detail">
    <SumContributionDetail
      v-if="kind === 'SUM_CONTRIBUTION'"
      :detail="detail"
      @page="emit('page', $event)"
    />
    <MedianSampleDetail
      v-else-if="kind === 'MEDIAN_SAMPLE'"
      :detail="detail"
      @page="emit('page', $event)"
    />
    <DualSourceDetail
      v-else-if="kind === 'DUAL_SOURCE'"
      :detail="detail"
      @group="emit('group', $event)"
      @page="emit('page', $event)"
    />
    <RateComparisonDetail
      v-else-if="kind === 'RATE_COMPARISON'"
      :detail="detail"
      @group="emit('group', $event)"
      @page="emit('page', $event)"
    />
    <CountRatioDetail
      v-else
      :detail="detail"
      :group="group === 'denominator' ? 'denominator' : 'numerator'"
      @group="emit('group', $event)"
      @page="emit('page', $event)"
    />
  </template>
</template>
