import { computed, onBeforeUnmount, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { useDiagnosisStore } from '@/stores/diagnosis';
import {
  classifyAssistantIntent,
  getAssistantConversation,
  getAssistantConversations,
  getDiagnosisAgentEvents,
  getDiagnosisDetails,
} from '@/services/diagnosis';
import {
  AUTONOMOUS_STATUS,
  DIAGNOSIS_ACTION,
  DIAGNOSIS_ASSISTANT_ACTION,
} from '@/constants/diagnosis';
import type { AssistantConversation, AssistantConversationSummary } from '@/types/diagnosis';
import {
  ASSISTANT_PICKER,
  assistantIntentReply,
  dedupePatientOptions,
  isAssistantGreeting,
  latestAssistantHistories,
  latestPatientClarificationHistory,
  latestSeq,
  patientOption,
  type AssistantGuidanceTurn,
  type AssistantPicker,
  type PatientOption,
} from '@/views/DiagnosisWorkspace/assistant';
import { createClientId } from '@/views/DiagnosisWorkspace/composables/usePatientClarificationTask';

const ACTIVE_STATUSES = new Set<string>([
  AUTONOMOUS_STATUS.RUNNING,
  AUTONOMOUS_STATUS.QUEUED,
  AUTONOMOUS_STATUS.WAITING_USER,
]);
const POLL_INTERVAL_MS = 650;

export function useDiagnosisAssistant(caseId: string) {
  const diagnosis = useDiagnosisStore();
  const picker = ref<AssistantPicker>(ASSISTANT_PICKER.NONE);
  const patientSearch = ref('');
  const patientOptions = ref<PatientOption[]>([]);
  const patientLoading = ref(false);
  const histories = ref<AssistantConversationSummary[]>([]);
  const historyTotal = ref(0);
  const historyLoading = ref(false);
  const activeConversation = ref<AssistantConversation>();
  const viewingHistory = ref(false);
  const busy = ref(false);
  const error = ref('');
  const draft = ref('');
  const intentLoading = ref(false);
  const guidanceTurns = ref<AssistantGuidanceTurn[]>([]);
  const autonomousCompose = ref(false);
  let pollTimer = 0;
  let eventSeq = 0;

  const snapshot = computed(() => diagnosis.getCase(caseId));
  const autonomousRun = computed(() => snapshot.value?.autonomousRun ?? {});
  const autonomousStatus = computed(() => String(autonomousRun.value.status ?? ''));
  const autonomousActive = computed(() => ACTIVE_STATUSES.has(autonomousStatus.value));

  async function loadHistory() {
    historyLoading.value = true;
    try {
      const result = await getAssistantConversations(caseId, 1, 50);
      histories.value = latestAssistantHistories(result.items);
      historyTotal.value = histories.value.length;
    } finally {
      historyLoading.value = false;
    }
  }

  async function openHistory(item: AssistantConversationSummary) {
    guidanceTurns.value = [];
    const conversation = await getAssistantConversation(caseId, item.conversationId);
    viewingHistory.value = false;
    if (conversation.type === 'PATIENT_CLARIFICATION') {
      activeConversation.value = conversation;
      autonomousCompose.value = false;
      picker.value = ASSISTANT_PICKER.CLARIFY_PATIENT;
      return;
    }
    activeConversation.value = undefined;
    autonomousCompose.value = true;
    picker.value = ASSISTANT_PICKER.NONE;
    startPolling();
  }

  async function showCurrentConversation(item: AssistantConversationSummary) {
    guidanceTurns.value = [];
    activeConversation.value = await getAssistantConversation(caseId, item.conversationId);
    viewingHistory.value = false;
  }

  function returnCurrent() {
    viewingHistory.value = false;
    activeConversation.value = undefined;
  }

  function appendPatientPending(message: string) {
    const now = new Date().toISOString();
    const current = activeConversation.value;
    const conversationId =
      current?.type === 'PATIENT_CLARIFICATION'
        ? current.conversationId
        : `PENDING_${createClientId()}`;
    const messages = current?.type === 'PATIENT_CLARIFICATION' ? current.messages : [];
    activeConversation.value = {
      conversationId,
      type: 'PATIENT_CLARIFICATION',
      title: '患者澄清',
      status: 'RUNNING',
      preview: message,
      createdAt: current?.createdAt ?? now,
      updatedAt: now,
      clarification: current?.clarification,
      messages: [
        ...messages,
        { role: 'USER', content: message, createdAt: now },
        { role: 'ASSISTANT', content: '', pending: true, createdAt: now },
      ],
    };
    viewingHistory.value = false;
    return conversationId.startsWith('PENDING_') ? undefined : conversationId;
  }

  function failPatientPending(message: string) {
    const current = activeConversation.value;
    if (!current || current.type !== 'PATIENT_CLARIFICATION') return;
    const messages = [...current.messages];
    let index = -1;
    for (let cursor = messages.length - 1; cursor >= 0; cursor -= 1) {
      if (messages[cursor]?.pending === true) {
        index = cursor;
        break;
      }
    }
    if (index >= 0) messages[index] = { ...messages[index], pending: false, content: message };
    activeConversation.value = { ...current, status: 'FAILED', messages };
  }

  function clearGuidance() {
    guidanceTurns.value = [];
  }

  function completeGuidanceTurn(assistantMessage: string) {
    const index = guidanceTurns.value.length - 1;
    if (index < 0) return;
    guidanceTurns.value[index] = { ...guidanceTurns.value[index], assistantMessage };
  }

  const searchPatients = useDebounceFn(async () => {
    patientLoading.value = true;
    error.value = '';
    try {
      const [numerator, denominator] = await Promise.all([
        getDiagnosisDetails(caseId, 'numerator', 1, 50, { search: patientSearch.value.trim() }),
        getDiagnosisDetails(caseId, 'denominator', 1, 50, { search: patientSearch.value.trim() }),
      ]);
      const options = [
        ...numerator.rows.map((row) => patientOption(row, 'numerator')),
        ...denominator.rows.map((row) => patientOption(row, 'denominator')),
      ].filter((item): item is PatientOption => item !== null);
      // 同一患者同时出现在分子和分母时保留前面的分子选项，避免列表错误标成“分母”。
      patientOptions.value = dedupePatientOptions(options);
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '患者搜索失败';
      patientOptions.value = [];
    } finally {
      patientLoading.value = false;
    }
  }, 300);

  function chooseAction(action: string) {
    error.value = '';
    if (action === DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION) {
      picker.value = ASSISTANT_PICKER.CLARIFY_PATIENT;
    } else if (action === DIAGNOSIS_ASSISTANT_ACTION.EXCLUDE_PATIENT) {
      picker.value = ASSISTANT_PICKER.EXCLUDE_PATIENT;
      void searchPatients();
    } else if (action === DIAGNOSIS_ASSISTANT_ACTION.EXCLUDE_DEPARTMENT) {
      picker.value = ASSISTANT_PICKER.EXCLUDE_DEPARTMENT;
    } else {
      picker.value = ASSISTANT_PICKER.NONE;
      returnCurrent();
    }
  }

  async function openLatestPatientConversation() {
    const existing = latestPatientClarificationHistory(histories.value);
    if (existing) {
      await openHistory(existing);
      return;
    }
    returnCurrent();
    chooseAction(DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION);
  }

  async function classifyDraft() {
    const message = draft.value.trim();
    if (!message || intentLoading.value) return;
    guidanceTurns.value = [...guidanceTurns.value, { userMessage: message, assistantMessage: '' }];
    draft.value = '';
    intentLoading.value = true;
    error.value = '';
    try {
      if (isAssistantGreeting(message)) {
        completeGuidanceTurn(
          '你好，我是 AI 排查助手。您可以点击下方快捷功能进行患者澄清、AI 生成对应 SQL、手动上传 SQL，或启动 AI 自主排查。',
        );
        return;
      }
      const result = await classifyAssistantIntent(caseId, message);
      completeGuidanceTurn(assistantIntentReply(result));
    } catch {
      completeGuidanceTurn('抱歉，暂时无法识别这条请求。您可以点击下方“AI 自主排查”功能试试。');
    } finally {
      intentLoading.value = false;
    }
  }

  async function startAutonomous() {
    autonomousCompose.value = true;
    if (autonomousActive.value) {
      returnCurrent();
      startPolling();
      return;
    }
    picker.value = ASSISTANT_PICKER.NONE;
    draft.value = '';
    error.value = '';
  }

  async function sendAutonomous(message: string) {
    const value = message.trim();
    if (!value || busy.value) return false;
    busy.value = true;
    error.value = '';
    try {
      const action = autonomousRun.value.conversationId
        ? autonomousStatus.value === AUTONOMOUS_STATUS.WAITING_USER
          ? DIAGNOSIS_ACTION.RESPOND_AUTONOMOUS_QUESTION
          : DIAGNOSIS_ACTION.SEND_AUTONOMOUS_MESSAGE
        : DIAGNOSIS_ACTION.START_AUTONOMOUS_INVESTIGATION;
      const payload =
        action === DIAGNOSIS_ACTION.START_AUTONOMOUS_INVESTIGATION
          ? { problem: value, clientMessageId: createClientId() }
          : { message: value, clientMessageId: createClientId() };
      await diagnosis.submitAction(caseId, action, payload);
      draft.value = '';
      await loadHistory();
      startPolling();
      return true;
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '自主排查消息发送失败';
      return false;
    } finally {
      busy.value = false;
    }
  }

  async function cancelAutonomous(reason = '用户已停止本轮自主排查') {
    if (!autonomousActive.value) return true;
    busy.value = true;
    error.value = '';
    try {
      await diagnosis.submitAction(caseId, DIAGNOSIS_ACTION.CANCEL_AUTONOMOUS_INVESTIGATION, {
        reason,
      });
      stopPolling();
      await loadHistory();
      return true;
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '停止自主排查失败';
      return false;
    } finally {
      busy.value = false;
    }
  }

  async function poll() {
    try {
      const response = await getDiagnosisAgentEvents(caseId, eventSeq);
      eventSeq = Math.max(eventSeq, latestSeq(response.events));
      await diagnosis.loadCase(caseId);
      if (ACTIVE_STATUSES.has(response.status)) schedulePoll();
      else {
        await loadHistory();
        stopPolling();
      }
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '自主排查状态刷新失败';
      schedulePoll();
    }
  }

  function schedulePoll() {
    window.clearTimeout(pollTimer);
    pollTimer = window.setTimeout(() => void poll(), POLL_INTERVAL_MS);
  }

  function startPolling() {
    stopPolling();
    if (autonomousActive.value) void poll();
  }

  function stopPolling() {
    window.clearTimeout(pollTimer);
    pollTimer = 0;
  }

  onBeforeUnmount(stopPolling);

  return {
    picker,
    patientSearch,
    patientOptions,
    patientLoading,
    histories,
    historyTotal,
    historyLoading,
    activeConversation,
    viewingHistory,
    busy,
    error,
    draft,
    intentLoading,
    guidanceTurns,
    autonomousRun,
    autonomousStatus,
    autonomousActive,
    autonomousCompose,
    loadHistory,
    openHistory,
    showCurrentConversation,
    returnCurrent,
    appendPatientPending,
    failPatientPending,
    clearGuidance,
    searchPatients,
    chooseAction,
    openLatestPatientConversation,
    classifyDraft,
    startAutonomous,
    sendAutonomous,
    cancelAutonomous,
    startPolling,
  };
}
