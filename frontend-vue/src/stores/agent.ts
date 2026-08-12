import { defineStore } from 'pinia'

import {
  loadCapabilities,
  streamAgent,
  uploadIndicatorFile,
  createSession,
  listSessions,
  getSessionMessages,
  deleteSession,
  loadAgentRun,
  loadBatchRun,
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
export type ExecutionNodeCategory = 'llm' | 'rule' | 'data' | 'verification' | 'summary' | 'failure'

export interface ExecutionNode {
  id: string
  nodeName: string
  nodeType: string
  label: string
  category: ExecutionNodeCategory
  status: StageState
  durationMs?: number
  toolName?: string
  capability?: string
  modelId?: string
  subtaskId?: string
  occurrence: number
  repeatCount?: number
  successCount?: number
  warningCount?: number
  failedCount?: number
  progressCompleted?: number
  progressTotal?: number
  indicatorCount?: number
  profileCount?: number
  errorCode?: string
  errorMessage?: string
}

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
  executionNodes?: ExecutionNode[]
  executionRef?: { batchRunId: string; traceId?: string }
  executionRestoreStatus?: 'idle' | 'loading' | 'ready' | 'expired'
  diagnosisCaseId?: string
}

export interface BatchIndicatorResult {
  batchRunId?: string
  ruleId: string
  ruleName: string
  profileId?: string
  profileLabel?: string
  status: string
  done: number
  total: number
  resultValue?: number
  numeratorCount?: number
  denominatorCount?: number
  sampleCount?: number
  targetValue?: number | string
  targetDirection?: string
  unit?: string
  calculationDisplay?: string
  statStart?: string
  statEnd?: string
  runId?: string
  dataFreshness?: string
  qualityStatus?: string
  errorCode?: string
  errorMessage?: string
  overviewSqlHash?: string
  detailKind?: string
  detailContractVersion?: string
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
  real_database_overview: '执行真实库概览 SQL',
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
  batch_indicator_enumerate: '确认本次指标清单',
  batch_data_initialization_validation: '数据初始化校验',
  real_snapshot_data_validation: '校验真实库本次数据',
  batch_indicator: '完成单项指标计算',
  batch_result_merge: '汇总本次计算结果',
}

function stageKind(value?: string): StageKind {
  if (value === 'llm' || value === 'tool' || value === 'database' || value === 'storage') {
    return value === 'database' ? 'tool' : value
  }
  return 'code'
}

const ruleNodeNames = new Set([
  'indicator_rule_match',
  'indicator_semantic_retrieval',
  'indicator_llm_disambiguation',
])

const ruleTools = new Set([
  'search_indicator_rules',
  'get_effective_rule',
  'inspect_indicator_implementation',
  'list_indicator_calibers',
  'resolve_indicator_caliber',
])

const dataNodeNames = new Set([
  'source_extraction_prepare',
  'source_data_extraction',
  'real_database_overview',
  'business_overview',
  'real_overview',
  'mras_patient_detail',
  'batch_indicator_enumerate',
  'batch_data_initialization_validation',
  'real_snapshot_data_validation',
  'batch_indicator',
])

// 这些节点会为每个口径各发一组 Trace 事件，页面只保留一个阶段节点，
// repeatCount 仅用于计算该阶段处理了多少个口径。
const profileStageNodeNames = new Set([
  'source_data_extraction',
  'real_snapshot_data_validation',
  'batch_indicator',
])

const dataTools = new Set([
  'prepare_indicator_sql',
  'trial_run_indicator_sql',
  'prepare_indicator_caliber_sql',
  'trial_run_indicator_caliber_sql',
  'analyze_uploaded_indicators',
])

const verificationNodeNames = new Set([
  'plan_verify',
  'response_guard',
  'dual_period_validation',
  'dual_comparison',
  'dual_department_detail',
  'dual_patient_detail',
  'difference_diagnosis_layer_1',
  'difference_diagnosis_layer_2',
  'difference_diagnosis_layer_3',
  'difference_diagnosis_layer_4',
  'difference_diagnosis_layer_5',
  'difference_diagnosis_layer_6',
])

const summaryNodeNames = new Set([
  'batch_result_merge',
  'compound_merge',
  'difference_diagnosis_conclusion',
  'difference_diagnosis_answer',
  'dual_diagnosis_conclusion',
  'prepared_sql_answer',
  'caliber_simulation_answer',
  'final_answer_llm',
])

function executionCategory(event: AgentEvent): ExecutionNodeCategory | null {
  const status = event.status || ''
  const nodeName = event.nodeName || ''
  const toolName = event.toolName || ''
  // 逐口径批处理节点即使有跳过、无样本或局部失败，也仍属于同一个数据阶段；
  // 非预期系统故障会由 batch_indicator_result 另建“系统异常”节点。
  if (['source_data_extraction', 'real_snapshot_data_validation', 'batch_indicator']
    .includes(nodeName)) return 'data'
  if (status === 'failed' || status === 'error') return 'failure'
  if (nodeName === 'batch_indicator'
      && !event.subtaskId
      && ((event.message || '').startsWith('正在计算指标')
        || (event.message || '').startsWith('完成指标计算'))) return null
  if (event.nodeType === 'llm') return 'llm'
  if (ruleNodeNames.has(nodeName)
      || (nodeName === 'tool_result' && ruleTools.has(toolName))) return 'rule'
  if (event.nodeType === 'database'
      || dataNodeNames.has(nodeName)
      || (nodeName === 'tool_result' && dataTools.has(toolName))) return 'data'
  if (verificationNodeNames.has(nodeName) || nodeName.includes('validation')) return 'verification'
  if (summaryNodeNames.has(nodeName) || nodeName.endsWith('_conclusion')) return 'summary'
  return null
}

function appendExecutionNode(message: ChatMessage, event: AgentEvent, label: string) {
  const category = executionCategory(event)
  if (!category) return
  const nodes = message.executionNodes || (message.executionNodes = [])
  const nodeName = event.nodeName || 'unknown'
  const subtaskId = event.subtaskId || 'root'
  const state = executionNodeState(event.status)
  const runningNode = nodes.find((node) =>
    node.nodeName === nodeName
    && (node.subtaskId || 'root') === subtaskId
    && node.status === 'running',
  )
  if (runningNode) {
    runningNode.label = label
    runningNode.status = state
    runningNode.durationMs = event.durationMs
    runningNode.toolName = event.toolName
    runningNode.capability = event.capability
    runningNode.modelId = event.modelId
    runningNode.errorCode = event.errorCode
    runningNode.errorMessage = event.errorMessage
    runningNode.progressCompleted = event.completed
    runningNode.progressTotal = event.total
    runningNode.indicatorCount = event.indicatorCount
    runningNode.profileCount = event.profileCount
    return
  }
  const repeatedBatchNode = (profileStageNodeNames.has(nodeName) || subtaskId.includes(':batch:'))
    ? nodes.find((node) => node.nodeName === nodeName && node.category === category)
    : undefined
  if (repeatedBatchNode) {
    repeatedBatchNode.label = label
    repeatedBatchNode.status = state
    repeatedBatchNode.durationMs = event.durationMs
    repeatedBatchNode.toolName = event.toolName
    repeatedBatchNode.capability = event.capability
    repeatedBatchNode.modelId = event.modelId
    repeatedBatchNode.subtaskId = subtaskId
    repeatedBatchNode.occurrence = 0
    repeatedBatchNode.repeatCount = (repeatedBatchNode.repeatCount || 1) + 1
    if (state === 'failed') repeatedBatchNode.failedCount = (repeatedBatchNode.failedCount ?? 0) + 1
    else if (state === 'warning') repeatedBatchNode.warningCount = (repeatedBatchNode.warningCount ?? 0) + 1
    else repeatedBatchNode.successCount = (repeatedBatchNode.successCount ?? 0) + 1
    repeatedBatchNode.errorCode = event.errorCode
    repeatedBatchNode.errorMessage = event.errorMessage
    return
  }
  const occurrence = nodes.filter((node) =>
    node.nodeName === nodeName && (node.subtaskId || 'root') === subtaskId,
  ).length
  nodes.push({
    id: `${nodeName}-${subtaskId}-${occurrence}`,
    nodeName,
    nodeType: event.nodeType || 'code',
    label,
    category,
    status: state,
    durationMs: event.durationMs,
    toolName: event.toolName,
    capability: event.capability,
    modelId: event.modelId,
    subtaskId,
    occurrence,
    errorCode: event.errorCode,
    errorMessage: event.errorMessage,
    successCount: state === 'success' ? 1 : 0,
    warningCount: state === 'warning' ? 1 : 0,
    failedCount: state === 'failed' ? 1 : 0,
    progressCompleted: event.completed,
    progressTotal: event.total,
    indicatorCount: event.indicatorCount,
    profileCount: event.profileCount,
  })
}

function executionNodeState(status?: string): StageState {
  if (status === 'running') return 'running'
  if (status === 'failed' || status === 'error') return 'failed'
  if (status === 'warning' || status === 'incomplete') return 'warning'
  return 'success'
}

function isUnexpectedSystemFailure(result: BatchIndicatorResult): boolean {
  if (result.status !== 'FAILED') return false
  const code = (result.errorCode || '').toUpperCase()
  if (code === 'PROFILE_NOT_IMPLEMENTED') return false
  if (code.startsWith('INIT_') && code !== 'INIT_DATABASE_UNAVAILABLE') return false
  return true
}

function appendSystemFailureNode(message: ChatMessage, result: BatchIndicatorResult) {
  if (!isUnexpectedSystemFailure(result)) return
  const id = `system-failure-${result.ruleId}-${result.profileId || 'default'}`
  if (message.executionNodes?.some((node) => node.id === id)) return
  const nodes = message.executionNodes || (message.executionNodes = [])
  nodes.push({
    id,
    nodeName: 'batch_system_failure',
    nodeType: 'code',
    label: `系统异常：${result.ruleName || result.ruleId}`,
    category: 'failure',
    status: 'failed',
    subtaskId: result.profileId,
    occurrence: 0,
    failedCount: 1,
    errorCode: result.errorCode,
    errorMessage: result.errorMessage,
  })
}

function executionNodesFromTrace(trace: Record<string, unknown>): ExecutionNode[] {
  const holder: ChatMessage = {
    id: 'trace-restore', role: 'agent', content: '', status: 'complete', evidence: [],
  }
  const rawNodes = Array.isArray(trace.nodes) ? trace.nodes : []
  for (const raw of rawNodes) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const node = raw as Record<string, unknown>
    const nodeName = String(node.nodeName || '')
    appendExecutionNode(holder, {
      event: 'stage_update',
      traceId: String(trace.traceId || ''),
      nodeName,
      nodeType: String(node.nodeType || 'code'),
      status: String(node.status || 'success'),
      durationMs: Number(node.durationMs || 0),
      subtaskId: String(node.subtaskId || 'root'),
      indicatorCount: Number((node.outputData as Record<string, unknown> | undefined)?.indicatorCount || 0) || undefined,
      profileCount: Number((node.outputData as Record<string, unknown> | undefined)?.profileCount || 0) || undefined,
    }, nodeLabels[nodeName] || nodeName || '运行节点')
  }
  return holder.executionNodes || []
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

const PERSISTENCE_POLL_INTERVAL_MS = 2_000
const PERSISTENCE_WAIT_TIMEOUT_MS = 30 * 60 * 1_000

async function waitForPersistedTurn(
  token: string,
  sessionId: string,
  minimumMessageCount: number,
): Promise<boolean> {
  const deadline = Date.now() + PERSISTENCE_WAIT_TIMEOUT_MS
  while (Date.now() < deadline) {
    try {
      const messages = await getSessionMessages(token, sessionId)
      const latest = messages[messages.length - 1]
      if (messages.length >= minimumMessageCount && latest?.role === 'assistant') {
        return true
      }
    } catch {
      // SSE 已断开时，短暂的历史接口失败不应把仍在后台运行的批次误判为失败。
    }
    await new Promise((resolve) => window.setTimeout(resolve, PERSISTENCE_POLL_INTERVAL_MS))
  }
  return false
}

/** 把持久化的批量卡片载荷（与 SSE batch_indicator_result 同形态）转为前端卡片数据 */
export function toBatchResult(raw: Record<string, unknown>): BatchIndicatorResult {
  return {
    batchRunId: raw.batchRunId as string | undefined,
    ruleId: String(raw.ruleId ?? ''),
    ruleName: String(raw.ruleName ?? ''),
    profileId: raw.profileId as string | undefined,
    profileLabel: (raw.profileLabel ?? raw.profileName) as string | undefined,
    status: String(raw.status ?? ''),
    done: Number(raw.done ?? 0),
    total: Number(raw.total ?? 0),
    resultValue: raw.resultValue as number | undefined,
    numeratorCount: raw.numeratorCount as number | undefined,
    denominatorCount: raw.denominatorCount as number | undefined,
    sampleCount: raw.sampleCount as number | undefined,
    targetValue: raw.targetValue as number | string | undefined,
    targetDirection: raw.targetDirection as string | undefined,
    unit: raw.unit as string | undefined,
    calculationDisplay: raw.calculationDisplay as string | undefined,
    statStart: raw.statStart as string | undefined,
    statEnd: raw.statEnd as string | undefined,
    runId: raw.runId as string | undefined,
    dataFreshness: raw.dataFreshness as string | undefined,
    qualityStatus: raw.qualityStatus as string | undefined,
    errorCode: raw.errorCode as string | undefined,
    errorMessage: raw.errorMessage as string | undefined,
    overviewSqlHash: raw.overviewSqlHash as string | undefined,
    detailKind: raw.detailKind as string | undefined,
    detailContractVersion: raw.detailContractVersion as string | undefined,
  }
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
    helpText: value.helpText || '',
    selectionMode: value.selectionMode === 'multiple' ? 'multiple' : 'single',
    options: value.options || [],
    allowFreeText: Boolean(value.allowFreeText),
    freeTextPlaceholder: value.freeTextPlaceholder || '补充说明',
    resumePrefix: value.resumePrefix || '继续处理上一条请求。补充信息：',
  }
}

export const useAgentStore = defineStore('agent', {
  state: () => ({
    token: 'guest',
    user: { userId: 'guest_user', accountId: 'guest', hospitalId: 'hospital_001', permissions: ['indicator_detail_view', 'indicator_detail_export'] } as HospitalUser,
    capabilities: null as AgentCapabilities | null,
    selectedModel: localStorage.getItem('wikiAgentSelectedModel') || '',
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
    async refreshCapabilities() {
      this.capabilities = await loadCapabilities(this.token)
      const ids = this.capabilities.models.map((model) => model.id)
      if (!ids.includes(this.selectedModel)) {
        this.selectedModel = ids.includes(this.capabilities.model)
          ? this.capabilities.model
          : ids[0] || ''
      }
      if (this.selectedModel) localStorage.setItem('wikiAgentSelectedModel', this.selectedModel)
    },
    selectModel(modelId: string) {
      const ids = this.capabilities?.models.map((model) => model.id) || []
      if (!ids.includes(modelId)) return
      this.selectedModel = modelId
      localStorage.setItem('wikiAgentSelectedModel', modelId)
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
        this.messages = list.map((message: SessionMessage) => {
          // 持久化的批量卡片载荷与 SSE batch_indicator_result 同形态，
          // 恢复后卡片组件渲染效果与实时推送一致。
          const rawBatch = Array.isArray(message.batchResults) ? message.batchResults : []
          const restoredResults = rawBatch.length ? rawBatch.map(toBatchResult) : undefined
          const restoredBatchRunId = restoredResults?.find((item) => item.batchRunId)?.batchRunId
          const diagnosisMatch = (message.content || '').match(/\{\{diagnosis_case:(DCASE_[A-Za-z0-9_]+)}}/)
          return {
            id: makeId('message'),
            role: (message.role === 'assistant' ? 'agent' : 'user') as 'agent' | 'user',
            content: (message.content || '').replace(/\{\{diagnosis_case:DCASE_[A-Za-z0-9_]+}}/, ''),
            status: 'complete' as const,
            evidence: [],
            batchResults: restoredResults,
            executionRef: restoredBatchRunId
              ? { batchRunId: restoredBatchRunId } : undefined,
            executionRestoreStatus: restoredBatchRunId ? 'idle' as const : undefined,
            diagnosisCaseId: diagnosisMatch?.[1],
          }
        })
        // 如果该会话仍有后台运行中的请求，把实时消息重新挂回列表，
        // 让用户切回来时能看到正在处理的进度，而不是误以为请求消失。
        if (runningMessage && !this.messages.some((message) => message.id === runningMessage.id)) {
          this.messages.push(runningMessage)
        }
      } catch (error) {
        this.error = error instanceof Error ? error.message : '恢复会话失败'
      }
    },
    /** 按已持久化 batchRunId 回读 trace，切换会话后不重新执行指标。 */
    async restoreExecution(message: ChatMessage) {
      if (message.status === 'running' || message.executionNodes?.length
          || message.executionRestoreStatus === 'loading') return
      const batchRunId = message.executionRef?.batchRunId
        || message.batchResults?.find((item) => item.batchRunId)?.batchRunId
      if (!batchRunId) return
      message.executionRestoreStatus = 'loading'
      try {
        const batch = await loadBatchRun(this.token, batchRunId)
        const traceId = batch.job.traceId
        if (!traceId) throw new Error('批次未保存运行证据编号。')
        const trace = await loadAgentRun(this.token, traceId)
        message.traceId = traceId
        message.executionRef = { batchRunId, traceId }
        message.executionNodes = executionNodesFromTrace(trace)
        for (const result of message.batchResults || []) appendSystemFailureNode(message, result)
        message.executionRestoreStatus = 'ready'
      } catch {
        message.executionRestoreStatus = 'expired'
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
      this.latestFileKey = result.fileKey
      this.latestFileName = result.fileName
      this.messages.push({
        id: makeId('message'),
        role: 'user',
        content: `已上传：${result.fileName}（${(result.sizeBytes / 1024).toFixed(1)} KB）`,
        status: 'complete',
        evidence: [],
      })
    },
    appendResolvedAction(userContent: string, agentContent: string) {
      this.messages.push(
        {
          id: makeId('message'),
          role: 'user',
          content: userContent,
          status: 'complete',
          evidence: [],
        },
        {
          id: makeId('message'),
          role: 'agent',
          content: agentContent,
          status: 'complete',
          evidence: [],
        },
      )
    },
    async send(query: string) {
      const normalized = query.trim()
      if (!normalized || this.running) return
      // 记录本轮请求所属的会话与消息标识。切换对话会把消息列表整个替换，
      // 导致进行中的消息被摘掉；完成后需要能判断是否要重新拉取。
      const requestSessionId = this.sessionId
      const minimumPersistedMessageCount = this.messages.length + 2
      let restoreFromPersistence = false
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
        let streamError: unknown
        try {
          await streamAgent(this.token, {
            query: normalized,
            sessionId: this.sessionId,
            modelId: this.selectedModel,
            fileKey: this.latestFileKey,
          }, (event) => this.applyEvent(activeMessage, event))
        } catch (error) {
          streamError = error
        }

        // 浏览器切换会话、代理连接波动或 SSE 提前结束时，后台批次仍会继续并最终
        // 持久化。只要已经收到过运行事件，就保持会话的“处理中”状态并轮询历史，
        // 直到本轮 assistant 结果落库；这样切回会话不会只剩用户问题。
        if (!activeMessage.pendingTerminalStatus
            && (activeMessage.traceId || activeMessage.batchResults?.length)) {
          activeMessage.content = '实时连接已结束，后台仍在继续计算，正在等待最终结果写入…'
          setStage(activeMessage, '后台继续计算，等待结果写入', 'storage', 'warning')
          const persisted = await waitForPersistedTurn(
            this.token,
            requestSessionId,
            minimumPersistedMessageCount,
          )
          if (!persisted) {
            throw streamError instanceof Error
              ? streamError
              : new Error('后台计算等待超时，请稍后从历史对话重新打开结果。')
          }
          restoreFromPersistence = true
          activeMessage.pendingTerminalStatus = 'complete'
        } else if (streamError) {
          throw streamError
        }

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
            && (restoreFromPersistence
              || !this.messages.some((message) => message.id === agentMessage.id))) {
          await this.restoreSession(requestSessionId).catch(() => undefined)
        }
      }
    },
    applyEvent(message: ChatMessage, event: AgentEvent) {
      if (event.traceId) message.traceId = event.traceId
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
        const backendLabel = event.message || ''
        const label = !backendLabel || backendLabel === event.nodeName
          ? nodeLabels[event.nodeName || ''] || backendLabel || '推进业务流程'
          : backendLabel
        appendExecutionNode(message, event, label)
        setStage(message, label, stageKind(event.nodeType),
          executionNodeState(event.status), event.durationMs)
      }
      if (event.event === 'batch_indicator_result') {
        if (!message.batchResults) message.batchResults = []
        const incoming: BatchIndicatorResult = {
          batchRunId: event.batchRunId,
          ruleId: event.ruleId || '',
          ruleName: event.ruleName || '',
          profileId: event.profileId,
          profileLabel: event.profileLabel,
          status: event.status || '',
          done: event.done || 0,
          total: event.total || 0,
          resultValue: event.resultValue,
          numeratorCount: event.numeratorCount,
          denominatorCount: event.denominatorCount,
          sampleCount: event.sampleCount,
          targetValue: event.targetValue,
          targetDirection: event.targetDirection,
          unit: event.unit,
          calculationDisplay: event.calculationDisplay,
          statStart: event.statStart,
          statEnd: event.statEnd,
          runId: event.runId,
          dataFreshness: event.dataFreshness,
          qualityStatus: event.qualityStatus,
          errorCode: event.errorCode,
          errorMessage: event.errorMessage,
          overviewSqlHash: event.overviewSqlHash,
          detailKind: event.detailKind,
          detailContractVersion: event.detailContractVersion,
        }
        if (incoming.batchRunId) {
          message.executionRef = {
            batchRunId: incoming.batchRunId,
            traceId: message.traceId,
          }
        }
        // 同一指标+口径重复推送时原地替换，避免叠出同形卡片；
        // 不同口径（profileId 不同）各自保留一张卡片。
        const existing = message.batchResults.findIndex(
          (item) => item.ruleId === incoming.ruleId
            && (item.profileId ?? '') === (incoming.profileId ?? ''))
        if (existing >= 0) {
          message.batchResults.splice(existing, 1, incoming)
        } else {
          message.batchResults.push(incoming)
        }
        appendSystemFailureNode(message, incoming)
        setStage(message,
          `已完成 ${event.done}/${event.total}：${event.ruleName || ''}`,
          'code', event.status === 'FAILED' ? 'warning' : 'success')
      }
      if (event.event === 'agent_done') {
        if (event.stopReason === 'clarification' || message.awaitingClarification) {
          message.pendingTerminalStatus = 'complete'
        } else if (event.status === 'completed') {
          message.pendingTerminalStatus = 'complete'
        } else {
          message.pendingTerminalStatus = 'failed'
        }
      }
      if (event.event === 'tool_call') {
        setStage(message, toolLabels[event.toolName || ''] || '调用受控业务工具', 'tool',
          'running')
        message.evidence.push({
          id: `${event.toolName || 'tool'}-${message.evidence.length}`,
          label: toolLabels[event.toolName || ''] || '处理业务信息',
          state: 'running',
          detail: '正在调用受控业务工具',
        })
      }
      if (event.event === 'tool_result') {
        const step = [...message.evidence].reverse().find((item) => item.state === 'running' && item.id.startsWith(event.toolName || 'tool'))
        if (!step) return
        step.state = event.status === 'success' || event.status === 'preview_ready' ? 'success' : 'warning'
        step.detail = event.reused ? '复用本轮已有结果' : event.message || event.code || '工具执行结束'
        step.durationMs = event.durationMs
        step.reused = event.reused
        setStage(message, step.label, 'tool',
          step.state === 'success' ? 'success' : 'warning', event.durationMs)
      }
    },
  },
})
