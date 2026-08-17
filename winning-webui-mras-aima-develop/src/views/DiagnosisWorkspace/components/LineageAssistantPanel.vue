<script setup lang="ts">
import { onMounted } from 'vue';
import type { AiSqlRepairEntry } from '@/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue';
import type { UploadSqlEntry } from '@/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue';
import DiagnosisAssistantPanel from '@/views/DiagnosisWorkspace/components/DiagnosisAssistantPanel.vue';
import { useDataConfirmation } from '@/views/DiagnosisWorkspace/composables/useDataConfirmation';

const props = defineProps<{ caseId: string }>();
const emit = defineEmits<{ startSqlRepair: [value: UploadSqlEntry | AiSqlRepairEntry] }>();
const {
  departmentOptions,
  departmentsLoading,
  submitting,
  loadDepartmentOptions,
  clarifyPatient,
  cancelPatientClarification,
} = useDataConfirmation(props.caseId);

onMounted(() => void loadDepartmentOptions());
</script>

<template>
  <DiagnosisAssistantPanel
    :case-id="caseId"
    :department-options="departmentOptions"
    :departments-loading="departmentsLoading"
    :operation-busy="submitting"
    :clarify-patient="clarifyPatient"
    :cancel-patient-clarification="cancelPatientClarification"
    @start-sql-repair="emit('startSqlRepair', $event)"
  />
</template>
