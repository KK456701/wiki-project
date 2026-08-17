<script setup lang="ts">
import type { DataFlow, DataFlowNode, RuleEffectiveQuery } from '@/types/chat';
import { ref, watch, nextTick, onBeforeUnmount } from 'vue';
import { useDisplay } from 'vuetify';
import { getRuleEffective } from '@/services/chat';
import { DATA_FLOW_TEMPLATE } from '@/types/chat';
import { useDataFlowGraph } from '../composables/useDataFlowGraph';
import DataFlowNodeDetail from './DataFlowNodeDetail.vue';

const props = defineProps<{
  open: boolean;
  query: RuleEffectiveQuery | null;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
}>();

const { mdAndDown } = useDisplay();

// ---- 状态 ----
const loading = ref(false);
const errorMessage = ref('');
const dataFlow = ref<DataFlow | null>(null);
const ruleName = ref('');
const showWarnings = ref(false);
const selectedNode = ref<DataFlowNode | null>(null);

// ---- 图例 ----
const LEGEND_ITEMS = [
  { label: '数据表', color: 'primary' },
  { label: '源表抽取', color: 'warning' },
  { label: '拓展事件', color: 'secondary' },
  { label: '概览统计', color: 'success' },
  { label: '科室/患者统计', color: 'success' },
  { label: '指标结果', color: 'primary' },
] as const;

// ---- G6 图（通过 composable） ----
function onNodeClick(node: DataFlowNode | null) {
  selectedNode.value = node;
}

const graphContainer = ref<HTMLElement | null>(null);

const { renderGraph, destroyGraph, fitView } = useDataFlowGraph(onNodeClick);

// ---- 数据加载 ----
async function loadData() {
  if (!props.open || !props.query) {
    dataFlow.value = null;
    errorMessage.value = '';
    ruleName.value = '';
    showWarnings.value = false;
    selectedNode.value = null;
    destroyGraph();
    return;
  }

  loading.value = true;
  errorMessage.value = '';
  dataFlow.value = null;
  selectedNode.value = null;

  try {
    const result = await getRuleEffective(props.query);
    ruleName.value = result.ruleName;
    dataFlow.value = result.dataFlow ?? null;
    showWarnings.value =
      dataFlow.value != null &&
      (dataFlow.value.status === 'incomplete' || dataFlow.value.warnings.length > 0);
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '查询规则信息失败';
  } finally {
    loading.value = false;
  }

  await nextTick();
  if (dataFlow.value && dataFlow.value.nodes.length > 0 && graphContainer.value) {
    await renderGraph(dataFlow.value, graphContainer.value);
  }
}

watch(() => props.open, loadData);

function retry() {
  loadData();
}

onBeforeUnmount(() => {
  destroyGraph();
});
</script>

<template>
  <v-dialog
    :model-value="open"
    fullscreen
    scrollable
    persistent
    @update:model-value="emit('update:open', $event)"
  >
    <v-card rounded="0">
      <v-toolbar density="comfortable" color="surface">
        <v-toolbar-title class="text-body-large font-weight-medium">
          {{ ruleName || '数据链路' }}
          <v-chip v-if="dataFlow" size="x-small" label class="ml-2" color="primary" variant="tonal">
            {{ dataFlow.templateLabel }}
          </v-chip>
        </v-toolbar-title>
        <v-spacer />
        <v-btn
          v-if="dataFlow && dataFlow.nodes.length > 0 && !loading"
          variant="text"
          icon="mdi-fit-to-page-outline"
          @click="fitView"
        />
        <v-btn variant="text" icon="mdi-close" @click="emit('update:open', false)" />
      </v-toolbar>

      <v-divider />

      <v-alert
        v-if="showWarnings"
        type="warning"
        variant="tonal"
        density="compact"
        class="ma-3 mb-0 flex-0-0"
      >
        <ul class="mb-0 pl-3">
          <li v-for="w in dataFlow?.warnings" :key="w">{{ w }}</li>
        </ul>
      </v-alert>

      <!-- 加载中 -->
      <v-card-text v-if="loading" class="d-flex flex-column align-center py-10 flex-grow-1">
        <v-progress-circular indeterminate color="primary" size="32" />
        <div class="text-medium-emphasis mt-3">加载数据链路...</div>
      </v-card-text>

      <!-- 加载失败 -->
      <v-card-text
        v-else-if="errorMessage"
        class="d-flex flex-column align-center py-10 flex-grow-1"
      >
        <v-icon icon="mdi-alert-circle" color="error" size="48" />
        <div class="text-error mt-3">{{ errorMessage }}</div>
        <v-btn variant="tonal" color="primary" size="small" class="mt-4" @click="retry">
          重试
        </v-btn>
      </v-card-text>

      <!-- INCOMPLETE 占位 -->
      <v-card-text
        v-else-if="dataFlow?.templateType === DATA_FLOW_TEMPLATE.INCOMPLETE"
        class="d-flex flex-column align-center py-10 flex-grow-1"
      >
        <v-icon icon="mdi-graph-outline" color="on-surface-variant" size="64" />
        <div class="text-headline-small text-medium-emphasis mt-4">配置不完整</div>
        <div
          class="text-body-medium text-medium-emphasis mt-2 text-center"
          style="max-width: 480px"
        >
          {{
            dataFlow?.nodes?.[0]?.description ?? '当前口径未配置概览 SQL，不能形成可执行统计链路。'
          }}
        </div>
      </v-card-text>

      <!-- 正常渲染 -->
      <div
        v-else-if="dataFlow && dataFlow.nodes.length > 0"
        class="d-flex flex-grow-1"
        style="min-height: 0"
      >
        <!-- 左侧：G6 图 + 图例 -->
        <div class="flex-grow-1 d-flex flex-column">
          <div ref="graphContainer" class="flex-grow-1" />

          <div class="d-flex flex-wrap ga-2 pa-2 border-t-sm">
            <v-chip
              v-for="item in LEGEND_ITEMS"
              :key="item.label"
              size="x-small"
              variant="tonal"
              :color="item.color"
              label
            >
              {{ item.label }}
            </v-chip>
          </div>
        </div>

        <!-- 大屏：右侧固定详情面板 -->
        <template v-if="!mdAndDown">
          <v-divider vertical />
          <div class="detail-panel">
            <DataFlowNodeDetail
              :node="selectedNode"
              :rule-id="query?.ruleId ?? ''"
              :profile-id="query?.profileId"
              :stat-start="query?.statStart"
              :stat-end="query?.statEnd"
              @close="selectedNode = null"
            />
          </div>
        </template>
      </div>

      <!-- 空数据 -->
      <v-card-text
        v-else
        class="d-flex flex-column align-center py-10 text-medium-emphasis flex-grow-1"
      >
        <v-icon icon="mdi-file-document-outline" size="48" class="mb-3" />
        暂无数据链路信息
      </v-card-text>

      <!-- 小屏：click 节点 → bottom-sheet -->
      <v-bottom-sheet
        v-if="mdAndDown && selectedNode && dataFlow && dataFlow.nodes.length > 0"
        :model-value="true"
        persistent
        @update:model-value="
          (val) => {
            if (!val) selectedNode = null;
          }
        "
      >
        <v-card rounded="t-xl" class="mx-auto" max-width="600">
          <DataFlowNodeDetail
            :node="selectedNode"
            :rule-id="query?.ruleId ?? ''"
            :profile-id="query?.profileId"
            :stat-start="query?.statStart"
            :stat-end="query?.statEnd"
            @close="selectedNode = null"
          />
        </v-card>
      </v-bottom-sheet>
    </v-card>
  </v-dialog>
</template>

<style lang="scss" scoped src="./styles/data-flow-dialog.scss"></style>
