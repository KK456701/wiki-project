<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'

import DetailDrawer from '../components/DetailDrawer.vue'
import ExecutionPanel from '../components/ExecutionPanel.vue'
import MarkdownMessage from '../components/MarkdownMessage.vue'
import NodeDetailDrawer from '../components/NodeDetailDrawer.vue'
import ClarificationChoices from '../components/ClarificationChoices.vue'
import BatchExecutiveSummary from '../components/BatchExecutiveSummary.vue'
import GuidedTaskPanel from '../components/GuidedTaskPanel.vue'
import { useAgentStore, type ExecutionNode } from '../stores/agent'
import {
  createDiagnosisReportExport,
  createUploadComparisonExport,
  downloadIndicatorExport,
  prepareBatchAnalysis,
  prepareIndicatorInspection,
  type BatchAnalysisAction,
  type InspectIndicatorAction,
  type SessionSummary,
} from '../api/agent'

const store = useAgentStore()
const query = ref('')
const selectedDetailRunId = ref('')
const selectedNode = ref<{ traceId: string; node: ExecutionNode } | null>(null)
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
    if (list.some((session) => session.sessionId === sid)) continue
    const firstUser = sid === store.sessionId
      ? store.messages.find((message) => message.role === 'user')
      : undefined
    list.unshift({
      sessionId: sid,
      title: firstUser?.content?.trim().slice(0, 24) || '新对话',
      lastMessageAt: new Date().toISOString(),
      messageCount: store.messages.length,
    })
  }
  return list
})

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

async function sendSummaryAction(
  payload: InspectIndicatorAction | BatchAnalysisAction,
  requiresCloud = false,
) {
  if (requiresCloud) {
    const cloud = store.capabilities?.models.find((model) =>
      model.provider !== 'ollama' && model.id === 'aliyun-qwen-plus')
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
        <div><strong>核心制度指标 Agent</strong><small>迁移验证版 · 当前会话</small></div>
      </div>
      <div class="topbar-controls">
        <label class="model-field">模型
          <select v-model="store.selectedModel">
            <option v-for="model in store.capabilities?.models || []" :key="model.id" :value="model.id">{{ model.name }}</option>
          </select>
        </label>
        <code>{{ store.sessionId?.slice(-12) ?? '…' }}</code>
        <RouterLink class="quiet-button" to="/metadata">数据库元数据</RouterLink>
        <RouterLink class="quiet-button" to="/terminology">医学术语</RouterLink>
        <RouterLink class="quiet-button" to="/runs">运行观察</RouterLink>
      </div>
    </header>

    <section class="workspace">
      <aside v-if="sidebarOpen" class="session-sidebar">
        <header class="sidebar-head">
          <div>
            <h3>对话</h3>
            <span>历史记录 {{ displaySessionList.length }}</span>
          </div>
          <button type="button" class="sidebar-new" @click="startNewSession()">＋ 新对话</button>
        </header>
        <ul class="session-list">
          <li
            v-for="session in displaySessionList"
            :key="session.sessionId"
            class="session-item"
            :class="{ active: session.sessionId === store.sessionId }"
            @click="switchSession(session.sessionId)"
          >
            <span v-if="store.runningSessions[session.sessionId]" class="session-spinner" title="处理中"></span>
            <span class="session-title">{{ session.title }}</span>
            <button type="button" class="session-delete" title="删除" @click="removeSession(session.sessionId, $event)">×</button>
          </li>
        </ul>
        <p v-if="!displaySessionList.length" class="sidebar-empty">暂无历史对话</p>
      </aside>

      <div class="conversation-column">
        <div ref="conversation" class="conversation-panel">
          <section v-if="!store.messages.length" class="welcome-panel">
            <p class="eyebrow">临床指标核算 · 当前生效口径</p>
            <h1>选好指标与时间，<br>核算过程<em>全程留痕</em>。</h1>
            <p>按引导选择指标和时间范围即可开始。每一步口径、数据来源与核算结果都可追溯。</p>
            <GuidedTaskPanel
              :token="store.token"
              :disabled="store.running"
              @send="send($event)"
            />
          </section>

          <article
            v-for="message in store.messages"
            :key="message.id"
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
        </div>

        <form class="composer" @submit.prevent="send()">
          <input ref="uploadInput" class="visually-hidden" type="file" accept=".xlsx,.xls" @change="uploadFile" />
          <button type="button" class="upload-button" @click="uploadInput?.click()">＋ Excel</button>
          <span v-if="store.latestFileName" class="file-chip">{{ store.latestFileName }}</span>
          <textarea v-model="query" rows="1" maxlength="5000" placeholder="输入指标、统计时间或对比要求…" @keydown.ctrl.enter.prevent="send()"></textarea>
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
  </main>
</template>
