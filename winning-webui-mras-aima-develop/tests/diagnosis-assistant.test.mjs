import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { URL } from 'node:url';

import {
  assistantIntroVisible,
  assistantIntentReply,
  assistantStopVisible,
  dedupePatientOptions,
  isAssistantGreeting,
  latestAssistantHistories,
  latestPatientClarificationHistory,
  latestSeq,
  patientClarificationPrompt,
  patientOption,
} from '../src/views/DiagnosisWorkspace/assistant.ts';
import {
  assistantConclusionText,
  assistantConclusionTitle,
  assistantConversationTurns,
  assistantDisplayEvents,
  assistantProcessDetail,
  settleAssistantProcessEvents,
} from '../src/views/DiagnosisWorkspace/assistant-events.ts';
import {
  createClientId,
  usePatientClarificationTask,
} from '../src/views/DiagnosisWorkspace/composables/usePatientClarificationTask.ts';
import { isLikelyEncounterId } from '../src/views/DiagnosisWorkspace/patient-search.ts';
import { troubleshootingCaseSummary } from '../src/views/DiagnosisWorkspace/troubleshooting-cases.ts';

test('client id falls back to getRandomValues when randomUUID is unavailable', () => {
  const source = {
    getRandomValues(bytes) {
      bytes.fill(0xab);
      return bytes;
    },
  };

  assert.equal(createClientId(source), 'abababab-abab-4bab-abab-abababababab');
});

test('patientOption extracts patient identity and source group', () => {
  const option = patientOption(
    { ENCOUNTER_ID: 'ENC-100', PATIENT_NAME: '张三', CURRENT_DEPT_NAME: '心内科' },
    'numerator',
  );

  assert.deepEqual(option, {
    value: 'ENC-100',
    title: '张三',
    subtitle: 'ENC-100 / 心内科 / 分子',
    displayLabel: '张三 · 心内科',
    row: { ENCOUNTER_ID: 'ENC-100', PATIENT_NAME: '张三', CURRENT_DEPT_NAME: '心内科' },
  });
});

test('patientOption prefers encounter id over a middle-table visit primary key', () => {
  const option = patientOption(
    {
      MRAS_BUSINESS_FIRSTVISIT_ID: '2087923864557457550',
      ENCOUNTER_ID: '449561199752026112',
      PERSON_NAME: '测试患者',
    },
    'numerator',
  );

  assert.equal(option?.value, '449561199752026112');
  assert.match(option?.subtitle ?? '', /^449561199752026112/);
});

test('patient clarification is staged as a user-confirmable prompt', () => {
  const option = patientOption(
    { ENCOUNTER_ID: 'E1', PATIENT_NAME: '张三', CURRENT_DEPT_NAME: '心内科' },
    'denominator',
  );

  assert.equal(
    option && patientClarificationPrompt(option),
    '请澄清患者：张三 · 心内科，请核对该患者进入当前分子或分母的实际依据，并说明原因。',
  );
});

test('under-counted patient prompt asks for the first missing data layer', () => {
  const option = {
    value: 'E2',
    title: '李四',
    subtitle: 'E2',
    displayLabel: '李四 · 外科',
    row: { ENCOUNTER_ID: 'E2' },
    direction: 'UNDER_COUNTED',
  };

  assert.equal(
    patientClarificationPrompt(option),
    '请澄清患者：李四 · 外科，请核对该患者从业务源、中间表到统计分母和分子的哪一层开始缺失，并说明原因。',
  );
});

test('patient options prefer numerator membership when the patient is in both groups', () => {
  const row = {
    ENCOUNTER_ID: '452304240745158656',
    PERSON_NAME: '住院患者20250904054252923',
  };
  const numerator = patientOption(row, 'numerator');
  const denominator = patientOption(row, 'denominator');

  const options = dedupePatientOptions([numerator, denominator].filter((item) => item !== null));

  assert.equal(options.length, 1);
  assert.match(options[0]?.subtitle ?? '', /分子$/);
});

test('patientOption rejects rows without a stable encounter identifier', () => {
  assert.equal(patientOption({ PATIENT_NAME: '李四' }, 'denominator'), null);
});

test('assistant introduction is hidden after the first submitted message', () => {
  assert.equal(assistantIntroVisible(false, false), true);
  assert.equal(assistantIntroVisible(false, true), false);
  assert.equal(assistantIntroVisible(true, false), false);
});

test('assistant history keeps only the latest patient and autonomous conversation', () => {
  const items = [
    {
      conversationId: 'P-OLD',
      type: 'PATIENT_CLARIFICATION',
      updatedAt: '2026-08-14T09:00:00+08:00',
    },
    {
      conversationId: 'A-LATEST',
      type: 'AUTONOMOUS',
      updatedAt: '2026-08-14T11:00:00+08:00',
    },
    {
      conversationId: 'P-LATEST',
      type: 'PATIENT_CLARIFICATION',
      updatedAt: '2026-08-14T12:00:00+08:00',
    },
  ];

  assert.deepEqual(
    latestAssistantHistories(items).map((item) => item.conversationId),
    ['P-LATEST', 'A-LATEST'],
  );
});

test('patient clarification opens the latest patient conversation instead of another history type', () => {
  const latest = latestPatientClarificationHistory([
    {
      conversationId: 'auto-new',
      type: 'AUTONOMOUS',
      title: '自主排查',
      status: 'COMPLETED',
      preview: '',
      createdAt: '2026-08-15T10:00:00+08:00',
      updatedAt: '2026-08-15T10:00:00+08:00',
    },
    {
      conversationId: 'patient-old',
      type: 'PATIENT_CLARIFICATION',
      title: '患者澄清',
      status: 'COMPLETED',
      preview: '',
      createdAt: '2026-08-15T09:00:00+08:00',
      updatedAt: '2026-08-15T09:00:00+08:00',
    },
  ]);
  assert.equal(latest?.conversationId, 'patient-old');
});

test('assistant intent replies guide users to a quick action without opening it', () => {
  assert.equal(isAssistantGreeting('你好'), true);
  assert.match(
    assistantIntentReply({
      intent: 'PATIENT_CLARIFICATION',
      target: 'PATIENT',
      source: 'RULE',
      confidence: 1,
    }),
    /点击下方“患者澄清”/,
  );
  assert.match(
    assistantIntentReply({
      intent: 'UNKNOWN',
      target: 'UNSPECIFIED',
      source: 'FALLBACK',
      confidence: 0,
    }),
    /“AI 自主排查”/,
  );
});

test('assistant exposes one unified SQL generation action and three explicit upload modes', () => {
  const actions = readFileSync(
    new URL('../src/views/DiagnosisWorkspace/assistant-actions.ts', import.meta.url),
    'utf8',
  );
  const picker = readFileSync(
    new URL(
      '../src/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue',
      import.meta.url,
    ),
    'utf8',
  );
  const aiPicker = readFileSync(
    new URL(
      '../src/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue',
      import.meta.url,
    ),
    'utf8',
  );

  assert.match(actions, /AI 生成对应 SQL/);
  assert.match(actions, /手动上传 SQL/);
  assert.doesNotMatch(actions, /生成排除患者后的 SQL|生成排除科室后的 SQL/);
  assert.match(picker, /上传新增患者或科室的 SQL/);
  assert.match(picker, /上传排查患者或科室的 SQL/);
  assert.match(picker, /上传完整候选 SQL/);
  assert.match(picker, /查看示例/);
  assert.match(picker, /仅支持粘贴 SQL 文本/);
  assert.doesNotMatch(picker, /v-file-input/);
  assert.match(aiPicker, /排除患者/);
  assert.match(aiPicker, /排除科室/);
  assert.match(aiPicker, /可同时选择多个排除条件/);
  assert.match(aiPicker, /确认并进入 SQL 脚本核查/);
});

test('troubleshooting case summaries keep the first readable problem sentence', () => {
  assert.equal(
    troubleshootingCaseSummary('> 现象：统计结果与现场不一致。\n\n第二段说明'),
    '统计结果与现场不一致。',
  );
  assert.equal(troubleshootingCaseSummary('1. 抽取脚本执行时报错'), '抽取脚本执行时报错');
  assert.equal(troubleshootingCaseSummary(''), '暂无问题描述');
});

test('latestSeq resumes polling after the highest received event', () => {
  assert.equal(latestSeq([{ seq: 2 }, { seq: 9 }, { seq: 4 }]), 9);
});

test('autonomous send control becomes a stop control only while work is running', () => {
  assert.equal(assistantStopVisible('RUNNING'), true);
  assert.equal(assistantStopVisible('QUEUED'), true);
  assert.equal(assistantStopVisible('WAITING_USER'), false);
  assert.equal(assistantStopVisible('READY'), false);
});

test('assistant events merge model and tool lifecycle pairs into readable steps', () => {
  const events = assistantDisplayEvents([
    {
      seq: 1,
      turnId: 'T1',
      iteration: 1,
      eventType: 'MODEL_STARTED',
      summary: '正在理解问题',
    },
    {
      seq: 2,
      turnId: 'T1',
      iteration: 1,
      eventType: 'ANALYSIS',
      analysisSummary: '确定先读取指标口径',
      analysisProcess: '先核对指标定义，再读取当前生效口径。',
    },
    {
      seq: 3,
      eventType: 'TOOL_CALL',
      toolCallId: 'CALL_1',
      toolDisplayName: '读取指标 Wiki',
      summary: '正在执行',
    },
    {
      seq: 4,
      eventType: 'OBSERVATION',
      toolCallId: 'CALL_1',
      toolDisplayName: '读取指标 Wiki',
      summary: '已读取生效口径',
    },
    { seq: 5, eventType: 'RESPONSE', answer: '口径读取完成' },
  ]);

  assert.deepEqual(
    events.map((event) => [event.title, event.text, event.kind]),
    [
      ['思考', '先核对指标定义，再读取当前生效口径。', 'THINKING'],
      ['工具调用 · 读取指标 Wiki', '已读取生效口径', 'TOOL'],
      ['回复', '口径读取完成', 'MESSAGE'],
    ],
  );
});

test('assistant conversation keeps each question, process and reply in the same turn', () => {
  const turns = assistantConversationTurns({
    status: 'READY',
    finalConclusion: { conclusion: '第二个回答' },
    turns: [
      {
        turnId: 'T1',
        userMessage: '第一个问题',
        status: 'COMPLETED',
        processEvents: [
          {
            seq: 1,
            turnId: 'T1',
            iteration: 1,
            eventType: 'ANALYSIS',
            analysisSummary: '摘要一',
            analysisProcess: '原始思考一',
            status: 'SUCCEEDED',
          },
          { seq: 2, turnId: 'T1', eventType: 'RESPONSE', answer: '第一个回答' },
        ],
      },
      {
        turnId: 'T2',
        userMessage: '第二个问题',
        status: 'COMPLETED',
        processEvents: [{ seq: 3, turnId: 'T2', eventType: 'RESPONSE', answer: '第二个回答' }],
      },
    ],
  });

  assert.deepEqual(
    turns.map((turn) => ({
      question: turn.userMessage,
      thinking: turn.processEvents.map((event) => event.text),
      replies: turn.replyEvents.map((event) => event.text),
    })),
    [
      { question: '第一个问题', thinking: ['原始思考一'], replies: ['第一个回答'] },
      { question: '第二个问题', thinking: [], replies: ['第二个回答'] },
    ],
  );
});

test('assistant conclusion renders business text instead of protocol JSON', () => {
  const conclusion = {
    conclusionLevel: 'DIRECT_RESPONSE',
    conclusion: '您好，请告诉我需要排查的问题。',
    evidenceIds: [],
    candidateRequired: false,
  };

  assert.equal(assistantConclusionText(conclusion), '您好，请告诉我需要排查的问题。');
  assert.equal(assistantConclusionTitle(conclusion), 'AI 排查助手');
});

test('completed thinking always reopens with its original full content', () => {
  const event = {
    key: 'thinking-1',
    title: '思考',
    text: '完整的原始思考内容',
    status: 'COMPLETED',
    kind: 'THINKING',
  };

  assert.equal(assistantProcessDetail(event, '只显示到这里'), '完整的原始思考内容');
});

test('a stopped run settles its last running thought for history display', () => {
  const [event] = settleAssistantProcessEvents(
    [
      {
        key: 'thinking-stopped',
        title: '思考中',
        text: '停止前已经产生的思考内容',
        status: 'RUNNING',
        kind: 'THINKING',
      },
    ],
    'STOPPED',
  );

  assert.equal(event?.title, '思考');
  assert.equal(event?.status, 'STOPPED');
  assert.equal(event && assistantProcessDetail(event, '部分'), '停止前已经产生的思考内容');
});

test('a completed conversation settles stale running status stored on its last turn', () => {
  const [turn] = assistantConversationTurns({
    status: 'CANCELLED',
    turns: [
      {
        turnId: 'T1',
        userMessage: '核查问题',
        status: 'RUNNING',
        processEvents: [
          {
            seq: 1,
            turnId: 'T1',
            iteration: 1,
            eventType: 'MODEL_STARTED',
            analysisProcess: '停止前保留的原始思考',
            status: 'RUNNING',
          },
        ],
      },
    ],
  });

  assert.equal(turn?.processEvents[0]?.title, '思考');
  assert.equal(turn?.processEvents[0]?.status, 'CANCELLED');
  assert.equal(turn?.processEvents[0]?.text, '停止前保留的原始思考');
});

test('patient clarification can stop the request and ignore the late response', async () => {
  let submittedRequestId = '';
  let cancelledRequestId = '';
  let submittedDirection = '';
  let settleClarification = () => {};
  const task = usePatientClarificationTask({
    clarify: async (_row, _message, options, direction) => {
      submittedRequestId = options?.requestId ?? '';
      submittedDirection = direction ?? '';
      return await new Promise((resolve) => {
        settleClarification = () => resolve(false);
      });
    },
    cancel: async (requestId) => {
      cancelledRequestId = requestId;
      settleClarification();
      return true;
    },
  });
  task.stage({
    value: 'P1',
    title: '患者1',
    subtitle: 'P1',
    displayLabel: '患者1',
    row: {},
    direction: 'UNDER_COUNTED',
  });

  const pending = task.submit(
    '请澄清患者1',
    async () => {},
    () => {},
  );
  await Promise.resolve();
  assert.equal(task.running.value, true);
  assert.equal(await task.stop(), true);
  await pending;

  assert.equal(task.running.value, false);
  assert.equal(task.stopped.value, true);
  assert.equal(cancelledRequestId, submittedRequestId);
  assert.equal(submittedDirection, 'UNDER_COUNTED');
});

test('patient search recognizes a likely encounter id before querying a bed number', () => {
  assert.equal(isLikelyEncounterId('451191414718765057'), true);
  assert.equal(isLikelyEncounterId('1203'), false);
});
