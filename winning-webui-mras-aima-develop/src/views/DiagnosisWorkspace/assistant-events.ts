export interface AssistantDisplayEvent {
  key: string;
  title: string;
  text: string;
  status: string;
  kind: 'THINKING' | 'TOOL' | 'MESSAGE' | 'STATUS';
}

export interface AssistantConversationTurn {
  key: string;
  userMessage: string;
  processEvents: AssistantDisplayEvent[];
  replyEvents: AssistantDisplayEvent[];
}

const ACTIVE_RUN_STATUSES = new Set(['RUNNING', 'QUEUED']);

function text(value: unknown): string {
  return value == null ? '' : String(value).trim();
}

function sameModelStep(
  started: Record<string, unknown>,
  completed: Record<string, unknown>,
): boolean {
  return (
    text(started.turnId) === text(completed.turnId) &&
    Number(started.iteration ?? 0) === Number(completed.iteration ?? 0)
  );
}

function toolTitle(event: Record<string, unknown>): string {
  const name = text(event.toolDisplayName ?? event.tool);
  return name ? `工具调用 · ${name}` : '工具调用';
}

export function assistantDisplayEvents(
  events: Array<Record<string, unknown>>,
): AssistantDisplayEvent[] {
  const result: AssistantDisplayEvent[] = [];
  const pendingModels = new Map<string, { source: Record<string, unknown>; index: number }>();
  const pendingTools = new Map<string, number>();

  for (const event of events) {
    const type = text(event.eventType).toUpperCase();
    const turnId = text(event.turnId);
    const iteration = Number(event.iteration ?? 0);
    const modelKey = `${turnId}:${iteration}`;
    const seq = text(event.seq);

    if (type === 'MODEL_STARTED') {
      const index = result.push({
        key: seq || modelKey,
        title: '思考中',
        text: text(event.analysisProcess ?? event.summary),
        status: text(event.status),
        kind: 'THINKING',
      });
      pendingModels.set(modelKey, { source: event, index: index - 1 });
      continue;
    }

    if (type === 'ANALYSIS') {
      const pending = pendingModels.get(modelKey);
      const value = {
        key: pending ? result[pending.index].key : seq || modelKey,
        title: text(event.status).toUpperCase() === 'RETRYING' ? '重新思考' : '思考',
        text: text(event.analysisProcess ?? event.analysisSummary ?? event.summary),
        status: text(event.status),
        kind: 'THINKING' as const,
      };
      if (pending && sameModelStep(pending.source, event)) result[pending.index] = value;
      else result.push(value);
      pendingModels.delete(modelKey);
      continue;
    }

    if (type === 'TOOL_CALL') {
      const toolCallId = text(event.toolCallId) || seq;
      const index = result.push({
        key: toolCallId,
        title: toolTitle(event),
        text: text(event.summary),
        status: text(event.status),
        kind: 'TOOL',
      });
      pendingTools.set(toolCallId, index - 1);
      continue;
    }

    if (type === 'OBSERVATION') {
      const toolCallId = text(event.toolCallId);
      const value = {
        key: toolCallId || seq,
        title: toolTitle(event),
        text: text(event.summary),
        status: text(event.status),
        kind: 'TOOL' as const,
      };
      const index = pendingTools.get(toolCallId);
      if (index == null) result.push(value);
      else result[index] = value;
      pendingTools.delete(toolCallId);
      continue;
    }

    const labels: Record<string, string> = {
      RESPONSE: '回复',
      STAGE_REPLY: '阶段回复',
      QUESTION: '需要补充',
      CONCLUSION: '排查结论',
      STOP: '排查停止',
    };
    result.push({
      key: seq || `${type}:${result.length}`,
      title: labels[type] ?? '排查进展',
      text: text(
        event.answer ??
          event.conclusion ??
          event.question ??
          event.summary ??
          event.analysisProcess,
      ),
      status: text(event.status),
      kind: type === 'STOP' ? 'STATUS' : 'MESSAGE',
    });
  }

  return result;
}

function objectList(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value)
    ? value.filter(
        (item): item is Record<string, unknown> =>
          Boolean(item) && typeof item === 'object' && !Array.isArray(item),
      )
    : [];
}

function fallbackReply(
  turn: Record<string, unknown>,
  run: Record<string, unknown>,
  isLast: boolean,
): string {
  const turnReply = text(turn.finalReply ?? turn.pendingQuestion);
  if (turnReply || !isLast) return turnReply;
  return text(run.pendingQuestion) || assistantConclusionText(run.finalConclusion);
}

function effectiveTurnStatus(turn: Record<string, unknown>, run: Record<string, unknown>): string {
  const runStatus = text(run.status).toUpperCase();
  if (ACTIVE_RUN_STATUSES.has(runStatus)) return text(turn.status) || runStatus;
  return runStatus || text(turn.status);
}

export function assistantConversationTurns(
  run: Record<string, unknown>,
): AssistantConversationTurn[] {
  const globalEvents = objectList(run.toolEvents);
  const sourceTurns = objectList(run.turns);
  const turns = sourceTurns.length
    ? sourceTurns
    : [
        {
          turnId: 'legacy-turn',
          userMessage: text(run.problem),
          processEvents: globalEvents,
          status: run.status,
        },
      ];

  return turns.map((turn, index) => {
    const turnId = text(turn.turnId) || `turn-${index}`;
    const ownEvents = objectList(turn.processEvents);
    const rawEvents = ownEvents.length
      ? ownEvents
      : globalEvents.filter((event) => text(event.turnId) === turnId);
    const displayEvents = assistantDisplayEvents(rawEvents);
    const turnStatus = effectiveTurnStatus(turn, run);
    const processEvents = settleAssistantProcessEvents(
      displayEvents.filter((event) => event.kind === 'THINKING' || event.kind === 'TOOL'),
      turnStatus,
    );
    const replyEvents = displayEvents.filter(
      (event) => event.kind !== 'THINKING' && event.kind !== 'TOOL',
    );
    const reply = fallbackReply(turn, run, index === turns.length - 1);
    if (reply && !replyEvents.length) {
      replyEvents.push({
        key: `${turnId}:reply`,
        title: 'AI 排查助手',
        text: reply,
        status: turnStatus,
        kind: 'MESSAGE',
      });
    }

    return {
      key: turnId,
      userMessage: text(turn.userMessage ?? turn.content),
      processEvents,
      replyEvents,
    };
  });
}

export function assistantConclusionText(value: unknown): string {
  if (typeof value === 'string') return value.trim();
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '';
  const conclusion = value as Record<string, unknown>;
  return text(conclusion.conclusion ?? conclusion.answer ?? conclusion.summary);
}

export function assistantConclusionTitle(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '排查结论';
  return text((value as Record<string, unknown>).conclusionLevel) === 'DIRECT_RESPONSE'
    ? 'AI 排查助手'
    : '排查结论';
}

export function assistantProcessDetail(event: AssistantDisplayEvent, revealed = ''): string {
  if (event.kind === 'THINKING' && event.status.toUpperCase() === 'RUNNING') {
    return revealed || '正在思考…';
  }
  return event.text || '暂无更多详情。';
}

export function settleAssistantProcessEvents(
  events: AssistantDisplayEvent[],
  runStatus: string,
): AssistantDisplayEvent[] {
  const normalizedStatus = runStatus.toUpperCase();
  if (!normalizedStatus || ['RUNNING', 'QUEUED'].includes(normalizedStatus)) return events;

  return events.map((event) =>
    event.status.toUpperCase() === 'RUNNING'
      ? {
          ...event,
          title: event.kind === 'THINKING' ? '思考' : event.title,
          status: normalizedStatus,
        }
      : event,
  );
}
