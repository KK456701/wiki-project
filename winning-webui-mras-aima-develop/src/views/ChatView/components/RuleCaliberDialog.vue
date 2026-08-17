<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import type { EffectiveRule, RuleEffectiveQuery } from '@/types/chat';
import { getRuleEffective } from '@/services/chat';
import { inferSqlDatabaseRole, type SqlDatabaseRole } from '@/services/sql-preview';
import RuleCaliberInfoPane from './RuleCaliberInfoPane.vue';
import RuleSqlTemplateCard from './RuleSqlTemplateCard.vue';

const props = defineProps<{
  open: boolean;
  query: RuleEffectiveQuery | null;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const loading = ref(false);
const errorMessage = ref('');
const data = ref<EffectiveRule | null>(null);
const tab = ref<'info' | 'sql'>('info');

/** SQL 区块（有则展示） */
const sqlBlocks = computed(() => {
  if (!data.value) return [];
  const blocks: { title: string; content: string; databaseRole: SqlDatabaseRole | null }[] = [];
  if (data.value.standardSql)
    blocks.push({
      title: '概览 SQL',
      content: data.value.standardSql,
      databaseRole: inferSqlDatabaseRole(data.value.standardSql, 'OVERVIEW_SQL'),
    });
  if (data.value.sourceExtractSql)
    blocks.push({
      title: '源数据抽取 SQL',
      content: data.value.sourceExtractSql,
      databaseRole: inferSqlDatabaseRole(data.value.sourceExtractSql, 'SOURCE_EXTRACT_SQL'),
    });
  if (data.value.patientDetailSql)
    blocks.push({
      title: '患者明细 SQL',
      content: data.value.patientDetailSql,
      databaseRole: inferSqlDatabaseRole(data.value.patientDetailSql, 'PATIENT_SQL'),
    });
  if (data.value.departmentDetailSql)
    blocks.push({
      title: '科室统计明细 SQL',
      content: data.value.departmentDetailSql,
      databaseRole: inferSqlDatabaseRole(data.value.departmentDetailSql, 'DEPARTMENT_SQL'),
    });
  return blocks;
});

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen || !props.query) {
      data.value = null;
      errorMessage.value = '';
      tab.value = 'info';
      return;
    }
    loading.value = true;
    errorMessage.value = '';
    data.value = null;
    try {
      data.value = await getRuleEffective(props.query);
    } catch (err) {
      errorMessage.value = err instanceof Error ? err.message : '查询规则信息失败';
    } finally {
      loading.value = false;
    }
  },
);
</script>

<template>
  <v-dialog
    :model-value="open"
    max-width="800"
    scrollable
    persistent
    @update:model-value="emit('update:open', $event)"
  >
    <v-card rounded="lg">
      <!-- 头部工具栏 -->
      <v-toolbar density="comfortable" color="surface">
        <v-toolbar-title class="text-body-large font-weight-medium">
          {{ data?.ruleName ?? '指标规则详情' }}
          <v-chip v-if="data" size="x-small" label class="ml-2" color="primary" variant="tonal">
            {{ data.profileName }}
          </v-chip>
        </v-toolbar-title>
        <v-btn variant="text" icon="mdi-close" @click="emit('update:open', false)" />
      </v-toolbar>

      <v-divider />

      <!-- 中间内容区（scrollable 自动处理滚动） -->
      <!-- 加载中 -->
      <v-card-text v-if="loading" class="text-center py-8">
        <v-progress-circular indeterminate color="primary" size="32" />
        <div class="text-medium-emphasis mt-3">查询中...</div>
      </v-card-text>

      <!-- 加载失败 -->
      <v-card-text v-else-if="errorMessage" class="text-center py-8">
        <v-icon icon="mdi-alert-circle" color="error" size="48" />
        <div class="text-error mt-3">{{ errorMessage }}</div>
        <v-btn
          variant="tonal"
          color="primary"
          size="small"
          class="mt-4"
          @click="
            data = null;
            errorMessage = '';
          "
        >
          重试
        </v-btn>
      </v-card-text>

      <!-- 正常内容 -->
      <v-card-text v-else-if="data">
        <v-tabs v-model="tab" density="compact" class="mb-3">
          <v-tab value="info">基本信息与契约</v-tab>
          <v-tab value="sql">SQL 模板</v-tab>
        </v-tabs>

        <v-window v-model="tab">
          <v-window-item value="info">
            <RuleCaliberInfoPane :data="data" />
          </v-window-item>

          <v-window-item value="sql">
            <div class="d-flex flex-column ga-3">
              <RuleSqlTemplateCard
                v-for="block in sqlBlocks"
                :key="block.title"
                :title="block.title"
                :sql="block.content"
                :database-role="block.databaseRole"
                :rule-id="data.ruleId"
                :profile-id="query?.profileId"
                :stat-start="query?.statStart"
                :stat-end="query?.statEnd"
              />
              <v-alert v-if="sqlBlocks.length === 0" type="info" variant="tonal" density="compact">
                暂无可用的 SQL 模板
              </v-alert>
            </div>
          </v-window-item>
        </v-window>
      </v-card-text>

      <!-- 空状态 -->
      <v-card-text v-else class="d-flex flex-column align-center py-8 text-medium-emphasis">
        <v-icon icon="mdi-file-document-outline" size="48" class="mb-3" />
        暂无规则数据
      </v-card-text>

      <v-divider />

      <!-- 底部操作栏 -->
      <v-card-actions class="px-4">
        <v-spacer />
        <v-btn variant="tonal" size="small" @click="emit('update:open', false)">关闭</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
