export type AutonomousActivityKind = 'MODEL' | 'TOOL' | 'REPLY' | 'QUESTION' | 'CONCLUSION' | 'STOP' | 'PROCESS'

export interface AutonomousActivityItem {
  id: string
  kind: AutonomousActivityKind
  turnId: string
  iteration: number
  seq: number
  status: string
  createdAt: string
  title: string
  summary: string
  eventType: string
  toolCallId?: string
  tool?: string
  toolDisplayName?: string
  durationMs?: number
  arguments?: unknown
  resultPreview?: unknown
  evidenceId?: string
  error?: string
  answer?: string
  question?: string
  conclusion?: string
  conclusionLevel?: string
  analysis?: {
    problemUnderstanding: string
    hypotheses: string[]
    evidenceRefs: string[]
    verificationGoal: string
    toolChoiceReason: string
    judgementUpdate: string
    nextStep: string
  }
}

type RawEvent = Record<string, unknown>

export function projectAutonomousActivities(
  turn: Record<string, unknown>,
  fallbackEvents: RawEvent[] = [],
): AutonomousActivityItem[] {
  const turnId = text(turn.turnId) || text(turn.clientMessageId) || 'TURN_LEGACY'
  const source = objectList(turn.processEvents).length
    ? objectList(turn.processEvents)
    : fallbackEvents.filter((event) => text(event.turnId) === turnId)
  const events = dedupeBySeq(source).sort((left, right) => number(left.seq) - number(right.seq))
  const activities = new Map<string, AutonomousActivityItem>()

  for (const event of events) {
    const type = eventType(event)
    const iteration = number(event.iteration)
    const seq = number(event.seq)
    if (type === 'MODEL_STARTED' || type === 'ANALYSIS') {
      const id = `model:${turnId}:${iteration}`
      const existing = activities.get(id)
      const analysisEvent = type === 'ANALYSIS' ? event : undefined
      activities.set(id, {
        ...(existing || baseItem(id, 'MODEL', turnId, iteration, seq, event)),
        seq: existing ? Math.min(existing.seq, seq) : seq,
        status: type === 'ANALYSIS' ? text(event.status) || 'SUCCEEDED' : existing?.status || text(event.status) || 'RUNNING',
        createdAt: existing?.createdAt || text(event.createdAt),
        title: type === 'ANALYSIS' ? '公开分析' : existing?.title || '思考中',
        summary: text(event.analysisSummary) || text(event.summary) || existing?.summary || '',
        eventType: type,
        analysis: analysisEvent ? analysisDetails(analysisEvent) : existing?.analysis,
      })
      continue
    }

    if (type === 'TOOL_CALL' || type === 'OBSERVATION') {
      const toolCallId = text(event.toolCallId) || `${turnId}:${iteration}:${seq}`
      const id = `tool:${toolCallId}`
      const existing = activities.get(id)
      activities.set(id, {
        ...(existing || baseItem(id, 'TOOL', turnId, iteration, seq, event)),
        seq: existing ? Math.min(existing.seq, seq) : seq,
        status: text(event.status) || existing?.status || (type === 'TOOL_CALL' ? 'RUNNING' : 'SUCCEEDED'),
        title: '调用工具',
        summary: text(event.summary) || existing?.summary || '',
        eventType: type,
        toolCallId,
        tool: text(event.tool) || existing?.tool,
        toolDisplayName: text(event.toolDisplayName) || existing?.toolDisplayName,
        durationMs: event.durationMs === undefined ? existing?.durationMs : number(event.durationMs),
        arguments: event.arguments ?? existing?.arguments,
        resultPreview: event.resultPreview ?? existing?.resultPreview,
        evidenceId: text(event.evidenceId) || existing?.evidenceId,
        error: text(event.error) || existing?.error,
      })
      continue
    }

    if (['RESPONSE', 'STAGE_REPLY', 'QUESTION', 'CONCLUSION'].includes(type)) {
      const kind: AutonomousActivityKind = type === 'QUESTION'
        ? 'QUESTION' : type === 'CONCLUSION' ? 'CONCLUSION' : 'REPLY'
      const id = `reply:${turnId}:${seq}`
      activities.set(id, {
        ...baseItem(id, kind, turnId, iteration, seq, event),
        title: kind === 'CONCLUSION' ? '排查总结果' : kind === 'QUESTION' ? '需要现场补充' : '阶段性回复',
        answer: text(event.answer),
        question: text(event.question),
        conclusion: text(event.conclusion),
        conclusionLevel: text(event.conclusionLevel),
      })
      continue
    }

    if (type === 'STOP') {
      const stoppedStatus = text(event.status) || 'FAILED'
      for (const [activityId, activity] of activities) {
        if (activity.status === 'RUNNING') {
          activities.set(activityId, { ...activity, status: stoppedStatus })
        }
      }
      const id = `stop:${turnId}:${seq}`
      activities.set(id, baseItem(id, 'STOP', turnId, iteration, seq, event))
      continue
    }

    const id = `process:${turnId}:${seq}`
    activities.set(id, baseItem(id, 'PROCESS', turnId, iteration, seq, event))
  }

  return [...activities.values()].sort((left, right) => left.seq - right.seq)
}

export function latestPendingQuestionId(
  turns: Array<Record<string, unknown>>,
  fallbackEvents: RawEvent[],
  runStatus: string,
): string {
  if (runStatus !== 'WAITING_USER') return ''
  return turns.flatMap((turn) => projectAutonomousActivities(turn, fallbackEvents))
    .filter((item) => item.kind === 'QUESTION')
    .at(-1)?.id || ''
}

function baseItem(
  id: string,
  kind: AutonomousActivityKind,
  turnId: string,
  iteration: number,
  seq: number,
  event: RawEvent,
): AutonomousActivityItem {
  return {
    id,
    kind,
    turnId,
    iteration,
    seq,
    status: text(event.status),
    createdAt: text(event.createdAt),
    title: text(event.title) || kind,
    summary: text(event.summary),
    eventType: eventType(event),
  }
}

function analysisDetails(event: RawEvent): NonNullable<AutonomousActivityItem['analysis']> {
  return {
    problemUnderstanding: text(event.problemUnderstanding) || text(event.analysisSummary),
    hypotheses: stringList(event.hypotheses),
    evidenceRefs: stringList(event.evidenceRefs),
    verificationGoal: text(event.verificationGoal),
    toolChoiceReason: text(event.toolChoiceReason),
    judgementUpdate: text(event.judgementUpdate),
    nextStep: text(event.nextStep) || text(event.publicPlan),
  }
}

function dedupeBySeq(events: RawEvent[]): RawEvent[] {
  const values = new Map<string, RawEvent>()
  for (const event of events) {
    const seq = number(event.seq)
    const key = seq > 0
      ? String(seq)
      : `${eventType(event)}:${text(event.toolCallId)}:${text(event.createdAt)}`
    values.set(key, event)
  }
  return [...values.values()]
}

function eventType(event: RawEvent): string {
  return text(event.eventType).toUpperCase() || (event.tool ? 'OBSERVATION' : 'ANALYSIS')
}

function objectList(value: unknown): RawEvent[] {
  return Array.isArray(value)
    ? value.filter((item): item is RawEvent => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
    : []
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map(text).filter(Boolean) : []
}

function number(value: unknown): number {
  const parsed = Number(value || 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value).trim()
}
