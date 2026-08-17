<script setup lang="ts">
import { markRaw, ref, watch } from 'vue';
import { VueFlow, useVueFlow } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';
import type { TraceFlowEdge, TraceFlowNode } from '../composables/useTraceDag';
import { layoutDagNodes } from '../composables/useTraceDag';
import type { TraceNodeFull } from '../types';
import { FLOW_STAGE_CONFIG, NODE_STATUS_COLOR, NODE_TYPE_ICON } from '../constants';

const props = defineProps<{
  nodes: TraceFlowNode[];
  edges: TraceFlowEdge[];
}>();

const emit = defineEmits<{
  (e: 'node-click', node: TraceNodeFull): void;
}>();

const { fitView } = useVueFlow();

/** Safely get flow stage config color — avoids "any" indexing error in template */
function getFlowStageColor(node: TraceNodeFull | undefined): string {
  if (!node) return '#888';
  return FLOW_STAGE_CONFIG[node.flowStage]?.color ?? '#888';
}

/** Safely get node type icon — avoids "any" indexing error in template */
function getNodeTypeIcon(node: TraceNodeFull | undefined): string {
  if (!node) return 'mdi-help-circle';
  return NODE_TYPE_ICON[node.nodeType];
}

/** Safely get node status color — avoids "any" indexing error in template */
function getNodeStatusColor(node: TraceNodeFull | undefined): string {
  if (!node) return 'grey';
  return NODE_STATUS_COLOR[node.status];
}

// Layout nodes
const layoutedNodes = ref<TraceFlowNode[]>([]);

watch(
  () => [props.nodes, props.edges],
  () => {
    if (props.nodes.length > 0) {
      const result = layoutDagNodes(JSON.parse(JSON.stringify(props.nodes)), props.edges);
      layoutedNodes.value = markRaw(result.nodes);
    } else {
      layoutedNodes.value = [];
    }
  },
  { immediate: true, deep: true },
);

function onNodeClick(event: { node: TraceFlowNode }) {
  const traceNode = event.node.data?.node;
  if (traceNode) {
    emit('node-click', traceNode);
  }
}

function onPaneReady() {
  if (layoutedNodes.value.length > 0) {
    fitView({ padding: 0.2, duration: 300 });
  }
}
</script>

<template>
  <div class="dag-view-container">
    <div v-if="nodes.length === 0" class="text-center text-medium-emphasis py-8">
      <v-icon icon="mdi-graph-outline" size="48" />
      <div class="mt-2">暂无节点数据</div>
    </div>

    <VueFlow
      v-else
      v-model:nodes="layoutedNodes"
      :edges="props.edges"
      :default-viewport="{ x: 0, y: 0, zoom: 1 }"
      :min-zoom="0.1"
      :max-zoom="4"
      :snap-to-grid="true"
      :snap-grid="[10, 10]"
      fit-view-on-init
      class="dag-flow"
      @node-click="onNodeClick"
      @pane-ready="onPaneReady"
    >
      <Background :gap="20" :size="1" />
      <Controls position="top-right" />

      <!-- Custom node template -->
      <template #node-trace-node="nodeProps">
        <div class="custom-node" :style="{ borderColor: getFlowStageColor(nodeProps.data?.node) }">
          <div class="node-header d-flex align-center pa-1">
            <v-icon
              v-if="nodeProps.data?.node"
              :icon="getNodeTypeIcon(nodeProps.data.node)"
              size="14"
              class="mr-1"
            />
            <span class="text-body-small font-weight-medium text-truncate flex-grow-1">
              {{ nodeProps.data?.node?.nodeTitle ?? nodeProps.id }}
            </span>
            <v-icon
              v-if="nodeProps.data?.node"
              :icon="
                nodeProps.data.node.status === 'success' ? 'mdi-check-circle' : 'mdi-close-circle'
              "
              :color="getNodeStatusColor(nodeProps.data.node)"
              size="12"
            />
          </div>
          <div class="node-footer text-body-small px-1 pb-1 text-medium-emphasis">
            <span>{{ nodeProps.data?.node?.durationMs ?? '-' }}ms</span>
          </div>
        </div>
      </template>
    </VueFlow>

    <!-- Legend -->
    <div v-if="nodes.length > 0" class="dag-legend pa-2">
      <div class="text-body-small font-weight-medium mb-1">图例</div>
      <div class="d-flex flex-wrap ga-3">
        <div v-for="(config, stage) in FLOW_STAGE_CONFIG" :key="stage" class="d-flex align-center">
          <span class="legend-dot mr-1" :style="{ backgroundColor: config.color }" />
          <span class="text-body-small">{{ config.title }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dag-view-container {
  height: 600px;
  position: relative;
  border: 1px solid rgba(var(--v-border-color), 0.12);
  border-radius: 8px;
  overflow: hidden;
}

.dag-flow {
  width: 100%;
  height: 100%;
}

.custom-node {
  background: rgb(var(--v-theme-surface));
  border: 2px solid #888;
  border-radius: 6px;
  min-width: 140px;
  max-width: 180px;
  cursor: pointer;
  transition: box-shadow 0.2s;

  &:hover {
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.18);
  }
}

.node-header {
  background: rgba(var(--v-theme-on-surface), 0.04);
  border-radius: 4px 4px 0 0;
}

.node-footer {
  border-top: 1px solid rgba(var(--v-border-color), 0.12);
}

.dag-legend {
  position: absolute;
  bottom: 8px;
  left: 8px;
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgba(var(--v-border-color), 0.12);
  border-radius: 6px;
  max-width: 360px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
</style>
