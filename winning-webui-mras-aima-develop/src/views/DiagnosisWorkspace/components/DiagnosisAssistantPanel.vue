<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { DIAGNOSIS_ASSISTANT_ACTION } from '@/constants/diagnosis';
import type { DiagnosisDataScreening } from '@/types/diagnosis';
import { ASSISTANT_QUICK_ACTIONS } from '@/views/DiagnosisWorkspace/assistant-actions';
import {
  ASSISTANT_PICKER,
  assistantInputLabel,
  assistantStopVisible,
  patientClarificationPrompt,
  type PatientOption,
} from '@/views/DiagnosisWorkspace/assistant';
import { useDiagnosisAssistant } from '@/views/DiagnosisWorkspace/composables/useDiagnosisAssistant';
import { useAssistantQuickActions } from '@/views/DiagnosisWorkspace/composables/useAssistantQuickActions';
import {
  usePatientClarificationTask,
  type PatientClarificationApi,
} from '@/views/DiagnosisWorkspace/composables/usePatientClarificationTask';
import AssistantComposer from '@/views/DiagnosisWorkspace/components/AssistantComposer.vue';
import AssistantConversationView from '@/views/DiagnosisWorkspace/components/AssistantConversationView.vue';
import AssistantIntroduction from '@/views/DiagnosisWorkspace/components/AssistantIntroduction.vue';
import AssistantPanelHeader from '@/views/DiagnosisWorkspace/components/AssistantPanelHeader.vue';
import type { UploadSqlEntry } from '@/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue';
import type { AiSqlRepairEntry } from '@/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue';
const props = defineProps<{
  caseId: string;
  departmentOptions: DiagnosisDataScreening['departmentOptions'];
  departmentsLoading: boolean;
  operationBusy: boolean;
  readonly?: boolean;
  clarifyPatient: PatientClarificationApi['clarify'];
  cancelPatientClarification: PatientClarificationApi['cancel'];
}>();
const emit = defineEmits<{
  startSqlRepair: [value: UploadSqlEntry | AiSqlRepairEntry];
}>();
const assistant = useDiagnosisAssistant(props.caseId);
const historyOpen = defineModel<boolean>('historyOpen', { default: false });
const clarification = usePatientClarificationTask({
  clarify: props.clarifyPatient,
  cancel: props.cancelPatientClarification,
});
const { activeAction, introDismissed, selectAction, selectSqlRepair, closePicker, resumeHistory } =
  useAssistantQuickActions(assistant, clarification, (value) => emit('startSqlRepair', value));
const working = computed(() =>
  Boolean(
    props.operationBusy ||
    assistant.busy.value ||
    assistant.intentLoading.value ||
    clarification.running.value,
  ),
);
const inputLabel = computed(() =>
  assistantInputLabel(
    assistant.autonomousStatus.value,
    assistant.autonomousCompose.value || assistant.autonomousActive.value,
  ),
);
const showIntro = computed(
  () =>
    !assistant.viewingHistory.value && !assistant.activeConversation.value && !introDismissed.value,
);
const autonomousRunning = computed(() => assistantStopVisible(assistant.autonomousStatus.value));
const taskRunning = computed(() => autonomousRunning.value || clarification.running.value);
async function selectPatient(option: PatientOption) {
  clarification.stage(option);
  assistant.draft.value = patientClarificationPrompt(option);
  assistant.picker.value = ASSISTANT_PICKER.NONE;
}
async function submitInput() {
  if (!assistant.draft.value.trim()) return;
  introDismissed.value = true;
  if (clarification.target.value) {
    const message = assistant.draft.value.trim();
    assistant.draft.value = '';
    const conversationId = assistant.appendPatientPending(message);
    await clarification.submit(
      message,
      conversationId,
      async () => {
        await assistant.loadHistory();
        const latest =
          assistant.histories.value.find((item) => item.conversationId === conversationId) ??
          assistant.histories.value.find(
            (item) => item.type === DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION,
          );
        if (latest) await assistant.showCurrentConversation(latest);
      },
      (value) => {
        assistant.draft.value = value;
        assistant.failPatientPending('本次患者澄清未完成，请重试。');
      },
    );
    return;
  }
  if (assistant.autonomousCompose.value || assistant.autonomousActive.value) {
    await assistant.sendAutonomous(assistant.draft.value);
  } else {
    await assistant.classifyDraft();
  }
}
async function stopTask() {
  if (await clarification.stop()) {
    assistant.failPatientPending('本次患者澄清已停止。');
    return;
  }
  await assistant.cancelAutonomous();
}
onMounted(async () => {
  await assistant.loadHistory();
  assistant.startPolling();
});
</script>
<template>
  <v-card variant="outlined" class="assistant-panel pa-4">
    <AssistantPanelHeader
      v-model="historyOpen"
      :history-loading="assistant.historyLoading.value"
      :history-total="assistant.historyTotal.value"
      :histories="assistant.histories.value"
      :selected-id="assistant.activeConversation.value?.conversationId"
      @select="resumeHistory"
    />

    <div class="assistant-panel__content">
      <v-fade-transition>
        <AssistantIntroduction v-if="showIntro" :case-id="caseId" :mode="activeAction" />
      </v-fade-transition>

      <v-fade-transition>
        <AssistantConversationView
          v-if="
            assistant.activeConversation.value ||
            assistant.guidanceTurns.value.length ||
            assistant.autonomousCompose.value ||
            assistant.autonomousActive.value
          "
          :conversation="assistant.activeConversation.value"
          :autonomous-run="assistant.autonomousRun.value"
          :guidance-turns="assistant.guidanceTurns.value"
          :viewing-history="assistant.viewingHistory.value"
          class="mb-3"
        />
      </v-fade-transition>

      <v-fade-transition>
        <v-alert
          v-if="assistant.error.value"
          type="info"
          variant="tonal"
          density="compact"
          class="mb-3"
          :text="assistant.error.value"
        />
      </v-fade-transition>
    </div>

    <AssistantComposer
      v-if="!readonly"
      v-model="assistant.draft.value"
      v-model:patient-search="assistant.patientSearch.value"
      :case-id="caseId"
      :actions="ASSISTANT_QUICK_ACTIONS"
      :active-action="activeAction"
      :picker="assistant.picker.value"
      :input-label="inputLabel"
      :patient-options="assistant.patientOptions.value"
      :patient-loading="assistant.patientLoading.value"
      :department-options="departmentOptions"
      :department-loading="departmentsLoading"
      :working="working"
      :task-running="taskRunning"
      :stop-label="clarification.running.value ? '停止患者澄清' : '停止自主排查'"
      :readonly="readonly"
      @select-action="selectAction"
      @submit="submitInput"
      @stop="stopTask"
      @search-patients="assistant.searchPatients"
      @select-patient="selectPatient"
      @select-upload-mode="selectSqlRepair"
      @select-ai-sql-rules="selectSqlRepair"
      @close-picker="closePicker"
    />
  </v-card>
</template>

<style lang="scss" scoped src="../styles/diagnosis-assistant-panel.scss"></style>
