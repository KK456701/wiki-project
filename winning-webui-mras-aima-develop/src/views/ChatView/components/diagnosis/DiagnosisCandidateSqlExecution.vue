<script setup lang="ts">
import { computed } from 'vue';
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import { inferSqlDatabaseRole } from '@/services/sql-preview';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

const props = defineProps<{ snapshot: DiagnosisCaseSnapshot }>();

const candidateSql = computed(() => {
  const value =
    props.snapshot.candidateSql['sql'] ??
    props.snapshot.candidateSql['candidateSql'] ??
    props.snapshot.candidateSql['baselineSql'];
  return typeof value === 'string' ? value : '';
});
const candidateRole = computed(() =>
  inferSqlDatabaseRole(candidateSql.value, String(props.snapshot.candidateSql['layer'] ?? '')),
);
</script>

<template>
  <div v-if="candidateSql" class="d-flex align-center justify-end ga-2 mb-3">
    <span class="text-body-small text-medium-emphasis">只读查看候选 SQL 的实际返回结果</span>
    <SqlExecuteButton
      :sql="candidateSql"
      :database-role="candidateRole"
      :rule-id="snapshot.ruleId"
      :profile-id="snapshot.profileId"
      :stat-start="snapshot.caliberSnapshot.timeRange.start"
      :stat-end="snapshot.caliberSnapshot.timeRange.end"
    />
  </div>
</template>
