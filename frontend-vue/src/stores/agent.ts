import { defineStore } from 'pinia'

import {
  loadCapabilities,
  loginHospital,
  logoutHospital,
  streamAgent,
  uploadIndicatorFile,
  type AgentCapabilities,
  type AgentEvent,
  type HospitalUser,
} from '../api/agent'

export interface EvidenceStep {
  id: string
  label: string
  state: 'running' | 'success' | 'warning'
  detail: string
  durationMs?: number
  reused?: boolean
}

export interface ChatMessage {
  id: string
  role: 'user' | 'agent'
  content: string
  status: 'complete' | 'running' | 'failed'
  traceId?: string
  detailRunId?: string
  evidence: EvidenceStep[]
}

const toolLabels: Record<string, string> = {
  search_indicator_rules: '搜索相关指标',
  get_effective_rule: '读取本院生效口径',
  inspect_indicator_implementation: '检查字段与实施状态',
  prepare_indicator_sql: '生成并校验受控 SQL',
  trial_run_indicator_sql: '执行只读试运行',
  diagnose_indicator_issue: '分析指标异常',
  create_indicator_draft: '生成指标工作草稿',
  preview_rule_change: '预览本院口径变化',
  analyze_uploaded_indicators: '分析上传的指标文件',
}

function makeId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(16).slice(2)}`
}

function restoreUser(): HospitalUser | null {
  try {
    return JSON.parse(sessionStorage.getItem('vueHospitalUser') || 'null') as HospitalUser | null
  } catch {
    return null
  }
}

function setAgentContent(message: ChatMessage, value: string) {
  const marker = /\{\{detail_export:(RUN_[A-Za-z0-9_-]+)\}\}/g
  const match = marker.exec(value)
  if (match) message.detailRunId = match[1]
  message.content = value.replace(marker, '').replace(/\n{3,}/g, '\n\n').trim()
}

export const useAgentStore = defineStore('agent', {
  state: () => ({
    token: sessionStorage.getItem('vueHospitalToken') || '',
    user: restoreUser() as HospitalUser | null,
    capabilities: null as AgentCapabilities | null,
    selectedModel: '',
    sessionId: makeId('session').slice(0, 48),
    latestFileKey: '',
    latestFileName: '',
    messages: [] as ChatMessage[],
    running: false,
    error: '',
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user),
    latestAgentMessage: (state): ChatMessage | undefined => [...state.messages].reverse().find((message) => message.role === 'agent'),
  },
  actions: {
    async login(accountId: string, password: string) {
      const auth = await loginHospital(accountId, password)
      this.token = auth.token
      this.user = auth.user
      sessionStorage.setItem('vueHospitalToken', auth.token)
      sessionStorage.setItem('vueHospitalUser', JSON.stringify(auth.user))
      await this.refreshCapabilities()
    },
    async logout() {
      await logoutHospital(this.token).catch(() => undefined)
      this.token = ''
      this.user = null
      this.capabilities = null
      this.messages = []
      sessionStorage.removeItem('vueHospitalToken')
      sessionStorage.removeItem('vueHospitalUser')
    },
    async refreshCapabilities() {
      this.capabilities = await loadCapabilities(this.token)
      const ids = this.capabilities.models.map((model) => model.id)
      if (!ids.includes(this.selectedModel)) {
        this.selectedModel = ids.includes(this.capabilities.model)
          ? this.capabilities.model
          : ids[0] || ''
      }
    },
    newSession() {
      this.sessionId = makeId('session').slice(0, 48)
      this.latestFileKey = ''
      this.latestFileName = ''
      this.messages = []
      this.error = ''
    },
    async upload(file: File) {
      const result = await uploadIndicatorFile(this.token, file)
      this.latestFileKey = result.file_key
      this.latestFileName = result.file_name
      this.messages.push({
        id: makeId('message'),
        role: 'user',
        content: `已上传：${result.file_name}（${(result.size_bytes / 1024).toFixed(1)} KB）`,
        status: 'complete',
        evidence: [],
      })
    },
    async send(query: string) {
      const normalized = query.trim()
      if (!normalized || this.running) return
      this.error = ''
      this.running = true
      const userMessage: ChatMessage = {
        id: makeId('message'), role: 'user', content: normalized, status: 'complete', evidence: [],
      }
      const agentMessage: ChatMessage = {
        id: makeId('message'), role: 'agent', content: '', status: 'running', evidence: [],
      }
      this.messages.push(userMessage, agentMessage)

      try {
        await streamAgent(this.token, {
          query: normalized,
          sessionId: this.sessionId,
          modelId: this.selectedModel,
          fileKey: this.latestFileKey,
        }, (event) => this.applyEvent(agentMessage, event))
        if (agentMessage.status === 'running') agentMessage.status = 'complete'
        if (!agentMessage.content) agentMessage.content = '本轮处理已结束，但没有返回可展示的业务回答。'
      } catch (error) {
        agentMessage.status = 'failed'
        agentMessage.content = error instanceof Error ? error.message : 'Agent 请求失败，请稍后重试。'
        this.error = agentMessage.content
      } finally {
        this.running = false
      }
    },
    applyEvent(message: ChatMessage, event: AgentEvent) {
      if (event.trace_id) message.traceId = event.trace_id
      if (event.event === 'assistant_message' || event.event === 'clarification_required') {
        setAgentContent(message, event.message || '')
      }
      if (event.event === 'agent_error') {
        message.status = 'failed'
        message.content = event.message || 'Agent 运行未完成。'
      }
      if (event.event === 'agent_done') message.status = 'complete'
      if (event.event === 'tool_call') {
        message.evidence.push({
          id: `${event.tool_name || 'tool'}-${message.evidence.length}`,
          label: toolLabels[event.tool_name || ''] || '处理业务信息',
          state: 'running',
          detail: '正在调用受控业务工具',
        })
      }
      if (event.event === 'tool_result') {
        const step = [...message.evidence].reverse().find((item) => item.state === 'running' && item.id.startsWith(event.tool_name || 'tool'))
        if (!step) return
        step.state = event.status === 'success' || event.status === 'preview_ready' ? 'success' : 'warning'
        step.detail = event.reused ? '复用本轮已有结果' : event.message || event.code || '工具执行结束'
        step.durationMs = event.duration_ms
        step.reused = event.reused
      }
    },
  },
})
