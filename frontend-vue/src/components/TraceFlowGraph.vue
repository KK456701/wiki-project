<script setup lang="ts">
import { computed } from 'vue'

type TraceNode = Record<string, unknown>
type FlowEdge = Record<string, unknown>
type PositionedEdge = FlowEdge & {
  path: string
  labelX: number
  labelY: number
}

const props = defineProps<{
  nodes: TraceNode[]
  edges: FlowEdge[]
  selectedNodeId?: string
}>()
const emit = defineEmits<{ select: [node: TraceNode] }>()

const nodeWidth = 184
const nodeHeight = 76
const columnGap = 52
const laneGap = 132
const marginX = 34
const marginY = 45

const lanes = computed(() => {
  const values = new Map<string, TraceNode[]>()
  props.nodes.forEach((node) => {
    const lane = String(node.subtask_id || 'root')
    if (!values.has(lane)) values.set(lane, [])
    values.get(lane)?.push(node)
  })
  return [...values.entries()].map(([id, nodes]) => ({
    id,
    nodes: [...nodes].sort((left, right) =>
      Number(left.sequence || 0) - Number(right.sequence || 0),
    ),
  }))
})

const positionedNodes = computed(() => lanes.value.flatMap((lane, laneIndex) =>
  lane.nodes.map((node, columnIndex) => ({
    node,
    id: String(node.node_id || `${lane.id}-${columnIndex}`),
    x: marginX + columnIndex * (nodeWidth + columnGap),
    y: marginY + laneIndex * laneGap,
  })),
))
const positionById = computed(() => new Map(positionedNodes.value.map((item) => [item.id, item])))
const canvasWidth = computed(() => Math.max(
  760,
  marginX * 2 + Math.max(1, ...lanes.value.map((lane) => lane.nodes.length))
    * (nodeWidth + columnGap),
))
const canvasHeight = computed(() => Math.max(170, marginY * 2 + lanes.value.length * laneGap))

const visibleEdges = computed<PositionedEdge[]>(() => props.edges.flatMap((edge): PositionedEdge[] => {
  const source = positionById.value.get(String(edge.from_node_id || ''))
  const target = positionById.value.get(String(edge.to_node_id || ''))
  if (!source || !target) return []
  const startX = source.x + nodeWidth
  const startY = source.y + nodeHeight / 2
  const endX = target.x
  const endY = target.y + nodeHeight / 2
  const bend = Math.max(24, Math.abs(endX - startX) * .46)
  return [{
    ...edge,
    path: `M ${startX} ${startY} C ${startX + bend} ${startY}, ${endX - bend} ${endY}, ${endX} ${endY}`,
    labelX: (startX + endX) / 2,
    labelY: (startY + endY) / 2 - 7,
  } as PositionedEdge]
}))

function nodeClass(node: TraceNode): string[] {
  const type = String(node.node_type || 'code')
  const status = String(node.status || '')
  return [`is-${type}`, status === 'failed' || status === 'error' ? 'is-failed' : '']
}

function shortTitle(node: TraceNode): string {
  const value = String(node.node_title || node.node_name || '未命名节点')
  return value.length > 13 ? `${value.slice(0, 13)}…` : value
}
</script>

<template>
  <div class="trace-flow-scroll" aria-label="Agent 运行流程图">
    <svg class="trace-flow-svg" :width="canvasWidth" :height="canvasHeight" role="img">
      <defs>
        <marker id="flow-arrow" viewBox="0 0 10 10" refX="9" refY="5"
          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" />
        </marker>
      </defs>
      <g v-for="(lane, index) in lanes" :key="lane.id" class="flow-lane">
        <text x="8" :y="marginY + index * laneGap - 14">{{ lane.id }}</text>
        <line x1="8" :x2="canvasWidth - 18"
          :y1="marginY + index * laneGap + nodeHeight + 22"
          :y2="marginY + index * laneGap + nodeHeight + 22" />
      </g>
      <g class="flow-edges">
        <g v-for="(edge, index) in visibleEdges" :key="`${String(edge.from_node_id)}-${String(edge.to_node_id)}-${index}`"
          :class="`edge-${String(edge.edge_type || 'sequence')}`">
          <path :d="String(edge.path)" marker-end="url(#flow-arrow)" />
          <text v-if="edge.label" :x="Number(edge.labelX)" :y="Number(edge.labelY)">
            {{ String(edge.label) }}
          </text>
        </g>
      </g>
      <g v-for="item in positionedNodes" :key="item.id"
        class="flow-node"
        :class="[...nodeClass(item.node), { 'is-selected': selectedNodeId === item.id }]"
        :transform="`translate(${item.x}, ${item.y})`"
        tabindex="0"
        role="button"
        @click="emit('select', item.node)"
        @keydown.enter="emit('select', item.node)">
        <rect :width="nodeWidth" :height="nodeHeight" rx="2" />
        <circle cx="15" cy="16" r="5" />
        <text class="flow-sequence" x="27" y="20">
          {{ String(item.node.sequence || '').padStart(2, '0') }}
        </text>
        <text class="flow-title" x="14" y="44">{{ shortTitle(item.node) }}</text>
        <text class="flow-meta" x="14" y="64">
          {{ String(item.node.node_name || '') }} · {{ Number(item.node.duration_ms || 0) }}ms
        </text>
      </g>
    </svg>
  </div>
</template>
