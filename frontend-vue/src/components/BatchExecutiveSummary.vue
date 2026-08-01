<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import {
  createBatchReportSnapshot,
  downloadBatchReport,
  fetchIndicatorDetails,
  type BatchAnalysisAction,
  type BatchReportSnapshot,
  type InspectIndicatorAction,
} from '../api/agent'
import type { BatchIndicatorResult } from '../stores/agent'
import IndicatorResultCards from './IndicatorResultCards.vue'

const props = defineProps<{
  results: BatchIndicatorResult[]
  token: string
  modelId?: string
}>()

const emit = defineEmits<{
  action: [payload: InspectIndicatorAction | BatchAnalysisAction, requiresCloud?: boolean]
}>()

const reportOpen = ref(false)
const reportSnapshot = ref<BatchReportSnapshot | null>(null)
const reportLoading = ref(false)
const reportError = ref('')
const reportDownload = ref<'docx' | 'pdf' | 'xlsx' | ''>('')
const selectedAttention = ref<BatchIndicatorResult | null>(null)
const prewarmState = ref<'idle' | 'running' | 'ready' | 'partial'>('idle')
const prewarmed = ref(0)
const prewarmTotal = ref(0)
let prewarmGeneration = 0

type Outcome = 'reached' | 'not_reached' | 'pending' | 'no_sample' | 'failed'

interface AttentionItem {
  result: BatchIndicatorResult
  level: 'error' | 'warning' | 'pending'
  category: 'failure' | 'quality' | 'pending' | 'not_reached'
  label: string
  reason: string
  priority: number
}

const total = computed(() => props.results.length)
const batchRunId = computed(() => props.results.find((item) => item.batchRunId)?.batchRunId || '')
const statPeriod = computed(() => {
  const result = props.results.find((item) => item.statStart && item.statEnd)
  if (!result?.statStart || !result.statEnd) return '本次统计周期'
  return `${result.statStart.slice(0, 10)} 至 ${result.statEnd.slice(0, 10)}`
})

function numeric(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string') return null
  const parsed = Number(value.replace(/[%倍分钟小时天]/g, '').trim())
  return Number.isFinite(parsed) ? parsed : null
}

function outcome(item: BatchIndicatorResult): Outcome {
  if (item.status === 'FAILED') return 'failed'
  if (item.status === 'NO_SAMPLE') return 'no_sample'
  const value = numeric(item.resultValue)
  const target = numeric(item.targetValue)
  if (value === null || target === null || !item.targetDirection) return 'pending'
  const direction = item.targetDirection.replace(/\s/g, '')
  const reached = direction.includes('<')
    ? (direction.includes('=') ? value <= target : value < target)
    : direction.includes('>')
      ? (direction.includes('=') ? value >= target : value > target)
      : value === target
  return reached ? 'reached' : 'not_reached'
}

const counts = computed(() => {
  const values: Record<Outcome, number> = {
    reached: 0,
    not_reached: 0,
    pending: 0,
    no_sample: 0,
    failed: 0,
  }
  for (const item of props.results) values[outcome(item)]++
  return values
})

const qualityAbnormal = computed(() => props.results.filter((item) =>
  item.dataFreshness === 'extraction_failed_stale'
  || item.status === 'FAILED'
  || item.status === 'NO_SAMPLE').length)

const attentionItems = computed<AttentionItem[]>(() => {
  const items: AttentionItem[] = []
  for (const result of props.results) {
    const current = outcome(result)
    if (result.status === 'FAILED') {
      items.push({
        result,
        level: 'error',
        category: 'failure',
        label: '计算异常',
        reason: result.errorMessage || '数据源或执行链路未能完成',
        priority: 0,
      })
    } else if (result.status === 'NO_SAMPLE') {
      items.push({
        result,
        level: 'warning',
        category: 'quality',
        label: '无可用样本',
        reason: '统计窗口内没有可核算记录',
        priority: 1,
      })
    } else if (result.dataFreshness === 'extraction_failed_stale') {
      items.push({
        result,
        level: 'warning',
        category: 'quality',
        label: '数据质量',
        reason: '本次抽取失败，结果使用了旧快照',
        priority: 1,
      })
    } else if (current === 'pending') {
      items.push({
        result,
        level: 'pending',
        category: 'pending',
        label: '待确认',
        reason: '缺少可判定的目标值或指标方向',
        priority: 2,
      })
    } else if (current === 'not_reached') {
      items.push({
        result,
        level: 'warning',
        category: 'not_reached',
        label: '未达标',
        reason: `结果 ${formatValue(result)}，目标 ${formatTarget(result)}`,
        priority: 3,
      })
    }
  }
  return items.sort((left, right) =>
    left.priority - right.priority
    || left.result.ruleId.localeCompare(right.result.ruleId))
})

/**
 * 摘要只展示跨类别的五项代表，不让大量 NO_SAMPLE 淹没真正的失败、
 * 待确认和未达标。完整集合仍保留在报告和全部指标中。
 */
const visibleAttentionItems = computed<AttentionItem[]>(() => {
  const selected: AttentionItem[] = []
  const categories: AttentionItem['category'][] =
    ['failure', 'quality', 'pending', 'not_reached']
  for (const category of categories) {
    const item = attentionItems.value.find((candidate) =>
      candidate.category === category && !selected.includes(candidate))
    if (item) selected.push(item)
  }
  for (const item of attentionItems.value) {
    if (!selected.includes(item)) selected.push(item)
    if (selected.length >= 5) break
  }
  return selected.slice(0, 5)
})

function formatValue(item: BatchIndicatorResult): string {
  if (item.resultValue === undefined || item.resultValue === null) return '—'
  const value = Number(item.resultValue)
  const display = Number.isFinite(value)
    ? (Number.isInteger(value) ? String(value) : value.toFixed(2))
    : String(item.resultValue)
  if (item.unit === 'percentage' || item.unit === 'percent') return `${display}%`
  if (item.unit === 'ratio') return `${display} 倍`
  return item.unit ? `${display}${item.unit}` : display
}

function formatTarget(item: BatchIndicatorResult): string {
  if (item.targetValue === undefined || item.targetValue === null) return '待确认'
  const unit = item.unit === 'percentage' || item.unit === 'percent'
    ? '%' : item.unit === 'ratio' ? ' 倍' : ''
  return `${item.targetDirection || ''}${item.targetValue}${unit}`
}

function openAttentionAndInspect(item: AttentionItem) {
  // 用户主动操作优先：取消尚未开始的低优先级预热项；当前已进入医院锁的单项
  // 会自然完成，后续队列不再继续占用全局可变中间表。
  prewarmGeneration++
  selectedAttention.value = item.result
  inspectWithAi(item.result)
}

function inspectWithAi(item: BatchIndicatorResult) {
  emit('action', {
    action: 'inspect_indicator',
    batchRunId: item.batchRunId || '',
    indicatorId: item.ruleId,
    profileId: item.profileId,
  }, true)
}

function generateChecklist() {
  emit('action', {
    action: 'batch_confirmation_checklist',
    batchRunId: batchRunId.value,
  })
}

function explainQuality() {
  emit('action', {
    action: 'batch_data_quality_review',
    batchRunId: batchRunId.value,
  })
}

function printReport() {
  window.print()
}

async function openReport() {
  reportOpen.value = true
  await ensureReportSnapshot()
}

async function ensureReportSnapshot(): Promise<BatchReportSnapshot | null> {
  if (reportSnapshot.value) return reportSnapshot.value
  if (reportLoading.value || !batchRunId.value) return null
  reportLoading.value = true
  reportError.value = ''
  try {
    reportSnapshot.value = await createBatchReportSnapshot(props.token, batchRunId.value)
    return reportSnapshot.value
  } catch (error) {
    reportError.value = error instanceof Error ? error.message : '报告快照生成失败。'
    return null
  } finally {
    reportLoading.value = false
  }
}

async function downloadReport(format: 'docx' | 'pdf' | 'xlsx') {
  if (reportDownload.value) return
  const snapshot = await ensureReportSnapshot()
  if (!snapshot) return
  reportDownload.value = format
  reportError.value = ''
  try {
    const file = await downloadBatchReport(
      props.token, snapshot.reportId, format)
    const url = URL.createObjectURL(file.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = file.fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    reportError.value = error instanceof Error ? error.message : `${format} 下载失败。`
  } finally {
    reportDownload.value = ''
  }
}

async function prewarm(items: AttentionItem[]) {
  const generation = ++prewarmGeneration
  const candidates = items.slice(0, 5)
    .map((item) => item.result)
    .filter((item) => item.batchRunId
      && (item.status === 'SUCCESS' || item.status === 'NO_SAMPLE'))
  prewarmTotal.value = candidates.length
  if (!candidates.length) {
    prewarmState.value = 'ready'
    return
  }
  prewarmState.value = 'running'
  prewarmed.value = 0
  for (const item of candidates) {
    if (generation !== prewarmGeneration) {
      prewarmState.value = prewarmed.value ? 'partial' : 'idle'
      return
    }
    try {
      await fetchIndicatorDetails(
        props.token,
        item.ruleId,
        'denominator',
        item.batchRunId!,
        item.statStart || '',
        item.statEnd || '',
        item.profileId,
        1,
        50,
      )
      prewarmed.value++
    } catch {
      // 预热失败不得遮蔽摘要；用户点击时仍会获得原始、可解释的错误信息。
    }
    await new Promise<void>((resolve) => window.setTimeout(resolve, 0))
  }
  prewarmState.value = prewarmed.value === candidates.length ? 'ready' : 'partial'
}

watch(visibleAttentionItems, (items) => {
  void prewarm(items)
}, { immediate: true })
</script>

<template>
  <section class="batch-executive">
    <div class="batch-completion">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6" /></svg>
      <span>已解析参数 / 完成 {{ total }} 项指标 / 结果已按批次固化</span>
      <code v-if="batchRunId">{{ batchRunId }}</code>
      <small class="prewarm-status" :data-state="prewarmState">
        {{ prewarmState === 'running' ? `后台预热 ${prewarmed}/${prewarmTotal}`
          : prewarmState === 'ready' ? `重点明细已预热 ${prewarmed}`
            : prewarmState === 'partial' ? `已预热 ${prewarmed}，其余按需查询` : '明细按需查询' }}
      </small>
    </div>

    <article class="executive-card">
      <header class="executive-title">
        <div>
          <span class="section-kicker">本次运行结论</span>
          <h2>核心结论</h2>
          <p>{{ statPeriod }} · 所有数量均由当前批次动态生成</p>
        </div>
        <button type="button" class="report-text-link" @click="openReport">
          查看全部 {{ total }} 项
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
        </button>
      </header>

      <div class="executive-metrics">
        <div><strong>{{ total }}</strong><span>覆盖口径</span></div>
        <div data-tone="success"><strong>{{ counts.reached }}</strong><span>达标</span></div>
        <div data-tone="danger"><strong>{{ counts.not_reached }}</strong><span>未达标</span></div>
        <div data-tone="warning"><strong>{{ counts.pending }}</strong><span>待确认</span></div>
        <div data-tone="muted"><strong>{{ counts.no_sample + counts.failed }}</strong><span>无法计算</span></div>
        <div data-tone="quality">
          <strong>{{ total - qualityAbnormal }}<small>/{{ qualityAbnormal }}</small></strong>
          <span>质量正常/异常</span>
        </div>
      </div>

      <div class="executive-findings">
        <p><span>01</span><b>数量闭合</b>：达标、未达标、待确认和无法计算合计 {{ total }} 项。</p>
        <p><span>02</span><b>质量独立判断</b>：{{ qualityAbnormal }} 项存在数据质量或可用性问题，不与达标状态混淆。</p>
        <p><span>03</span><b>详情运行绑定</b>：分子、分母与后续报告均绑定批次 {{ batchRunId || '—' }}。</p>
      </div>

      <section class="attention-section">
        <header>
          <div>
            <span class="attention-pulse" aria-hidden="true"></span>
            <h3>需重点关注</h3>
            <small>跨类别展示最需处理的 5 项，无样本不会淹没其他问题</small>
          </div>
          <strong>重点展示 {{ visibleAttentionItems.length }}/{{ attentionItems.length }} 项</strong>
        </header>

        <div v-if="attentionItems.length" class="attention-list">
          <article
            v-for="item in visibleAttentionItems"
            :key="`${item.result.ruleId}-${item.result.profileId || ''}`"
            class="attention-row"
            :data-level="item.level"
            :class="{ active: selectedAttention === item.result }"
          >
            <button type="button" class="attention-main" @click="openAttentionAndInspect(item)">
              <span class="attention-badge">{{ item.label }}</span>
              <strong>{{ item.result.ruleName || item.result.ruleId }}</strong>
              <code>{{ item.result.ruleId }}</code>
              <small>{{ item.reason }} · 点击立即排查</small>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
            </button>
            <div v-if="selectedAttention === item.result" class="attention-detail">
              <IndicatorResultCards
                :results="[item.result]"
                :token="token"
                :model-id="modelId"
              />
              <p class="attention-sent">已发送结构化排查请求；回答会显示在本条报告下方。</p>
            </div>
          </article>
        </div>
        <p v-else class="attention-empty">本批次没有需要优先排查的指标。</p>
      </section>

      <footer class="executive-actions">
        <button type="button" class="primary-report" @click="openReport">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h9l3 3v15H6zM9 11h6M9 15h6" /></svg>
          查看完整报告（{{ total }} 项）
        </button>
      </footer>
    </article>

    <nav class="executive-followups" aria-label="报告快捷操作">
      <button type="button" @click="generateChecklist">
        <span>确认清单</span>
        <strong>把重点指标生成待确认清单</strong>
        <small>直接读取当前批次事实，不重新计算</small>
      </button>
      <button type="button" @click="explainQuality">
        <span>质量核查</span>
        <strong>哪些未达标可能是数据问题？</strong>
        <small>严格区分未达标与无样本/计算失败</small>
      </button>
      <button type="button" :disabled="!!reportDownload" @click="downloadReport('docx')">
        <span>报告导出</span>
        <strong>{{ reportDownload === 'docx' ? '正在生成 Word…' : '导出完整调研报告 Word 版' }}</strong>
        <small>按当前批次生成可追溯报告快照</small>
      </button>
    </nav>
  </section>

  <Teleport to="body">
    <div v-if="reportOpen" class="batch-report-backdrop" @click.self="reportOpen = false">
      <aside class="batch-report-drawer" role="dialog" aria-modal="true" aria-label="完整指标报告">
        <header class="batch-report-head">
          <div>
            <span>
              批次报告 · {{ reportSnapshot?.reportStatus === 'FORMAL' ? '正式' : '草稿' }}
              <template v-if="reportSnapshot"> · V{{ reportSnapshot.version }}</template>
            </span>
            <h2>完整调研报告（{{ total }} 项）</h2>
            <p>{{ statPeriod }} · {{ batchRunId }}</p>
          </div>
          <button type="button" aria-label="关闭完整报告" @click="reportOpen = false">×</button>
        </header>
        <div class="batch-report-toolbar">
          <button
            type="button"
            class="active"
            :disabled="!reportSnapshot || !!reportDownload"
            @click="downloadReport('docx')"
          >{{ reportDownload === 'docx' ? '正在生成 Word…' : '下载 Word' }}</button>
          <button
            type="button"
            :disabled="!reportSnapshot || !!reportDownload"
            @click="downloadReport('pdf')"
          >{{ reportDownload === 'pdf' ? '正在生成 PDF…' : '下载 PDF' }}</button>
          <button
            type="button"
            :disabled="!reportSnapshot || !!reportDownload"
            @click="downloadReport('xlsx')"
          >{{ reportDownload === 'xlsx' ? '正在生成 Excel…' : '下载 Excel' }}</button>
          <button type="button" :disabled="reportLoading" @click="printReport">打印预览</button>
          <span>存在待确认或异常项时，报告保持草稿状态</span>
        </div>
        <div class="batch-report-body">
          <p v-if="reportLoading" class="indicator-loading">正在固化报告快照…</p>
          <p v-if="reportError" class="indicator-error">{{ reportError }}</p>
          <section class="report-overview">
            <h3>一、总体情况</h3>
            <div class="executive-metrics compact">
              <div><strong>{{ total }}</strong><span>覆盖口径</span></div>
              <div data-tone="success"><strong>{{ counts.reached }}</strong><span>达标</span></div>
              <div data-tone="danger"><strong>{{ counts.not_reached }}</strong><span>未达标</span></div>
              <div data-tone="warning"><strong>{{ counts.pending }}</strong><span>待确认</span></div>
              <div data-tone="muted"><strong>{{ counts.no_sample + counts.failed }}</strong><span>无法计算</span></div>
            </div>
          </section>
          <section>
            <h3>二、各指标明细</h3>
            <IndicatorResultCards
              :results="results"
              :token="token"
              :model-id="modelId"
            />
          </section>
        </div>
      </aside>
    </div>
  </Teleport>
</template>
