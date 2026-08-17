<script setup lang="ts">
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import type { SqlDatabaseRole } from '@/services/sql-preview';

defineProps<{
  title: string;
  sql: string;
  databaseRole: SqlDatabaseRole | null;
  ruleId: string;
  profileId?: string | null;
  statStart?: string;
  statEnd?: string;
}>();
</script>

<template>
  <v-card variant="outlined" density="compact">
    <v-card-title class="d-flex align-center text-body-medium py-2">
      {{ title }}
      <v-spacer />
      <SqlExecuteButton
        :sql="sql"
        :database-role="databaseRole"
        :rule-id="ruleId"
        :profile-id="profileId"
        :stat-start="statStart"
        :stat-end="statEnd"
        size="x-small"
      />
    </v-card-title>
    <v-card-text>
      <pre class="text-body-small sql-block">{{ sql }}</pre>
    </v-card-text>
  </v-card>
</template>

<style lang="scss" scoped>
.sql-block {
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(var(--v-theme-on-surface), 0.03);
  padding: 8px;
  border-radius: 4px;
  max-height: 300px;
  overflow-y: auto;
}
</style>
