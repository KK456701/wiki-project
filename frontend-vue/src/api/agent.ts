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
