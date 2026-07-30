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
  unit?: string
  calculationDisplay?: string
  statStart?: string
  statEnd?: string
  runId?: string
  dataFreshness?: string
  errorCode?: string
  errorMessage?: string
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

export interface AgentRunSummary {
  traceId: string
  sessionId?: string
  intent?: string
  finalStatus?: string
  errorCount?: number
  fallbackCount?: number
  startedAt?: string
  endedAt?: string
  durationMs?: number
}

export interface AgentRunMetrics {
  hospitalId: string
  requestCount: number
  successRate: number
  incompleteRate: number
  latencyMs: { average: number; p50: number; p95: number; p99: number }
  statusCounts: Record<string, number>
  trend: Array<{ date: string; requests: number; plannerMs: number; finalAnswerMs: number }>
  tools: Array<{ toolName: string; calls: number; failures: number; durationMs: number }>
  models: Array<{ modelId: string; calls: number; timeouts: number; durationMs: number; inputTokens: number; outputTokens: number }>
  repeatedCallStopRate: number
  replanRate: number
  compoundRequestCount: number
  compoundAverageDurationMs: number
  warnings: Array<{ code: string; message: string }>
  thresholds: Record<string, number>
}

export interface MetadataChange {
  tableName: string
  fieldName: string
  changeType: string
  changeDesc: string
}

export interface MetadataAffectedRule {
  ruleId: string
  matchedColumns: string[]
  businessFields: string[]
}

export interface MetadataOverview {
  hospitalId: string
  dbName: string
  sourceId?: string
  hasSnapshot: boolean
  metadataSource?: string
  batchId?: string
  syncedAt?: string
  tableCount: number
  columnCount: number
  changes: MetadataChange[]
  affectedRules: MetadataAffectedRule[]
  traceId?: string
}

export interface TerminologyConcept {
  conceptCode: string
  canonicalName: string
  conceptType: string
  definition: string
  standardCode?: string
  sourceLevel: string
  sourceReference: string
  aliasCount?: number
  aliasesPreview?: string[]
}

export interface TerminologyConceptDetail extends TerminologyConcept {
  hospitalId: string
  aliases: Array<Record<string, unknown>>
  ruleLinks: Array<Record<string, unknown>>
  hospitalMappings: Array<Record<string, unknown>>
  activeRelease: Record<string, unknown>
}

export interface TerminologyNormalization {
  originalText: string
  normalizedText: string
  matches: Array<Record<string, unknown>>
  ambiguities: Array<Record<string, unknown>>
  releaseVersion: string
  durationMs: number
  sqlEligible: boolean
}

function authHeaders(token: string): HeadersInit {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await response.json().catch(() => ({})) as T & { detail?: string | { message?: string } }
  if (!response.ok) {
    const detail = data.detail
    const message = typeof detail === 'string' ? detail : detail?.message
    throw new Error(message || `请求失败（HTTP ${response.status}）`)
  }
  return data
}

export async function loadCapabilities(token: string): Promise<AgentCapabilities> {
  const response = await fetch('/api/agent/capabilities', { headers: authHeaders(token) })
  return readJson<AgentCapabilities>(response)
}

export interface IndicatorItem {
  ruleId: string
  ruleName: string
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
): Promise<EffectiveRule> {
  // 传入 profileId 时按口径变体读取，让同一指标的多个口径各自返回自己的口径 / 核算方式
  const suffix = profileId ? `?profileId=${encodeURIComponent(profileId)}` : ''
  const response = await fetch(`/api/kb/rules/${encodeURIComponent(ruleId)}/effective${suffix}`, {
    headers: authHeaders(token),
  })
  return readJson<EffectiveRule>(response)
}

export interface IndicatorDetailResult {
  ruleId: string
  ruleName?: string
  group: 'numerator' | 'denominator'
  statStart: string
  statEnd: string
  rowCount: number
  rows: Record<string, unknown>[]
  truncated?: boolean
  sqlSource: string
  detailSql?: string
}

/** 按指标 + 统计区间直接查询分子/分母患者明细（卡片「明细」按钮） */
export async function fetchIndicatorDetails(
  token: string,
  ruleId: string,
  group: 'numerator' | 'denominator',
  start: string,
  end: string,
  modelId?: string,
  profileId?: string,
): Promise<IndicatorDetailResult> {
  const query = new URLSearchParams({ group, start, end })
  // 明细 SQL 由所选模型现场合成：把用户当前选的模型透传给后端，选什么模型就用什么模型
  if (modelId) query.set('modelId', modelId)
  // 传入 profileId 时按口径变体查询，让同一指标的每个口径各自查各自的明细
  if (profileId) query.set('profileId', profileId)
  const response = await fetch(`/api/kb/rules/${encodeURIComponent(ruleId)}/details?${query}`, {
    headers: authHeaders(token),
  })
  return readJson<IndicatorDetailResult>(response)
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

export async function loadAgentRuns(
  token: string,
  filters: Record<string, string> = {},
): Promise<{ hospitalId: string; count: number; items: AgentRunSummary[] }> {
  const query = new URLSearchParams(filters)
  const response = await fetch(`/api/agent/runs${query.size ? `?${query}` : ''}`, {
    headers: authHeaders(token),
  })
  return readJson(response)
}

export async function loadAgentRunMetrics(
  token: string,
  filters: Record<string, string> = {},
): Promise<AgentRunMetrics> {
  const query = new URLSearchParams(filters)
  const response = await fetch(`/api/agent/runs/metrics${query.size ? `?${query}` : ''}`, {
    headers: authHeaders(token),
  })
  return readJson(response)
}

export async function loadMetadataOverview(
  token: string,
  hospitalId: string,
): Promise<MetadataOverview> {
  const query = new URLSearchParams({ hospitalId })
  const response = await fetch(`/api/metadata/overview?${query}`, {
    headers: authHeaders(token),
  })
  return readJson<MetadataOverview>(response)
}

export async function syncMetadata(
  token: string,
  hospitalId: string,
): Promise<MetadataOverview> {
  const response = await fetch('/api/metadata/sync', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ hospitalId, dbName: '', source: 'dbhub' }),
  })
  return readJson<MetadataOverview>(response)
}

export async function loadTerminologyConcepts(
  token: string,
  filters: { query?: string; conceptType?: string; ruleId?: string } = {},
): Promise<{ items: TerminologyConcept[]; total: number }> {
  const query = new URLSearchParams()
  if (filters.query) query.set('query', filters.query)
  if (filters.conceptType) query.set('conceptType', filters.conceptType)
  if (filters.ruleId) query.set('ruleId', filters.ruleId)
  const response = await fetch(`/api/terminology/concepts${query.size ? `?${query}` : ''}`, {
    headers: authHeaders(token),
  })
  return readJson(response)
}

export async function loadTerminologyConcept(
  token: string,
  conceptCode: string,
  hospitalId: string,
): Promise<TerminologyConceptDetail> {
  const query = new URLSearchParams({ hospitalId })
  const response = await fetch(
    `/api/terminology/concepts/${encodeURIComponent(conceptCode)}?${query}`,
    { headers: authHeaders(token) },
  )
  return readJson(response)
}

export async function testTerminologyRecognition(
  token: string,
  hospitalId: string,
  text: string,
): Promise<TerminologyNormalization> {
  const response = await fetch('/api/terminology/test', {
    method: 'POST',
    headers: { ...authHeaders(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ hospitalId, text }),
  })
  return readJson(response)
}

export async function loadTerminologyReleases(
  token: string,
): Promise<{ items: Array<Record<string, unknown>> }> {
  const response = await fetch('/api/terminology/releases', { headers: authHeaders(token) })
  return readJson(response)
}

export async function createTerminologyAlias(
  hospitalToken: string, payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/aliases', {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return readJson(response)
}

export async function approveTerminologyAlias(
  hospitalToken: string, aliasId: number,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/aliases/${aliasId}/approve`, {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId: 'admin' }),
  })
  return readJson(response)
}

export async function createTerminologyMapping(
  hospitalToken: string, payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/hospital-mappings', {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return readJson(response)
}

export async function approveTerminologyMapping(
  hospitalToken: string, mappingId: number,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/hospital-mappings/${mappingId}/approve`, {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId: 'admin' }),
  })
  return readJson(response)
}

export async function publishTerminology(hospitalToken: string): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/releases/publish', {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId: 'admin' }),
  })
  return readJson(response)
}

export async function restoreTerminology(
  hospitalToken: string, releaseId: string,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/releases/${encodeURIComponent(releaseId)}/restore`, {
    method: 'POST', headers: { ...authHeaders(hospitalToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actorId: 'admin' }),
  })
  return readJson(response)
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
