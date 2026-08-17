<script setup lang="ts">
import { ref, watch, nextTick, onBeforeUnmount } from 'vue';
import { useDisplay } from 'vuetify';
import type { DataFlow, DataFlowNode, RuleEffectiveQuery } from '@/types/chat';
import { getRuleEffective } from '@/services/chat';
import { DATA_FLOW_NODE_TYPE, DATA_FLOW_TEMPLATE } from '@/types/chat';
import { useDataFlowGraph } from '@/views/DiagnosisWorkspace/composables/useDataFlowGraph';
import {
  useSqlGeneration,
  useOverallExecution,
  type SqlGeneratePayload,
} from '@/views/DiagnosisWorkspace/composables/useDataFlowActions';
import DataFlowNodeDetail from '@/views/DiagnosisWorkspace/components/DataFlowNodeDetail.vue';
import LineageExecutionResultDialog from '@/views/DiagnosisWorkspace/components/LineageExecutionResultDialog.vue';
import DataFlowExplanationDialog from '@/views/DiagnosisWorkspace/components/DataFlowExplanationDialog.vue';
import LineageAssistantPanel from '@/views/DiagnosisWorkspace/components/LineageAssistantPanel.vue';
import * as lineageState from '@/views/DiagnosisWorkspace/composables/useEffectiveLineageSnapshot';

const props = defineProps<{
  ruleId: string;
  profileId?: string | null;
  statStart: string;
  statEnd: string;
  caseId?: string | null;
}>();

const { mdAndDown } = useDisplay();
const loading = ref(false);
const errorMessage = ref('');
const dataFlow = ref<DataFlow | null>(null);
const showWarnings = ref(false);
const selectedNode = ref<DataFlowNode | null>(null);
const graphContainer = ref<HTMLElement | null>(null);
const { renderGraph, destroyGraph, fitView, selectNode } = useDataFlowGraph((node) => {
  selectedNode.value = node;
});
const {
  generating,
  generateResult,
  generateError,
  generationLayer,
  generationSnapshot,
  handleGenerate,
} = useSqlGeneration(() => props.caseId ?? null);
const {
  executing: overallExecuting,
  executeStage: overallStage,
  executeStages: overallStages,
  executeError: overallError,
  executeResult: overallResult,
  handleOverallExecution,
} = useOverallExecution(
  () => props.caseId ?? null,
  () => selectedNode.value,
);
const executionDialogOpen = ref(false);
const explanationDialogOpen = ref(false);
const repairRevision = ref(0);
const effectiveSnapshot = lineageState.useEffectiveLineageSnapshot(
  () => props.caseId ?? null,
  generationSnapshot,
);
async function executeCurrentSql() {
  executionDialogOpen.value = true;
  await handleOverallExecution();
}
function startSqlRepair(value: unknown) {
  if (lineageState.storeLineageSqlRepair(props.caseId, value)) repairRevision.value += 1;
}
async function loadData() {
  if (!props.ruleId || !props.statStart || !props.statEnd) return;
  loading.value = true;
  errorMessage.value = '';
  dataFlow.value = null;
  selectedNode.value = null;
  destroyGraph();
  try {
    const query: RuleEffectiveQuery = {
      ruleId: props.ruleId,
      profileId: props.profileId,
      statStart: props.statStart,
      statEnd: props.statEnd,
    };
    const result = await getRuleEffective(query);
    dataFlow.value = result.dataFlow ?? null;
    showWarnings.value =
      dataFlow.value != null &&
      (dataFlow.value.status === 'incomplete' || dataFlow.value.warnings.length > 0);
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '查询数据链路失败';
  } finally {
    loading.value = false;
  }
  await nextTick();
  if (dataFlow.value && dataFlow.value.nodes.length > 0 && graphContainer.value) {
    await renderGraph(dataFlow.value, graphContainer.value);
    const defaultNode =
      dataFlow.value.nodes.find(
        (n) =>
          n.nodeType === DATA_FLOW_NODE_TYPE.SOURCE_EXTRACT_SQL ||
          n.nodeType === DATA_FLOW_NODE_TYPE.OVERVIEW_SQL,
      ) ?? dataFlow.value.nodes[0];
    selectNode(defaultNode ?? null);
  }
}
watch(
  () => [props.ruleId, props.profileId, props.statStart, props.statEnd],
  () => void loadData(),
  { immediate: true },
);
onBeforeUnmount(() => destroyGraph());
</script>

<template>
  <div class="dw-lineage">
    <div v-if="loading" class="d-flex flex-column align-center justify-center py-10">
      <v-progress-circular indeterminate color="primary" size="32" />
      <div class="text-medium-emphasis mt-3">加载数据链路…</div>
    </div>
    <div v-else-if="errorMessage" class="d-flex flex-column align-center justify-center py-10">
      <v-icon icon="mdi-alert-circle" color="error" size="48" />
      <div class="text-error mt-3">{{ errorMessage }}</div>
      <v-btn variant="tonal" color="primary" size="small" class="mt-4" @click="loadData"
        >重试</v-btn
      >
    </div>
    <div
      v-else-if="dataFlow?.templateType === DATA_FLOW_TEMPLATE.INCOMPLETE"
      class="d-flex flex-column align-center justify-center py-10 text-center"
    >
      <v-icon icon="mdi-graph-outline" color="on-surface-variant" size="64" />
      <div class="text-headline-small text-medium-emphasis mt-4">配置不完整</div>
      <div class="text-body-medium text-medium-emphasis mt-2" style="max-width: 480px">
        {{
          dataFlow?.nodes?.[0]?.description ?? '当前口径未配置概览 SQL，不能形成可执行统计链路。'
        }}
      </div>
    </div>
    <div v-else-if="dataFlow && dataFlow.nodes.length > 0" class="dw-lineage-body">
      <v-alert
        v-if="showWarnings"
        type="warning"
        variant="tonal"
        density="compact"
        border="start"
        class="ma-3 mb-0"
      >
        <ul class="mb-0 pl-3">
          <li v-for="w in dataFlow?.warnings" :key="w">{{ w }}</li>
        </ul>
      </v-alert>
      <div class="dw-lineage-split" :class="{ 'is-column': mdAndDown }">
        <section class="dw-graph-pane" aria-label="数据链路图">
          <header class="dw-graph-header">
            <div class="d-flex align-center">
              <v-btn
                size="x-small"
                density="compact"
                variant="text"
                prepend-icon="mdi-graph-outline"
                class="text-label-large"
                @click="explanationDialogOpen = true"
              >
                数据链路图说明
              </v-btn>
              <v-spacer />
              <v-btn
                color="primary"
                size="x-small"
                variant="flat"
                height="30"
                class="flex-shrink-0"
                :disabled="!caseId || overallExecuting"
                title="没有已保存修改时执行当前正式链路；存在已保存候选 SQL 时优先执行候选链路"
                @click="executeCurrentSql"
              >
                <v-progress-circular
                  v-if="overallExecuting"
                  indeterminate
                  size="15"
                  width="2"
                  class="mr-2"
                />
                <v-icon v-else icon="mdi-play-circle-outline" size="small" class="mr-1" />
                {{ overallExecuting ? overallStage || '正在执行…' : '查看当前指标结果' }}
              </v-btn>
              <v-btn
                variant="text"
                size="x-small"
                icon="mdi-fit-to-page-outline"
                :disabled="!dataFlow"
                aria-label="适应画布"
                @click="fitView"
              />
            </div>
          </header>
          <v-divider />
          <div ref="graphContainer" class="dw-graph" />
        </section>
        <section class="dw-detail-pane" aria-label="节点详情">
          <DataFlowNodeDetail
            :key="`${selectedNode?.id ?? 'none'}:${repairRevision}`"
            :node="selectedNode"
            :case-id="caseId"
            :generating="generating"
            :generate-result="generateResult"
            :generate-error="generateError"
            :generation-layer="generationLayer"
            :generation-snapshot="effectiveSnapshot"
            @close="selectNode(null)"
            @generate="
              (payload: SqlGeneratePayload) => selectedNode && handleGenerate(selectedNode, payload)
            "
          />
          <LineageAssistantPanel
            v-if="caseId"
            :case-id="caseId"
            class="ma-3"
            @start-sql-repair="startSqlRepair"
          />
        </section>
      </div>
    </div>
    <div v-else class="d-flex flex-column align-center justify-center py-10 text-medium-emphasis">
      <v-icon icon="mdi-file-document-outline" size="48" class="mb-3" />
      暂无数据链路信息
    </div>

    <LineageExecutionResultDialog
      :open="executionDialogOpen"
      :running="overallExecuting"
      :stages="overallStages"
      :current-stage="overallStage"
      :result-message="overallResult"
      :error="overallError"
      :snapshot="effectiveSnapshot"
      :case-id="caseId ?? null"
      @close="executionDialogOpen = false"
    />
    <DataFlowExplanationDialog
      v-if="dataFlow"
      :open="explanationDialogOpen"
      :flow="dataFlow"
      @close="explanationDialogOpen = false"
    />
  </div>
</template>

<style lang="scss" scoped src="../styles/step-lineage.scss"></style>
