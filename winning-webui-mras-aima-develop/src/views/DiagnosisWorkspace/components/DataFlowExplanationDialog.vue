<script setup lang="ts">
import { computed } from 'vue';
import type { DataFlow } from '@/types/chat';
import { DATA_FLOW_NODE_TYPE } from '@/types/chat';
import { DATA_FLOW_NODE_TYPE_LABEL } from '@/views/DiagnosisWorkspace/constants';
import {
  dataFlowNodeHint,
  dataFlowNodePurpose,
} from '@/views/DiagnosisWorkspace/composables/useDataFlowNodeInfo';

const props = defineProps<{
  open: boolean;
  flow: DataFlow;
}>();

const emit = defineEmits<{ close: [] }>();

const SQL_NODE_TYPES = new Set<string>([
  DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL,
  DATA_FLOW_NODE_TYPE.EXTENDED_EVENT_SQL,
  DATA_FLOW_NODE_TYPE.OVERVIEW_SQL,
  DATA_FLOW_NODE_TYPE.DEPARTMENT_SQL,
  DATA_FLOW_NODE_TYPE.PATIENT_SQL,
]);

const orderedNodes = computed(() => [...props.flow.nodes].sort((a, b) => a.sequence - b.sequence));
const sqlNodes = computed(() =>
  orderedNodes.value.filter((node) => SQL_NODE_TYPES.has(node.nodeType)),
);
const flowSummary = computed(() => orderedNodes.value.map((node) => node.title).join(' → '));
const editGuidance = computed(() => {
  const hasExtract = sqlNodes.value.some(
    (node) => node.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL,
  );
  const hasEvent = sqlNodes.value.some(
    (node) => node.nodeType === DATA_FLOW_NODE_TYPE.EXTENDED_EVENT_SQL,
  );
  if (!hasExtract) {
    return '这个指标没有独立的源表抽取 SQL。先核对直接查询的业务数据；只有中间数据正确但合计结果、计算公式或达标判断错误时，才修改概览统计 SQL。';
  }
  return `患者、科室、统计时间、排除范围或去重结果不对时，通常修改源表抽取 SQL。${
    hasEvent ? '如果业务事件本身没有生成或判定错误，应先核对对应的拓展事件 SQL。' : ''
  }只有中间表数据正确，但分子、分母、计算公式或达标判断错误时，才修改概览统计 SQL。科室统计和患者明细 SQL 主要用于查看结果，一般不修改正式口径。`;
});
</script>

<template>
  <v-dialog
    :model-value="open"
    max-width="760"
    scrollable
    @update:model-value="!$event && emit('close')"
  >
    <v-card rounded="lg">
      <v-card-title class="d-flex align-center ga-2 text-title-medium">
        <v-icon icon="mdi-graph-outline" color="primary" />
        数据链路图说明
        <v-spacer />
        <v-btn
          icon="mdi-close"
          variant="text"
          size="small"
          aria-label="关闭"
          @click="emit('close')"
        />
      </v-card-title>
      <v-divider />
      <v-card-text class="pa-4">
        <section class="mb-4">
          <div class="text-label-large font-weight-medium mb-1">数据从哪里来、流向哪里</div>
          <p class="text-body-medium text-medium-emphasis mb-0">{{ flowSummary }}</p>
        </section>

        <section class="mb-4">
          <div class="text-label-large font-weight-medium mb-2">每个 SQL 节点的作用</div>
          <div class="d-flex flex-column ga-2">
            <div v-for="node in sqlNodes" :key="node.id" class="sql-explanation pa-3 rounded-lg">
              <div class="d-flex align-center ga-2 mb-1">
                <strong class="text-body-medium">{{ node.title }}</strong>
                <v-chip
                  v-if="
                    node.title.trim() !==
                    String(DATA_FLOW_NODE_TYPE_LABEL[node.nodeType] ?? node.nodeType).trim()
                  "
                  size="x-small"
                  variant="tonal"
                  label
                >
                  {{ DATA_FLOW_NODE_TYPE_LABEL[node.nodeType] ?? node.nodeType }}
                </v-chip>
              </div>
              <p class="text-body-small mb-1">{{ dataFlowNodePurpose(node) }}</p>
              <p class="text-body-small text-medium-emphasis mb-0">{{ dataFlowNodeHint(node) }}</p>
            </div>
          </div>
        </section>

        <section class="edit-guidance pa-3 rounded-lg">
          <div class="text-label-large font-weight-medium mb-1">一般应该修改哪个 SQL</div>
          <p class="text-body-small mb-0">{{ editGuidance }}</p>
        </section>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped>
.sql-explanation {
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  background: rgb(var(--v-theme-surface));
}

.edit-guidance {
  border-left: 3px solid rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.06);
}
</style>
