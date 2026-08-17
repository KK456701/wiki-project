<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getSqlRepairOptions } from '@/services/diagnosis';
import type { AiPatientOption, AiScopeTarget, SqlRepairOptions } from '@/types/diagnosis';
import { useAiExcludeScope } from '@/views/DiagnosisWorkspace/composables/useAiExcludeScope';

export interface AiSqlRepairEntry {
  mode: 'AI_GENERATE_SQL';
  category: 'PATIENT' | 'DEPARTMENT';
  requirement: string;
  scopeTargets: AiScopeTarget[];
  selectedRules: string[];
  recommendedLayer: 'SOURCE_EXTRACT' | 'OVERVIEW';
}

const props = defineProps<{ caseId: string }>();
const emit = defineEmits<{ confirm: [value: AiSqlRepairEntry]; close: [] }>();

const options = ref<SqlRepairOptions>();
const loadingOptions = ref(false);
const selectedRules = ref<string[]>([]);
const category = ref<'PATIENT' | 'DEPARTMENT'>('PATIENT');

const {
  aiScopeMode,
  aiPatientSearch,
  filteredPatientOptions,
  aiSelectedPatients,
  aiDepartmentSearch,
  aiSelectedDepartments,
  filteredDepartments,
  scopeTargets,
  loading,
  error,
  searchPatients,
  loadDepartmentOptions,
} = useAiExcludeScope(() => props.caseId);

const choiceCatalog: Record<string, Array<{ value: string; label: string }>> = {
  STAY_DURATION: [
    { value: '排除出院时间－入院时间少于 8 小时的患者', label: '排除 8 小时内出院患者' },
    { value: '排除出院时间－入院时间少于 24 小时的患者', label: '排除 24 小时内出院患者' },
    { value: '仅保留出院时间－入院时间大于等于 8 小时的患者', label: '住院时长 ≥ 8 小时' },
    { value: '仅保留出院时间－入院时间大于等于 24 小时的患者', label: '住院时长 ≥ 24 小时' },
  ],
  STATUS_AND_DELETE_FLAG: [
    { value: '仅保留删除标记为 0 的记录', label: '仅保留未删除记录' },
    { value: '排除删除标记为 1 的记录', label: '排除已删除记录' },
  ],
  CONSULTATION_STATUS: [
    { value: '排除作废会诊，会诊状态为 399329839', label: '排除作废会诊' },
    { value: '要求会诊完成时间不为空', label: '会诊已完成' },
    { value: '要求会诊后医嘱 ID 不为空', label: '已有会诊后医嘱' },
  ],
  SURGERY_LEVEL: [
    { value: '排除手术等级为三级的记录', label: '排除三级手术' },
    { value: '排除手术等级为四级的记录', label: '排除四级手术' },
  ],
};

const availableConditionGroups = computed(() =>
  (options.value?.rules ?? [])
    .filter((rule) => rule.available && choiceCatalog[rule.key]?.length)
    .map((rule) => ({ ...rule, choices: choiceCatalog[rule.key] ?? [] })),
);
const sourceAvailable = computed(() =>
  (options.value?.nodes ?? []).some((node) => node.available && node.sqlKind === 'SOURCE_EXTRACT'),
);
const recommendedLayer = computed<'SOURCE_EXTRACT' | 'OVERVIEW'>(() =>
  options.value?.recommendedLayer ?? (sourceAvailable.value ? 'SOURCE_EXTRACT' : 'OVERVIEW'),
);
const targetLabel = computed(() =>
  recommendedLayer.value === 'SOURCE_EXTRACT'
    ? '当前指标存在抽取 SQL，本次条件将优先带入源表抽取 SQL。'
    : '当前指标没有抽取 SQL，本次条件将带入概览及相关统计 SQL。',
);
const canConfirm = computed(() => scopeTargets.value.length > 0 || selectedRules.value.length > 0);

function switchCategory(value: 'PATIENT' | 'DEPARTMENT') {
  category.value = value;
  aiScopeMode.value = value;
  selectedRules.value = [];
  if (value === 'PATIENT') void searchPatients();
  else void loadDepartmentOptions();
}

function updatePatients(items: AiPatientOption[]) {
  aiScopeMode.value = 'PATIENT';
  aiSelectedPatients.value = items;
  aiSelectedDepartments.value = [];
}

function updateDepartments(values: string[]) {
  aiScopeMode.value = 'DEPARTMENT';
  aiSelectedDepartments.value = values;
  aiSelectedPatients.value = [];
}

function confirm() {
  if (!canConfirm.value) return;
  const objectSummary = scopeTargets.value.flatMap((target) => target.labels).join('、');
  const parts = [
    objectSummary
      ? category.value === 'PATIENT'
        ? `排除患者：${objectSummary}`
        : `排除科室：${objectSummary}`
      : '',
    ...selectedRules.value,
  ].filter(Boolean);
  emit('confirm', {
    mode: 'AI_GENERATE_SQL',
    category: category.value,
    requirement: parts.join('；') + '。',
    scopeTargets: scopeTargets.value,
    selectedRules: [...selectedRules.value],
    recommendedLayer: recommendedLayer.value,
  });
}

onMounted(async () => {
  loadingOptions.value = true;
  try {
    options.value = await getSqlRepairOptions(props.caseId);
    await searchPatients();
  } finally {
    loadingOptions.value = false;
  }
});
</script>

<template>
  <v-card variant="outlined" class="ai-sql-rule-picker pa-3" rounded="lg">
    <div class="d-flex align-center justify-space-between mb-2">
      <span class="text-label-medium font-weight-medium">选择排除对象和规则</span>
      <v-btn icon="mdi-close" variant="text" size="x-small" aria-label="关闭" @click="$emit('close')" />
    </div>

    <v-btn-toggle
      :model-value="category"
      mandatory
      density="compact"
      variant="outlined"
      class="mb-3"
    >
      <v-btn value="PATIENT" size="small" @click="switchCategory('PATIENT')">排除患者</v-btn>
      <v-btn value="DEPARTMENT" size="small" @click="switchCategory('DEPARTMENT')">排除科室</v-btn>
    </v-btn-toggle>

    <template v-if="category === 'PATIENT'">
      <v-autocomplete
        v-model:search="aiPatientSearch"
        :model-value="aiSelectedPatients"
        :items="filteredPatientOptions"
        item-title="label"
        return-object
        multiple
        chips
        closable-chips
        density="compact"
        variant="outlined"
        label="选择或搜索患者（可多选）"
        :loading="loading"
        hide-details="auto"
        @update:model-value="updatePatients"
        @keyup.enter="searchPatients"
      />
      <p class="text-body-small text-medium-emphasis mt-1 mb-0">可按姓名、就诊号或住院号搜索当前统计明细。</p>
    </template>
    <template v-else>
      <v-autocomplete
        v-model:search="aiDepartmentSearch"
        :model-value="aiSelectedDepartments"
        :items="filteredDepartments"
        item-title="label"
        item-value="value"
        multiple
        chips
        closable-chips
        density="compact"
        variant="outlined"
        label="选择或搜索科室（可多选）"
        :loading="loading"
        hide-details="auto"
        @update:model-value="updateDepartments"
      />
    </template>

    <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mt-2">{{ error }}</v-alert>

    <div v-if="category === 'PATIENT' && availableConditionGroups.length" class="mt-3">
      <p class="text-label-medium text-medium-emphasis mb-2">可同时选择多个排除条件</p>
      <div v-for="group in availableConditionGroups" :key="group.key" class="rule-group mb-2">
        <span class="text-body-small text-medium-emphasis">{{ group.label }}</span>
        <div class="d-flex flex-wrap ga-1 mt-1">
          <v-chip
            v-for="choice in group.choices"
            :key="choice.value"
            :color="selectedRules.includes(choice.value) ? 'primary' : undefined"
            :variant="selectedRules.includes(choice.value) ? 'flat' : 'outlined'"
            size="small"
            @click="selectedRules = selectedRules.includes(choice.value) ? selectedRules.filter((item) => item !== choice.value) : [...selectedRules, choice.value]"
          >
            {{ choice.label }}
          </v-chip>
        </div>
      </div>
    </div>

    <p class="text-body-small text-medium-emphasis mt-3 mb-0">{{ targetLabel }}</p>
    <div class="d-flex justify-end mt-3">
      <v-btn
        color="primary"
        variant="flat"
        size="small"
        :disabled="loadingOptions || !canConfirm"
        prepend-icon="mdi-arrow-right"
        @click="confirm"
      >
        确认并进入 SQL 脚本核查
      </v-btn>
    </div>
  </v-card>
</template>

<style scoped>
.ai-sql-rule-picker {
  width: min(600px, calc(100vw - 48px));
  max-height: min(620px, calc(100vh - 120px));
  overflow-y: auto;
  border-color: rgba(var(--v-theme-outline), 0.24);
  background: rgb(var(--v-theme-surface));
}
</style>
