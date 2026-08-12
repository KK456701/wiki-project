<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

import {
  executeSqlPreview,
  type SqlPreviewDatabaseRole,
  type SqlPreviewResult,
} from '../api/agent'

const props = withDefaults(defineProps<{
  token: string
  sql: string
  databaseRole?: string
  ruleId?: string
  profileId?: string
  statStart?: unknown
  statEnd?: unknown
  disabledReason?: string
  compact?: boolean
}>(), {
  databaseRole: '',
  ruleId: '',
  profileId: '',
  disabledReason: '',
  compact: false,
})

type Phase = 'idle' | 'validating' | 'querying' | 'formatting'

const phase = ref<Phase>('idle')
const result = ref<SqlPreviewResult | null>(null)
const error = ref('')
const dialogOpen = ref(false)
const page = ref(1)
const copied = ref(false)
let copyTimer: number | undefined

const role = computed<SqlPreviewDatabaseRole | ''>(() => {
  const value = props.databaseRole.toUpperCase()
  return value === 'BUSINESS' || value === 'REAL' ? value : ''
})
const databaseLabel = computed(() => role.value === 'BUSINESS'
  ? 'Oracle 业务库' : role.value === 'REAL' ? 'SQL Server 中间库' : '数据库')
const inferredReason = computed(() => {
  if (props.disabledReason) return props.disabledReason
  if (!props.sql.trim()) return '当前没有可执行的 SQL。'
  if (!props.token) return '登录状态已失效，请重新登录。'
  if (!props.ruleId) return '缺少指标标识，无法校验医院与可访问表。'
  if (!role.value) return '无法确定该 SQL 应执行的数据库。'
  const periodTokens = /:(?:start_time|startTime|marptBeginAt|end_time|endTime|marptEndAt)\b|@(?:startTime|endTime)\b/i
  if (periodTokens.test(props.sql) && (!props.statStart || !props.statEnd)) {
    return '当前 SQL 需要统计周期，但页面没有提供开始或结束时间。'
  }
  return ''
})
const busy = computed(() => phase.value !== 'idle')
const buttonText = computed(() => {
  if (phase.value === 'validating') return '正在校验…'
  if (phase.value === 'querying') return `正在查询 ${databaseLabel.value}…`
  if (phase.value === 'formatting') return '正在整理结果…'
  return '执行 SQL'
})
const totalPages = computed(() => Math.max(1, Math.ceil((result.value?.rows.length || 0) / 20)))
const visibleRows = computed(() => result.value?.rows.slice((page.value - 1) * 20, page.value * 20) || [])

async function execute(event: MouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  if (busy.value || inferredReason.value || !role.value) return
  result.value = null
  error.value = ''
  page.value = 1
  dialogOpen.value = true
  phase.value = 'validating'
  await nextTick()
  await new Promise<void>((resolve) => window.setTimeout(resolve, 80))
  phase.value = 'querying'
  try {
    const response = await executeSqlPreview(props.token, {
      sql: props.sql,
      databaseRole: role.value,
      ruleId: props.ruleId,
      profileId: props.profileId || props.ruleId,
      statStart: String(props.statStart || ''),
      statEnd: String(props.statEnd || ''),
    })
    phase.value = 'formatting'
    await nextTick()
    result.value = response
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '只读 SQL 执行失败。'
  } finally {
    phase.value = 'idle'
  }
}

function display(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function copySql() {
  const sql = result.value?.executedSql || props.sql
  await navigator.clipboard.writeText(sql)
  copied.value = true
  if (copyTimer) window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => { copied.value = false }, 1600)
}

function close() {
  if (!busy.value) dialogOpen.value = false
}

onBeforeUnmount(() => {
  if (copyTimer) window.clearTimeout(copyTimer)
})
</script>

<template>
  <span class="sql-preview-trigger" :data-compact="compact">
    <button
      type="button"
      class="sql-preview-button"
      :disabled="Boolean(inferredReason) || busy"
      :title="inferredReason || `只读查询 ${databaseLabel}，最多返回 200 行`"
      @click="execute"
    >
      <span v-if="busy" class="sql-preview-spinner" aria-hidden="true" />
      <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 7 7-7 7M15 19h3" /></svg>
      {{ buttonText }}
    </button>
  </span>

  <Teleport to="body">
    <div v-if="dialogOpen" class="sql-preview-backdrop" @click.self="close">
      <section class="sql-preview-dialog" role="dialog" aria-modal="true" aria-label="SQL 执行结果">
        <header>
          <div>
            <small>只读查询 · 最多 200 行</small>
            <h2>SQL 执行结果</h2>
            <p>仅查询当前 SQL，不会触发抽取写入、指标重算或正式发布。</p>
          </div>
          <button type="button" class="sql-preview-close" :disabled="busy" aria-label="关闭" @click="close">×</button>
        </header>

        <div v-if="busy" class="sql-preview-progress">
          <span class="sql-preview-spinner" aria-hidden="true" />
          <strong>{{ buttonText }}</strong>
          <p>服务器正在执行只读校验和限时查询，请勿重复提交。</p>
        </div>

        <div v-else-if="error" class="sql-preview-error">
          <strong>SQL 执行失败</strong>
          <p>{{ error }}</p>
          <button type="button" @click="dialogOpen = false">关闭</button>
        </div>

        <template v-else-if="result">
          <div class="sql-preview-stats">
            <div><span>执行状态</span><strong>查询完成</strong></div>
            <div><span>数据库</span><strong>{{ result.databaseLabel }}</strong></div>
            <div><span>耗时</span><strong>{{ result.durationMs }} ms</strong></div>
            <div><span>返回行数</span><strong>{{ result.rowCount }}<small v-if="result.truncated">+</small></strong></div>
          </div>
          <p v-if="result.truncated" class="sql-preview-truncated">结果超过 200 行，当前仅展示前 200 行；本功能不提供全量导出。</p>
          <div class="sql-preview-table-wrap">
            <table v-if="result.columns.length">
              <thead><tr><th v-for="column in result.columns" :key="column">{{ column }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, index) in visibleRows" :key="`${page}-${index}`">
                  <td v-for="column in result.columns" :key="column" :title="display(row[column])">{{ display(row[column]) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="sql-preview-empty"><strong>查询成功，没有返回数据</strong><p>列和结果行均为空。</p></div>
          </div>
          <nav v-if="totalPages > 1" class="sql-preview-pagination" aria-label="SQL 结果分页">
            <button type="button" :disabled="page <= 1" @click="page--">上一页</button>
            <span>第 {{ page }} / {{ totalPages }} 页</span>
            <button type="button" :disabled="page >= totalPages" @click="page++">下一页</button>
          </nav>
          <details class="sql-preview-executed">
            <summary><span>查看实际执行 SQL</span><button type="button" @click.prevent.stop="copySql">{{ copied ? '已复制' : '复制 SQL' }}</button></summary>
            <pre>{{ result.executedSql }}</pre>
          </details>
        </template>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.sql-preview-trigger{display:inline-flex}.sql-preview-button{display:inline-flex;align-items:center;justify-content:center;gap:7px;min-height:36px;padding:0 14px;border:1px solid #0d8b76;border-radius:9px;background:#fff;color:#087563;font:700 13px/1 system-ui;cursor:pointer;white-space:nowrap}.sql-preview-button:hover:not(:disabled){background:#eaf8f4}.sql-preview-button:disabled{border-color:#cbd8d5;background:#edf1f0;color:#91a29e;cursor:not-allowed}.sql-preview-button svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}[data-compact=true] .sql-preview-button{min-height:31px;padding:0 10px;font-size:12px}.sql-preview-spinner{width:15px;height:15px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:sql-spin .75s linear infinite}@keyframes sql-spin{to{transform:rotate(360deg)}}
.sql-preview-backdrop{position:fixed;z-index:5000;inset:0;display:flex;align-items:center;justify-content:center;padding:24px;background:rgba(5,30,27,.48);backdrop-filter:blur(5px)}.sql-preview-dialog{width:min(1320px,96vw);max-height:92vh;overflow:auto;border:1px solid #d7e5e1;border-radius:22px;background:#fdfefe;box-shadow:0 24px 80px rgba(5,45,38,.22);color:#102f2b}.sql-preview-dialog>header{display:flex;justify-content:space-between;gap:24px;padding:26px 28px 22px;border-bottom:1px solid #dce8e5;background:linear-gradient(135deg,#fff 55%,#eefaf6)}.sql-preview-dialog h2{margin:5px 0 4px;font-size:28px}.sql-preview-dialog header small{color:#07816c;font-weight:800}.sql-preview-dialog header p{margin:0;color:#617570}.sql-preview-close{width:42px;height:42px;border:1px solid #c8d9d5;border-radius:12px;background:#fff;color:#163f38;font-size:28px;cursor:pointer}.sql-preview-progress,.sql-preview-error{display:flex;min-height:270px;flex-direction:column;align-items:center;justify-content:center;padding:40px;text-align:center}.sql-preview-progress .sql-preview-spinner{width:30px;height:30px;color:#07816c}.sql-preview-progress strong,.sql-preview-error strong{margin-top:16px;font-size:20px}.sql-preview-progress p,.sql-preview-error p{max-width:720px;color:#667974}.sql-preview-error strong{color:#b33333}.sql-preview-error button{padding:9px 18px;border:0;border-radius:9px;background:#137f6e;color:#fff;font-weight:700}.sql-preview-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:22px 28px 14px}.sql-preview-stats div{padding:15px 17px;border:1px solid #dbe8e5;border-radius:13px;background:#f7fbfa}.sql-preview-stats span{display:block;color:#6c7e7a;font-size:12px}.sql-preview-stats strong{display:block;margin-top:7px;font-size:17px}.sql-preview-stats small{color:#07816c}.sql-preview-truncated{margin:0 28px 12px;padding:10px 13px;border-radius:8px;background:#fff5db;color:#745910;font-size:13px}.sql-preview-table-wrap{margin:0 28px;overflow:auto;border:1px solid #dce8e5;border-radius:13px;background:#fff;max-height:47vh}.sql-preview-table-wrap table{width:100%;border-collapse:collapse;font-size:12px}.sql-preview-table-wrap th{position:sticky;top:0;z-index:1;padding:11px 12px;background:#eaf6f3;color:#16463e;text-align:left;white-space:nowrap}.sql-preview-table-wrap td{max-width:420px;padding:9px 12px;border-top:1px solid #edf2f1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.sql-preview-table-wrap tr:nth-child(even) td{background:#fafcfb}.sql-preview-empty{padding:42px;text-align:center}.sql-preview-empty p{color:#74847f}.sql-preview-pagination{display:flex;align-items:center;justify-content:center;gap:14px;padding:15px}.sql-preview-pagination button{padding:7px 13px;border:1px solid #bcd8d1;border-radius:8px;background:#fff;color:#087563}.sql-preview-executed{margin:0 28px 26px;border:1px solid #dce8e5;border-radius:11px;background:#f8fbfa}.sql-preview-executed summary{display:flex;align-items:center;justify-content:space-between;padding:13px 16px;cursor:pointer;font-weight:750}.sql-preview-executed summary button{padding:6px 11px;border:1px solid #9fcfc4;border-radius:7px;background:#fff;color:#087563}.sql-preview-executed pre{margin:0;padding:16px;border-top:1px solid #dce8e5;overflow:auto;background:#102b28;color:#d8f5ed;font:12px/1.65 Consolas,monospace;white-space:pre-wrap}@media(max-width:760px){.sql-preview-backdrop{padding:8px}.sql-preview-dialog>header{padding:20px}.sql-preview-stats{grid-template-columns:repeat(2,1fr);padding:16px}.sql-preview-table-wrap,.sql-preview-executed{margin-left:16px;margin-right:16px}}
</style>
