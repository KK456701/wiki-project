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
