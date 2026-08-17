import { ref } from 'vue';
import { DIAGNOSIS_ASSISTANT_ACTION } from '@/constants/diagnosis';
import type { AssistantConversationSummary } from '@/types/diagnosis';
import { ASSISTANT_PICKER } from '@/views/DiagnosisWorkspace/assistant';
import type { AiSqlRepairEntry } from '@/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue';
import type { UploadSqlEntry } from '@/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue';
import type { useDiagnosisAssistant } from '@/views/DiagnosisWorkspace/composables/useDiagnosisAssistant';
import type { usePatientClarificationTask } from '@/views/DiagnosisWorkspace/composables/usePatientClarificationTask';

type Assistant = ReturnType<typeof useDiagnosisAssistant>;
type Clarification = ReturnType<typeof usePatientClarificationTask>;

export function useAssistantQuickActions(
  assistant: Assistant,
  clarification: Clarification,
  startSqlRepair: (value: UploadSqlEntry | AiSqlRepairEntry) => void,
) {
  const activeAction = ref('');
  const introDismissed = ref(false);

  async function selectAction(action: string) {
    clarification.reset();
    assistant.clearGuidance();
    introDismissed.value = false;
    if (activeAction.value === action && action === DIAGNOSIS_ASSISTANT_ACTION.AUTONOMOUS) {
      if (assistant.autonomousActive.value && !(await assistant.cancelAutonomous())) return;
      activeAction.value = '';
      assistant.autonomousCompose.value = false;
      assistant.returnCurrent();
      return;
    }
    if (activeAction.value === action && assistant.picker.value !== ASSISTANT_PICKER.NONE) {
      activeAction.value = '';
      assistant.picker.value = ASSISTANT_PICKER.NONE;
      return;
    }
    activeAction.value = action;
    if (action === DIAGNOSIS_ASSISTANT_ACTION.PATIENT_CLARIFICATION) {
      await assistant.openLatestPatientConversation();
      return;
    }
    assistant.returnCurrent();
    if (action === DIAGNOSIS_ASSISTANT_ACTION.AUTONOMOUS) await assistant.startAutonomous();
    else if (action === DIAGNOSIS_ASSISTANT_ACTION.UPLOAD_SQL) {
      assistant.picker.value = ASSISTANT_PICKER.UPLOAD_SQL;
    } else if (action === DIAGNOSIS_ASSISTANT_ACTION.AI_GENERATE_SQL) {
      if (
        assistant.autonomousActive.value &&
        !(await assistant.cancelAutonomous('已切换到 AI 生成对应 SQL'))
      )
        return;
      assistant.picker.value = ASSISTANT_PICKER.AI_GENERATE_SQL;
    }
  }

  async function selectSqlRepair(value: UploadSqlEntry | AiSqlRepairEntry) {
    if (
      assistant.autonomousActive.value &&
      !(await assistant.cancelAutonomous('已切换到 SQL 脚本核查'))
    )
      return;
    assistant.picker.value = ASSISTANT_PICKER.NONE;
    startSqlRepair(value);
  }

  function closePicker() {
    assistant.picker.value = ASSISTANT_PICKER.NONE;
    activeAction.value = '';
  }

  async function resumeHistory(item: AssistantConversationSummary) {
    clarification.reset();
    assistant.clearGuidance();
    introDismissed.value = true;
    activeAction.value = item.type;
    await assistant.openHistory(item);
  }

  return {
    activeAction,
    introDismissed,
    selectAction,
    selectSqlRepair,
    closePicker,
    resumeHistory,
  };
}
