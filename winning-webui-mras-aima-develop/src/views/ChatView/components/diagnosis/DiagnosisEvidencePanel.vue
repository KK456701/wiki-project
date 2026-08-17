<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import DiagnosisEvidenceForm from './DiagnosisEvidenceForm.vue';
import DiagnosisEvidenceItem from './DiagnosisEvidenceItem.vue';
import type { DiagnosisActionName } from './diagnosis-constants';

const props = defineProps<{
  snapshot: DiagnosisCaseSnapshot;
}>();

const emit = defineEmits<{
  submit: [action: DiagnosisActionName, payload: Record<string, unknown>];
}>();

// 直接从快照派生，确保提交证据后随 store 替换自动刷新（修复本地 ref 不更新 bug）
const evidence = computed(() => props.snapshot.evidence ?? []);
const showForm = ref(false);
// SUBMIT_EVIDENCE 仅属于 CASE_INVESTIGATION 步骤；后续步骤（含拟定修改方案）只展示历史证据，
// 禁止再次提交，否则会把 CASE_INVESTIGATION 的动作打到 CHANGE_PROPOSAL 等步骤，触发 DIAGNOSIS_STEP_ORDER_VIOLATION
const canSubmit = computed(() => props.snapshot.currentStep === 'CASE_INVESTIGATION');
</script>

<template>
  <div class="evidence-panel">
    <div class="d-flex align-center justify-space-between mb-2">
      <div class="text-label-large font-weight-medium d-flex align-center ga-1">
        <v-icon icon="mdi-file-document-multiple-outline" size="18" color="primary" />
        取证记录
        <v-chip size="x-small" label variant="tonal">{{ evidence.length }}</v-chip>
      </div>
      <v-btn
        v-if="canSubmit"
        size="x-small"
        variant="text"
        color="primary"
        prepend-icon="mdi-plus"
        @click="showForm = !showForm"
      >
        提交证据
      </v-btn>
    </div>

    <v-alert
      v-if="evidence.length === 0"
      type="info"
      variant="tonal"
      density="compact"
      class="mb-2"
    >
      {{
        canSubmit
          ? '暂无证据，请通过下方表单提交人工摘要、自动取证或 SQL 要求。'
          : '本步骤暂无取证记录。'
      }}
    </v-alert>

    <v-list v-else density="compact" class="pa-0">
      <v-list-item v-for="item in evidence" :key="item.evidenceId" class="px-0 mb-1">
        <DiagnosisEvidenceItem
          :item="item"
          :rule-id="snapshot.ruleId"
          :profile-id="snapshot.profileId"
          :stat-start="snapshot.caliberSnapshot.timeRange.start"
          :stat-end="snapshot.caliberSnapshot.timeRange.end"
        />
      </v-list-item>
    </v-list>

    <v-expand-transition>
      <DiagnosisEvidenceForm
        v-if="showForm && canSubmit"
        @submit="
          (action, payload) => {
            emit('submit', action, payload);
            showForm = false;
          }
        "
        @cancel="showForm = false"
      />
    </v-expand-transition>
  </div>
</template>

<style lang="scss" scoped>
.evidence-panel {
  width: 100%;
}
</style>
