<script setup lang="ts">
import type { IndicatorDetailResult } from '../api/agent'
import SpecialDetailPager from './SpecialDetailPager.vue'
const props = defineProps<{ detail: IndicatorDetailResult }>()
const emit = defineEmits<{
  group: [value: string]
  page: [value: number]
}>()
</script>

<template>
  <div class="special-detail-heading">
    <div><span>实际开展术种（按科室）</span><strong>{{ detail.actualCount ?? 0 }}</strong></div>
    <div><span>备案术种口径（按科室展开）</span><strong>{{ detail.registeredCount ?? 0 }}</strong></div>
    <p>两个数字来自独立查询计划；没有可靠业务标识时，不臆造交集或差集。</p>
  </div>
  <div class="indicator-detail-groups">
    <button type="button" :class="{ active: detail.group === 'actual' }" @click="emit('group', 'actual')">
      实际开展（{{ detail.actualCount ?? 0 }}）
    </button>
    <button type="button" :class="{ active: detail.group === 'registered' }" @click="emit('group', 'registered')">
      备案目录（{{ detail.registeredCount ?? 0 }}）
    </button>
  </div>
  <p class="special-detail-current">
    当前查看：{{ detail.group === 'registered' ? '备案术种目录' : '实际开展术种' }}；
    两组来自独立查询，切换按钮不会把两组记录混在一起。
  </p>
  <SpecialDetailPager
    :rows="detail.group === 'registered' ? detail.registeredRows || [] : detail.actualRows || []"
    :total="detail.rowCount"
    :page="detail.page"
    :page-size="detail.pageSize"
    @page="emit('page', $event)"
  />
</template>
