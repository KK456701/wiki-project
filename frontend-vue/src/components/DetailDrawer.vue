<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import {
  createIndicatorExport,
  downloadIndicatorExport,
  ensureIndicatorDetails,
  loadIndicatorDetailPage,
  type DetailPage,
  type DetailSnapshot,
} from '../api/agent'

const props = defineProps<{
  token: string
  runId: string
  canExport: boolean
}>()
const emit = defineEmits<{ close: [] }>()

const summary = ref<DetailSnapshot | null>(null)
const detail = ref<DetailPage | null>(null)
const group = ref<DetailPage['group']>('denominator')
const page = ref(1)
const pageSize = ref(50)
const loading = ref(true)
const exporting = ref(false)
const confirmed = ref(false)
const error = ref('')

const columns = computed(() => Object.keys(detail.value?.items[0] || {}))
const pageCount = computed(() => Math.max(1, Math.ceil((detail.value?.total || 0) / pageSize.value)))
const groupTitle: Record<DetailPage['group'], string> = {
  denominator: '统计范围',
  numerator: '达到要求',
  unmatched: '未达到要求',
}

onMounted(loadSnapshot)
watch([group, pageSize], async () => {
  page.value = 1
  await loadPage()
})

async function loadSnapshot() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await ensureIndicatorDetails(props.token, props.runId)
    await loadPage()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '明细加载失败。'
  } finally {
    loading.value = false
  }
}

async function loadPage() {
  if (!summary.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await loadIndicatorDetailPage(
      props.token, props.runId, group.value, page.value, pageSize.value,
    )
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '分页明细加载失败。'
  } finally {
    loading.value = false
  }
}

async function movePage(next: number) {
  if (next < 1 || next > pageCount.value || next === page.value) return
  page.value = next
  await loadPage()
}

async function exportWorkbook() {
  if (!confirmed.value || !props.canExport) return
  exporting.value = true
  error.value = ''
  try {
    const value = await createIndicatorExport(props.token, props.runId, true)
    await downloadIndicatorExport(props.token, value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Excel 导出失败。'
  } finally {
    exporting.value = false
  }
}

function display(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <section class="detail-drawer" role="dialog" aria-modal="true" aria-label="指标明细核对">
      <header class="detail-head">
        <div>
          <p class="eyebrow">Verified run · {{ runId }}</p>
          <h2>指标明细核对</h2>
          <p v-if="summary">{{ summary.rule_name }} · {{ summary.stat_start }} 至 {{ summary.stat_end }}</p>
        </div>
        <button class="drawer-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div v-if="summary" class="detail-summary">
        <article><span>统计范围</span><strong>{{ summary.denominator_count }}</strong></article>
        <article><span>达到要求</span><strong>{{ summary.numerator_count }}</strong></article>
        <article><span>未达到要求</span><strong>{{ summary.unmatched_count }}</strong></article>
        <article><span>口径版本</span><strong>{{ summary.effective_level === 'hospital' ? `本院 v${summary.hospital_version}` : `标准 v${summary.national_version}` }}</strong></article>
      </div>

      <div v-if="summary" class="detail-source">
        <span>数据源：{{ summary.source_database || '已配置业务库' }}</span>
        <span>表：{{ summary.source_tables.join('、') }}</span>
        <span>快照到期：{{ summary.expires_at }}</span>
      </div>

      <nav v-if="summary" class="detail-tabs" aria-label="明细分组">
        <button
          v-for="item in (['denominator', 'numerator', 'unmatched'] as const)"
          :key="item"
          type="button"
          :class="{ active: group === item }"
          @click="group = item"
        >{{ groupTitle[item] }}</button>
      </nav>

      <div v-if="error" class="detail-error">{{ error }}</div>
      <div v-else-if="loading" class="detail-loading"><span></span>正在校验聚合数量并加载脱敏明细…</div>
      <div v-else-if="detail" class="detail-table-shell">
        <table v-if="detail.items.length" class="detail-table">
          <thead><tr><th v-for="column in columns" :key="column">{{ column }}</th></tr></thead>
          <tbody>
            <tr v-for="(row, rowIndex) in detail.items" :key="rowIndex">
              <td v-for="column in columns" :key="column">{{ display(row[column]) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="detail-empty">{{ groupTitle[group] }}当前没有记录。</div>
      </div>

      <footer v-if="summary" class="detail-footer">
        <div class="detail-pagination">
          <span>共 {{ detail?.total || 0 }} 条</span>
          <select v-model="pageSize" aria-label="每页条数"><option :value="20">20 / 页</option><option :value="50">50 / 页</option><option :value="100">100 / 页</option></select>
          <button type="button" :disabled="page <= 1" @click="movePage(page - 1)">上一页</button>
          <strong>{{ page }} / {{ pageCount }}</strong>
          <button type="button" :disabled="page >= pageCount" @click="movePage(page + 1)">下一页</button>
        </div>
        <div v-if="canExport" class="detail-export">
          <label><input v-model="confirmed" type="checkbox" /> 我确认明细仅用于本院授权范围内核对</label>
          <button class="primary-button" type="button" :disabled="!confirmed || exporting" @click="exportWorkbook">
            {{ exporting ? '正在生成…' : '导出三表 Excel' }}
          </button>
        </div>
        <p v-else class="detail-permission-note">当前账号可查看脱敏明细，但没有导出权限。</p>
      </footer>
    </section>
  </div>
</template>
