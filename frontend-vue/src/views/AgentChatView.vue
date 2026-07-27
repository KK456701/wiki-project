<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'

import TraceDrawer from '../components/TraceDrawer.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import MarkdownMessage from '../components/MarkdownMessage.vue'
import ClarificationChoices from '../components/ClarificationChoices.vue'
import { useAgentStore } from '../stores/agent'
import {
  createDiagnosisReportExport,
  createUploadComparisonExport,
  downloadIndicatorExport,
  type SessionSummary,
} from '../api/agent'

const store = useAgentStore()
const query = ref('')
const selectedTraceId = ref('')
const selectedDetailRunId = ref('')
const uploadInput = ref<HTMLInputElement | null>(null)
const conversation = ref<HTMLElement | null>(null)
const exportingComparison = ref('')
const exportingDiagnosis = ref('')
const sessionList = ref<SessionSummary[]>([])
const sidebarOpen = ref(true)

const canExportDetails = computed(() => store.user?.permissions.includes('indicator_detail_export') || false)
// 侧边栏实际渲染的列表：后端会话列表 + 正在处理但还未写入列表的会话。
// 新对话的第一条消息处理时，后端还没来得及把会话返回给前端，
// 这里临时补一个条目，让“处理中”的旋转图标有地方显示。
const displaySessionList = computed<SessionSummary[]>(() => {
  const list = [...sessionList.value]
  for (const [sid, isRunning] of Object.entries(store.runningSessions)) {
    if (!isRunning) continue
    if (list.some((session) => session.session_id === sid)) continue
    const firstUser = sid === store.sessionId
      ? store.messages.find((message) => message.role === 'user')
      : undefined
    list.unshift({
      session_id: sid,
      title: firstUser?.content?.trim().slice(0, 24) || '新对话',
      last_message_at: new Date().toISOString(),
      message_count: store.messages.length,
    })
  }
  return list
})
const suggestions = [
  '急会诊及时到位率的定义、分子和分母口径是什么？',
  '计算本月患者入院48小时内转科的比例。',
  '生成本月术中自体血回输率的概览 SQL，不执行数据库。',
  '排查本月急会诊及时到位率结果异常的原因。',
]

onMounted(async () => {
  try {
    await store.refreshCapabilities()
  } catch {
    // 访客模式，忽略刷新失败
  }
  await refreshSessionList()
})

async function refreshSessionList() {
  sessionList.value = await store.loadSessionList()
}

async function switchSession(sessionId: string) {
  await store.restoreSession(sessionId)
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight })
}

async function startNewSession() {
  await store.newSession()
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
  await store.send(normalized)
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
  await refreshSessionList()
}

async function continueFromClarification(messageId: string, values: string[]) {
  const message = store.messages.find((item) => item.id === messageId)
  if (!message?.clarification || !values.length) return
  message.clarificationResolved = true
  const continuation = `${message.clarification.resumePrefix}${values.join('、')}`
  await send(continuation)
}

async function uploadFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    await store.upload(file)
  } catch (error) {
    store.error = error instanceof Error ? error.message : '文件上传失败。'
  } finally {
    input.value = ''
  }
}

function openTrace(traceId?: string) {
  if (traceId) selectedTraceId.value = traceId
}

function formatDuration(durationMs?: number): string {
  if (durationMs === undefined) return ''
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(durationMs < 10_000 ? 2 : 1)} 秒`
}

function formatStageNumber(value?: number): string {
  return String(value || 1).padStart(2, '0')
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
        <div><strong>核心制度指标 Agent</strong><small>迁移验证版 · 当前会话</small></div>
      </div>
      <div class="topbar-controls">
        <label class="model-field">模型
          <select v-model="store.selectedModel">
            <option v-for="model in store.capabilities?.models || []" :key="model.id" :value="model.id">{{ model.name }}</option>
          </select>
        </label>
        <code>{{ store.sessionId.slice(-12) }}</code>
        <RouterLink class="quiet-button" to="/metadata">数据库元数据</RouterLink>
        <RouterLink class="quiet-button" to="/terminology">医学术语</RouterLink>
        <RouterLink class="quiet-button" to="/runs">运行观察</RouterLink>
      </div>
    </header>

    <section class="workspace">
      <aside v-if="sidebarOpen" class="session-sidebar">
        <header class="sidebar-head">
          <h3>最近对话</h3>
          <button type="button" class="sidebar-new" @click="startNewSession()">＋ 新对话</button>
        </header>
        <ul class="session-list">
          <li
            v-for="session in displaySessionList"
            :key="session.session_id"
            class="session-item"
            :class="{ active: session.session_id === store.sessionId }"
            @click="switchSession(session.session_id)"
          >
            <span v-if="store.runningSessions[session.session_id]" class="session-spinner" title="处理中"></span>
            <span class="session-title">{{ session.title }}</span>
            <button type="button" class="session-delete" title="删除" @click="removeSession(session.session_id, $event)">×</button>
          </li>
        </ul>
        <p v-if="!displaySessionList.length" class="sidebar-empty">暂无历史对话</p>
      </aside>

      <div ref="conversation" class="conversation-panel">
        <section v-if="!store.messages.length" class="welcome-panel">
          <p class="eyebrow">核心制度 · 当前生效口径</p>
          <h1>问清指标，算清结果，<br>也说清<em>原因</em>。</h1>
          <p>可查询指标定义、分子分母口径和受控 SQL，计算指定周期的本院结果，或基于双库与明细证据排查异常；也可上传 Excel 与本院结果核对。每次运行都保留可核验的证据链。</p>
          <div class="suggestions">
            <button v-for="item in suggestions" :key="item" type="button" @click="send(item)">{{ item }}</button>
          </div>
        </section>

        <article v-for="message in store.messages" :key="message.id" class="message" :class="`is-${message.role}`">
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
                  <b>{{ formatStageNumber(message.stageNumber) }}</b>
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
            <MarkdownMessage
              v-if="message.role === 'agent'"
              :content="message.content || '正在读取规则与证据…'"
            />
            <div v-else class="message-content">{{ message.content }}</div>
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
            <button v-if="message.traceId" type="button" class="trace-link" @click="openTrace(message.traceId)">查看链路 →</button>
          </div>
        </article>
      </div>
    </section>

    <form class="composer" @submit.prevent="send()">
      <input ref="uploadInput" class="visually-hidden" type="file" accept=".xlsx,.xls" @change="uploadFile" />
      <button type="button" class="upload-button" @click="uploadInput?.click()">＋ Excel</button>
      <span v-if="store.latestFileName" class="file-chip">{{ store.latestFileName }}</span>
      <textarea v-model="query" rows="1" maxlength="5000" placeholder="输入指标、统计时间或对比要求…" @keydown.ctrl.enter.prevent="send()"></textarea>
      <button class="send-button" type="submit" :disabled="store.running || !query.trim()">{{ store.running ? '处理中' : '发送' }}</button>
    </form>
    <p v-if="store.error" class="global-error">{{ store.error }}</p>

    <TraceDrawer v-if="selectedTraceId" :token="store.token" :trace-id="selectedTraceId" @close="selectedTraceId = ''" />
    <DetailDrawer
      v-if="selectedDetailRunId"
      :token="store.token"
      :run-id="selectedDetailRunId"
      :can-export="canExportDetails"
      @close="selectedDetailRunId = ''"
    />
  </main>
</template>
