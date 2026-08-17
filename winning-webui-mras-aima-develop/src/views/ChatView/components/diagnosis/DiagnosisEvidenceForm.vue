<script setup lang="ts">
import { ref } from 'vue';
import { DIAGNOSIS_ACTION, type DiagnosisActionName } from './diagnosis-constants';

const emit = defineEmits<{
  submit: [action: DiagnosisActionName, payload: Record<string, unknown>];
  cancel: [];
}>();

type EvidenceMode = 'summary' | 'sql';

interface PatchCondition {
  field: string;
  operator: string;
  value: string;
}

const MODE_ITEMS = [
  { title: '轻量摘要（仅记录，不触发影子试跑）', value: 'summary' as EvidenceMode },
  { title: 'SQL 改写要求（自动取证 + 候选）', value: 'sql' as EvidenceMode },
];

const mode = ref<EvidenceMode>('summary');
const summary = ref('');
const requestAiAnalysis = ref(false);

// SQL 改写要求形态字段（仅 mode==='sql' 时提交）
const suspectedLayer = ref('SOURCE_EXTRACT');
const requirement = ref('');
const candidateSql = ref('');
const validationSql = ref('');
const patchConditions = ref<PatchCondition[]>([]);
const runAutomatic = ref(true);

const LAYER_ITEMS = [
  { title: '源抽取层 (SOURCE_EXTRACT)', value: 'SOURCE_EXTRACT' },
  { title: '概览层 (OVERVIEW)', value: 'OVERVIEW' },
];
const OPERATOR_ITEMS = [
  { title: '等于 (EQ)', value: 'EQ' },
  { title: '不等于 (NEQ)', value: 'NEQ' },
  { title: '大于 (GT)', value: 'GT' },
  { title: '小于 (LT)', value: 'LT' },
  { title: '大于等于 (GTE)', value: 'GTE' },
  { title: '小于等于 (LTE)', value: 'LTE' },
  { title: '包含 (IN)', value: 'IN' },
  { title: '模糊 (LIKE)', value: 'LIKE' },
];

function addCondition() {
  patchConditions.value.push({ field: '', operator: 'EQ', value: '' });
}
function removeCondition(idx: number) {
  patchConditions.value.splice(idx, 1);
}

function submit() {
  if (!summary.value.trim()) return;
  const payload: Record<string, unknown> = {
    summary: summary.value.trim(),
    requestAiAnalysis: requestAiAnalysis.value,
  };
  if (mode.value === 'sql') {
    payload.type = 'IMPLEMENTER_SQL_REQUIREMENT';
    payload.suspectedLayer = suspectedLayer.value;
    payload.runAutomatic = runAutomatic.value;
    if (requirement.value.trim()) payload.requirement = requirement.value.trim();
    if (candidateSql.value.trim()) payload.candidateSql = candidateSql.value.trim();
    if (validationSql.value.trim()) payload.validationSql = validationSql.value.trim();
    const conditions = patchConditions.value
      .filter((c) => c.field.trim() && c.value.trim())
      .map((c) => ({ field: c.field.trim(), operator: c.operator, value: c.value.trim() }));
    if (conditions.length) payload.patchConditions = conditions;
  }
  emit('submit', DIAGNOSIS_ACTION.SUBMIT_EVIDENCE, payload);
  reset();
}

function reset() {
  mode.value = 'summary';
  summary.value = '';
  requestAiAnalysis.value = false;
  requirement.value = '';
  candidateSql.value = '';
  validationSql.value = '';
  patchConditions.value = [];
  runAutomatic.value = true;
}
</script>

<template>
  <div class="form-panel mt-2 pa-3">
    <v-radio-group v-model="mode" inline hide-details class="mb-2">
      <v-radio
        v-for="m in MODE_ITEMS"
        :key="m.value"
        :label="m.title"
        :value="m.value"
        density="comfortable"
      />
    </v-radio-group>

    <v-textarea
      v-model="summary"
      label="证据说明（必填）"
      rows="2"
      density="comfortable"
      hide-details
      class="mb-2"
    />

    <template v-if="mode === 'sql'">
      <v-select
        v-model="suspectedLayer"
        :items="LAYER_ITEMS"
        label="疑似问题层"
        density="comfortable"
        hide-details
        class="mb-2"
      />

      <v-textarea
        v-model="requirement"
        label="取证要求（业务规则描述，可选）"
        rows="2"
        density="comfortable"
        hide-details
        class="mb-2"
      />

      <div class="text-body-small text-medium-emphasis mb-1">结构化判断条件（可选）</div>
      <div v-for="(cond, idx) in patchConditions" :key="idx" class="d-flex ga-2 align-center mb-2">
        <v-text-field
          v-model="cond.field"
          label="字段"
          density="comfortable"
          hide-details
          class="flex-2"
        />
        <v-select
          v-model="cond.operator"
          :items="OPERATOR_ITEMS"
          label="运算符"
          density="comfortable"
          hide-details
          class="flex-1"
        />
        <v-text-field
          v-model="cond.value"
          label="值"
          density="comfortable"
          hide-details
          class="flex-2"
        />
        <v-btn
          icon="mdi-close"
          variant="text"
          size="small"
          color="error"
          @click="removeCondition(idx)"
        />
      </div>
      <v-btn
        size="x-small"
        variant="text"
        color="primary"
        prepend-icon="mdi-plus"
        class="mb-2"
        @click="addCondition"
      >
        添加判断条件
      </v-btn>

      <v-textarea
        v-model="candidateSql"
        label="候选 SQL（可选，留空由程序生成）"
        rows="3"
        density="comfortable"
        hide-details
        class="mb-2"
      />

      <v-textarea
        v-model="validationSql"
        label="验证 SQL（可选）"
        rows="2"
        density="comfortable"
        hide-details
        class="mb-2"
      />

      <v-switch
        v-model="runAutomatic"
        label="自动取证"
        color="primary"
        hide-details
        density="compact"
      />
    </template>

    <v-switch
      v-model="requestAiAnalysis"
      label="AI 解释"
      color="primary"
      hide-details
      density="compact"
      class="mt-1"
    />

    <div class="d-flex justify-end ga-2 mt-2">
      <v-btn variant="text" size="small" @click="emit('cancel')">取消</v-btn>
      <v-btn
        color="primary"
        variant="flat"
        size="small"
        :disabled="!summary.trim()"
        @click="submit"
      >
        提交证据
      </v-btn>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.form-panel {
  background: rgb(var(--v-theme-surface-variant));
  border-radius: 8px;
}
.flex-1 {
  flex: 1;
}
.flex-2 {
  flex: 2;
}
</style>
