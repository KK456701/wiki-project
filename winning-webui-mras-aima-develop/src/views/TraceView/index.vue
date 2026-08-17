<script setup lang="ts">
import { ref } from 'vue';
import { useRoute } from 'vue-router';
import { useTraceData } from './composables/useTraceData';
import { useTraceDag } from './composables/useTraceDag';
import type { TraceNodeFull } from './types';
import TraceHeader from './components/TraceHeader.vue';
import TraceSummary from './components/TraceSummary.vue';
import TraceDagView from './components/TraceDagView.vue';
import TraceStageList from './components/TraceStageList.vue';
import TraceEvidencePanel from './components/TraceEvidencePanel.vue';
import TraceNodeDetail from './components/TraceNodeDetail.vue';

const route = useRoute();

const traceId = () => route.params.traceId as string | undefined;

const { data, loading, error } = useTraceData(traceId);

const nodesData = () => data.value?.nodes ?? [];
const edgesData = () => data.value?.flowEdges ?? [];

const { dagNodes, dagEdges } = useTraceDag(nodesData, edgesData);

const activeTab = ref<'dag' | 'list' | 'evidence'>('dag');
const selectedNode = ref<TraceNodeFull | null>(null);

function openNodeDetail(node: TraceNodeFull) {
  selectedNode.value = node;
}
</script>

<template>
  <v-container fluid class="trace-view-container pa-4">
    <!-- Loading -->
    <div v-if="loading" class="d-flex flex-column align-center justify-center py-12">
      <v-progress-circular indeterminate color="primary" size="48" />
      <div class="text-medium-emphasis mt-3">加载链路数据中...</div>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="d-flex flex-column align-center justify-center py-12">
      <v-icon icon="mdi-alert-circle-outline" color="error" size="48" />
      <div class="text-error mt-3">{{ error }}</div>
    </div>

    <!-- Data -->
    <template v-else-if="data">
      <TraceHeader :data="data" />

      <TraceSummary :summary="data.timingSummary" :total-duration="data.durationMs" />

      <v-tabs v-model="activeTab" class="mb-3">
        <v-tab value="dag">
          <v-icon icon="mdi-graph-outline" size="18" class="mr-1" />
          DAG 视图
        </v-tab>
        <v-tab value="list">
          <v-icon icon="mdi-format-list-bulleted" size="18" class="mr-1" />
          阶段列表
        </v-tab>
        <v-tab value="evidence">
          <v-icon icon="mdi-file-document-multiple-outline" size="18" class="mr-1" />
          Evidence
        </v-tab>
      </v-tabs>

      <v-tabs-window v-model="activeTab">
        <v-tabs-window-item value="dag">
          <TraceDagView :nodes="dagNodes" :edges="dagEdges" @node-click="openNodeDetail" />
        </v-tabs-window-item>
        <v-tabs-window-item value="list">
          <TraceStageList :nodes="data.nodes" @node-click="openNodeDetail" />
        </v-tabs-window-item>
        <v-tabs-window-item value="evidence">
          <TraceEvidencePanel :evidence="data.evidence" />
        </v-tabs-window-item>
      </v-tabs-window>

      <TraceNodeDetail v-model="selectedNode" />
    </template>
  </v-container>
</template>

<style lang="scss" scoped>
.trace-view-container {
  max-width: 1200px;
}
</style>
