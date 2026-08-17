<script setup lang="ts">
import { ref } from 'vue';
import { DIAGNOSIS_ACTION, type DiagnosisActionName } from './diagnosis-constants';

const props = defineProps<{
  formType: 'case' | 'cause' | 'candidate';
  /** 候选表单提交的动作（BUILD_CANDIDATE 或 REVISE_CANDIDATE），由父级动作栏传入 */
  action?: DiagnosisActionName;
}>();

const emit = defineEmits<{
  submit: [action: DiagnosisActionName, payload: Record<string, unknown>];
  cancel: [];
}>();

// ---- 提交记录信息表单 ----
const recordField = ref('ENCOUNTER_ID');
const recordIdsText = ref('');
const symptom = ref('');
const RECORD_FIELD_ITEMS = [
  { title: '就诊号 (ENCOUNTER_ID)', value: 'ENCOUNTER_ID' },
  { title: '事件号 (EVENT_ID)', value: 'EVENT_ID' },
  { title: '医嘱号 (ORDER_ID)', value: 'ORDER_ID' },
  { title: '手术号 (SURGERY_ID)', value: 'SURGERY_ID' },
];

// ---- 确认根因表单 ----
const conclusion = ref('');

// ---- 拟定修改方案表单 ----
const candidateType = ref('');
const candidateLayer = ref('SOURCE_EXTRACT');
const candidateSql = ref('');
const candidateRequirements = ref('');
const candidateExpectedEffect = ref('');
const showLayer = ref(false);
const CANDIDATE_TYPE_ITEMS = [
  { title: '数据修复 (DATA_REPAIR)', value: 'DATA_REPAIR' },
  { title: '事件配置 (EVENT_CONFIG)', value: 'EVENT_CONFIG' },
  { title: '抽取/概览 SQL (SQL_CHANGE)', value: 'SQL_CHANGE' },
  { title: '口径变更 (CALIBER_CHANGE)', value: 'CALIBER_CHANGE' },
];
const LAYER_ITEMS = [
  { title: '源抽取层 (SOURCE_EXTRACT)', value: 'SOURCE_EXTRACT' },
  { title: '概览层 (OVERVIEW)', value: 'OVERVIEW' },
];

function onTypeChange() {
  // 仅 SQL_CHANGE / CALIBER_CHANGE 需要指定作用层与候选 SQL（对齐后端契约 §4.3.10 与 readonly buildCandidate）
  showLayer.value =
    candidateType.value === 'SQL_CHANGE' || candidateType.value === 'CALIBER_CHANGE';
}

function submit() {
  if (props.formType === 'case') {
    const recordIds = recordIdsText.value
      .split(/[,\s]+/)
      .map((s) => s.trim())
      .filter(Boolean)
      .slice(0, 20);
    if (recordIds.length === 0) return;
    const payload: Record<string, unknown> = { recordField: recordField.value, recordIds };
    if (symptom.value.trim()) payload.symptom = symptom.value.trim();
    emit('submit', DIAGNOSIS_ACTION.SUBMIT_CASE, payload);
  } else if (props.formType === 'cause') {
    if (!conclusion.value.trim()) return;
    emit('submit', DIAGNOSIS_ACTION.CONFIRM_CAUSE, { conclusion: conclusion.value.trim() });
  } else {
    if (!candidateType.value) return;
    // 对齐后端契约 §4.3.10 与 readonly buildCandidate：BUILD_CANDIDATE 必须携带完整的
    // type + layer + requirements + sql + expectedCaseEffect（即便为空串也要发，不能省略）。
    // 缺 layer 会让后端按校验不通过处理、currentStep 不推进到 SHADOW_TRIAL。
    const payload: Record<string, unknown> = {
      type: candidateType.value,
      layer: candidateLayer.value || 'SOURCE_EXTRACT',
      requirements: candidateRequirements.value.trim(),
      sql: candidateSql.value.trim(),
      expectedCaseEffect: candidateExpectedEffect.value.trim(),
    };
    // 候选表单可能被「拟定修改方案」(BUILD_CANDIDATE) 或「修订候选」(REVISE_CANDIDATE) 复用，按父级传入的动作提交
    emit('submit', props.action ?? DIAGNOSIS_ACTION.BUILD_CANDIDATE, payload);
  }
}
</script>

<template>
  <div class="form-panel mt-3 pa-3">
    <!-- 提交记录信息 -->
    <template v-if="formType === 'case'">
      <v-select
        v-model="recordField"
        :items="RECORD_FIELD_ITEMS"
        label="记录类型"
        density="comfortable"
        hide-details
        class="mb-2"
      />
      <v-text-field
        v-model="recordIdsText"
        label="记录编号（多个用逗号或空格分隔，最多 20 个）"
        density="comfortable"
        hide-details
        class="mb-2"
      />
      <v-text-field
        v-model="symptom"
        label="症状描述（可选）"
        density="comfortable"
        hide-details
        class="mb-2"
      />
    </template>

    <!-- 确认根因 -->
    <template v-else-if="formType === 'cause'">
      <v-textarea
        v-model="conclusion"
        label="根因结论（必填）"
        rows="3"
        density="comfortable"
        hide-details
        class="mb-2"
      />
    </template>

    <!-- 拟定修改方案 -->
    <template v-else>
      <v-select
        v-model="candidateType"
        :items="CANDIDATE_TYPE_ITEMS"
        label="修改类型"
        density="comfortable"
        hide-details
        class="mb-2"
        @update:model-value="onTypeChange"
      />
      <template v-if="showLayer">
        <v-select
          v-model="candidateLayer"
          :items="LAYER_ITEMS"
          label="作用层"
          density="comfortable"
          hide-details
          class="mb-2"
        />
        <v-textarea
          v-model="candidateSql"
          label="候选 SQL（可选，由程序生成时留空）"
          rows="3"
          density="comfortable"
          hide-details
          class="mb-2"
        />
      </template>
      <v-textarea
        v-model="candidateRequirements"
        label="修改要求（可选）"
        rows="2"
        density="comfortable"
        hide-details
        class="mb-2"
      />
      <v-textarea
        v-model="candidateExpectedEffect"
        label="预期效果（可选）"
        rows="2"
        density="comfortable"
        hide-details
        class="mb-2"
      />
    </template>

    <div class="d-flex justify-end ga-2">
      <v-btn variant="text" size="small" @click="emit('cancel')">取消</v-btn>
      <v-btn
        color="primary"
        variant="flat"
        size="small"
        :disabled="
          formType === 'case'
            ? !recordIdsText.trim()
            : formType === 'cause'
              ? !conclusion.trim()
              : !candidateType
        "
        @click="submit"
      >
        提交
      </v-btn>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.form-panel {
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 8px;
}
</style>
