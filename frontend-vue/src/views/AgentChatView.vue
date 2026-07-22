<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'

import TraceDrawer from '../components/TraceDrawer.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import { useAgentStore } from '../stores/agent'
import { createUploadComparisonExport, downloadIndicatorExport } from '../api/agent'

const store = useAgentStore()
const accountId = ref('user_001')
const password = ref('')
const loginError = ref('')
const loggingIn = ref(false)
const query = ref('')
const selectedTraceId = ref('')
const selectedDetailRunId = ref('')
const uploadInput = ref<HTMLInputElement | null>(null)
const conversation = ref<HTMLElement | null>(null)
const exportingComparison = ref('')

const activeEvidence = computed(() => store.latestAgentMessage?.evidence || [])
const canExportDetails = computed(() => store.user?.permissions.includes('indicator_detail_export') || false)
const suggestions = [
  '急会诊及时到位率怎么算？',
  '患者入院 48 小时内转科的比例从一月份到现在是多少？',
  '这两个指标的 SQL 怎么写？',
]

onMounted(async () => {
  if (!store.isAuthenticated) return
  try {
    await store.refreshCapabilities()
  } catch {
    await store.logout()
  }
})

async function login() {
  loginError.value = ''
  loggingIn.value = true
  try {
    await store.login(accountId.value.trim(), password.value)
    password.value = ''
  } catch (error) {
    loginError.value = error instanceof Error ? error.message : '登录失败。'
  } finally {
    loggingIn.value = false
  }
}

async function send(text = query.value) {
  const normalized = text.trim()
  if (!normalized) return
  query.value = ''
  await store.send(normalized)
  await nextTick()
  conversation.value?.scrollTo({ top: conversation.value.scrollHeight, behavior: 'smooth' })
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
</script>

<template>
  <main v-if="!store.isAuthenticated" class="login-shell">
    <section class="login-story">
      <p class="eyebrow">Java / Vue 渐进迁移</p>
      <h1>结论可读，<br><em>依据可追。</em></h1>
      <p>迁移版先复用现有 FastAPI 和 DBHub，所有指标规则、SQL 安全边界与医院权限保持不变。</p>
      <div class="contract-stamp">
        <strong>CONTRACT V1</strong>
        <span>REST · SSE · DBHub MCP</span>
      </div>
    </section>
    <form class="login-card" @submit.prevent="login">
      <p class="eyebrow">医院人员登录</p>
      <h2>进入指标助手</h2>
      <label>账号<input v-model="accountId" autocomplete="username" /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" /></label>
      <p v-if="loginError" class="form-error">{{ loginError }}</p>
      <button class="primary-button" :disabled="loggingIn" type="submit">{{ loggingIn ? '正在验证…' : '进入系统' }}</button>
      <small>登录请求仍由现有医院认证接口处理。</small>
    </form>
  </main>

  <main v-else class="app-shell">
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
        <RouterLink class="quiet-button" to="/monitoring">指标监控</RouterLink>
        <RouterLink class="quiet-button" to="/implementation">指标实施</RouterLink>
        <button type="button" class="quiet-button" @click="store.newSession()">新会话</button>
        <div class="identity"><span>医院人员</span><strong>{{ store.user?.accountId }}</strong><small>{{ store.user?.hospitalId }}</small></div>
        <button type="button" class="quiet-button" @click="store.logout()">退出</button>
      </div>
    </header>

    <section class="workspace">
      <div ref="conversation" class="conversation-panel">
        <section v-if="!store.messages.length" class="welcome-panel">
          <p class="eyebrow">核心制度 · 当前生效口径</p>
          <h1>把问题说完整，<br>系统把<em>证据链</em>留完整。</h1>
          <p>可查询指标定义、实际结果、受控 SQL、异常原因，也可以上传 Excel 与本院结果核对。</p>
          <div class="suggestions">
            <button v-for="item in suggestions" :key="item" type="button" @click="send(item)">{{ item }}</button>
          </div>
        </section>

        <article v-for="message in store.messages" :key="message.id" class="message" :class="`is-${message.role}`">
          <div class="message-avatar">{{ message.role === 'agent' ? 'AI' : '你' }}</div>
          <div class="message-card">
            <header><strong>{{ message.role === 'agent' ? '核心制度指标 Agent' : store.user?.accountId }}</strong><span>{{ message.status === 'running' ? '处理中' : message.status === 'failed' ? '未完成' : '已完成' }}</span></header>
            <div class="message-content">{{ message.content || '正在读取规则与证据…' }}</div>
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
            <button v-if="message.traceId" type="button" class="trace-link" @click="openTrace(message.traceId)">查看链路 →</button>
          </div>
        </article>
      </div>

      <aside class="evidence-rail">
        <header><p class="eyebrow">Live evidence</p><h2>证据轨道</h2><span>{{ activeEvidence.length }} 个工具节点</span></header>
        <ol v-if="activeEvidence.length">
          <li v-for="step in activeEvidence" :key="step.id" :data-state="step.state">
            <span class="rail-dot"></span>
            <div><strong>{{ step.label }}</strong><p>{{ step.detail }}</p><small v-if="step.durationMs !== undefined">{{ step.durationMs }}ms<span v-if="step.reused"> · 已复用</span></small></div>
          </li>
        </ol>
        <div v-else class="rail-empty"><span>○</span><p>发送问题后，这里按实际执行顺序显示规则、SQL 和数据库工具。</p></div>
        <footer><span class="legend llm">LLM</span><span class="legend code">代码</span><span class="legend tool">工具</span></footer>
      </aside>
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
