import { defineStore } from 'pinia'

import {
  loadCapabilities,
  loginHospital,
  logoutHospital,
  streamAgent,
  uploadIndicatorFile,
  createSession,
  listSessions,
  getSessionMessages,
  deleteSession,
  type AgentCapabilities,
  type AgentClarification,
  type AgentEvent,
  type HospitalUser,
  type SessionSummary,
  type SessionMessage,
} from '../api/agent'

export interface EvidenceStep {
  id: string
  label: string
  state: 'running' | 'success' | 'warning'
  detail: string
  durationMs?: number
  reused?: boolean
}

export type StageKind = 'llm' | 'code' | 'tool' | 'storage' | 'done'
export type StageState = 'running' | 'success' | 'warning' | 'failed'

interface StageTransition {
  label: string
  kind: StageKind
  state: StageState
  durationMs?: number
  terminalStatus?: 'complete' | 'failed'
}

export interface ChatMessage {
  id: string
  role: 'user' | 'agent'
  content: string
  status: 'complete' | 'running' | 'failed'
  traceId?: string
  detailRunId?: string
  detailRunIds?: string[]
  comparisonRunId?: string
  comparisonFileToken?: string
  comparisonExports?: Array<{ runId: string; fileToken: string }>
  diagnosisReportIds?: string[]
  evidence: EvidenceStep[]
  stageLabel?: string
  stageKind?: StageKind
  stageState?: StageState
  stageNumber?: number
  stageDurationMs?: number
  stageQueue?: StageTransition[]
  stageFlowBusy?: boolean
  pendingTerminalStatus?: 'complete' | 'failed'
  startedAtMs?: number
  durationMs?: number
  clarification?: AgentClarification
  awaitingClarification?: boolean
  clarificationResolved?: boolean
  batchResults?: BatchIndicatorResult[]
}

export interface BatchIndicatorResult {
  ruleId: string
  ruleName: string
  status: string
  done: number
  total: number
  resultValue?: number
  numeratorCount?: number
  denominatorCount?: number
  unit?: string
  calculationDisplay?: string
  statStart?: string
  statEnd?: string
  runId?: string
  errorCode?: string
  errorMessage?: string
}

const toolLabels: Record<string, string> = {
  search_indicator_rules: '搜索相关指标',
  get_effective_rule: '读取本院生效口径',
  inspect_indicator_implementation: '检查字段与实施状态',
  prepare_indicator_sql: '生成并校验受控 SQL',
  trial_run_indicator_sql: '执行只读试运行',
  resolve_indicator_caliber: '解析候选口径',
  prepare_indicator_caliber_sql: '准备候选口径 SQL',
  trial_run_indicator_caliber_sql: '试运行候选口径',
  diagnose_indicator_issue: '分析指标异常',
  diagnose_indicator_difference: '执行指标差异分层诊断',
  create_indicator_draft: '生成指标工作草稿',
  preview_rule_change: '预览本院口径变化',
  analyze_uploaded_indicators: '分析上传的指标文件',
}

const nodeLabels: Record<string, string> = {
  indicator_rule_match: '规则精确识别指标',
  indicator_semantic_retrieval: '本地语义召回指标',
  indicator_llm_disambiguation: '模型候选内消歧',
  memory_load: '读取会话上下文',
  planner_llm: '规划业务目标',
  plan_goal_alignment: '校验目标与计划',
  plan_alignment_review_llm: '审核复杂口径目标',
  plan_replan: '重新规划业务目标',
  plan_alignment_revalidate: '复核替代计划',
  plan_alignment_deterministic_fallback: '生成受控修正计划',
  followup_plan_resolve: '解析追问目标',
  plan_compile: '编译业务计划',
  plan_validate: '校验业务计划',
  failure_router: '路由失败处理',
  state_controller: '选择下一业务能力',
  deterministic_tool_dispatch: '编译受控工具调用',
  tool_result: '执行并观察工具结果',
  plan_verify: '校验证据完整性',
  final_answer_llm: '生成最终回答',
  prepared_sql_answer: '生成受控 SQL 回答',
  caliber_simulation_answer: '生成候选口径回答',
  difference_diagnosis_layer_1: '诊断范围预检',
  difference_diagnosis_layer_2: '实时结构核验',
  difference_diagnosis_layer_3: '执行当前口径',
  difference_diagnosis_layer_4: '试运行候选口径',
  difference_diagnosis_layer_5: '核对记录集合',
  difference_diagnosis_layer_6: '检查数据质量',
  difference_diagnosis_conclusion: '生成诊断结论',
  difference_diagnosis_answer: '整理差异诊断回答',
  dual_period_validation: '校验统计范围',
  source_extraction_prepare: '准备源数据抽取',
  source_data_extraction: '抽取数据到真实库',
  business_overview: '计算业务库概览',
  real_overview: '计算真实库概览',
  dual_comparison: '核对双库结果',
  dual_department_detail: '核对科室差异',
  dual_patient_detail: '核对患者明细',
  dual_diagnosis_conclusion: '生成诊断结论',
  response_guard: '检查回答协议',
  memory_save: '保存会话上下文',
  compound_split: '拆分复合指标请求',
  compound_subtask: '执行指标子任务',
  compound_merge: '按输入顺序合并结果',
}

function stageKind(value?: string): StageKind {
  if (value === 'llm' || value === 'tool' || value === 'database' || value === 'storage') {
    return value === 'database' ? 'tool' : value
  }
  return 'code'
}

// 毫秒级代码节点会在同一帧内连续到达；保留短暂驻留时间，确保状态文字可被看到。
const STAGE_MIN_VISIBLE_MS = 200

function applyStage(message: ChatMessage, transition: StageTransition) {
  const changed = message.stageLabel !== transition.label || message.stageKind !== transition.kind
  message.stageLabel = transition.label
  message.stageKind = transition.kind
  message.stageState = transition.state
  message.stageDurationMs = transition.durationMs
  if (changed) message.stageNumber = (message.stageNumber || 0) + 1
  if (transition.terminalStatus) message.status = transition.terminalStatus
}

function advanceStage(message: ChatMessage) {
  const next = message.stageQueue?.shift()
  if (!next) {
    message.stageFlowBusy = false
    return
  }
  applyStage(message, next)
  window.setTimeout(() => advanceStage(message), STAGE_MIN_VISIBLE_MS)
}

/**
 * SSE 可能在一个浏览器渲染帧内连续送达多个毫秒级节点。
 * 这里只为单一状态槽排队，不保存或展示历史列表，确保用户能看到状态逐项流转。
 */
function setStage(
  message: ChatMessage,
  label: string,
  kind: StageKind,
  state: StageState = 'running',
  durationMs?: number,
  terminalStatus?: 'complete' | 'failed',
) {
  if (!label) return
  if (state !== 'running' && message.stageLabel === label && message.stageKind === kind) {
    applyStage(message, { label, kind, state, durationMs, terminalStatus })
    return
  }
  const queued = state === 'running' ? undefined : [...(message.stageQueue || [])].reverse()
    .find((stage) => stage.label === label && stage.kind === kind)
  if (queued) {
    queued.state = state
    queued.durationMs = durationMs
    queued.terminalStatus = terminalStatus
    return
  }

  const transition = { label, kind, state, durationMs, terminalStatus }
  if (!message.stageFlowBusy) {
    message.stageFlowBusy = true
    applyStage(message, transition)
    window.setTimeout(() => advanceStage(message), STAGE_MIN_VISIBLE_MS)
    return
  }
  const queue = message.stageQueue || (message.stageQueue = [])
  const last = queue[queue.length - 1]
  if (last?.label === label && last.kind === kind && last.state === state) return
  queue.push(transition)
}

function finishTiming(message: ChatMessage) {
  if (message.durationMs !== undefined || message.startedAtMs === undefined) return
  message.durationMs = Math.max(0, Date.now() - message.startedAtMs)
}

function makeId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(16).slice(2)}`
}

function setAgentContent(message: ChatMessage, value: string) {
  const detailMarker = /\{\{detail_export:(RUN_[A-Za-z0-9_-]+)\}\}/g
  const comparisonMarker = /\{\{upload_comparison_export:(RUN_[A-Za-z0-9_-]+):([A-Za-z0-9_-]+)\}\}/g
  const diagnosisMarker = /\{\{diagnosis_export:(DDR_[A-Za-z0-9_-]+)\}\}/g
  const detailMatches = Array.from(value.matchAll(detailMarker))
  const comparisonMatches = Array.from(value.matchAll(comparisonMarker))
  const diagnosisMatches = Array.from(value.matchAll(diagnosisMarker))
  const detailMatch = detailMatches[0]
  const comparisonMatch = comparisonMatches[0]
  if (detailMatch) message.detailRunId = detailMatch[1]
  if (detailMatches.length) message.detailRunIds = detailMatches.map((match) => match[1])
  if (comparisonMatch) {
    message.comparisonRunId = comparisonMatch[1]
    message.comparisonFileToken = comparisonMatch[2]
  }
  if (comparisonMatches.length) {
    message.comparisonExports = comparisonMatches.map((match) => ({
      runId: match[1], fileToken: match[2],
    }))
  }
  if (diagnosisMatches.length) {
    message.diagnosisReportIds = diagnosisMatches.map((match) => match[1])
  }
  message.content = value.replace(detailMarker, '').replace(comparisonMarker, '')
    .replace(diagnosisMarker, '')
    .replace(/\n{3,}/g, '\n\n').trim()
}

function normalizeClarification(
  value?: import('../api/agent').AgentClarificationWire,
): AgentClarification | undefined {
  if (!value) return undefined
  return {
    code: value.code || '',
    kind: value.kind || 'free_text',
    title: value.title || '还需要你补充一点信息',
    question: value.question || '',
    helpText: value.help_text || '',
    selectionMode: value.selection_mode === 'multiple' ? 'multiple' : 'single',
    options: value.options || [],
    allowFreeText: Boolean(value.allow_free_text),
    freeTextPlaceholder: value.free_text_placeholder || '补充说明',
    resumePrefix: value.resume_prefix || '继续处理上一条请求。补充信息：',
  }
}

export const useAgentStore = defineStore('agent', {
  state: () => ({
    token: 'guest',
    user: { userId: 'guest_user', accountId: 'guest', hospitalId: 'hospital_001', permissions: ['indicator_detail_view', 'indicator_detail_export'] } as HospitalUser,
    capabilities: null as AgentCapabilities | null,
    selectedModel: '',
    sessionId: localStorage.getItem('vueAgentSessionId') || makeId('session').slice(0, 48),
    latestFileKey: '',
    latestFileName: '',
    messages: [] as ChatMessage[],
    // 运行状态按会话隔离：sessionId -> 是否正在请求。
    // 这样在 A 会话请求进行中，仍可以在 B 会话并行提问。
    runningSessions: {} as Record<string, boolean>,
    // 各会话运行中的实时消息引用：sessionId -> 正在生成的 agent 消息。
    // 切换对话时消息列表会被数据库快照替换，但请求仍在后台继续；
    // 凭此引用可在切回该会话时把实时进度重新挂回列表。
    activeRunMessages: {} as Record<string, ChatMessage>,
    error: '',
  }),
  getters: {
    isAuthenticated: () => true,
    /** 当前会话是否正在请求（供模板与发送拦截使用）。 */
    running: (state): boolean => Boolean(state.runningSessions[state.sessionId]),
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
      this.user = { userId: 'guest_user', accountId: 'guest', hospitalId: 'demo_hospital', permissions: [] }
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
    async newSession() {
      try {
        this.sessionId = await createSession(this.token)
      } catch {
        this.sessionId = makeId('session').slice(0, 48)
      }
      localStorage.setItem('vueAgentSessionId', this.sessionId)
      this.latestFileKey = ''
      this.latestFileName = ''
      this.messages = []
      this.error = ''
    },
    /** 加载历史会话列表 */
    async loadSessionList(): Promise<SessionSummary[]> {
      try {
        return await listSessions(this.token)
      } catch {
        return []
      }
    },
    /** 恢复指定历史会话的消息记录 */
    async restoreSession(sessionId: string) {
      // 必须在 await 之前先抓住运行中的消息引用：
      // 若请求恰好在下拉会话的 await 期间完成，finally 会先删除 activeRunMessages，
      // 等 await 返回后再读就是 undefined，导致运行中的消息丢失。
      // 提前捕获的引用是响应式代理，请求完成时会被更新为最终内容，挂回去即可看到结果。
      const runningMessage = this.activeRunMessages[sessionId]
      try {
        const historyMessages = await getSessionMessages(this.token, sessionId)
        this.sessionId = sessionId
        localStorage.setItem('vueAgentSessionId', sessionId)
        this.latestFileKey = ''
        this.latestFileName = ''
        this.error = ''
        const list = Array.isArray(historyMessages) ? historyMessages : []
        this.messages = list.map((message: SessionMessage) => ({
          id: makeId('message'),
          role: (message.role === 'assistant' ? 'agent' : 'user') as 'agent' | 'user',
          content: message.content || '',
          status: 'complete' as const,
          evidence: [],
        }))
        // 如果该会话仍有后台运行中的请求，把实时消息重新挂回列表，
        // 让用户切回来时能看到正在处理的进度，而不是误以为请求消失。
        if (runningMessage && !this.messages.some((message) => message.id === runningMessage.id)) {
          this.messages.push(runningMessage)
        }
      } catch (error) {
        this.error = error instanceof Error ? error.message : '恢复会话失败'
      }
    },
    /** 删除指定会话 */
    async removeSession(sessionId: string) {
      await deleteSession(this.token, sessionId)
      if (this.sessionId === sessionId) {
        await this.newSession()
      }
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
      // 记录本轮请求所属的会话与消息标识。切换对话会把消息列表整个替换，
      // 导致进行中的消息被摘掉；完成后需要能判断是否要重新拉取。
      const requestSessionId = this.sessionId
      const pendingClarification = [...this.messages].reverse().find(
        (message) => message.role === 'agent'
          && message.awaitingClarification
          && !message.clarificationResolved,
      )
      if (pendingClarification) pendingClarification.clarificationResolved = true
      this.error = ''
      this.runningSessions[requestSessionId] = true
      const userMessage: ChatMessage = {
        id: makeId('message'), role: 'user', content: normalized, status: 'complete', evidence: [],
      }
      const agentMessage: ChatMessage = {
        id: makeId('message'), role: 'agent', content: '', status: 'running', evidence: [],
        startedAtMs: Date.now(),
      }
      this.messages.push(userMessage, agentMessage)
      // Pinia 会把数组中的消息转换为响应式代理。后续 SSE 必须修改这个代理，
      // 如果继续修改 push 前的原始对象，页面通常只会在 this.running 变化时看到最终状态。
      const activeMessage = this.messages[this.messages.length - 1]
      setStage(activeMessage, '准备运行', 'code')
      // 记下本轮运行中的消息。切换对话会替换消息列表，但请求仍在后台继续，
      // 切回该会话时凭此引用把实时进度重新挂回列表。
      this.activeRunMessages[requestSessionId] = activeMessage

      try {
        await streamAgent(this.token, {
          query: normalized,
          sessionId: this.sessionId,
          modelId: this.selectedModel,
          fileKey: this.latestFileKey,
        }, (event) => this.applyEvent(activeMessage, event))
        const terminalStatus = activeMessage.pendingTerminalStatus || 'complete'
        if (activeMessage.awaitingClarification) {
          setStage(activeMessage, '等待你选择', 'done', 'warning', undefined, 'complete')
        } else {
          setStage(activeMessage,
            terminalStatus === 'complete' ? '流程完成' : '运行失败',
            'done',
            terminalStatus === 'complete' ? 'success' : 'failed',
            undefined,
            terminalStatus)
        }
        if (!activeMessage.content) activeMessage.content = '本轮处理已结束，但没有返回可展示的业务回答。'
      } catch (error) {
        activeMessage.stageQueue = []
        activeMessage.stageFlowBusy = false
        activeMessage.status = 'failed'
        applyStage(activeMessage, {
          label: '运行失败', kind: 'done', state: 'failed', terminalStatus: 'failed',
        })
        finishTiming(activeMessage)
        activeMessage.content = error instanceof Error ? error.message : 'Agent 请求失败，请稍后重试。'
        this.error = activeMessage.content
      } finally {
        finishTiming(activeMessage)
        this.runningSessions[requestSessionId] = false
        delete this.activeRunMessages[requestSessionId]
        // 后端在用户切换对话后仍会继续计算并将会话写入数据库。
        // 如果本轮消息已被切换动作摘掉、且用户仍停留在该会话，
        // 完成后从后端重新拉取消息，保证结果能正常显示。
        if (this.sessionId === requestSessionId
            && !this.messages.some((message) => message.id === agentMessage.id)) {
          await this.restoreSession(requestSessionId).catch(() => undefined)
        }
      }
    },
    applyEvent(message: ChatMessage, event: AgentEvent) {
      if (event.trace_id) message.traceId = event.trace_id
      if (event.event === 'assistant_message' || event.event === 'clarification_required') {
        setAgentContent(message, event.message || '')
        if (event.event === 'assistant_message') {
          setStage(message, '整理业务回答', 'code', 'success')
        } else {
          message.clarification = normalizeClarification(event.clarification)
          message.awaitingClarification = true
          message.pendingTerminalStatus = 'complete'
          setStage(message, '等待你选择', 'code', 'warning')
        }
      }
      if (event.event === 'agent_error') {
        message.content = event.message || 'Agent 运行未完成。'
        message.pendingTerminalStatus = 'failed'
      }
      if (event.event === 'agent_start') setStage(message, '读取会话上下文', 'storage')
      if (event.event === 'model_start') setStage(message, event.message || '模型处理中', 'llm')
      if (event.event === 'stage_update' && message.status === 'running') {
        const label = event.message || nodeLabels[event.node_name || ''] || '推进业务流程'
        setStage(message, label, stageKind(event.node_type),
          event.status === 'failed' ? 'failed' : 'success', event.duration_ms)
      }
      if (event.event === 'batch_indicator_result') {
        if (!message.batchResults) message.batchResults = []
        message.batchResults.push({
          ruleId: event.rule_id || '',
          ruleName: event.rule_name || '',
          status: event.status || '',
          done: event.done || 0,
          total: event.total || 0,
          resultValue: event.result_value,
          numeratorCount: event.numerator_count,
          denominatorCount: event.denominator_count,
          unit: event.unit,
          calculationDisplay: event.calculation_display,
          statStart: event.stat_start,
          statEnd: event.stat_end,
          runId: event.run_id,
          errorCode: event.error_code,
          errorMessage: event.error_message,
        })
        setStage(message,
          `已完成 ${event.done}/${event.total}：${event.rule_name || ''}`,
          'code', event.status === 'FAILED' ? 'warning' : 'success')
      }
      if (event.event === 'agent_done') {
        if (event.stop_reason === 'clarification' || message.awaitingClarification) {
          message.pendingTerminalStatus = 'complete'
        } else if (event.status === 'completed') {
          message.pendingTerminalStatus = 'complete'
        } else {
          message.pendingTerminalStatus = 'failed'
        }
      }
      if (event.event === 'tool_call') {
        setStage(message, toolLabels[event.tool_name || ''] || '调用受控业务工具', 'tool',
          'running')
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
        setStage(message, step.label, 'tool',
          step.state === 'success' ? 'success' : 'warning', event.duration_ms)
      }
    },
  },
})
