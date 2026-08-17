<script setup lang="ts">
import type { DataFlowNode } from '@/types/chat';
import type { DiagnosisCaseSnapshot } from '@/types/diagnosis';
import { computed, ref, watch } from 'vue';
import {
  DATA_FLOW_NODE_TYPE_LABEL,
  DATA_FLOW_NODE_TYPE_COLOR,
} from '@/views/DiagnosisWorkspace/constants';
import type { SqlGeneratePayload } from '@/views/DiagnosisWorkspace/composables/useDataFlowActions';
import { dataFlowNodePurpose } from '@/views/DiagnosisWorkspace/composables/useDataFlowNodeInfo';
import DataFlowNodeMeta from './DataFlowNodeMeta.vue';
import DataFlowSqlSection from './DataFlowSqlSection.vue';

const props = defineProps<{
  node: DataFlowNode | null;
  caseId?: string | null;
  generating?: boolean;
  generateResult?: string;
  generateError?: string;
  generationLayer?: string;
  generationSnapshot?: DiagnosisCaseSnapshot | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'generate', payload: SqlGeneratePayload): void;
}>();

const hasSql = computed(() => {
  const n = props.node;
  return !!n && n.sqlKind !== '' && n.sql !== '';
});

const nodePurpose = computed(() => dataFlowNodePurpose(props.node));

const nodeTypeLabel = computed(
  () => DATA_FLOW_NODE_TYPE_LABEL[props.node?.nodeType ?? ''] ?? props.node?.nodeType ?? '',
);
const nodeTypeColor = computed(
  () => DATA_FLOW_NODE_TYPE_COLOR[props.node?.nodeType ?? ''] ?? 'primary',
);
const showNodeTitle = computed(
  () => Boolean(props.node?.title) && props.node?.title.trim() !== nodeTypeLabel.value.trim(),
);

/** 元数据面板展开状态：无 SQL 的只读节点默认展开，有 SQL 节点默认收起以聚焦 SQL */
const metaExpanded = ref(false);

watch(
  () => props.node,
  (n) => {
    metaExpanded.value = !(n && n.sqlKind !== '' && n.sql !== '');
  },
  { immediate: true },
);
</script>

<template>
  <div class="d-flex flex-column" style="height: 100%">
    <v-empty-state
      v-if="!node"
      icon="mdi-cursor-pointer"
      title="点击左侧图中的节点查看详情"
      text="或使用顶部「定位 / 切换节点」快速跳转"
      class="flex-grow-1"
    />

    <template v-else>
      <header class="dw-detail-head d-flex align-center ga-2">
        <v-chip
          :color="nodeTypeColor"
          size="small"
          variant="flat"
          label
          class="node-type-chip text-body-medium font-weight-medium"
        >
          {{ nodeTypeLabel }}
        </v-chip>
        <span v-if="showNodeTitle" class="text-body-medium font-weight-medium flex-shrink-0">
          {{ node.title }}
        </span>
        <p
          v-if="nodePurpose"
          class="text-body-small text-medium-emphasis text-truncate flex-grow-1 mb-0"
        >
          {{ nodePurpose }}
        </p>
      </header>
      <v-divider />

      <div class="flex-grow-1 overflow-y-auto pa-3">
        <div class="d-flex flex-column ga-3">
          <!-- SQL 主卡片：有 SQL 节点的视觉焦点 -->
          <DataFlowSqlSection
            v-if="hasSql"
            :node="node"
            :generating="generating ?? false"
            :generate-result="generateResult ?? ''"
            :generate-error="generateError ?? ''"
            :generation-layer="generationLayer ?? ''"
            :case-id="caseId ?? null"
            :generation-snapshot="generationSnapshot ?? null"
            @generate="emit('generate', $event)"
          />

          <!-- 节点元数据：有 SQL 时默认折叠，只读节点默认展开 -->
          <v-card variant="outlined" rounded="lg">
            <v-expansion-panels v-model="metaExpanded" variant="accordion" flat>
              <v-expansion-panel>
                <v-expansion-panel-title class="node-meta-title text-label-large">
                  <v-icon icon="mdi-information-outline" size="small" class="mr-2" />
                  节点元数据
                </v-expansion-panel-title>
                <v-expansion-panel-text>
                  <DataFlowNodeMeta :node="node" />
                </v-expansion-panel-text>
              </v-expansion-panel>
            </v-expansion-panels>
          </v-card>
        </div>
      </div>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.dw-detail-head {
  flex-shrink: 0;
  height: 52px;
  min-height: 52px;
  padding: 8px 12px;
  box-sizing: border-box;
}

.node-type-chip {
  letter-spacing: normal;
}

.node-meta-title {
  min-height: 44px;
  padding: 8px 12px;
}
</style>
