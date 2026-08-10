<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import DetailDrawer from '../components/DetailDrawer.vue'
import ExecutionPanel from '../components/ExecutionPanel.vue'
import MarkdownMessage from '../components/MarkdownMessage.vue'
import NodeDetailDrawer from '../components/NodeDetailDrawer.vue'
import ClarificationChoices from '../components/ClarificationChoices.vue'
import BatchExecutiveSummary from '../components/BatchExecutiveSummary.vue'
import GuidedTaskPanel from '../components/GuidedTaskPanel.vue'
import DiagnosisCasePanel from '../components/DiagnosisCasePanel.vue'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import { useAgentStore, type ExecutionNode } from '../stores/agent'
import {
  createDiagnosisReportExport,
  createUploadComparisonExport,
  downloadIndicatorExport,
  prepareBatchAnalysis,
  prepareIndicatorInspection,
  actOnDiagnosisCase,
  createDiagnosisCase,
  loadDiagnosisAgentEvents,
  loadDiagnosisCase,
  type BatchAnalysisAction,
  type CreateDiagnosisCaseInput,
  type DiagnosisCaseSnapshot,
  type InspectIndicatorAction,
  type SessionSummary,
} from '../api/agent'

const store = useAgentStore()
const router = useRouter()
const query = ref('')
const selectedDetailRunId = ref('')
const selectedNode = ref<{ traceId: string; node: ExecutionNode } | null>(null)
const conversation = ref<HTMLElement | null>(null)
const exportingComparison = ref('')
const exportingDiagnosis = ref('')
const sessionList = ref<SessionSummary[]>([])
const settingsOpen = ref(false)
const diagnosisCases = ref<DiagnosisCaseSnapshot[]>([])
const diagnosisCasesLoading = ref(true)
const diagnosisBusy = ref('')
const sidebarCollapsed = ref(false)
const historySearchOpen = ref(false)
const historySearch = ref('')
const historySearchInput = ref<HTMLInputElement | null>(null)
const autonomousPollCases = new Set<string>()
const diagnosisPanelRefs = new Map<string, InstanceType<typeof DiagnosisCasePanel>>()

function setDiagnosisPanelRef(caseId: string, el: unknown) {
  if (el) diagnosisPanelRefs.set(caseId, el as InstanceType<typeof DiagnosisCasePanel>)
  else diagnosisPanelRefs.delete(caseId)
}

// 最近的自主排查会话存在时，页面底部主输入框直接接续该排查对话，
// 不再在排查面板内另外常驻一个输入条。
const activeAutonomousCase = computed(() => {
  const last = diagnosisCases.value[diagnosisCases.value.length - 1]
  return last && String(last.investigationMode || '') === 'AUTONOMOUS' ? last : null
})
const showWelcome = computed(() => !diagnosisCasesLoading.value
  && store.messages.length === 0
  && diagnosisCases.value.length === 0)
const composerPlaceholder = computed(() => (activeAutonomousCase.value
  ? '输入消息继续当前异常排查对话…'
  : '输入指标、统计时间或对比要求…'))
const activeAutonomousContextStats = computed<Record<string, unknown>>(() => {
  const value = activeAutonomousCase.value?.autonomousRun?.contextStats
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
})
const autonomousContextIsActual = computed(() => activeAutonomousContextStats.value.usageEstimated === false)
const autonomousContextPercent = computed(() => Math.min(100, Math.round(contextStatNumber('usagePercent'))))
const composerModels = computed(() => store.capabilities?.models || [])

function contextStatNumber(key: string): number {
  const value = Number(activeAutonomousContextStats.value[key] || 0)
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

function selectComposerModel(event: Event) {
  const modelId = (event.target as HTMLSelectElement).value
  store.selectModel(modelId)
}

async function refreshRuntimeModelSettings() {
  try {
    await store.refreshCapabilities()
  } catch (reason) {
    store.error = reason instanceof Error ? reason.message : '模型配置已保存，但模型列表刷新失败。'
  }
}

const canExportDetails = computed(() => store.user?.permissions.includes('indicator_detail_export') || false)
// 侧边栏实际渲染的列表：后端会话列表 + 正在处理但还未写入列表的会话。
// 新对话的第一条消息处理时，后端还没来得及把会话返回给前端，
// 这里临时补一个条目，让“处理中”的旋转图标有地方显示。
const displaySessionList = computed<SessionSummary[]>(() => {
  const list = [...sessionList.value]
  for (const [sid, isRunning] of Object.entries(store.runningSessions)) {
    if (!isRunning) continue
    if (list.some((session) => session.sessionId === sid)) continue
    list.unshift({
      sessionId: sid,
      title: '新对话',
      lastMessageAt: new Date().toISOString(),
      messageCount: store.messages.length,
    })
  }
  return list
})

const filteredSessionList = computed(() => {
  const keyword = historySearch.value.trim().toLocaleLowerCase()
  if (!keyword) return displaySessionList.value
  return displaySessionList.value.filter((session) => session.title.toLocaleLowerCase().includes(keyword))
})

const sessionGroups = computed(() => {
  const groups = [
    { key: 'today', label: '今天', sessions: [] as SessionSummary[] },
    { key: 'week', label: '7天内', sessions: [] as SessionSummary[] },
    { key: 'month', label: '30天内', sessions: [] as SessionSummary[] },
    { key: 'earlier', label: '更早', sessions: [] as SessionSummary[] },
  ]
  const today = new Date()
  today.setHours(0, 0, 0, 0)

  for (const session of filteredSessionList.value) {
    const lastMessageAt = new Date(session.lastMessageAt)
    lastMessageAt.setHours(0, 0, 0, 0)
    const daysAgo = Number.isNaN(lastMessageAt.getTime())
      ? Number.POSITIVE_INFINITY
      : Math.floor((today.getTime() - lastMessageAt.getTime()) / 86_400_000)

    if (daysAgo <= 0) groups[0].sessions.push(session)
    else if (daysAgo <= 7) groups[1].sessions.push(session)
    else if (daysAgo <= 30) groups[2].sessions.push(session)
    else groups[3].sessions.push(session)
  }

  return groups.filter((group) => group.sessions.length > 0)
})

onMounted(async () => {
  try {
    await store.refreshCapabilities()
  } catch {
    // 访客模式，忽略刷新失败
  }
  await store.newSession()
  await refreshSessionList()
  await restoreDiagnosisCases(store.sessionId)
})

async function refreshSessionList() {
  sessionList.value = await store.loadSessionList()
}

async function toggleHistorySearch() {
  if (sidebarCollapsed.value) sidebarCollapsed.value = false
  historySearchOpen.value = !historySearchOpen.value
  if (historySearchOpen.value) {
    await nextTick()
    historySearchInput.value?.focus()
  }
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  if (sidebarCollapsed.value) historySearchOpen.value = false
}

async function switchSession(sessionId: string) {
  await store.restoreSession(sessionId)
  await restoreDiagnosisCases(sessionId)
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight })
}

async function startNewSession() {
  await store.newSession()
  diagnosisCases.value = []
  diagnosisCasesLoading.value = false
  await refreshSessionList()
}

async function removeSession(sessionId: string, event: Event) {
  event.stopPropagation()
  await store.removeSession(sessionId)
  await refreshSessionList()
}

async function send(text = query.value) {
  const normalized = text.trim()
  if (!normalized) return
  query.value = ''
  const autonomous = activeAutonomousCase.value
  const panel = autonomous ? diagnosisPanelRefs.get(autonomous.caseId) : undefined
  if (panel?.sendAutonomousText) {
    panel.sendAutonomousText(normalized)
    await nextTick()
    conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
    return
  }
  await store.send(normalized)
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
  await refreshSessionList()
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!store.running && query.value.trim()) void send()
}

async function sendSummaryAction(
  payload: InspectIndicatorAction | BatchAnalysisAction,
  requiresCloud = false,
) {
  if (requiresCloud) {
    const cloud = store.capabilities?.models.find((model) =>
      model.provider !== 'ollama' && model.id === 'aliyun-qwen-distill-7b')
      || store.capabilities?.models.find((model) => model.provider !== 'ollama')
    if (!cloud) {
      store.error = '当前没有可用的云端 API 模型，已停止排查，未调用本地模型。'
      return
    }
    store.selectedModel = cloud.id
  }
  try {
    if (payload.action !== 'inspect_indicator') {
      const prepared = await prepareBatchAnalysis(store.token, payload)
      store.appendResolvedAction(
        `${prepared.displayPrompt}（审计 ${prepared.auditId}）`,
        prepared.answer,
      )
      await nextTick()
      conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
      return
    }
    const prepared = await prepareIndicatorInspection(store.token, {
      ...payload,
      modelId: store.selectedModel,
    })
    store.appendResolvedAction(
      `进入指标排查：${payload.indicatorId}（审计 ${prepared.auditId}）`,
      prepared.answer,
    )
    await nextTick()
    conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
  } catch (error) {
    store.error = error instanceof Error ? error.message : '读取批次排查事实失败。'
  }
}

function diagnosisStorageKey(sessionId: string): string {
  return `diagnosisCases:${sessionId}`
}

function rememberDiagnosisCase(sessionId: string, caseId: string) {
  const key = diagnosisStorageKey(sessionId)
  const values = JSON.parse(localStorage.getItem(key) || '[]') as string[]
  if (!values.includes(caseId)) localStorage.setItem(key, JSON.stringify([...values, caseId]))
}

async function restoreDiagnosisCases(sessionId: string) {
  diagnosisCasesLoading.value = true
  try {
    const localIds = JSON.parse(localStorage.getItem(diagnosisStorageKey(sessionId)) || '[]') as string[]
    const messageIds = store.messages.map((message) => message.diagnosisCaseId).filter(Boolean) as string[]
    const ids = [...new Set([...localIds, ...messageIds])]
    if (ids.length) localStorage.setItem(diagnosisStorageKey(sessionId), JSON.stringify(ids))
    const loaded = await Promise.all(ids.map((caseId) => loadDiagnosisCase(store.token, caseId).catch(() => null)))
    diagnosisCases.value = loaded.filter((item): item is DiagnosisCaseSnapshot => Boolean(item))
    for (const snapshot of diagnosisCases.value) {
      if (String(snapshot.autonomousRun?.status || '') === 'RUNNING') {
        store.runningSessions[snapshot.sessionId] = true
        void pollAutonomousDiagnosis(snapshot.caseId)
      }
    }
  } finally {
    diagnosisCasesLoading.value = false
  }
}

async function startDiagnosis(input: CreateDiagnosisCaseInput) {
  diagnosisBusy.value = 'creating'
  store.error = ''
  try {
    const created = await createDiagnosisCase(store.token, input)
    diagnosisCases.value.push(created)
    rememberDiagnosisCase(store.sessionId, created.caseId)
    await refreshSessionList()
    await nextTick()
    conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
  } catch (error) {
    store.error = error instanceof Error ? error.message : '异常排查任务创建失败。'
  } finally {
    diagnosisBusy.value = ''
  }
}

function openStandardDiagnosis() {
  void router.push({ name: 'standard-diagnosis-new' })
}

async function diagnosisAction(
  snapshot: DiagnosisCaseSnapshot,
  action: string,
  payload: Record<string, unknown>,
) {
  const autonomousAction = ['START_AUTONOMOUS_INVESTIGATION', 'SEND_AUTONOMOUS_MESSAGE',
    'RESPOND_AUTONOMOUS_QUESTION'].includes(action)
  if (autonomousAction) store.runningSessions[snapshot.sessionId] = true
  diagnosisBusy.value = snapshot.caseId
  store.error = ''
  try {
    let updated = await actOnDiagnosisCase(store.token, snapshot.caseId, action, payload)
    replaceDiagnosisSnapshot(updated)
    if (action === 'CONFIRM_CALIBER' || action === 'RECHECK_GATE' || action === 'RUN_BASE_CHECKS') {
      updated = await advanceDiagnosisGates(updated)
    }
    if (action === 'START_AUTONOMOUS_INVESTIGATION'
      || action === 'RESPOND_AUTONOMOUS_QUESTION'
      || action === 'SEND_AUTONOMOUS_MESSAGE') {
      void pollAutonomousDiagnosis(updated.caseId)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '异常排查步骤执行失败。'
    store.error = message
    if (['START_AUTONOMOUS_INVESTIGATION', 'SEND_AUTONOMOUS_MESSAGE', 'RESPOND_AUTONOMOUS_QUESTION']
      .includes(action)) {
      markAutonomousMessageFailed(snapshot, payload, message)
      store.runningSessions[snapshot.sessionId] = false
      await refreshSessionList()
    }
  } finally {
    diagnosisBusy.value = ''
  }
}

function markAutonomousMessageFailed(
  snapshot: DiagnosisCaseSnapshot,
  payload: Record<string, unknown>,
  message: string,
) {
  const clientMessageId = String(payload.clientMessageId || '')
  if (!clientMessageId) return
  const turns = Array.isArray(snapshot.autonomousRun.turns)
    ? [...snapshot.autonomousRun.turns] as Array<Record<string, unknown>> : []
  const existing = turns.findIndex((turn) => String(turn.clientMessageId || '') === clientMessageId)
  const failed = {
    clientMessageId,
    turnId: `FAILED_${clientMessageId}`,
    userMessage: String(payload.message || payload.problem || payload.answer || ''),
    submittedAt: new Date().toISOString(),
    status: 'FAILED',
    errorMessage: message,
    processEvents: [],
  }
  if (existing >= 0) turns[existing] = { ...turns[existing], ...failed }
  else turns.push(failed)
  replaceDiagnosisSnapshot({
    ...snapshot,
    autonomousRun: { ...snapshot.autonomousRun, turns },
  })
}

async function pollAutonomousDiagnosis(caseId: string) {
  if (autonomousPollCases.has(caseId)) return
  autonomousPollCases.add(caseId)
  const deadline = Date.now() + 5 * 60 * 1000 + 15_000
  let afterSeq = 0
  let cycles = 0
  try {
    while (Date.now() < deadline) {
      // The first read happens immediately after the user submits a message or
      // answers a question. Later reads keep the existing 650 ms cadence.
      const update = await loadDiagnosisAgentEvents(store.token, caseId, afterSeq)
      for (const event of update.events) {
        const seq = Number(event.seq || 0)
        if (Number.isFinite(seq)) afterSeq = Math.max(afterSeq, seq)
      }
      const current = diagnosisCases.value.find((item) => item.caseId === caseId)
      if (current) {
        replaceDiagnosisSnapshot({
          ...current,
          autonomousRun: update.autonomousRun,
          updatedAt: update.updatedAt,
        })
      }
      cycles += 1
      const status = String(update.status || '')
      if (status && !['RUNNING', 'QUEUED'].includes(status)) {
        replaceDiagnosisSnapshot(await loadDiagnosisCase(store.token, caseId))
        return
      }
      // The event endpoint carries the complete autonomous run. Refresh the
      // whole case less frequently to pick up candidate/shadow state changes.
      if (cycles % 8 === 0) replaceDiagnosisSnapshot(await loadDiagnosisCase(store.token, caseId))
      await new Promise((resolve) => window.setTimeout(resolve, 650))
    }
  } catch (error) {
    store.error = error instanceof Error ? error.message : '自主排查进度读取失败。'
  } finally {
    const current = diagnosisCases.value.find((item) => item.caseId === caseId)
    if (current) store.runningSessions[current.sessionId] = false
    await refreshSessionList()
    autonomousPollCases.delete(caseId)
  }
}

function replaceDiagnosisSnapshot(snapshot: DiagnosisCaseSnapshot) {
  diagnosisCases.value = diagnosisCases.value.map((item) =>
    item.caseId === snapshot.caseId ? snapshot : item)
}

function currentGateNumber(step: string): number {
  if (step === 'GATE_1_SCHEMA') return 1
  if (step === 'GATE_2_EVENT') return 2
  if (step === 'GATE_3_VALUE') return 3
  return 0
}

async function advanceDiagnosisGates(initial: DiagnosisCaseSnapshot): Promise<DiagnosisCaseSnapshot> {
  let current = initial
  for (;;) {
    const gate = currentGateNumber(current.currentStep)
    if (!gate) return current
    const existing = current.gateResults.find((item) => Number(item.gate) === gate)
    if (String(existing?.status || '') === 'BLOCKED') return current
    await nextTick()
    current = await actOnDiagnosisCase(store.token, current.caseId, 'RUN_GATE', { gate })
    replaceDiagnosisSnapshot(current)
    const result = current.gateResults.find((item) => Number(item.gate) === gate)
    if (String(result?.status || '') === 'BLOCKED') return current
  }
}

async function continueFromClarification(messageId: string, values: string[]) {
  const message = store.messages.find((item) => item.id === messageId)
  if (!message?.clarification || !values.length) return
  message.clarificationResolved = true
  const continuation = `${message.clarification.resumePrefix}${values.join('、')}`
  await send(continuation)
}

function openNode(traceId: string | undefined, node: ExecutionNode) {
  if (traceId) selectedNode.value = { traceId, node }
}

function formatDuration(durationMs?: number): string {
  if (durationMs === undefined) return ''
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(durationMs < 10_000 ? 2 : 1)} 秒`
}

async function exportComparison(runId?: string, fileToken?: string) {
  if (!runId || !fileToken || exportingComparison.value) return
  if (!window.confirm('差异表可能包含患者级业务明细。确认仅在授权范围内使用并立即下载吗？')) return
  exportingComparison.value = runId
  store.error = ''
  try {
    const created = await createUploadComparisonExport(store.token, runId, fileToken, true)
    await downloadIndicatorExport(store.token, created)
  } catch (error) {
    store.error = error instanceof Error ? error.message : '逐条差异表导出失败。'
  } finally {
    exportingComparison.value = ''
  }
}

async function exportDiagnosis(reportId?: string) {
  if (!reportId || exportingDiagnosis.value) return
  if (!window.confirm('诊断导出可能包含患者级业务明细。确认仅在授权范围内使用并立即下载吗？')) return
  exportingDiagnosis.value = reportId
  store.error = ''
  try {
    const created = await createDiagnosisReportExport(store.token, reportId, true)
    await downloadIndicatorExport(store.token, created)
  } catch (error) {
    store.error = error instanceof Error ? error.message : '诊断明细导出失败。'
  } finally {
    exportingDiagnosis.value = ''
  }
}
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div class="brand-block">
        <span class="brand-mark">AI</span>
        <div><strong>核心制度指标智能体</strong><small>指标核算与异常排查</small></div>
      </div>
      <div class="topbar-controls">
        <RouterLink class="quiet-button knowledge-review-button" to="/knowledge-review">
          <span class="knowledge-review-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M4 6.5 12 3l8 3.5-8 3.5-8-3.5Z"/><path d="m4 11 8 3.5 8-3.5M4 15.5 12 19l8-3.5"/></svg>
          </span>
          <span>知识库回收与审批</span>
          <svg class="knowledge-review-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>
        </RouterLink>
        <button type="button" class="settings-button" aria-label="打开系统设置" title="系统设置" @click="settingsOpen = true">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1a1.7 1.7 0 0 0-1.1-1.6 1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.1A1.7 1.7 0 0 0 4.7 8.5a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.5 4.7a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.15.38.37.72.66 1 .3.27.68.4 1.08.4H21v4h-.1a1.7 1.7 0 0 0-1.5.6Z"/></svg>
        </button>
      </div>
    </header>

    <section class="workspace" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <aside class="session-sidebar" :class="{ collapsed: sidebarCollapsed }" aria-label="历史对话">
        <header class="sidebar-head">
          <div class="sidebar-heading">
            <h3>历史对话</h3>
            <span>{{ displaySessionList.length }} 条记录</span>
          </div>
          <div class="sidebar-toolbar">
            <button
              type="button"
              class="sidebar-icon-button"
              :class="{ active: historySearchOpen }"
              aria-label="搜索历史对话"
              title="搜索历史对话"
              :aria-expanded="historySearchOpen"
              @click="toggleHistorySearch"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/></svg>
            </button>
            <button
              type="button"
              class="sidebar-icon-button"
              :aria-label="sidebarCollapsed ? '展开历史对话' : '收起历史对话'"
              :title="sidebarCollapsed ? '展开历史对话' : '收起历史对话'"
              @click="toggleSidebar"
            >
              <svg v-if="!sidebarCollapsed" viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4" width="17" height="16" rx="3"/><path d="M9 4v16"/><path d="m15 9-3 3 3 3"/></svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="4" width="17" height="16" rx="3"/><path d="M9 4v16"/><path d="m13 9 3 3-3 3"/></svg>
            </button>
          </div>
        </header>
        <button type="button" class="sidebar-new" title="开启新对话" @click="startNewSession()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M12 8v8M8 12h8"/></svg>
          <span>开启新对话</span>
        </button>
        <div v-if="historySearchOpen && !sidebarCollapsed" class="sidebar-search">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/></svg>
          <input
            ref="historySearchInput"
            v-model="historySearch"
            type="search"
            placeholder="搜索历史对话"
            aria-label="搜索历史对话"
            @keydown.esc="historySearchOpen = false"
          />
        </div>
        <ul class="session-list">
          <template v-for="group in sessionGroups" :key="group.key">
            <li class="session-group-label">{{ group.label }}</li>
            <li
              v-for="session in group.sessions"
              :key="session.sessionId"
              class="session-item"
              :class="{ active: session.sessionId === store.sessionId }"
              @click="switchSession(session.sessionId)"
            >
              <span v-if="store.runningSessions[session.sessionId]" class="session-spinner" title="处理中"></span>
              <span class="session-title">{{ session.title }}</span>
              <button type="button" class="session-delete" title="删除" @click="removeSession(session.sessionId, $event)">×</button>
            </li>
          </template>
        </ul>
        <p v-if="!filteredSessionList.length" class="sidebar-empty">{{ historySearch.trim() ? '没有找到相关对话' : '暂无历史对话' }}</p>
      </aside>

      <div class="conversation-column">
        <div ref="conversation" class="conversation-panel">
          <section v-if="showWelcome" class="welcome-panel">
            <span class="welcome-orbit" aria-hidden="true">AI</span>
            <h1>有什么我能帮你的吗？</h1>
            <p>我可以帮你计算核心制度指标，或从一条异常线索开始排查。</p>
            <p class="welcome-guidance">选择下面的任务，我会一步一步引导你完成。</p>
            <GuidedTaskPanel
              :token="store.token"
              :session-id="store.sessionId"
              :model-id="store.selectedModel"
              :disabled="store.running"
              @send="send($event)"
              @start-diagnosis="startDiagnosis"
              @open-standard-diagnosis="openStandardDiagnosis"
            />
          </section>

          <article
            v-for="message in store.messages"
            :key="message.id"
            v-show="!message.diagnosisCaseId"
            class="message"
            :class="[`is-${message.role}`, { 'has-batch-results': message.batchResults?.length }]"
          >
            <div class="message-avatar">{{ message.role === 'agent' ? 'AI' : '你' }}</div>
            <div class="message-card">
              <header class="message-head">
                <div class="message-owner">
                  <strong>{{ message.role === 'agent' ? '核心制度指标 Agent' : store.user?.accountId }}</strong>
                  <span
                    v-if="message.role === 'agent' && message.stageLabel"
                    class="stage-machine"
                    :data-kind="message.stageKind"
                    :data-state="message.stageState"
                    :data-running="message.status === 'running'"
                  >
                    <i aria-hidden="true"></i>
                    <span>{{ message.stageLabel }}</span>
                    <time v-if="message.stageDurationMs !== undefined">
                      {{ formatDuration(message.stageDurationMs) }}
                    </time>
                  </span>
                </div>
                <div class="message-outcome">
                  <time v-if="message.role === 'agent' && message.durationMs !== undefined">
                    本轮耗时 {{ formatDuration(message.durationMs) }}
                  </time>
                  <span>{{
                    message.status === 'running'
                      ? '处理中'
                      : message.status === 'failed'
                        ? '未完成'
                        : message.awaitingClarification && !message.clarificationResolved
                          ? '等待选择'
                          : '已完成'
                  }}</span>
                </div>
              </header>

              <ExecutionPanel
                v-if="message.role === 'agent'"
                :message="message"
                @select="openNode(message.traceId, $event)"
                @restore="store.restoreExecution($event)"
              />

              <!-- 批量指标结果只展示卡片，隐藏文字汇总（实时推送与历史恢复一致） -->
              <MarkdownMessage
                v-if="message.role === 'agent' && !message.batchResults?.length"
                :content="message.content || '正在读取规则与证据…'"
              />
              <div v-else-if="message.role !== 'agent'" class="message-content">{{ message.content }}</div>
              <BatchExecutiveSummary
                v-if="message.role === 'agent' && message.batchResults?.length"
                :results="message.batchResults"
                :token="store.token"
                :model-id="store.selectedModel"
                @action="sendSummaryAction"
              />
              <ClarificationChoices
                v-if="message.role === 'agent' && message.clarification"
                :clarification="message.clarification"
                :disabled="store.running"
                :resolved="message.clarificationResolved"
                @submit="continueFromClarification(message.id, $event)"
              />
              <button
                v-for="(runId, detailIndex) in message.detailRunIds || (message.detailRunId ? [message.detailRunId] : [])"
                :key="`${runId}-${detailIndex}`"
                type="button"
                class="detail-link"
                @click="selectedDetailRunId = runId"
              >查看第 {{ detailIndex + 1 }} 个指标明细并导出 Excel →</button>
              <button
                v-for="(comparison, comparisonIndex) in message.comparisonExports || (message.comparisonRunId && message.comparisonFileToken ? [{ runId: message.comparisonRunId, fileToken: message.comparisonFileToken }] : [])"
                v-show="canExportDetails"
                :key="`${comparison.runId}-${comparisonIndex}`"
                type="button"
                class="detail-link"
                :disabled="exportingComparison === comparison.runId"
                @click="exportComparison(comparison.runId, comparison.fileToken)"
              >{{ exportingComparison === comparison.runId ? '正在生成差异表…' : `导出第 ${comparisonIndex + 1} 个逐条差异 Excel →` }}</button>
              <button
                v-for="reportId in message.diagnosisReportIds || []"
                v-show="canExportDetails"
                :key="reportId"
                type="button"
                class="detail-link"
                :disabled="exportingDiagnosis === reportId"
                @click="exportDiagnosis(reportId)"
              >{{ exportingDiagnosis === reportId ? '正在生成诊断明细…' : '导出诊断明细 Excel →' }}</button>
            </div>
          </article>

          <template v-for="item in diagnosisCases" :key="item.caseId">
            <RouterLink
              v-if="String(item.caseInput.entryMode || '') === 'STANDARD_WORKSPACE'"
              class="standard-diagnosis-resume"
              :to="{ name: 'standard-diagnosis', params: { caseId: item.caseId }, query: { step: item.currentStep.startsWith('GATE_') ? 'checks' : item.currentStep === 'CASE_INPUT' ? 'data' : 'lineage' } }"
            >
              <span>标准排查工作区</span>
              <strong>{{ String(item.caliberSnapshot.ruleName || item.ruleId) }}</strong>
              <small>{{ item.profileId }} · {{ item.currentStep }}</small>
              <em>继续排查 →</em>
            </RouterLink>
            <DiagnosisCasePanel
              v-else
              :ref="(el) => setDiagnosisPanelRef(item.caseId, el)"
              :snapshot="item"
              :token="store.token"
              :busy="diagnosisBusy === item.caseId"
              :models="composerModels"
              @action="(action, payload) => diagnosisAction(item, action, payload)"
            />
          </template>
        </div>

        <form class="composer" @submit.prevent="send()">
            <textarea v-model="query" rows="1" maxlength="5000" :placeholder="showWelcome ? '输入问题，或从上方选择一个任务…' : composerPlaceholder" @keydown="handleComposerKeydown"></textarea>
           <label v-if="composerModels.length" class="composer-model-select" title="选择本次对话和新建异常排查任务使用的模型">
             <span class="visually-hidden">选择模型</span>
             <select :value="store.selectedModel" aria-label="选择对话模型" @change="selectComposerModel">
               <option v-for="model in composerModels" :key="model.id" :value="model.id" :disabled="model.available === false">{{ model.name }}{{ model.available === false ? '（未配置）' : '' }}</option>
             </select>
           </label>
           <span v-if="activeAutonomousCase && contextStatNumber('contextWindowTokens')" class="composer-context-ring" role="progressbar" aria-label="当前对话容量使用进度" :aria-valuenow="autonomousContextPercent" aria-valuemin="0" aria-valuemax="100" :title="`对话容量约 ${autonomousContextPercent}%（${autonomousContextIsActual ? '按本轮实际用量计算' : '当前为估算'}）`" :style="{ background: `conic-gradient(#16836f ${autonomousContextPercent}%, #dfeae7 0)` }"></span>
          <button class="send-button" type="submit" :disabled="store.running || !query.trim()">{{ store.running ? '处理中' : '发送' }}</button>
        </form>
      </div>

    </section>

    <p v-if="store.error" class="global-error">{{ store.error }}</p>

    <NodeDetailDrawer
      v-if="selectedNode"
      :token="store.token"
      :trace-id="selectedNode.traceId"
      :node="selectedNode.node"
      @close="selectedNode = null"
    />
    <DetailDrawer
      v-if="selectedDetailRunId"
      :token="store.token"
      :run-id="selectedDetailRunId"
      :can-export="canExportDetails"
      @close="selectedDetailRunId = ''"
    />
    <SettingsDrawer
      v-if="settingsOpen"
      :token="store.token"
      :selected-model="store.selectedModel"
      :models="store.capabilities?.models || []"
      @select-model="store.selectModel"
      @settings-updated="refreshRuntimeModelSettings"
      @close="settingsOpen = false"
    />
  </main>
</template>
