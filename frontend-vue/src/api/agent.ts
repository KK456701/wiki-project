export interface HospitalUser {
  userId: string
  accountId: string
  hospitalId: string
  permissions: string[]
}

interface LoginWireResponse {
  token: string
  user_id: string
  account_id: string
  hospital_id: string
  permissions: string[]
  must_change_password: boolean
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
  max_steps: number
  orchestration?: string
}

export interface AgentEvent {
  event: string
  trace_id: string
  step?: number
  step_count?: number
  tool_name?: string
  status?: string
  code?: string
  message?: string
  stop_reason?: string
  duration_ms?: number
  reused?: boolean
  retryable?: boolean
}

export interface UploadResult {
  file_key: string
  file_name: string
  size_bytes: number
}

export interface DetailColumn {
  field: string
  label: string
  sensitivity: string
}

export interface DetailSnapshot {
  snapshot_id: string
  run_id: string
  hospital_id: string
  rule_id: string
  rule_name: string
  effective_level: string
  national_version?: string
  hospital_version?: number
  stat_start: string
  stat_end: string
  denominator_count: number
  numerator_count: number
  unmatched_count: number
  columns: DetailColumn[]
  created_at: string
  expires_at: string
  reused: boolean
  source_database: string
  source_tables: string[]
}

export interface DetailPage {
  snapshot_id: string
  run_id: string
  group: 'denominator' | 'numerator' | 'unmatched'
  page: number
  page_size: number
  total: number
  items: Array<Record<string, unknown>>
}

export interface IndicatorExport {
  export_id: string
  run_id: string
  hospital_id: string
  rule_id: string
  file_name: string
  row_count: number
  status: string
  created_at: string
  expires_at: string
  download_count: number
}

export interface AgentRunSummary {
  trace_id: string
  session_id?: string
  intent?: string
  final_status?: string
  error_count?: number
  fallback_count?: number
  started_at?: string
  ended_at?: string
  duration_ms?: number
}

export interface AgentRunMetrics {
  hospital_id: string
  request_count: number
  success_rate: number
  incomplete_rate: number
  latency_ms: { average: number; p50: number; p95: number; p99: number }
  status_counts: Record<string, number>
  trend: Array<{ date: string; requests: number; planner_ms: number; final_answer_ms: number }>
  tools: Array<{ tool_name: string; calls: number; failures: number; duration_ms: number }>
  models: Array<{ model_id: string; calls: number; timeouts: number; duration_ms: number; input_tokens: number; output_tokens: number }>
  repeated_call_stop_rate: number
  replan_rate: number
  compound_request_count: number
  compound_average_duration_ms: number
  warnings: Array<{ code: string; message: string }>
  thresholds: Record<string, number>
}

export interface MetadataChange {
  table_name: string
  field_name: string
  change_type: string
  change_desc: string
}

export interface MetadataAffectedRule {
  rule_id: string
  matched_columns: string[]
  business_fields: string[]
}

export interface MetadataOverview {
  hospital_id: string
  db_name: string
  source_id?: string
  has_snapshot: boolean
  metadata_source?: string
  batch_id?: string
  synced_at?: string
  table_count: number
  column_count: number
  changes: MetadataChange[]
  affected_rules: MetadataAffectedRule[]
  trace_id?: string
}

export interface TerminologyConcept {
  concept_code: string
  canonical_name: string
  concept_type: string
  definition: string
  standard_code?: string
  source_level: string
  source_reference: string
  alias_count?: number
  aliases_preview?: string[]
}

export interface TerminologyConceptDetail extends TerminologyConcept {
  hospital_id: string
  aliases: Array<Record<string, unknown>>
  rule_links: Array<Record<string, unknown>>
  hospital_mappings: Array<Record<string, unknown>>
  active_release: Record<string, unknown>
}

export interface TerminologyNormalization {
  original_text: string
  normalized_text: string
  matches: Array<Record<string, unknown>>
  ambiguities: Array<Record<string, unknown>>
  release_version: string
  duration_ms: number
  sql_eligible: boolean
}

export interface MonitoringPlan {
  plan_id: string
  hospital_id: string
  rule_id: string
  plan_name: string
  frequency: 'daily' | 'monthly'
  run_time: string
  day_of_month: number
  timezone: string
  mom_enabled: boolean
  mom_threshold_pct: number
  yoy_enabled: boolean
  yoy_threshold_pct: number
  status: 'enabled' | 'disabled'
  next_run_at?: string
  last_run_at?: string
}

export interface MonitoringResult {
  id: number
  rule_id: string
  stat_period: string
  result_value?: number
  run_status?: string
  trigger_type?: string
  duration_ms?: number
  is_abnormal?: boolean
  created_at?: string
  error_message?: string
}

export interface MonitoringAlert {
  alert_id: string
  rule_id: string
  alert_type: string
  alert_level: string
  conclusion_code: string
  current_value?: number
  mom_change_rate?: number
  yoy_change_rate?: number
  diagnose_status: string
  status: 'open' | 'acknowledged' | 'closed'
  created_at?: string
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

export async function loginHospital(accountId: string, password: string): Promise<{ token: string; user: HospitalUser }> {
  const response = await fetch('/api/auth/hospital/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account_id: accountId, password }),
  })
  const data = await readJson<LoginWireResponse>(response)
  if (data.must_change_password) {
    throw new Error('该账号需要先在现有页面修改初始密码，再进入迁移版页面。')
  }
  return {
    token: data.token,
    user: {
      userId: data.user_id,
      accountId: data.account_id,
      hospitalId: data.hospital_id,
      permissions: data.permissions || [],
    },
  }
}

export async function logoutHospital(token: string): Promise<void> {
  if (!token) return
  await fetch('/api/auth/hospital/logout', {
    method: 'POST',
    headers: authHeaders(token),
  })
}

export async function loadCapabilities(token: string): Promise<AgentCapabilities> {
  const response = await fetch('/api/agent/capabilities', { headers: authHeaders(token) })
  return readJson<AgentCapabilities>(response)
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
): Promise<{ hospital_id: string; count: number; items: AgentRunSummary[] }> {
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
  const query = new URLSearchParams({ hospital_id: hospitalId })
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
    body: JSON.stringify({ hospital_id: hospitalId, db_name: '', source: 'dbhub' }),
  })
  return readJson<MetadataOverview>(response)
}

export async function loadTerminologyConcepts(
  token: string,
  filters: { query?: string; conceptType?: string; ruleId?: string } = {},
): Promise<{ items: TerminologyConcept[]; total: number }> {
  const query = new URLSearchParams()
  if (filters.query) query.set('query', filters.query)
  if (filters.conceptType) query.set('concept_type', filters.conceptType)
  if (filters.ruleId) query.set('rule_id', filters.ruleId)
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
  const query = new URLSearchParams({ hospital_id: hospitalId })
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
    body: JSON.stringify({ hospital_id: hospitalId, text }),
  })
  return readJson(response)
}

export async function loadTerminologyReleases(
  token: string,
): Promise<{ items: Array<Record<string, unknown>> }> {
  const response = await fetch('/api/terminology/releases', { headers: authHeaders(token) })
  return readJson(response)
}

export async function loginAdmin(password: string): Promise<{ token: string; message: string }> {
  const response = await fetch('/api/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  return readJson(response)
}

export async function logoutAdmin(token: string): Promise<void> {
  if (!token) return
  const response = await fetch('/api/admin/logout', {
    method: 'POST', headers: authHeaders(token),
  })
  await readJson(response)
}

function terminologyAdminHeaders(adminToken: string, hospitalToken: string): HeadersInit {
  return {
    Authorization: `Bearer ${adminToken}`,
    'X-Hospital-Authorization': `Bearer ${hospitalToken}`,
    'Content-Type': 'application/json',
  }
}

function monitoringHeaders(adminToken: string, hospitalToken: string, json = false): HeadersInit {
  return {
    Authorization: `Bearer ${adminToken}`,
    'X-Hospital-Authorization': `Bearer ${hospitalToken}`,
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  }
}

export async function loadMonitoringPlans(
  adminToken: string, hospitalToken: string, hospitalId: string,
): Promise<{ items: MonitoringPlan[] }> {
  const query = new URLSearchParams({ hospital_id: hospitalId })
  const response = await fetch(`/api/monitoring/plans?${query}`, {
    headers: monitoringHeaders(adminToken, hospitalToken),
  })
  return readJson(response)
}

export async function saveMonitoringPlan(
  adminToken: string, hospitalToken: string, payload: Record<string, unknown>, planId = '',
): Promise<MonitoringPlan> {
  const response = await fetch(planId ? `/api/monitoring/plans/${encodeURIComponent(planId)}` : '/api/monitoring/plans', {
    method: planId ? 'PUT' : 'POST', headers: monitoringHeaders(adminToken, hospitalToken, true),
    body: JSON.stringify(payload),
  })
  return readJson(response)
}

export async function setMonitoringPlanStatus(
  adminToken: string, hospitalToken: string, hospitalId: string, planId: string, enabled: boolean,
): Promise<MonitoringPlan> {
  const query = new URLSearchParams({ hospital_id: hospitalId })
  const response = await fetch(
    `/api/monitoring/plans/${encodeURIComponent(planId)}/${enabled ? 'enable' : 'disable'}?${query}`,
    { method: 'POST', headers: monitoringHeaders(adminToken, hospitalToken) },
  )
  return readJson(response)
}

export async function loadMonitoringResults(
  adminToken: string, hospitalToken: string, hospitalId: string,
): Promise<{ items: MonitoringResult[] }> {
  const query = new URLSearchParams({ hospital_id: hospitalId, limit: '100' })
  const response = await fetch(`/api/monitoring/results?${query}`, {
    headers: monitoringHeaders(adminToken, hospitalToken),
  })
  return readJson(response)
}

export async function loadMonitoringAlerts(
  adminToken: string, hospitalToken: string, hospitalId: string,
): Promise<{ items: MonitoringAlert[] }> {
  const query = new URLSearchParams({ hospital_id: hospitalId, limit: '100' })
  const response = await fetch(`/api/monitoring/alerts?${query}`, {
    headers: monitoringHeaders(adminToken, hospitalToken),
  })
  return readJson(response)
}

export async function transitionMonitoringAlert(
  adminToken: string, hospitalToken: string, hospitalId: string, actorId: string,
  alertId: string, action: 'acknowledge' | 'close',
): Promise<MonitoringAlert> {
  const response = await fetch(`/api/monitoring/alerts/${encodeURIComponent(alertId)}/${action}`, {
    method: 'POST', headers: monitoringHeaders(adminToken, hospitalToken, true),
    body: JSON.stringify({ hospital_id: hospitalId, actor_id: actorId }),
  })
  return readJson(response)
}

export async function createTerminologyAlias(
  adminToken: string, hospitalToken: string, payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/aliases', {
    method: 'POST', headers: terminologyAdminHeaders(adminToken, hospitalToken),
    body: JSON.stringify(payload),
  })
  return readJson(response)
}

export async function approveTerminologyAlias(
  adminToken: string, hospitalToken: string, aliasId: number,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/aliases/${aliasId}/approve`, {
    method: 'POST', headers: terminologyAdminHeaders(adminToken, hospitalToken),
    body: JSON.stringify({ actor_id: 'admin' }),
  })
  return readJson(response)
}

export async function createTerminologyMapping(
  adminToken: string, hospitalToken: string, payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/hospital-mappings', {
    method: 'POST', headers: terminologyAdminHeaders(adminToken, hospitalToken),
    body: JSON.stringify(payload),
  })
  return readJson(response)
}

export async function approveTerminologyMapping(
  adminToken: string, hospitalToken: string, mappingId: number,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/hospital-mappings/${mappingId}/approve`, {
    method: 'POST', headers: terminologyAdminHeaders(adminToken, hospitalToken),
    body: JSON.stringify({ actor_id: 'admin' }),
  })
  return readJson(response)
}

export async function publishTerminology(adminToken: string): Promise<Record<string, unknown>> {
  const response = await fetch('/api/terminology/releases/publish', {
    method: 'POST', headers: { ...authHeaders(adminToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actor_id: 'admin' }),
  })
  return readJson(response)
}

export async function restoreTerminology(
  adminToken: string, releaseId: string,
): Promise<Record<string, unknown>> {
  const response = await fetch(`/api/terminology/releases/${encodeURIComponent(releaseId)}/restore`, {
    method: 'POST', headers: { ...authHeaders(adminToken), 'Content-Type': 'application/json' },
    body: JSON.stringify({ actor_id: 'admin' }),
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
  const query = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
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
      body: JSON.stringify({ confirmed, file_token: fileToken }),
    },
  )
  return readJson<IndicatorExport>(response)
}

export async function downloadIndicatorExport(
  token: string,
  value: IndicatorExport,
): Promise<void> {
  const response = await fetch(`/api/indicator-exports/${encodeURIComponent(value.export_id)}/download`, {
    headers: authHeaders(token),
  })
  if (!response.ok) await readJson(response)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = value.file_name
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
    session_id: input.sessionId,
  }
  if (input.modelId) body.model_id = input.modelId
  if (input.fileKey) body.file_key = input.fileKey
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
