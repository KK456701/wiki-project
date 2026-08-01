<script setup lang="ts">
import { computed } from 'vue'
import DetailRowsTable from './DetailRowsTable.vue'

const props = defineProps<{
  rows: Record<string, unknown>[]
  total?: number
  page?: number
  pageSize?: number
  emptyText?: string
}>()
const emit = defineEmits<{ page: [value: number] }>()
const currentPage = computed(() => props.page || 1)
const size = computed(() => props.pageSize || 50)
const totalPages = computed(() => Math.max(
  1, Math.ceil((props.total || 0) / size.value)))
</script>

<template>
  <p class="indicator-detail-summary">
    共 {{ total || 0 }} 条记录 · 第 {{ currentPage }}/{{ totalPages }} 页
  </p>
  <DetailRowsTable :rows="rows" :empty-text="emptyText || '当前数据组没有记录。'" />
  <nav v-if="totalPages > 1" class="detail-pagination" aria-label="特殊指标明细分页">
    <button type="button" :disabled="currentPage <= 1" @click="emit('page', currentPage - 1)">上一页</button>
    <span>{{ currentPage }} / {{ totalPages }}</span>
    <button type="button" :disabled="currentPage >= totalPages" @click="emit('page', currentPage + 1)">下一页</button>
  </nav>
</template>
