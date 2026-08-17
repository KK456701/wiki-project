import type {
  AssistantConversationSummary,
  AssistantIntentResult,
  DiagnosisDetailRow,
  PatientClarificationDirection,
} from '@/types/diagnosis';

const UNDER_COUNTED_DIRECTION: PatientClarificationDirection = 'UNDER_COUNTED';
const CONVERSATION_TYPE = {
  PATIENT_CLARIFICATION: 'PATIENT_CLARIFICATION',
  AUTONOMOUS: 'AUTONOMOUS',
} as const;
const INTENT_TYPE = {
  PATIENT_CLARIFICATION: 'PATIENT_CLARIFICATION',
  SQL_GENERATION: 'SQL_GENERATION',
} as const;
const INTENT_TARGET = {
  PATIENT: 'PATIENT',
  DEPARTMENT: 'DEPARTMENT',
} as const;
const WAITING_USER_STATUS = 'WAITING_USER';

export const ASSISTANT_PICKER = {
  NONE: '',
  CLARIFY_PATIENT: 'CLARIFY_PATIENT',
  AI_GENERATE_SQL: 'AI_GENERATE_SQL',
  UPLOAD_SQL: 'UPLOAD_SQL',
  /** @deprecated 旧版排除入口兼容。 */
  EXCLUDE_PATIENT: 'EXCLUDE_PATIENT',
  /** @deprecated 旧版排除入口兼容。 */
  EXCLUDE_DEPARTMENT: 'EXCLUDE_DEPARTMENT',
} as const;

export type AssistantPicker = (typeof ASSISTANT_PICKER)[keyof typeof ASSISTANT_PICKER];

export interface PatientOption {
  value: string;
  title: string;
  subtitle: string;
  displayLabel: string;
  row: DiagnosisDetailRow;
  direction?: PatientClarificationDirection;
}

export interface AssistantGuidanceTurn {
  userMessage: string;
  assistantMessage: string;
}

const SIMPLE_GREETING_PATTERN =
  /^(你好|您好|嗨|hi|hello|在吗|你是谁|你能做什么|你可以做什么|帮助|帮帮我)[!！,.，。?？\s]*$/i;

export function latestAssistantHistories(
  items: AssistantConversationSummary[],
): AssistantConversationSummary[] {
  const latestByType = new Map<string, AssistantConversationSummary>();
  const sorted = [...items].sort(
    (left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt),
  );
  for (const item of sorted) {
    if (!latestByType.has(item.type)) latestByType.set(item.type, item);
  }
  return [
    latestByType.get(CONVERSATION_TYPE.PATIENT_CLARIFICATION),
    latestByType.get(CONVERSATION_TYPE.AUTONOMOUS),
  ].filter((item): item is AssistantConversationSummary => Boolean(item));
}

export function latestPatientClarificationHistory(
  items: AssistantConversationSummary[],
): AssistantConversationSummary | undefined {
  return latestAssistantHistories(items).find(
    (item) => item.type === CONVERSATION_TYPE.PATIENT_CLARIFICATION,
  );
}

export function isAssistantGreeting(message: string): boolean {
  return SIMPLE_GREETING_PATTERN.test(message.trim());
}

export function assistantIntentReply(result: AssistantIntentResult): string {
  if (result.intent === INTENT_TYPE.PATIENT_CLARIFICATION) {
    return '好的，如果您想核对某位患者为什么被统计或没有被统计，请点击下方“患者澄清”。';
  }
  if (result.intent === INTENT_TYPE.SQL_GENERATION && result.target === INTENT_TARGET.DEPARTMENT) {
    return '好的，如果您想调整科室范围，请点击下方“AI 生成对应 SQL”。';
  }
  if (result.intent === INTENT_TYPE.SQL_GENERATION && result.target === INTENT_TARGET.PATIENT) {
    return '好的，如果您想调整患者范围，请点击下方“AI 生成对应 SQL”。';
  }
  if (result.intent === INTENT_TYPE.SQL_GENERATION) {
    return '好的，请点击下方“AI 生成对应 SQL”，我会根据当前指标可用的表和字段引导修改。';
  }
  return '抱歉，暂时无法处理这类请求。您可以点击下方“AI 自主排查”功能试试。';
}

export function assistantInputLabel(status: string, autonomousMode: boolean): string {
  if (status === WAITING_USER_STATUS) return '填写现场确认结果';
  if (autonomousMode) return '描述需要自主排查的问题';
  return '也可以描述你想做什么，我会引导到对应功能';
}

export function patientOption(row: DiagnosisDetailRow, group: string): PatientOption | null {
  const keys = Object.keys(row);
  const find = (patterns: RegExp[]) => {
    for (const pattern of patterns) {
      const key = keys.find((candidate) => pattern.test(candidate.toLowerCase()));
      if (key) return key;
    }
    return undefined;
  };
  const encounter = find([
    /^encounter_id$|^encounterid$|^就诊号$|^住院号$/i,
    /admission.*id|inhospital.*id|visit.*id/i,
    /encounter|就诊号|住院号|enc/i,
  ]);
  const name = find([/name|姓名|患者|patient_name|patientname/i]);
  const department = find([
    /dept_name|deptname|department_name|科室名称|当前科室$/i,
    /dept|科室|department/i,
  ]);
  const id = encounter ? String(row[encounter] ?? '').trim() : '';
  if (!id) return null;
  const patientName = name ? String(row[name] ?? '').trim() : '';
  const departmentName = department ? String(row[department] ?? '').trim() : '';
  return {
    value: id,
    title: patientName || id,
    subtitle: [id, departmentName, group === 'numerator' ? '分子' : '分母']
      .filter(Boolean)
      .join(' / '),
    displayLabel: [patientName || id, departmentName].filter(Boolean).join(' · '),
    row,
  };
}

export function patientClarificationPrompt(option: PatientOption): string {
  const question =
    option.direction === UNDER_COUNTED_DIRECTION
      ? '请核对该患者从业务源、中间表到统计分母和分子的哪一层开始缺失，并说明原因。'
      : '请核对该患者进入当前分子或分母的实际依据，并说明原因。';
  return `请澄清患者：${option.displayLabel}，${question}`;
}

export function dedupePatientOptions(options: PatientOption[]): PatientOption[] {
  const unique = new Map<string, PatientOption>();
  for (const option of options) {
    if (!unique.has(option.value)) unique.set(option.value, option);
  }
  return [...unique.values()];
}

export function assistantIntroVisible(viewingHistory: boolean, introDismissed: boolean): boolean {
  return !viewingHistory && !introDismissed;
}

export function assistantStopVisible(status: string): boolean {
  return status === 'RUNNING' || status === 'QUEUED';
}

export function latestSeq(events: Array<Record<string, unknown>>): number {
  return events.reduce((max, event) => Math.max(max, Number(event.seq ?? 0)), 0);
}
