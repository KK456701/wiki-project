<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import {
  previewSql,
  SQL_DATABASE_ROLE,
  type SqlDatabaseRole,
  type SqlPreviewResult,
} from '@/services/sql-preview';
import SqlExecutionDialog from './SqlExecutionDialog.vue';

const props = withDefaults(
  defineProps<{
    sql: string;
    databaseRole: SqlDatabaseRole | null;
    ruleId: string;
    profileId?: string | null;
    statStart?: string;
    statEnd?: string;
    disabledReason?: string;
    idleLabel?: string;
    size?: 'x-small' | 'small' | 'default';
  }>(),
  {
    profileId: null,
    statStart: undefined,
    statEnd: undefined,
    disabledReason: '',
    idleLabel: '执行 SQL',
    size: 'small',
  },
);

type ExecutionStage = 'idle' | 'validating' | 'querying' | 'formatting';

const stage = ref<ExecutionStage>('idle');
const dialogOpen = ref(false);
const result = ref<SqlPreviewResult | null>(null);
const error = ref('');
const running = computed(() => stage.value !== 'idle');
const unavailableReason = computed(() => {
  if (props.disabledReason) return props.disabledReason;
  if (!props.sql.trim()) return '当前没有可执行的 SQL';
  if (!props.ruleId) return '缺少指标上下文，无法校验访问范围';
  if (!props.databaseRole) return '无法确定 SQL 应执行的数据库';
  return '';
});
const buttonText = computed(() => {
  if (stage.value === 'validating') return '正在校验…';
  if (stage.value === 'formatting') return '正在整理结果…';
  if (stage.value === 'querying') {
    return props.databaseRole === SQL_DATABASE_ROLE.BUSINESS
      ? '正在查询 Oracle 业务库…'
      : '正在查询 SQL Server 中间库…';
  }
  return props.idleLabel;
});

async function execute() {
  if (running.value || unavailableReason.value || !props.databaseRole) return;
  result.value = null;
  error.value = '';
  stage.value = 'validating';
  await nextTick();
  stage.value = 'querying';
  try {
    const response = await previewSql({
      sql: props.sql,
      databaseRole: props.databaseRole,
      ruleId: props.ruleId,
      profileId: props.profileId,
      statStart: props.statStart,
      statEnd: props.statEnd,
    });
    stage.value = 'formatting';
    await nextTick();
    result.value = response;
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'SQL 执行失败，请稍后重试';
  } finally {
    stage.value = 'idle';
    dialogOpen.value = true;
  }
}
</script>

<template>
  <v-tooltip :text="unavailableReason" :disabled="!unavailableReason">
    <template #activator="{ props: tooltipProps }">
      <span v-bind="tooltipProps" class="d-inline-flex align-center">
        <v-btn
          color="primary"
          variant="tonal"
          :size="size"
          :disabled="Boolean(unavailableReason) || running"
          @click="execute"
        >
          <v-progress-circular v-if="running" indeterminate size="15" width="2" class="mr-2" />
          <v-icon v-else icon="mdi-play-circle-outline" size="small" class="mr-1" />
          {{ buttonText }}
        </v-btn>
      </span>
    </template>
  </v-tooltip>

  <SqlExecutionDialog
    :open="dialogOpen"
    :result="result"
    :error="error"
    @close="dialogOpen = false"
  />
</template>
