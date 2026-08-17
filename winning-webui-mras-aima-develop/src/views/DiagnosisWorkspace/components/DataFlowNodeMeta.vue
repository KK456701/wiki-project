<script setup lang="ts">
import type { DataFlowNode } from '@/types/chat';
import { computed } from 'vue';
import {
  DATA_FLOW_NODE_TYPE_LABEL,
  DATA_FLOW_DB_ROLE_LABEL,
  DATA_FLOW_NODE_TYPE_COLOR,
} from '@/views/DiagnosisWorkspace/constants';
import { dataFlowTablePurpose } from '@/views/DiagnosisWorkspace/composables/useDataFlowNodeInfo';

const props = defineProps<{
  node: DataFlowNode;
}>();

const nodeLabel = computed(() => {
  return DATA_FLOW_NODE_TYPE_LABEL[props.node.nodeType] ?? props.node.nodeType;
});

const dbRoleLabel = computed(() => {
  return DATA_FLOW_DB_ROLE_LABEL[props.node.databaseRole] ?? (props.node.databaseRole || '');
});

const legendColor = computed(() => {
  return DATA_FLOW_NODE_TYPE_COLOR[props.node.nodeType] ?? 'primary';
});

const hasTables = computed(() => props.node.tableNames.length > 0);

/** 关联表：先展示前 2 张主表，其余折叠 */
const visibleTables = computed(() => props.node.tableNames.slice(0, 2));

const hasMoreTables = computed(() => props.node.tableNames.length > 2);

const collapsedTables = computed(() => props.node.tableNames.slice(2));

const hasPrimaryTables = computed(() => {
  return props.node.primaryTables && props.node.primaryTables.length > 0;
});

const hasParameterTables = computed(() => {
  return props.node.parameterTables && props.node.parameterTables.length > 0;
});

const hasParameters = computed(() => {
  return props.node.parameters && props.node.parameters.length > 0;
});
</script>

<template>
  <!-- 节点类型 -->
  <div>
    <div class="text-body-small text-medium-emphasis mb-1">节点类型</div>
    <v-chip size="x-small" variant="tonal" :color="legendColor" label>
      {{ nodeLabel }}
    </v-chip>
  </div>

  <!-- 数据库角色 -->
  <div v-if="dbRoleLabel">
    <div class="text-body-small text-medium-emphasis mb-1">数据库角色</div>
    <span class="text-body-medium">{{ dbRoleLabel }}</span>
  </div>

  <!-- 描述 -->
  <div v-if="node.description">
    <div class="text-body-small text-medium-emphasis mb-1">描述</div>
    <span class="text-body-medium">{{ node.description }}</span>
  </div>

  <!-- 关联表 -->
  <div v-if="hasTables">
    <div class="text-body-small text-medium-emphasis mb-1">这一环节用到的数据表</div>
    <div class="d-flex flex-column ga-2">
      <div v-for="table in visibleTables" :key="table">
        <div class="d-flex align-center ga-1">
          <v-icon icon="mdi-table" size="x-small" color="primary" />
          <code class="text-body-small font-weight-medium">{{ table }}</code>
        </div>
        <span class="text-body-small text-medium-emphasis">
          {{ dataFlowTablePurpose(table, node.tableDescriptions?.[table]) }}
        </span>
      </div>

      <v-expansion-panels v-if="hasMoreTables" variant="accordion" flat density="compact">
        <v-expansion-panel>
          <v-expansion-panel-title class="text-body-small">
            查看其余 {{ collapsedTables.length }} 张表
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <div class="d-flex flex-column ga-2 pt-1">
              <div v-for="table in collapsedTables" :key="table">
                <div class="d-flex align-center ga-1">
                  <v-icon icon="mdi-table" size="x-small" color="primary" />
                  <code class="text-body-small font-weight-medium">{{ table }}</code>
                </div>
                <span class="text-body-small text-medium-emphasis">
                  {{ dataFlowTablePurpose(table, node.tableDescriptions?.[table]) }}
                </span>
              </div>
            </div>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </div>
  </div>

  <!-- 主数据表 -->
  <div v-if="hasPrimaryTables">
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

  <!-- 参数表 -->
  <div v-if="hasParameterTables">
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

  <!-- SQL 参数 -->
  <div v-if="hasParameters">
    <div class="text-body-small text-medium-emphasis mb-1">SQL 参数</div>
    <div class="d-flex flex-wrap ga-1">
      <v-chip v-for="p in node.parameters" :key="p" size="x-small" variant="tonal" label>
        {{ p }}
      </v-chip>
    </div>
  </div>
</template>
