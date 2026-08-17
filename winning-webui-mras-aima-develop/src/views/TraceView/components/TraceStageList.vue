<script setup lang="ts">
import { computed } from 'vue';
import type { TraceNodeFull } from '../types';
import { FLOW_STAGE_CONFIG } from '../constants';
import TraceNodeCard from './TraceNodeCard.vue';

const props = defineProps<{
  nodes: TraceNodeFull[];
}>();

defineEmits<{
  (e: 'node-click', node: TraceNodeFull): void;
}>();

/** 按 flow_stage_order 分组节点 */
const stageGroups = computed(() => {
  const groups = new Map<
    string,
    { stage: string; title: string; color: string; order: number; nodes: TraceNodeFull[] }
  >();

  for (const node of props.nodes) {
    const stage = node.flowStage;
    if (!groups.has(stage)) {
      const config = FLOW_STAGE_CONFIG[stage] ?? { title: stage, color: '#888', order: 99 };
      groups.set(stage, {
        stage,
        title: config.title,
        color: config.color,
        order: config.order,
        nodes: [],
      });
    }
    const group = groups.get(stage)!;
    group.nodes.push(node);
  }

  // Sort nodes within each group by sequence
  for (const group of groups.values()) {
    group.nodes.sort((a, b) => a.sequence - b.sequence);
  }

  // Sort groups by order
  return [...groups.values()].sort((a, b) => a.order - b.order);
});

function groupDuration(nodes: TraceNodeFull[]): number {
  return nodes.reduce((sum, n) => sum + n.durationMs, 0);
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
</script>

<template>
  <div class="trace-stage-list">
    <v-expansion-panels v-for="group in stageGroups" :key="group.stage" class="mb-2">
      <v-expansion-panel>
        <v-expansion-panel-title>
          <div class="d-flex align-center w-100">
            <span class="stage-dot mr-2" :style="{ backgroundColor: group.color }" />
            <span class="text-body-medium font-weight-medium">
              {{ group.order }}. {{ group.title }}
            </span>
            <v-spacer />
            <v-chip size="x-small" variant="tonal" class="mr-2">
              {{ group.nodes.length }} 个节点
            </v-chip>
            <span class="text-body-small text-medium-emphasis">
              {{ formatMs(groupDuration(group.nodes)) }}
            </span>
          </div>
        </v-expansion-panel-title>
        <v-expansion-panel-text>
          <div class="d-flex flex-column gap-2 pt-2">
            <TraceNodeCard
              v-for="node in group.nodes"
              :key="node.nodeId"
              :node="node"
              @click="$emit('node-click', node)"
            />
          </div>
        </v-expansion-panel-text>
      </v-expansion-panel>
    </v-expansion-panels>
  </div>
</template>

<style lang="scss" scoped>
.stage-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
</style>
