<script setup lang="ts">
defineProps<{
  rows: Record<string, unknown>[]
  emptyText?: string
}>()

function columns(rows: Record<string, unknown>[]): string[] {
  return rows[0] ? Object.keys(rows[0]) : []
}

function cell(row: Record<string, unknown>, column: string): string {
  const value = row[column]
  return value === undefined || value === null ? '' : String(value)
}
</script>

<template>
  <div v-if="rows.length" class="indicator-detail-table">
    <table>
      <thead>
        <tr><th v-for="column in columns(rows)" :key="column">{{ column }}</th></tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="index">
          <td v-for="column in columns(rows)" :key="column">{{ cell(row, column) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
  <p v-else class="indicator-loading">{{ emptyText || '当前数据集没有记录。' }}</p>
</template>
