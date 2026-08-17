<script setup lang="ts">
import { getSqlRepairOptions } from '@/services/diagnosis';
import type { SqlRepairOptions } from '@/types/diagnosis';
import { ref, watch } from 'vue';

const props = defineProps<{ caseId: string | null }>();
const emit = defineEmits<{ (e: 'apply', requirement: string): void }>();

const options = ref<SqlRepairOptions | null>(null);
const loading = ref(false);

const templates: Record<string, string> = {
  PATIENT_SCOPE: '排除指定患者，请在下方选择患者。',
  DEPARTMENT_SCOPE: '排除指定科室，请在下方选择科室。',
  TIME_RANGE: '调整统计时间范围，请补充开始时间和结束时间。',
  STATUS_AND_DELETE_FLAG: '按状态码或删除标记筛选，请补充字段和值。',
  STAY_DURATION: '出院时间－入院时间大于等于 8 小时。',
  CONSULTATION_STATUS: '排除作废会诊，且会诊完成时间和会诊后医嘱 ID 均不为空。',
  SURGERY_LEVEL: '按手术等级筛选，请补充等级编码。',
};

watch(
  () => props.caseId,
  async (caseId) => {
    options.value = null;
    if (!caseId) return;
    loading.value = true;
    try {
      options.value = await getSqlRepairOptions(caseId);
    } catch {
      options.value = null;
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

function applyRule(key: string) {
  emit('apply', templates[key] ?? '请按当前指标已有字段补充筛选条件。');
}
</script>

<template>
  <div v-if="loading || options?.rules.length" class="rule-catalog mb-2">
    <span class="text-body-small text-medium-emphasis">当前指标可用规则</span>
    <v-progress-circular v-if="loading" indeterminate size="14" width="2" />
    <v-chip
      v-for="rule in options?.rules ?? []"
      :key="rule.key"
      size="small"
      variant="outlined"
      color="primary"
      @click="applyRule(rule.key)"
    >
      {{ rule.label }}
    </v-chip>
  </div>
</template>

<style scoped>
.rule-catalog {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
