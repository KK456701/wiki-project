<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDiagnosisStore } from '@/stores/diagnosis';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import { DIAGNOSIS_ACTION, DIAGNOSIS_STEP, type DiagnosisActionName } from './diagnosis-constants';
import DiagnosisActionForm from './DiagnosisActionForm.vue';

const props = defineProps<{
  snapshot: DiagnosisCaseSnapshot;
}>();

const emit = defineEmits<{
  submit: [action: DiagnosisActionName, payload: Record<string, unknown>];
}>();

const diagnosisStore = useDiagnosisStore();
const caseId = computed(() => props.snapshot.caseId);
const submitting = computed(() => diagnosisStore.isActionSubmitting(caseId.value));

interface ActionDescriptor {
  action: DiagnosisActionName;
  label: string;
  icon: string;
  color?: string;
  payload?: Record<string, unknown>;
  formType?: 'case' | 'cause' | 'candidate';
}

/** 按 currentStep 推导可用动作（动作矩阵见 diagnosis-case-actions.md §2） */
function buildActions(step: string): ActionDescriptor[] {
  switch (step) {
    case DIAGNOSIS_STEP.CALIBER_CONFIRMATION:
      return [
        {
          action: DIAGNOSIS_ACTION.CONFIRM_CALIBER,
          label: '确认冻结口径',
          icon: 'mdi-check-decagram',
          color: 'primary',
          payload: { confirmed: true },
        },
      ];
    case DIAGNOSIS_STEP.GATE_1_SCHEMA:
    case DIAGNOSIS_STEP.GATE_2_EVENT:
    case DIAGNOSIS_STEP.GATE_3_VALUE:
      // 三门校验由「确认口径/重跑基础检查/修复后重新检查」触发后自动连跑，
      // 此处仅保留手动「重跑基础检查」入口（RUN_BASE_CHECKS 按当前关卡重跑）
      return [
        {
          action: DIAGNOSIS_ACTION.RUN_BASE_CHECKS,
          label: '重跑基础检查',
          icon: 'mdi-refresh',
          color: 'secondary',
        },
      ];
    case DIAGNOSIS_STEP.CASE_INPUT:
      return [
        {
          action: DIAGNOSIS_ACTION.SUBMIT_CASE,
          label: '提交记录信息',
          icon: 'mdi-form-textbox',
          color: 'primary',
          formType: 'case',
        },
      ];
    case DIAGNOSIS_STEP.CASE_CALIBER_CLARIFICATION:
      return [
        {
          action: DIAGNOSIS_ACTION.CONFIRM_CASE_CALIBER,
          label: '确认案例口径',
          icon: 'mdi-file-compare',
          color: 'primary',
          payload: { confirmed: true },
        },
      ];
    case DIAGNOSIS_STEP.CASE_INVESTIGATION:
      return [
        {
          action: DIAGNOSIS_ACTION.CONFIRM_CAUSE,
          label: '确认根因',
          icon: 'mdi-target',
          color: 'warning',
          formType: 'cause',
        },
        {
          action: DIAGNOSIS_ACTION.CLOSE_AS_CORRECT,
          label: '确认计算正确',
          icon: 'mdi-check-circle',
          color: 'success',
        },
      ];
    case DIAGNOSIS_STEP.CHANGE_PROPOSAL:
      return [
        {
          action: DIAGNOSIS_ACTION.BUILD_CANDIDATE,
          label: '拟定修改方案',
          icon: 'mdi-file-edit',
          color: 'warning',
          formType: 'candidate',
        },
      ];
    case DIAGNOSIS_STEP.SHADOW_TRIAL:
      return [
        {
          action: DIAGNOSIS_ACTION.RUN_SHADOW_TRIAL,
          label: '影子试跑',
          icon: 'mdi-test-tube-off',
          color: 'primary',
        },
        // A1：影子试跑失败 / 需回改候选 SQL 时提供「修订候选」入口，提交 REVISE_CANDIDATE 而非 BUILD_CANDIDATE
        {
          action: DIAGNOSIS_ACTION.REVISE_CANDIDATE,
          label: '修订候选 SQL',
          icon: 'mdi-file-edit-outline',
          color: 'warning',
          formType: 'candidate',
        },
      ];
    case DIAGNOSIS_STEP.DRAFT_SAVE:
      return [
        {
          action: DIAGNOSIS_ACTION.SAVE_HOSPITAL_DRAFT,
          label: '保存医院草稿',
          icon: 'mdi-content-save',
          color: 'success',
          payload: { confirmed: true },
        },
      ];
    case DIAGNOSIS_STEP.COMPLETED:
      return [
        {
          action: DIAGNOSIS_ACTION.REVALIDATE_HOSPITAL_DRAFT,
          label: '复核草稿',
          icon: 'mdi-restart',
          color: 'secondary',
        },
      ];
    default:
      return [];
  }
}

const actions = computed(() => buildActions(props.snapshot.currentStep));
const activeForm = ref<{
  formType: 'case' | 'cause' | 'candidate';
  action: DiagnosisActionName;
} | null>(null);

function onActionClick(desc: ActionDescriptor) {
  if (desc.payload) {
    emit('submit', desc.action, desc.payload);
    return;
  }
  if (desc.formType) {
    const next = { formType: desc.formType, action: desc.action };
    activeForm.value =
      activeForm.value && activeForm.value.formType === desc.formType ? null : next;
  } else {
    emit('submit', desc.action, {});
  }
}

function onFormSubmit(action: DiagnosisActionName, payload: Record<string, unknown>) {
  emit('submit', action, payload);
  activeForm.value = null;
}
</script>

<template>
  <div class="action-bar">
    <div class="d-flex flex-wrap ga-2">
      <v-btn
        v-for="desc in actions"
        :key="desc.action"
        :color="desc.color ?? 'primary'"
        variant="flat"
        size="small"
        :prepend-icon="desc.icon"
        :loading="submitting"
        :disabled="submitting"
        @click="onActionClick(desc)"
      >
        {{ desc.label }}
      </v-btn>
    </div>

    <v-expand-transition>
      <DiagnosisActionForm
        v-if="activeForm"
        :form-type="activeForm.formType"
        :action="activeForm.action"
        @submit="onFormSubmit"
        @cancel="activeForm = null"
      />
    </v-expand-transition>
  </div>
</template>

<style lang="scss" scoped>
.action-bar {
  width: 100%;
}
</style>
