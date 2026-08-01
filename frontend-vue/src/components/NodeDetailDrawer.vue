<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { loadAgentRun } from '../api/agent'
import type { ExecutionNode } from '../stores/agent'

type TraceNode = Record<string, unknown>

const props = defineProps<{ token: string; traceId: string; node: ExecutionNode }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const traceNode = ref<TraceNode | null>(null)
const traceNodes = ref<TraceNode[]>([])
const searchText = ref('')
const databaseFilter = ref('ALL')
const categoryFilter = ref('ALL')
const focusedExecution = ref<TraceNode | null>(null)
const openGroups = ref<Set<string>>(new Set(['CONFIRMED']))
const realDetailsOpen = ref(false)
let refreshTimer: number | undefined

const inputData = computed(() => redact(traceNode.value?.inputData ?? {}))
const outputData = computed(() => redact(traceNode.value?.outputData ?? {}))
const hasTraceDetails = computed(() =>
  traceNode.value !== null && ('inputData' in traceNode.value || 'outputData' in traceNode.value),
)
const hasError = computed(() =>
  props.node.status === 'failed'
  || Boolean(traceNode.value?.errorCode)
  || Boolean(traceNode.value?.errorMessage),
)
const isInitializationNode = computed(() =>
  String(traceNode.value?.nodeName || props.node.nodeName) === 'batch_data_initialization_validation',
)
const initializationOutput = computed<Record<string, unknown>>(() =>
  asRecord(traceNode.value?.outputData),
)
const initializationReady = computed(() => Number(initializationOutput.value.profileCount || 0) > 0)
const initializationItems = computed<Record<string, unknown>[]>(() => {
  const items = asRecords(initializationOutput.value.items)
  return items
    .filter((item) => databaseFilter.value === 'ALL'
      || String(item.databaseRole || '') === databaseFilter.value)
    .filter((item) => categoryFilter.value === 'ALL'
      || String(item.category || '') === categoryFilter.value)
    .filter((item) => {
      const needle = searchText.value.trim().toLowerCase()
      if (!needle) return true
      return [item.ruleId, item.ruleName, item.profileId, item.tableName, item.fieldName]
        .some((value) => String(value || '').toLowerCase().includes(needle))
    })
    .sort((left, right) => severityRank(left.severity) - severityRank(right.severity))
})
const impactSummary = computed(() => ({
  confirmed: impactProfileCount('CONFIRMED'),
  possible: impactProfileCount('POSSIBLE'),
  noImpact: impactProfileCount('NO_IMPACT'),
  unknown: impactProfileCount('UNKNOWN'),
  confirmedChecks: impactItems('CONFIRMED').length,
  possibleChecks: impactItems('POSSIBLE').length,
  noImpactChecks: impactItems('NO_IMPACT').length,
  unknownChecks: impactItems('UNKNOWN').length,
}))
const realSnapshotNodes = computed(() => traceNodes.value
  .filter((node) => String(node.nodeName || '') === 'real_snapshot_data_validation')
  .sort((left, right) => severityRank(left.status) - severityRank(right.status)))
const realSnapshotSummary = computed(() => {
  const total = Number(initializationOutput.value.runnableCount || 0)
  const completed = realSnapshotNodes.value.length
  const failed = realSnapshotNodes.value.filter((node) => String(node.status || '') === 'failed').length
  return {
    total,
    completed,
    failed,
    success: completed - failed,
    waiting: Math.max(0, total - completed),
  }
})
const validationGroups = computed(() => [
  {
    key: 'CONFIRMED',
    label: '确定影响计算',
    hint: '已经导致无样本或阻断，系统不会返回一个看似正常的错误结果。',
    items: impactItems('CONFIRMED'),
  },
  {
    key: 'POSSIBLE',
    label: '可能影响结果',
    hint: '存在空值、关联缺口或空表，需要结合字段作用和医院业务确认。',
    items: impactItems('POSSIBLE'),
  },
  {
    key: 'NO_IMPACT',
    label: '不影响结果',
    hint: '已经证明只涉及展示信息，不参与筛选、关联、分子或分母。',
    items: impactItems('NO_IMPACT'),
  },
  {
    key: 'UNKNOWN',
    label: '无法判断',
    hint: '程序无法安全生成该项质量检查；这不等于数据异常。',
    items: impactItems('UNKNOWN'),
  },
])

watch(
  () => [props.traceId, props.node.id],
  async () => {
    loading.value = true
    error.value = ''
    traceNode.value = fallbackNode()
    try {
      const trace = await loadAgentRun(props.token, props.traceId)
      applyTrace(trace)
      if (!hasTraceDetails.value && !isInitializationNode.value) {
        error.value = '该节点的详细参数尚未写入，请稍后再试。'
      }
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : '节点明细加载失败。'
      error.value = `详细入出参暂不可读取：${message}`
    } finally {
      loading.value = false
    }
    if (isInitializationNode.value) startRefresh()
  },
  { immediate: true },
)

watch(categoryFilter, (category) => {
  if (category === 'ALL') return
  const next = new Set(openGroups.value)
  if (category === 'UNSUPPORTED') next.add('UNKNOWN')
  else if (category === 'NULL_RATE' || category === 'JOIN_COVERAGE' || category === 'NO_DATA') {
    next.add('POSSIBLE')
    next.add('CONFIRMED')
  } else next.add('CONFIRMED')
  openGroups.value = next
})

onBeforeUnmount(() => stopRefresh())

function startRefresh() {
  stopRefresh()
  refreshTimer = window.setInterval(async () => {
    try {
      const trace = await loadAgentRun(props.token, props.traceId)
      applyTrace(trace)
    } catch {
      // 保留最后一次成功读取的证据，避免轮询抖动清空详情。
    }
  }, 2500)
}

function applyTrace(trace: Record<string, unknown>) {
  const nodes = Array.isArray(trace.nodes) ? trace.nodes as TraceNode[] : []
  traceNodes.value = nodes
  const sameName = nodes.filter((node) =>
    String(node.nodeName || '') === props.node.nodeName
    && String(node.subtaskId || 'root') === (props.node.subtaskId || 'root'),
  )
  const matched = sameName[props.node.occurrence]
    || nodes.find((node) => String(node.nodeName || '') === props.node.nodeName)
  if (matched) traceNode.value = matched
}

function isGroupOpen(key: string): boolean {
  return openGroups.value.has(key)
}

function toggleGroup(event: Event, key: string) {
  const details = event.currentTarget as HTMLDetailsElement
  const next = new Set(openGroups.value)
  if (details.open) next.add(key)
  else next.delete(key)
  openGroups.value = next
}

function toggleRealDetails(event: Event) {
  realDetailsOpen.value = (event.currentTarget as HTMLDetailsElement).open
}

function stopRefresh() {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  refreshTimer = undefined
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
}

function asRecords(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') as Record<string, unknown>[] : []
}

function impactLevel(item: Record<string, unknown>): string {
  const severity = String(item.severity || '').toUpperCase()
  const category = String(item.category || '').toUpperCase()
  if (item.affectsCalculation === true || severity === 'BLOCKED' || severity === 'NO_SAMPLE') {
    return 'CONFIRMED'
  }
  if (category === 'UNSUPPORTED') return 'UNKNOWN'
  if (category === 'NULL_RATE' || category === 'JOIN_COVERAGE' || category === 'NO_DATA') {
    return 'POSSIBLE'
  }
  return 'NO_IMPACT'
}

function impactItems(level: string): Record<string, unknown>[] {
  return initializationItems.value.filter((item) => impactLevel(item) === level)
}

function impactProfileCount(level: string): number {
  return new Set(impactItems(level).map((item) => String(item.profileId || item.ruleId || ''))
    .filter(Boolean)).size
}

function categoryText(value: unknown): string {
  const category = String(value || '')
  if (category === 'MISSING_TABLE') return '缺少数据表'
  if (category === 'MISSING_COLUMN') return '缺少字段'
  if (category === 'NO_DATA') return '无数据'
  if (category === 'NULL_RATE') return '字段存在空值'
  if (category === 'JOIN_COVERAGE') return '关联未完全匹配'
  if (category === 'UNSUPPORTED') return '检查未完成'
  return '校验信息'
}

function impactText(item: Record<string, unknown>): string {
  const level = impactLevel(item)
  if (level === 'CONFIRMED') return '确定影响当前计算'
  if (level === 'POSSIBLE') return '可能影响结果，计算继续'
  if (level === 'UNKNOWN') return '无法判断，不代表数据异常'
  return '已证明不影响结果'
}

function severityRank(value: unknown): number {
  const severity = String(value || '').toUpperCase()
  if (severity === 'FAILED' || severity === 'ERROR') return 0
  if (severity === 'BLOCKED') return 0
  if (severity === 'WARNING') return 1
  if (severity === 'NO_SAMPLE') return 2
  return 3
}

function qualityText(value: unknown): string {
  const quality = String(value || '')
  if (quality === 'ALL_BLOCKED') return '全部阻断'
  if (quality === 'PARTIAL_BLOCKED') return '部分阻断'
  if (quality === 'WARNING') return '有警告'
  return '正常'
}

function databaseText(value: unknown): string {
  const role = String(value || '').toLowerCase()
  return role === 'business' ? '业务库' : role === 'real' ? '真实库' : '—'
}

function percent(value: unknown): string {
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(2)}%` : '—'
}

function focusExecution(profileId: unknown) {
  focusedExecution.value = traceNodes.value.find((node) =>
    String(node.nodeName || '') === 'batch_indicator'
    && String(asRecord(node.inputData).profileId || '') === String(profileId || '')) || null
}

function fallbackNode(): TraceNode {
  return {
    nodeName: props.node.nodeName,
    nodeType: props.node.nodeType,
    nodeTitle: props.node.label,
    status: props.node.status,
    durationMs: props.node.durationMs,
    toolName: props.node.toolName,
    modelId: props.node.modelId,
    capability: props.node.capability,
    subtaskId: props.node.subtaskId || 'root',
    processingSummary: '以下为运行时实时摘要；详细参数需由 Trace 接口返回。',
  }
}

function redact(value: unknown, key = ''): unknown {
  const lower = key.toLowerCase()
  const blocked = lower.includes('prompt')
    || lower.includes('authorization')
    || lower.includes('password')
    || lower.includes('secret')
    || lower.includes('patient')
    || ['token', 'access_token', 'refresh_token', 'messages', 'conversation_history',
      'raw_rows', 'rows'].includes(lower)
  if (blocked) return '[不在对话页展示]'
  if (Array.isArray(value)) {
    const items = value.slice(0, 50).map((item) => redact(item))
    if (value.length > 50) items.push(`…其余 ${value.length - 50} 项已收起`)
    return items
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([name, item]) => [name, redact(item, name)]),
    )
  }
  return value
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function formatDuration(value?: unknown): string {
  const duration = Number(value ?? props.node.durationMs ?? 0)
  return duration < 1000 ? `${duration} ms` : `${(duration / 1000).toFixed(2)} 秒`
}

function statusText(value?: unknown): string {
  const status = String(value || props.node.status)
  if (status === 'success') return '已完成'
  if (status === 'failed') return '失败'
  if (status === 'warning' || status === 'incomplete') return '需关注'
  return status
}
</script>

<template>
  <div class="drawer-backdrop node-detail-backdrop" @click.self="emit('close')">
    <aside class="node-detail-drawer" :class="{ 'is-initialization': isInitializationNode }" aria-label="节点明细">
      <header class="node-detail-head">
        <div>
          <p class="eyebrow">{{ node.category }} · Node detail</p>
          <h2>节点明细</h2>
          <span>{{ node.label }}</span>
        </div>
        <button class="icon-button" type="button" aria-label="关闭节点明细" @click="emit('close')">×</button>
      </header>

      <p v-if="loading" class="drawer-state">正在读取节点参数…</p>

      <div v-else-if="traceNode" class="node-detail-body">
        <p v-if="error" class="node-detail-warning">{{ error }}</p>
        <section class="node-detail-overview">
          <div><span>状态</span><strong :data-state="String(traceNode.status || node.status)">{{ statusText(traceNode.status) }}</strong></div>
          <div><span>耗时</span><strong>{{ formatDuration(traceNode.durationMs) }}</strong></div>
          <div><span>模型 / 工具</span><strong>{{ String(traceNode.modelId || traceNode.toolName || '—') }}</strong></div>
          <div><span>子任务</span><strong>{{ String(traceNode.subtaskId || 'root') }}</strong></div>
        </section>

        <template v-if="isInitializationNode">
          <section v-if="!initializationReady" class="initialization-pending" aria-live="polite">
            <i aria-hidden="true"></i>
            <div>
              <p class="eyebrow">双库初始化校验</p>
              <h3>正在逐表检查业务库与真实库</h3>
              <p>节点已经开始执行。完成前先显示进度，不会再等全部 SQL 跑完才出现。</p>
            </div>
          </section>

          <template v-else>
          <section class="initialization-hero" :data-quality="String(initializationOutput.qualityStatus || 'NORMAL')">
            <div>
              <p class="eyebrow">Dual database preflight</p>
              <h3>{{ qualityText(initializationOutput.qualityStatus) }}</h3>
              <p>业务源库检查数据质量，真实库先核对结构；抽取完成后在同一处补齐本次快照验证。</p>
            </div>
            <strong>{{ Number(initializationOutput.profileCount || 0) }}<small>个口径</small></strong>
          </section>

          <section class="initialization-stats" aria-label="初始化校验汇总">
            <div><strong>{{ Number(initializationOutput.runnableCount || 0) }}</strong><span>可继续</span></div>
            <div><strong>{{ Number(initializationOutput.noSampleCount || 0) }}</strong><span>无样本</span></div>
            <div><strong>{{ Number(initializationOutput.blockedCount || 0) }}</strong><span>被阻断</span></div>
            <div><strong>{{ Number(initializationOutput.missingTableCount || 0) }}</strong><span>缺表</span></div>
            <div><strong>{{ Number(initializationOutput.missingColumnCount || 0) }}</strong><span>缺字段</span></div>
            <div><strong>{{ Number(initializationOutput.emptySourceCount || 0) }}</strong><span>无数据</span></div>
          </section>

          <section class="initialization-impact-summary" aria-label="结果影响分级">
            <div data-impact="confirmed">
              <strong>{{ impactSummary.confirmed }}</strong><span>个口径确定受影响</span>
              <small>阻断或无样本</small>
            </div>
            <div data-impact="possible">
              <strong>{{ impactSummary.possible }}</strong><span>个口径可能受影响</span>
              <small>{{ impactSummary.possibleChecks }} 条空值、关联或空表检查</small>
            </div>
            <div data-impact="none">
              <strong>{{ impactSummary.noImpact }}</strong><span>个口径确认不受影响</span>
              <small>仅展示字段等非计算信息</small>
            </div>
            <div data-impact="unknown">
              <strong>{{ impactSummary.unknown }}</strong><span>个口径暂无法判断</span>
              <small>{{ impactSummary.unknownChecks }} 项检查未完成</small>
            </div>
          </section>
          <p class="initialization-count-note">口径数在每一类内去重；同一口径可同时存在“可能影响”和“无法判断”，四类数字不能相加。展开后显示逐字段、逐关联证据，同一字段被多个口径使用时会出现多条检查。</p>

          <section class="initialization-connection-strip">
            <span :data-ok="Boolean(initializationOutput.businessConnected)">业务库 {{ initializationOutput.businessConnected ? '已连接' : '不可用' }}</span>
            <span :data-ok="Boolean(initializationOutput.realConnected)">真实库 {{ initializationOutput.realConnected ? '已连接' : '不可用' }}</span>
            <span>本次实查 · 未复用</span>
            <span>{{ formatDuration(initializationOutput.durationMs) }}</span>
          </section>

          <section class="initialization-filters" aria-label="校验结果筛选">
            <input v-model="searchText" type="search" placeholder="搜索指标编码、表名或字段名">
            <select v-model="databaseFilter" aria-label="数据库筛选">
              <option value="ALL">全部数据库</option>
              <option value="business">业务库</option>
              <option value="real">真实库</option>
            </select>
            <select v-model="categoryFilter" aria-label="问题类型筛选">
              <option value="ALL">全部类型</option>
              <option value="MISSING_TABLE">缺表</option>
              <option value="MISSING_COLUMN">缺字段</option>
              <option value="NO_DATA">无数据</option>
              <option value="NULL_RATE">空值率</option>
              <option value="JOIN_COVERAGE">关联覆盖率</option>
              <option value="UNSUPPORTED">未完成检查</option>
            </select>
          </section>

          <details
            v-for="group in validationGroups"
            :key="group.key"
            class="initialization-group"
            :open="isGroupOpen(group.key)"
            @toggle="toggleGroup($event, group.key)"
          >
            <summary>
              <div><h3>{{ group.label }}</h3><p>{{ group.hint }}</p></div>
              <span>{{ impactProfileCount(group.key) }} 个口径 · {{ group.items.length }} 条检查</span>
              <small>{{ isGroupOpen(group.key) ? '收起' : '展开' }}</small>
            </summary>
            <template v-if="isGroupOpen(group.key)">
              <p v-if="group.items.length === 0" class="initialization-empty">未发现</p>
              <article
                v-for="(item, itemIndex) in group.items"
                v-else
                :key="`${group.key}-${String(item.profileId || '')}-${String(item.tableName || '')}-${String(item.fieldName || '')}-${itemIndex}`"
                class="validation-item"
                :data-severity="String(item.severity || item.decision || 'NORMAL')"
              >
              <div class="validation-item-title">
                <span>{{ databaseText(item.databaseRole) }} · {{ categoryText(item.category) }}</span>
                <b>{{ String(item.action || '继续') }}</b>
              </div>
              <button type="button" class="validation-indicator-link" @click="focusExecution(item.profileId)">
                {{ String(item.ruleId || '—') }} · {{ String(item.ruleName || '') }}
              </button>
              <p>{{ String(item.profileLabel || item.profileId || '默认口径') }}</p>
              <dl>
                <div><dt>对象</dt><dd><code>{{ String(item.tableName || '—') }}{{ item.fieldName ? `.${String(item.fieldName)}` : '' }}</code><span v-if="item.fieldLabel">{{ String(item.fieldLabel) }}</span></dd></div>
                <div><dt>来源 / 范围</dt><dd>{{ String(item.sourceSystem || '未登记') }} · {{ String(item.scope || '—') }}</dd></div>
                <div v-if="item.actualCount !== null && item.actualCount !== undefined"><dt>实际数量</dt><dd>{{ Number(item.actualCount).toLocaleString() }}</dd></div>
                <div v-if="item.nullCount !== null && item.nullCount !== undefined"><dt>空值</dt><dd>{{ Number(item.nullCount).toLocaleString() }} / {{ Number(item.totalCount || 0).toLocaleString() }}（{{ percent(item.rate) }}）</dd></div>
                <div v-if="item.matchedCount !== null && item.matchedCount !== undefined"><dt>关联覆盖</dt><dd>{{ Number(item.matchedCount).toLocaleString() }} / {{ Number(item.totalCount || 0).toLocaleString() }}，未匹配 {{ Number(item.unmatchedCount || 0).toLocaleString() }}（{{ percent(item.rate) }}）</dd></div>
                <div><dt>影响判断</dt><dd>{{ impactText(item) }}</dd></div>
                <div><dt>原因</dt><dd>{{ String(item.message || '—') }}</dd></div>
                <div><dt>错误码</dt><dd><code>{{ String(item.errorCode || '—') }}</code></dd></div>
              </dl>
              <details v-if="item.sql" class="validation-sql">
                <summary>查看校验 SQL</summary>
                <p class="validation-sql-purpose">用途：初始化阶段生成的只读聚合探针，只统计表行数、空值或关联覆盖情况；不修改数据，也不是正式指标计算 SQL。</p>
                <div class="validation-sql-meta">
                  <span>{{ databaseText(item.databaseRole) }}</span>
                  <span>{{ formatDuration(item.durationMs) }}</span>
                  <span>返回 {{ Number(item.returnedRows || 0) }} 行</span>
                </div>
                <pre>{{ String(item.sql) }}</pre>
                <pre v-if="Object.keys(asRecord(item.parameters)).length">参数：{{ pretty(item.parameters) }}</pre>
                <p v-if="item.databaseError" class="node-detail-warning">{{ String(item.databaseError) }}</p>
              </details>
              </article>
            </template>
          </details>

          <details
            class="initialization-group real-snapshot-group"
            :open="realDetailsOpen || realSnapshotSummary.failed > 0"
            @toggle="toggleRealDetails"
          >
            <summary>
              <h3>真实库本次数据校验</h3>
              <span>{{ realSnapshotSummary.completed }}/{{ realSnapshotSummary.total }}</span>
              <small v-if="realSnapshotSummary.failed > 0">{{ realSnapshotSummary.failed }} 项失败，展开查看</small>
              <small v-else-if="realSnapshotSummary.waiting > 0">正在抽取，剩余 {{ realSnapshotSummary.waiting }} 项</small>
              <small v-else>全部一致，按需展开</small>
            </summary>
            <div class="real-snapshot-summary">
              <span data-state="success">一致 {{ realSnapshotSummary.success }}</span>
              <span :data-state="realSnapshotSummary.failed ? 'failed' : 'muted'">失败 {{ realSnapshotSummary.failed }}</span>
              <span :data-state="realSnapshotSummary.waiting ? 'running' : 'muted'">等待 {{ realSnapshotSummary.waiting }}</span>
            </div>
            <p v-if="realSnapshotNodes.length === 0" class="initialization-empty">
              前置校验已完成，正在逐口径抽取；每完成一个口径，这里会追加业务库源行数与真实库写入行数。
            </p>
            <article v-for="snapshot in realSnapshotNodes" v-else :key="String(snapshot.nodeId || snapshot.subtaskId)" class="validation-item">
              <div class="validation-item-title">
                <span>{{ String(asRecord(snapshot.outputData).ruleId || '—') }} · {{ String(asRecord(snapshot.outputData).profileLabel || asRecord(snapshot.outputData).profileId || '') }}</span>
                <b>{{ asRecord(snapshot.outputData).matched === false ? '不一致' : '一致' }}</b>
              </div>
              <dl>
                <div><dt>目标表</dt><dd><code>{{ String(asRecord(snapshot.outputData).tableName || '—') }}</code></dd></div>
                <div><dt>业务库源行数</dt><dd>{{ String(asRecord(snapshot.outputData).businessSourceCount ?? '未生成') }}</dd></div>
                <div><dt>真实库写入行数</dt><dd>{{ String(asRecord(snapshot.outputData).realRowCount ?? '—') }}</dd></div>
                <div><dt>状态</dt><dd>{{ statusText(snapshot.status) }}</dd></div>
                <div v-if="asRecord(snapshot.outputData).message || asRecord(snapshot.outputData).databaseError"><dt>原因</dt><dd>{{ String(asRecord(snapshot.outputData).message || asRecord(snapshot.outputData).databaseError) }}</dd></div>
              </dl>
              <details v-if="asRecord(snapshot.outputData).sql" class="validation-sql">
                <summary>查看校验 SQL</summary>
                <pre>{{ String(asRecord(snapshot.outputData).sql) }}</pre>
              </details>
            </article>
          </details>

          <section v-if="focusedExecution" class="node-detail-section execution-focus">
            <button type="button" @click="focusedExecution = null">关闭指标执行定位</button>
            <p class="eyebrow">Execution node</p>
            <h3>{{ String(asRecord(focusedExecution.inputData).ruleId || '') }} · 对应执行节点</h3>
            <dl>
              <div><dt>状态</dt><dd>{{ statusText(focusedExecution.status) }}</dd></div>
              <div><dt>口径</dt><dd>{{ String(asRecord(focusedExecution.inputData).profileLabel || asRecord(focusedExecution.inputData).profileId || '—') }}</dd></div>
              <div><dt>耗时</dt><dd>{{ formatDuration(focusedExecution.durationMs) }}</dd></div>
              <div><dt>输出</dt><dd><pre>{{ pretty(redact(focusedExecution.outputData || {})) }}</pre></dd></div>
            </dl>
          </section>

          <details class="node-params">
            <summary><strong>原始校验证据</strong><span>完整 Trace 输出</span></summary>
            <pre>{{ pretty(outputData) }}</pre>
          </details>
          </template>
        </template>

        <template v-else>
          <section class="node-detail-section">
            <p class="eyebrow">Overview</p>
            <h3>处理概览</h3>
            <p>{{ String(traceNode.processingSummary || '该节点已完成受控处理。') }}</p>
            <dl>
              <div><dt>节点标识</dt><dd><code>{{ String(traceNode.nodeName || node.nodeName) }}</code></dd></div>
              <div><dt>节点 ID</dt><dd><code>{{ String(traceNode.nodeId || '—') }}</code></dd></div>
              <div><dt>能力</dt><dd>{{ String(traceNode.capability || node.capability || '—') }}</dd></div>
              <div><dt>数据 / 运行引用</dt><dd>{{ String(traceNode.sqlId || traceNode.runId || traceNode.dbSource || '—') }}</dd></div>
            </dl>
          </section>

          <section v-if="hasError" class="node-detail-section is-error">
            <p class="eyebrow">Exception</p>
            <h3>异常信息</h3>
            <dl>
              <div><dt>错误码</dt><dd>{{ String(traceNode.errorCode || '未提供') }}</dd></div>
              <div><dt>失败原因</dt><dd>{{ String(traceNode.errorMessage || '节点未通过验证，请检查输入条件。') }}</dd></div>
            </dl>
          </section>

          <details v-if="hasTraceDetails" class="node-params">
            <summary><strong>输入参数</strong><span>已过滤敏感字段</span></summary>
            <pre>{{ pretty(inputData) }}</pre>
          </details>
          <details v-if="hasTraceDetails" class="node-params">
            <summary><strong>输出参数</strong><span>已过滤敏感字段</span></summary>
            <pre>{{ pretty(outputData) }}</pre>
          </details>
          <p v-if="!hasTraceDetails" class="node-detail-note">实时摘要仍可查看；详细参数需要运行时 Trace 接口正常返回。</p>
          <p v-else class="node-detail-note">患者原文、鉴权信息和模型提示词不会在对话页展示；SQL及大段参数默认收起。</p>
        </template>
      </div>
    </aside>
  </div>
</template>
