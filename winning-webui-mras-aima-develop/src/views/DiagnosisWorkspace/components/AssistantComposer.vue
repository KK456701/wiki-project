<script setup lang="ts">
import { DIAGNOSIS_ASSISTANT_ACTION } from '@/constants/diagnosis';
import type { DiagnosisDataScreening } from '@/types/diagnosis';
import {
  ASSISTANT_PICKER,
  type AssistantPicker,
  type PatientOption,
} from '@/views/DiagnosisWorkspace/assistant';
import AssistantTargetPicker from '@/views/DiagnosisWorkspace/components/AssistantTargetPicker.vue';
import AssistantUploadModePicker, {
  type UploadSqlEntry,
} from '@/views/DiagnosisWorkspace/components/AssistantUploadModePicker.vue';
import AssistantAiSqlRulePicker, {
  type AiSqlRepairEntry,
} from '@/views/DiagnosisWorkspace/components/AssistantAiSqlRulePicker.vue';

type DepartmentOption = DiagnosisDataScreening['departmentOptions'][number];

defineProps<{
  caseId: string;
  actions: ReadonlyArray<{ value: string; label: string; icon: string }>;
  activeAction: string;
  picker: AssistantPicker;
  inputLabel: string;
  patientOptions: PatientOption[];
  patientLoading: boolean;
  departmentOptions: DepartmentOption[];
  departmentLoading: boolean;
  working: boolean;
  taskRunning: boolean;
  stopLabel: string;
  readonly?: boolean;
}>();

const draft = defineModel<string>({ required: true });
const patientSearch = defineModel<string>('patientSearch', { required: true });

defineEmits<{
  selectAction: [value: string];
  submit: [];
  stop: [];
  searchPatients: [];
  selectPatient: [option: PatientOption];
  selectDepartment: [value: string];
  selectUploadMode: [value: UploadSqlEntry];
  selectAiSqlRules: [value: AiSqlRepairEntry];
  closePicker: [];
}>();
</script>

<template>
  <div class="assistant-composer">
    <v-textarea
      v-model="draft"
      :placeholder="inputLabel"
      variant="plain"
      class="assistant-composer__input text-body-medium"
      density="compact"
      rows="1"
      auto-grow
      max-rows="5"
      hide-details
      :readonly="readonly"
      @keydown.enter.exact.prevent="$emit('submit')"
    />
    <div class="assistant-composer__toolbar">
      <div class="assistant-quick-actions">
        <div
          v-for="action in actions"
          :key="action.value"
          class="assistant-quick-action"
          :class="{ 'is-active': activeAction === action.value }"
        >
          <v-btn
            variant="text"
            size="small"
            class="text-label-medium"
            :color="activeAction === action.value ? 'primary' : undefined"
            :prepend-icon="action.icon"
            :disabled="readonly || working"
            @click="$emit('selectAction', action.value)"
          >
            {{ action.label }}
          </v-btn>
          <v-fade-transition>
            <div
              v-if="
                action.value === activeAction &&
                action.value !== DIAGNOSIS_ASSISTANT_ACTION.AUTONOMOUS &&
                picker !== ASSISTANT_PICKER.NONE
              "
              class="assistant-picker-popover"
            >
              <AssistantUploadModePicker
                v-if="picker === ASSISTANT_PICKER.UPLOAD_SQL"
                :case-id="caseId"
                @select="$emit('selectUploadMode', $event)"
                @close="$emit('closePicker')"
              />
              <AssistantAiSqlRulePicker
                v-else-if="picker === ASSISTANT_PICKER.AI_GENERATE_SQL"
                :case-id="caseId"
                @confirm="$emit('selectAiSqlRules', $event)"
                @close="$emit('closePicker')"
              />
              <AssistantTargetPicker
                v-else
                :case-id="caseId"
                :picker="picker"
                :patient-search="patientSearch"
                :patient-options="patientOptions"
                :patient-loading="patientLoading"
                :department-options="departmentOptions"
                :department-loading="departmentLoading"
                :busy="working"
                @update:patient-search="patientSearch = $event"
                @search-patients="$emit('searchPatients')"
                @select-patient="$emit('selectPatient', $event)"
                @select-department="$emit('selectDepartment', $event)"
                @close="$emit('closePicker')"
              />
            </div>
          </v-fade-transition>
        </div>
      </div>
      <v-btn
        v-if="taskRunning"
        class="assistant-composer__stop"
        color="primary"
        variant="text"
        icon
        size="small"
        rounded="sm"
        :disabled="readonly"
        :aria-label="stopLabel"
        @click="$emit('stop')"
      >
        <v-icon icon="mdi-stop" />
        <span class="assistant-sr-only">{{ stopLabel }}</span>
      </v-btn>
      <v-btn
        v-else
        color="primary"
        variant="text"
        icon="mdi-send"
        size="small"
        :loading="working"
        :disabled="readonly || !draft.trim() || working"
        aria-label="发送"
        @click="$emit('submit')"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped src="../styles/assistant-composer.scss"></style>
