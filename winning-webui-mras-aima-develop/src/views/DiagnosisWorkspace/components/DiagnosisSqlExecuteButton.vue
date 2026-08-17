<script setup lang="ts">
import { computed } from 'vue';
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import { inferSqlDatabaseRole } from '@/services/sql-preview';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';

const props = defineProps<{
  sql: string;
  roleHint?: string | null;
  nodeKind?: string | null;
  snapshot: DiagnosisCaseSnapshot | null;
}>();

const databaseRole = computed(() => inferSqlDatabaseRole(props.sql, props.roleHint));
const idleLabel = computed(() => {
  const candidate = props.snapshot?.candidateSql;
  const candidateSql = String(candidate?.candidateSqlExecutable ?? candidate?.sql ?? '').trim();
  const candidateMode = Boolean(candidateSql && candidateSql === props.sql.trim());
  const kind = String(props.nodeKind ?? '').toUpperCase();
  const nodeLabel =
    kind.includes('SOURCE') || kind.includes('EXTRACT')
      ? '抽取 SQL'
      : kind.includes('OVERVIEW')
        ? '概览 SQL'
        : kind.includes('DEPARTMENT')
          ? '科室 SQL'
          : kind.includes('PATIENT')
            ? '患者明细 SQL'
            : 'SQL';
  return `执行${candidateMode ? '候选' : ''}${nodeLabel}`;
});
const unavailableReason = computed(() =>
  props.snapshot ? '' : '排查案例尚未加载，无法校验 SQL 访问范围',
);
</script>

<template>
  <SqlExecuteButton
    :sql="sql"
    :database-role="databaseRole"
    :rule-id="snapshot?.ruleId ?? ''"
    :profile-id="snapshot?.profileId"
    :stat-start="snapshot?.caliberSnapshot.timeRange.start"
    :stat-end="snapshot?.caliberSnapshot.timeRange.end"
    :disabled-reason="unavailableReason"
    :idle-label="idleLabel"
    size="x-small"
  />
</template>
