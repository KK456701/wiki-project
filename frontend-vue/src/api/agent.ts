export interface HospitalUser {
  userId: string
  accountId: string
  hospitalId: string
  permissions: string[]
}

export interface AgentModel {
  id: string
  name: string
  provider: string
  model?: string
  thinking?: boolean
  available?: boolean
}

export interface AgentCapabilities {
  enabled: boolean
  model: string
  models: AgentModel[]
  streaming: boolean
  maxSteps: number
  orchestration?: string
}

export interface AgentClarificationOption {
  id: string
  label: string
  value: string
  description: string
  group: string
}

export interface AgentClarification {
  code: string
  kind: string
  title: string
  question: string
  helpText: string
  selectionMode: 'single' | 'multiple'
  options: AgentClarificationOption[]
  allowFreeText: boolean
  freeTextPlaceholder: string
  resumePrefix: string
}

export interface AgentClarificationWire {
  code: string
  kind: string
  title: string
  question: string
  helpText: string
  selectionMode: 'single' | 'multiple'
  options: AgentClarificationOption[]
  allowFreeText: boolean
  freeTextPlaceholder: string
  resumePrefix: string
}

export interface AgentEvent {
  event: string
  traceId: string
  step?: number
  stepCount?: number
  toolName?: string
  status?: string
  code?: string
  message?: string
  stopReason?: string
  durationMs?: number
  reused?: boolean
  retryable?: boolean
  nodeName?: string
  nodeType?: 'llm' | 'code' | 'tool' | 'database' | 'storage' | string
  capability?: string
  modelId?: string
  subtaskId?: string
  clarification?: AgentClarificationWire
  ruleId?: string
  ruleName?: string
  profileId?: string
  profileLabel?: string
  done?: number
  total?: number
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
  batchRunId?: string
  runId?: string
  dataFreshness?: string
  qualityStatus?: string
  errorCode?: string
  errorMessage?: string
  overviewSqlHash?: string
  detailKind?: string
  detailContractVersion?: string
  phase?: string
  completed?: number
  checkedObjectCount?: number
  indicatorCount?: number
  profileCount?: number
  runnableCount?: number
  noSampleCount?: number
  blockedCount?: number
  skippedCount?: number
}

export interface UploadResult {
  fileKey: string
  fileName: string
  sizeBytes: number
}

export interface DetailColumn {
  field: string
  label: string
  sensitivity: string
}

export interface DetailSnapshot {
  snapshotId: string
  runId: string
  hospitalId: string
  ruleId: string
  ruleName: string
  effectiveLevel: string
  nationalVersion?: string
  hospitalVersion?: number
  statStart: string
  statEnd: string
  denominatorCount: number
  numeratorCount: number
  unmatchedCount: number
  columns: DetailColumn[]
  createdAt: string
  expiresAt: string
  reused: boolean
  sourceDatabase: string
  sourceTables: string[]
}

export interface DetailPage {
  snapshotId: string
  runId: string
  group: 'denominator' | 'numerator' | 'unmatched'
  page: number
  pageSize: number
  total: number
  items: Array<Record<string, unknown>>
}

export interface IndicatorExport {
  exportId: string
  runId: string
  hospitalId: string
  ruleId: string
  fileName: string
  rowCount: number
  status: string
  createdAt: string
  expiresAt: string
  downloadCount: number
}

function authHeaders(token: string): HeadersInit {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await response.json().catch(() => ({})) as T & {
    code?: string
    message?: string
    detail?: string | { message?: string }
  }
  if (!response.ok) {
    const detail = data.detail
    const message = data.message || (typeof detail === 'string' ? detail : detail?.message)
    throw new Error(message || `请求失败（HTTP ${response.status}）`)
  }
  return data
}

export async function loadCapabilities(token: string): Promise<AgentCapabilities> {
  const response = await fetch('/api/agent/capabilities', { headers: authHeaders(token) })
  return readJson<AgentCapabilities>(response)
}

export interface RuntimeDatabaseSetting {
  id: 'business' | 'real' | 'oracle'
  name: string
  purpose: string
  enabled: boolean
  configured: boolean
  engine: string
  endpoint: string
  username: string
  schema: string
  credentialConfigured: boolean
  pool: Record<string, number>
  formalChain: boolean
}

export interface RuntimeModelSetting extends AgentModel {
  baseUrl: string
  completionsPath: string
  enableThinking: boolean | null
  apiKeyConfigured: boolean
}

export interface RuntimeSettings {
  securityNotice: string
  defaultModel: string
  models: RuntimeModelSetting[]
  databases: RuntimeDatabaseSetting[]
}

export interface ConnectionTestResult {
  connectionId: string
  status: 'CONNECTED' | 'FAILED' | 'DISABLED'
  message: string
  durationMs: number
}

export interface RuntimeConnectionTestInput {
  driverClassName: string
  url: string
  username: string
  password: string
  schema: string
}

export interface RuntimeConnectionSaveInput extends RuntimeConnectionTestInput {
  enabled: boolean
  maximumPoolSize?: number
  minimumIdle?: number
  connectionTimeoutMs?: number
  validationQuery?: string
}

export interface RuntimeModelConfigInput {
  id: string
  name: string
  provider: 'ollama' | 'openai-compatible'
  model: string
  baseUrl: string
  completionsPath: string
  /** 只写字段：空值表示保留已保存的 Key，响应永不返回 Key。 */
  apiKey: string
  thinking: boolean
  enableThinking: boolean | null
}

export async function loadRuntimeSettings(token: string): Promise<RuntimeSettings> {
  const response = await fetch('/api/settings/runtime', { headers: authHeaders(token) })
  return readJson<RuntimeSettings>(response)
}

export async function testRuntimeConnection(
  token: string,
  connectionId: RuntimeDatabaseSetting['id'],
  input?: RuntimeConnectionTestInput,
): Promise<ConnectionTestResult> {
  const response = await fetch(`/api/settings/connections/${encodeURIComponent(connectionId)}/test`, {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(input || {}),
  })
  return readJson<ConnectionTestResult>(response)
}

export async function setRuntimeDefaultModel(token: string, modelId: string): Promise<{ defaultModel: string; message: string }> {
  const response = await fetch('/api/settings/models/default', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelId }),
  })
  return readJson(response)
}

export async function saveRuntimeModelConfiguration(
  token: string,
  input: { defaultModel: string; models: RuntimeModelConfigInput[] },
): Promise<{ defaultModel: string; models: RuntimeModelSetting[]; message: string }> {
  const response = await fetch('/api/settings/models/configuration', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
  return readJson(response)
}

export async function saveRuntimeConnection(
  token: string,
  connectionId: RuntimeDatabaseSetting['id'],
  input: RuntimeConnectionSaveInput,
): Promise<{ connectionId: string; configured: boolean; restartRequired: boolean; message: string }> {
  const response = await fetch(`/api/settings/connections/${encodeURIComponent(connectionId)}/save`, {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
  return readJson(response)
}

export interface IndicatorItem {
  ruleId: string
  ruleName: string
}

export interface DiagnosisCaseSnapshot {
  caseId: string
  hospitalId: string
  userId: string
  sessionId: string
  status: string
  currentStep: string
  ruleId: string
  profileId: string
  knowledgeReleaseId: string
  modelId: string
  caseInput: Record<string, unknown>
  caliberSnapshot: Record<string, unknown>
  caseExpectedClassification: Record<string, unknown>
  gateResults: Array<Record<string, unknown>>
  evidence: Array<Record<string, unknown>>
  causeConclusion: Record<string, unknown>
  changeProposal: Record<string, unknown>
  candidateSql: Record<string, unknown>
  shadowTrial: Record<string, unknown>
  investigationMode: 'STANDARD' | 'AUTONOMOUS'
  autonomousRun: Record<string, unknown>
  draftResult: Record<string, unknown>
  releaseResult: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export interface DiagnosisShadowDiffPage {
  trialId: string
  type: 'ADDED' | 'REMOVED' | 'CHANGED' | 'DUPLICATE'
  page: number
  pageSize: number
  total: number
  items: Array<{
    businessKey: string
    beforeRows: Array<Record<string, unknown>>
    afterRows: Array<Record<string, unknown>>
    changedFields: string[]
  }>
}

export interface DiagnosisAgentEventsResponse {
  caseId: string
  events: Array<Record<string, unknown>>
  status: string
  autonomousRun: Record<string, unknown>
  updatedAt: string
}

export async function loadDiagnosisAgentEvents(
  token: string,
  caseId: string,
  afterSeq = 0,
): Promise<DiagnosisAgentEventsResponse> {
  const query = new URLSearchParams({ afterSeq: String(Math.max(0, afterSeq)) })
  const response = await fetch(`/api/diagnosis/cases/${encodeURIComponent(caseId)}/agent-events?${query}`, {
    headers: authHeaders(token),
  })
  return readJson<DiagnosisAgentEventsResponse>(response)
}

export async function loadDiagnosisShadowDiffs(
  token: string,
  caseId: string,
  trialId: string,
  type: DiagnosisShadowDiffPage['type'],
  page = 1,
  pageSize = 50,
  search = '',
): Promise<DiagnosisShadowDiffPage> {
  const query = new URLSearchParams({ trialId, type, page: String(page), pageSize: String(pageSize) })
  if (search.trim()) query.set('search', search.trim())
  const response = await fetch(`/api/diagnosis/cases/${encodeURIComponent(caseId)}/shadow-diffs?${query}`, {
    headers: authHeaders(token),
  })
  return readJson<DiagnosisShadowDiffPage>(response)
}

export interface CreateDiagnosisCaseInput {
  sessionId: string
  ruleId: string
  profileId: string
  statStart: string
  statEnd: string
  modelId: string
  caseInput: Record<string, unknown>
  expectedClassification?: Record<string, unknown>
}

export async function createDiagnosisCase(
  token: string,
  input: CreateDiagnosisCaseInput,
): Promise<DiagnosisCaseSnapshot> {
  const response = await fetch('/api/diagnosis/cases', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
  return readJson<DiagnosisCaseSnapshot>(response)
}

export async function loadDiagnosisCase(
  token: string,
  caseId: string,
): Promise<DiagnosisCaseSnapshot> {
  const response = await fetch(`/api/diagnosis/cases/${encodeURIComponent(caseId)}`, {
    headers: authHeaders(token),
  })
  return readJson<DiagnosisCaseSnapshot>(response)
}

export async function actOnDiagnosisCase(
  token: string,
  caseId: string,
  action: string,
  payload: Record<string, unknown> = {},
): Promise<DiagnosisCaseSnapshot> {
  const response = await fetch(`/api/diagnosis/cases/${encodeURIComponent(caseId)}/actions`, {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, payload }),
  })
  return readJson<DiagnosisCaseSnapshot>(response)
}

export interface HospitalKnowledgeDraft {
  draftId: string
  hospitalId: string
  ruleId: string
  profileId: string
  changeLayer: 'SOURCE_EXTRACT' | 'OVERVIEW'
  reviewStatus: 'PENDING_REVIEW'
  createdAt: string
  createdBy?: string
  originalSql: string
  candidateSql: string
  trialPassed: boolean
  formalEffect: boolean
  issueSummary?: string
  changeSummary?: string
  expectedImpact?: string
  verificationSummary?: string
  changeRequest: Record<string, unknown>
  shadowTrial: Record<string, unknown>
}

export async function listHospitalKnowledgeDrafts(
  token: string,
): Promise<{ hospitalId: string, count: number, packageAvailable: boolean, items: HospitalKnowledgeDraft[] }> {
  const response = await fetch('/api/diagnosis/hospital-drafts', { headers: authHeaders(token) })
  return readJson(response)
}

export async function loadHospitalKnowledgeDraft(
  token: string,
  draftId: string,
): Promise<HospitalKnowledgeDraft> {
  const response = await fetch(`/api/diagnosis/hospital-drafts/${encodeURIComponent(draftId)}`, {
    headers: authHeaders(token),
  })
  return readJson(response)
}

export async function exportHospitalKnowledgePackage(token: string): Promise<void> {
  const response = await fetch('/api/diagnosis/hospital-drafts/export/package', {
    headers: authHeaders(token),
  })
  if (!response.ok) {
    await readJson(response)
    return
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const simple = /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  const fileName = encoded ? decodeURIComponent(encoded) : simple || 'hospital-knowledge.zip'
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(url)
}

/** 查询异常排查基础校验所冻结的分子或分母明细。 */
export async function fetchDiagnosisCaseDetails(
  token: string,
  caseId: string,
  group: 'numerator' | 'denominator',
  page = 1,
  pageSize = 50,
): Promise<IndicatorDetailResult> {
  const query = new URLSearchParams({
    group,
    page: String(page),
    pageSize: String(pageSize),
  })
  const response = await fetch(
    `/api/diagnosis/cases/${encodeURIComponent(caseId)}/details?${query}`,
    { headers: authHeaders(token) },
  )
  return readJson<IndicatorDetailResult>(response)
}

export interface DiagnosisScopeClarification {
  scopeType: 'RECORD' | 'DEPARTMENT'
  object: string
  requestedField: string
  matchedFields: string[]
  status: 'IN_NUMERATOR_AND_DENOMINATOR' | 'IN_DENOMINATOR_ONLY' | 'NOT_IN_DETAIL'
  denominatorCount: number
  numeratorCount: number
  denominatorRule: string
  numeratorRule: string
  statStart: string
  statEnd: string
  summary: string
  reasons: string[]
  naturalLanguageExplanation: string
  explanationSource: 'MODEL' | 'PROGRAM_FALLBACK'
  explanationModel: string
  sampleRows: Array<Record<string, unknown>>
  sampleTruncated: boolean
  detailCountsReconciled: boolean
  overviewSqlHash: string
  snapshotReused: boolean
}

/** 解释所选患者或科室为何出现在本次分子、分母明细中。 */
export async function fetchDiagnosisScopeClarification(
  token: string,
  caseId: string,
): Promise<DiagnosisScopeClarification> {
  const response = await fetch(
    `/api/diagnosis/cases/${encodeURIComponent(caseId)}/scope-clarification`,
    { headers: authHeaders(token) },
  )
  return readJson<DiagnosisScopeClarification>(response)
}

/** 获取全部活跃指标（供引导面板渲染指标多选列表） */
export async function listIndicators(token: string): Promise<IndicatorItem[]> {
  const response = await fetch('/api/kb/rules/list', { headers: authHeaders(token) })
  return readJson<IndicatorItem[]>(response)
}

/** 指标当前生效口径（卡片「口径 / 核算方式」按钮数据源），字段为后端知识库原始键名 */
export type EffectiveRule = Record<string, unknown>

export async function fetchEffectiveRule(
  token: string,
  ruleId: string,
  profileId?: string,
  statStart?: string,
  statEnd?: string,
): Promise<EffectiveRule> {
  // 传入 profileId 时按口径变体读取，让同一指标的多个口径各自返回自己的口径 / 核算方式
  const query = new URLSearchParams()
  if (profileId) query.set('profileId', profileId)
  if (statStart) query.set('statStart', statStart)
  if (statEnd) query.set('statEnd', statEnd)
  const queryText = query.toString()
  const suffix = queryText ? `?${queryText}` : ''
  const response = await fetch(`/api/kb/rules/${encodeURIComponent(ruleId)}/effective${suffix}`, {
    headers: authHeaders(token),
  })
  return readJson<EffectiveRule>(response)
}

export interface IndicatorDetailResult {
  batchRunId: string
  ruleId: string
  ruleName?: string
  group: string
  statStart: string
  statEnd: string
  page?: number
  pageSize?: number
  rowCount?: number
  rows?: Record<string, unknown>[]
  truncated?: boolean
  snapshotId?: string
  snapshotReused?: boolean
  durationMs?: number
  sqlSource: string
  detailKind?: string
  detailContractVersion?: string
  cardNumerator?: number
  cardDenominator?: number
  detailNumerator?: number
  detailDenominator?: number
  overviewSqlHash?: string
  numeratorContributionTotal?: number
  denominatorContributionTotal?: number
  medianValue?: number
  sampleCount?: number
  actualCount?: number
  registeredCount?: number
  actualRows?: Record<string, unknown>[]
  registeredRows?: Record<string, unknown>[]
  level4Rate?: string
  level3Rate?: string
  resultDisplay?: string
  level4Hit?: Record<string, unknown>[]
  level4Total?: Record<string, unknown>[]
  level3Hit?: Record<string, unknown>[]
  level3Total?: Record<string, unknown>[]
  groupCounts?: Record<string, number>
}

/** 按指标 + 统计区间直接查询分子/分母患者明细（卡片「明细」按钮） */
export async function fetchIndicatorDetails(
  token: string,
  ruleId: string,
  group: string,
  batchRunId: string,
  start: string,
  end: string,
  profileId?: string,
  page = 1,
  pageSize = 50,
): Promise<IndicatorDetailResult> {
  const query = new URLSearchParams({
    group,
    batchRunId,
    start,
    end,
    page: String(page),
    pageSize: String(pageSize),
  })
  // 传入 profileId 时按口径变体查询，让同一指标的每个口径各自查各自的明细
  if (profileId) query.set('profileId', profileId)
  const response = await fetch(`/api/kb/rules/${encodeURIComponent(ruleId)}/details?${query}`, {
    headers: authHeaders(token),
  })
  return readJson<IndicatorDetailResult>(response)
}

export interface InspectIndicatorAction {
  action: 'inspect_indicator'
  batchRunId: string
  indicatorId: string
  profileId?: string
  modelId?: string
}

export interface PreparedIndicatorInspection {
  action: string
  auditId: string
  requiredModelProvider: 'cloud_api'
  modelId: string
  facts: Record<string, unknown>
  prompt: string
  answer: string
}

/** 将重点指标点击动作绑定到服务端保存的批次事实，并生成云端解释提示。 */
export async function prepareIndicatorInspection(
  token: string,
  action: InspectIndicatorAction,
): Promise<PreparedIndicatorInspection> {
  const response = await fetch('/api/agent/actions/inspect-indicator', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(action),
  })
  return readJson<PreparedIndicatorInspection>(response)
}

export interface BatchAnalysisAction {
  action: 'batch_confirmation_checklist' | 'batch_data_quality_review'
  batchRunId: string
}

export interface PreparedBatchAnalysis {
  action: BatchAnalysisAction['action']
  auditId: string
  batchRunId: string
  displayPrompt: string
  answer: string
}

/** 固定批次快捷动作：由服务端读取已保存事实并生成确定性回答，不走自由问句解析。 */
export async function prepareBatchAnalysis(
  token: string,
  action: BatchAnalysisAction,
): Promise<PreparedBatchAnalysis> {
  const response = await fetch('/api/agent/actions/analyze-batch', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(action),
  })
  return readJson<PreparedBatchAnalysis>(response)
}

export interface BatchReportSnapshot {
  reportId: string
  version: number
  reportStatus: 'DRAFT' | 'FORMAL'
  batchRunId: string
  statStart: string
  statEnd: string
  generatedAt: string
  total: number
  counts: Record<string, number>
  tasks: Record<string, unknown>[]
  statement: string
}

export interface BatchRunSnapshot {
  job: {
    batchRunId: string
    traceId: string
    total: number
    status: string
    statStart?: string
    statEnd?: string
  }
  tasks: Array<Record<string, unknown>>
}

/** 读取已持久化的批次与 Trace 引用，供历史会话恢复核算证据链。 */
export async function loadBatchRun(
  token: string,
  batchRunId: string,
): Promise<BatchRunSnapshot> {
  const response = await fetch(`/api/agent/batches/${encodeURIComponent(batchRunId)}`, {
    headers: authHeaders(token),
  })
  return readJson<BatchRunSnapshot>(response)
}

export async function createBatchReportSnapshot(
  token: string,
  batchRunId: string,
): Promise<BatchReportSnapshot> {
  const response = await fetch(`/api/batch-runs/${encodeURIComponent(batchRunId)}/reports`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  return readJson<BatchReportSnapshot>(response)
}

export async function downloadBatchReport(
  token: string,
  reportId: string,
  format: 'docx' | 'pdf' | 'xlsx',
): Promise<{ blob: Blob; fileName: string }> {
  const response = await fetch(
    `/api/batch-reports/${encodeURIComponent(reportId)}/download?format=${format}`,
    { headers: authHeaders(token) },
  )
  if (!response.ok) {
    await readJson(response)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return {
    blob: await response.blob(),
    fileName: encoded ? decodeURIComponent(encoded) : plain || `核心指标报告.${format}`,
  }
}

export async function uploadIndicatorFile(token: string, file: File): Promise<UploadResult> {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch('/api/agent/upload', {
    method: 'POST',
    headers: authHeaders(token),
    body,
  })
  return readJson<UploadResult>(response)
}

export async function loadAgentRun(token: string, traceId: string): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/agent/runs/${encodeURIComponent(traceId)}`, {
    headers: authHeaders(token),
  })
  return readJson<Record<string, unknown>>(response)
}

export async function ensureIndicatorDetails(token: string, runId: string): Promise<DetailSnapshot> {
  const response = await fetch(`/api/sql-runs/${encodeURIComponent(runId)}/details`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  return readJson<DetailSnapshot>(response)
}

export async function loadIndicatorDetailPage(
  token: string,
  runId: string,
  group: DetailPage['group'],
  page = 1,
  pageSize = 50,
): Promise<DetailPage> {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  const response = await fetch(
    `/api/sql-runs/${encodeURIComponent(runId)}/details/${group}?${query}`,
    { headers: authHeaders(token) },
  )
  return readJson<DetailPage>(response)
}

export async function createIndicatorExport(
  token: string,
  runId: string,
  confirmed: boolean,
): Promise<IndicatorExport> {
  const response = await fetch(`/api/sql-runs/${encodeURIComponent(runId)}/exports`, {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirmed }),
  })
  return readJson<IndicatorExport>(response)
}

export async function createUploadComparisonExport(
  token: string,
  runId: string,
  fileToken: string,
  confirmed: boolean,
): Promise<IndicatorExport> {
  const response = await fetch(
    `/api/sql-runs/${encodeURIComponent(runId)}/upload-comparison-exports`,
    {
      method: 'POST',
      headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
      body: JSON.stringify({ confirmed, fileToken }),
    },
  )
  return readJson<IndicatorExport>(response)
}

export async function createDiagnosisReportExport(
  token: string,
  reportId: string,
  confirmed: boolean,
): Promise<IndicatorExport> {
  const response = await fetch(
    `/api/diagnosis-reports/${encodeURIComponent(reportId)}/exports`,
    {
      method: 'POST',
      headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
      body: JSON.stringify({ confirmed }),
    },
  )
  return readJson<IndicatorExport>(response)
}

export async function downloadIndicatorExport(
  token: string,
  value: IndicatorExport,
): Promise<void> {
  const response = await fetch(`/api/indicator-exports/${encodeURIComponent(value.exportId)}/download`, {
    headers: authHeaders(token),
  })
  if (!response.ok) await readJson(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = value.fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

function parseSseBlock(block: string): AgentEvent | null {
  let eventName = 'message'
  const data: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  try {
    const payload = JSON.parse(data.join('\n')) as AgentEvent
    payload.event = eventName
    return payload
  } catch {
    return null
  }
}

export async function streamAgent(
  token: string,
  input: { query: string; sessionId: string; modelId?: string; fileKey?: string },
  onEvent: (event: AgentEvent) => void,
): Promise<void> {
  const body: Record<string, string> = {
    query: input.query,
    sessionId: input.sessionId,
  }
  if (input.modelId) body.modelId = input.modelId
  if (input.fileKey) body.fileKey = input.fileKey
  const response = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    await readJson(response)
  }
  if (!response.body) throw new Error('当前浏览器不支持流式读取。')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const chunk = await reader.read()
    if (chunk.done) break
    buffer += decoder.decode(chunk.value, { stream: true }).replace(/\r\n/g, '\n')
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      const event = parseSseBlock(block)
      if (event) onEvent(event)
    }
  }
  const finalEvent = parseSseBlock(buffer)
  if (finalEvent) onEvent(finalEvent)
}

// ─── 会话管理 ───────────────────────────────────────────────────────────────────

export interface SessionSummary {
  sessionId: string
  title: string
  lastMessageAt: string
  messageCount: number
}

export interface SessionMessage {
  role: string
  content: string
  ruleId?: string
  ruleName?: string
  statStart?: string
  statEnd?: string
  runId?: string
  createdAt: string
  /** 批量指标卡片载荷（与 SSE batch_indicator_result 同形态），供恢复会话时重建卡片 */
  batchResults?: Array<Record<string, unknown>> | null
}

/** 创建新会话，后端生成 session_id */
export async function createSession(token: string): Promise<string> {
  const response = await fetch('/api/agent/sessions', {
    method: 'POST',
    headers: authHeaders(token),
  })
  const payload = await readJson<{ sessionId: string }>(response)
  return payload.sessionId
}

/** 获取当前用户的历史会话列表 */
export async function listSessions(token: string): Promise<SessionSummary[]> {
  const response = await fetch('/api/agent/sessions', {
    headers: authHeaders(token),
  })
  return readJson(response)
}

/** 恢复指定会话的消息记录 */
export async function getSessionMessages(token: string, sessionId: string): Promise<SessionMessage[]> {
  const response = await fetch(`/api/agent/sessions/${encodeURIComponent(sessionId)}/messages`, {
    headers: authHeaders(token),
  })
  return readJson(response)
}

/** 删除指定会话 */
export async function deleteSession(token: string, sessionId: string): Promise<void> {
  await fetch(`/api/agent/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: authHeaders(token),
  })
}
