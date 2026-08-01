<script setup lang="ts">
import { computed } from 'vue'
import type { IndicatorDetailResult } from '../api/agent'
import DetailRowsTable from './DetailRowsTable.vue'

const props = defineProps<{
  detail?: IndicatorDetailResult
  group: 'numerator' | 'denominator'
}>()
const emit = defineEmits<{
  group: [value: 'numerator' | 'denominator']
  page: [value: number]
}>()

const currentPage = computed(() => props.detail?.page || 1)
const pageSize = computed(() => props.detail?.pageSize || 50)
const totalPages = computed(() => Math.max(
  1, Math.ceil((props.detail?.rowCount || 0) / pageSize.value)))
</script>

<template>
  <div class="indicator-detail-groups">
    <button type="button" :class="{ active: group === 'numerator' }" @click="emit('group', 'numerator')">分子明细</button>
    <button type="button" :class="{ active: group === 'denominator' }" @click="emit('group', 'denominator')">分母明细</button>
  </div>
  <template v-if="detail">
    <div class="detail-contract-summary">
      <span>卡片 {{ detail.cardNumerator ?? 0 }}/{{ detail.cardDenominator ?? 0 }}</span>
      <span>详情 {{ detail.detailNumerator ?? 0 }}/{{ detail.detailDenominator ?? 0 }}</span>
      <strong>对账通过</strong>
      <small v-if="detail.snapshotReused">已复用本批次详情快照</small>
    </div>
    <p class="indicator-detail-summary">
      共 {{ detail.rowCount || 0 }} 条记录 · 第 {{ currentPage }}/{{ totalPages }} 页
    </p>
    <DetailRowsTable :rows="detail.rows || []" empty-text="统计区间内没有明细记录。" />
    <nav v-if="totalPages > 1" class="detail-pagination" aria-label="明细分页">
      <button type="button" :disabled="currentPage <= 1" @click="emit('page', currentPage - 1)">上一页</button>
      <span>{{ currentPage }} / {{ totalPages }}</span>
      <button type="button" :disabled="currentPage >= totalPages" @click="emit('page', currentPage + 1)">下一页</button>
    </nav>
  </template>
</template>
