<script setup lang="ts">
import type { DataFlowNode } from '@/types/chat';
import { computed } from 'vue';
import { useClipboard } from '@vueuse/core';
import SqlExecuteButton from '@/components/SqlExecuteButton.vue';
import { inferSqlDatabaseRole } from '@/services/sql-preview';

const props = defineProps<{
  node: DataFlowNode | null;
  ruleId: string;
  profileId?: string | null;
  statStart?: string;
  statEnd?: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const NODE_TYPE_LABEL: Record<string, string> = {
  TABLE: '数据表',
  SOURCE_EXTRACT_SQL: '源表抽取 SQL',
  EXTENDED_EVENT_SQL: '拓展事件 SQL',
  OVERVIEW_SQL: '概览统计 SQL',
  DEPARTMENT_SQL: '科室统计 SQL',
  PATIENT_SQL: '患者明细 SQL',
  RESULT: '指标结果',
  CONFIGURATION: '配置状态',
};

const DB_ROLE_LABEL: Record<string, string> = {
  BUSINESS: '业务库',
  SYNC: '同步/ETL',
  REAL: '真实库',
  KNOWLEDGE: '知识库',
};

const LEGEND_ITEMS = [
  { label: '数据表', color: 'primary' },
  { label: '源表抽取', color: 'warning' },
  { label: '拓展事件', color: 'secondary' },
  { label: '概览统计', color: 'success' },
  { label: '科室/患者统计', color: 'success' },
  { label: '指标结果', color: 'primary' },
] as const;

const hasSql = computed(() => {
  const n = props.node;
  return !!n && n.sqlKind !== '' && n.sql !== '';
});

const hasTables = computed(() => {
  const n = props.node;
  return !!n && n.tableNames.length > 0;
});

const hasTableDesc = computed(() => {
  const n = props.node;
  return !!n && !!n.tableDescriptions && Object.keys(n.tableDescriptions).length > 0;
});

const nodeTypeLabel = computed(() => {
  if (!props.node) return '';
  return NODE_TYPE_LABEL[props.node.nodeType] ?? props.node.nodeType;
});

const nodeDbRoleLabel = computed(() => {
  if (!props.node) return '';
  return DB_ROLE_LABEL[props.node.databaseRole] ?? (props.node.databaseRole || '');
});

const sqlDatabaseRole = computed(() =>
  props.node ? inferSqlDatabaseRole(props.node.sql, props.node.nodeType) : null,
);

const { copy: clipboardCopy, copied: clipboardCopied } = useClipboard({ legacy: true });

async function copySql() {
  if (props.node?.sql) {
    await clipboardCopy(props.node.sql);
  }
}
</script>

<template>
  <div class="d-flex flex-column" style="height: 100%">
    <!-- 空提示 -->
    <div
      v-if="!node"
      class="d-flex flex-column align-center justify-center flex-grow-1 text-medium-emphasis"
    >
      <v-icon icon="mdi-cursor-default-click" size="32" class="mb-2" />
      <span class="text-body-small">点击图中的节点查看详情</span>
    </div>

    <!-- 节点详情 -->
    <template v-else>
      <v-toolbar density="compact" color="transparent">
        <v-toolbar-title class="text-body-medium font-weight-medium">
          {{ node.title }}
        </v-toolbar-title>
        <v-btn variant="text" size="x-small" icon="mdi-close" @click="emit('close')" />
      </v-toolbar>
      <v-divider />

      <div class="flex-grow-1 overflow-y-auto pa-3">
        <div class="d-flex flex-column ga-3">
          <div>
            <div class="text-body-small text-medium-emphasis mb-1">节点类型</div>
            <v-chip
              size="x-small"
              variant="tonal"
              :color="
                LEGEND_ITEMS.find((item) => item.label === nodeTypeLabel.split(' ')[0])?.color ??
                'primary'
              "
              label
            >
              {{ nodeTypeLabel }}
            </v-chip>
          </div>

          <div v-if="nodeDbRoleLabel">
            <div class="text-body-small text-medium-emphasis mb-1">数据库角色</div>
            <span class="text-body-medium">{{ nodeDbRoleLabel }}</span>
          </div>

          <div v-if="node.description">
            <div class="text-body-small text-medium-emphasis mb-1">描述</div>
            <span class="text-body-medium">{{ node.description }}</span>
          </div>

          <div v-if="hasTables">
            <div class="text-body-small text-medium-emphasis mb-1">关联表</div>
            <div class="d-flex flex-wrap ga-1">
              <v-chip
                v-for="table in node.tableNames"
                :key="table"
                size="x-small"
                variant="outlined"
                label
              >
                {{ table }}
              </v-chip>
            </div>
          </div>

          <div v-if="hasTableDesc">
            <div class="text-body-small text-medium-emphasis mb-1">表描述</div>
            <div class="d-flex flex-column ga-1">
              <div
                v-for="(desc, table) in node.tableDescriptions"
                :key="table"
                class="text-body-medium"
              >
                <code class="text-body-small">{{ table }}</code>
                <span class="ml-1">{{ desc }}</span>
              </div>
            </div>
          </div>

          <div v-if="node.primaryTables && node.primaryTables.length > 0">
            <div class="text-body-small text-medium-emphasis mb-1">主数据表</div>
            <div class="d-flex flex-wrap ga-1">
              <v-chip
                v-for="t in node.primaryTables"
                :key="t"
                size="x-small"
                variant="outlined"
                color="primary"
                label
              >
                {{ t }}
              </v-chip>
            </div>
          </div>

          <div v-if="node.parameterTables && node.parameterTables.length > 0">
            <div class="text-body-small text-medium-emphasis mb-1">参数表</div>
            <div class="d-flex flex-wrap ga-1">
              <v-chip
                v-for="t in node.parameterTables"
                :key="t"
                size="x-small"
                variant="outlined"
                color="warning"
                label
              >
                {{ t }}
              </v-chip>
            </div>
          </div>

          <div v-if="node.parameters && node.parameters.length > 0">
            <div class="text-body-small text-medium-emphasis mb-1">SQL 参数</div>
            <div class="d-flex flex-wrap ga-1">
              <v-chip v-for="p in node.parameters" :key="p" size="x-small" variant="tonal" label>
                {{ p }}
              </v-chip>
            </div>
          </div>

          <div v-if="hasSql" class="flex-grow-1" style="min-height: 0">
            <div class="d-flex align-center mb-1">
              <span class="text-body-small text-medium-emphasis">SQL 文本</span>
              <v-spacer />
              <SqlExecuteButton
                :sql="node.sql"
                :database-role="sqlDatabaseRole"
                :rule-id="ruleId"
                :profile-id="profileId"
                :stat-start="statStart"
                :stat-end="statEnd"
                size="x-small"
              />
              <v-btn
                variant="text"
                size="x-small"
                :color="clipboardCopied ? 'success' : undefined"
                :prepend-icon="clipboardCopied ? 'mdi-check-circle-outline' : 'mdi-content-copy'"
                class="text-body-small"
                @click="copySql"
              >
                {{ clipboardCopied ? '已复制' : '复制' }}
              </v-btn>
            </div>
            <pre class="sql-preview">{{ node.sql }}</pre>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.sql-preview {
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(var(--v-theme-on-surface), 0.03);
  padding: 8px;
  border-radius: 4px;
  max-height: 400px;
  overflow-y: auto;
  font-family: 'Roboto Mono', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.5;
}
</style>
