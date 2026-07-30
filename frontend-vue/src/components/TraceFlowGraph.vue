<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

type TraceNode = Record<string, unknown>
type FlowEdge = Record<string, unknown>
type StageDefinition = {
  id: string
  title: string
  order: number
}
type PositionedNode = {
  node: TraceNode
  id: string
  stageId: string
  x: number
  y: number
}
type PositionedEdge = FlowEdge & {
  path: string
  labelX: number
  labelY: number
  loop: boolean
}

const props = defineProps<{
  nodes: TraceNode[]
  edges: FlowEdge[]
  selectedNodeId?: string
}>()
const emit = defineEmits<{ select: [node: TraceNode] }>()

const scrollHost = ref<HTMLDivElement | null>(null)

const stages: StageDefinition[] = [
  { id: 'context', title: '上下文与指标识别', order: 1 },
  { id: 'planning', title: '规划与目标校验', order: 2 },
  { id: 'compilation', title: 'IR编译与能力选择', order: 3 },
  { id: 'execution', title: '工具与数据库执行', order: 4 },
  { id: 'verification', title: 'Evidence验证与安全检查', order: 5 },
  { id: 'answer', title: '回答组织与会话保存', order: 6 },
]

const nodeWidth = 188
const nodeHeight = 80
const nodeGap = 18
const stageWidth = 228
const stageGap = 16
const laneLabelWidth = 94
const marginX = 18
const marginY = 16
const stageHeaderHeight = 58
const lanePadding = 24
const laneGap = 14

const lanes = computed(() => {
  const ids: string[] = []
  props.nodes.forEach((node) => {
    const id = String(node.subtaskId || 'root')
    if (!ids.includes(id)) ids.push(id)
  })
  return ids.sort((left, right) => {
    if (left === 'root') return -1
    if (right === 'root') return 1
    return left.localeCompare(right)
  })
})

function stageId(node: TraceNode): string {
  const explicit = String(node.flowStage || '')
  if (stages.some((stage) => stage.id === explicit)) return explicit
  const type = String(node.nodeType || 'code')
  if (type === 'tool' || type === 'database') return 'execution'
  if (type === 'llm') return String(node.nodeName || '') === 'final_answer_llm'
    ? 'answer'
    : 'planning'
  if (type === 'storage') return String(node.nodeName || '') === 'memory_load'
    ? 'context'
    : 'answer'
  return 'execution'
}

const grouped = computed(() => {
  const values = new Map<string, TraceNode[]>()
  props.nodes.forEach((node) => {
    const key = `${String(node.subtaskId || 'root')}::${stageId(node)}`
    if (!values.has(key)) values.set(key, [])
    values.get(key)?.push(node)
  })
  values.forEach((nodes) => nodes.sort((left, right) =>
    Number(left.sequence || 0) - Number(right.sequence || 0),
  ))
  return values
})

const laneMetrics = computed(() => {
  let offset = stageHeaderHeight + marginY
  return lanes.value.map((laneId) => {
    const maximum = Math.max(1, ...stages.map((stage) =>
      grouped.value.get(`${laneId}::${stage.id}`)?.length || 0,
    ))
    const height = lanePadding * 2 + maximum * nodeHeight + (maximum - 1) * nodeGap
    const value = { id: laneId, y: offset, height }
    offset += height + laneGap
    return value
  })
})

const positionedNodes = computed<PositionedNode[]>(() => {
  const result: PositionedNode[] = []
  laneMetrics.value.forEach((lane) => {
    stages.forEach((stage, stageIndex) => {
      const nodes = grouped.value.get(`${lane.id}::${stage.id}`) || []
      nodes.forEach((node, index) => {
        result.push({
          node,
          id: String(node.nodeId || `${lane.id}-${stage.id}-${index}`),
          stageId: stage.id,
          x: marginX + laneLabelWidth + stageIndex * (stageWidth + stageGap)
            + (stageWidth - nodeWidth) / 2,
          y: lane.y + lanePadding + index * (nodeHeight + nodeGap),
        })
      })
    })
  })
  return result
})

const positionById = computed(() =>
  new Map(positionedNodes.value.map((item) => [item.id, item])),
)
const stageCounts = computed(() => new Map(stages.map((stage) => [
  stage.id,
  positionedNodes.value.filter((item) => item.stageId === stage.id).length,
])))
const canvasWidth = computed(() =>
  marginX * 2 + laneLabelWidth + stages.length * stageWidth
    + (stages.length - 1) * stageGap,
)
const canvasHeight = computed(() => {
  const last = laneMetrics.value.at(-1)
  return Math.max(260, (last ? last.y + last.height : stageHeaderHeight + 160) + marginY)
})

const visibleEdges = computed<PositionedEdge[]>(() => props.edges.flatMap((edge): PositionedEdge[] => {
  const source = positionById.value.get(String(edge.fromNodeId || ''))
  const target = positionById.value.get(String(edge.toNodeId || ''))
  if (!source || !target) return []
  const sourceRight = source.x + nodeWidth
  const sourceCenterX = source.x + nodeWidth / 2
  const sourceCenterY = source.y + nodeHeight / 2
  const targetCenterX = target.x + nodeWidth / 2
  const targetCenterY = target.y + nodeHeight / 2
  const sameStageDownward = source.stageId === target.stageId && target.y > source.y
  if (sameStageDownward) {
    return [{
      ...edge,
      path: `M ${sourceCenterX} ${source.y + nodeHeight} V ${target.y}`,
      labelX: sourceCenterX + 5,
      labelY: (source.y + nodeHeight + target.y) / 2,
      loop: false,
    }]
  }
  const forward = target.x > sourceRight
  if (forward) {
    const bendX = (sourceRight + target.x) / 2
    return [{
      ...edge,
      path: `M ${sourceRight} ${sourceCenterY} H ${bendX} V ${targetCenterY} H ${target.x}`,
      labelX: bendX + 4,
      labelY: (sourceCenterY + targetCenterY) / 2 - 7,
      loop: false,
    }]
  }
  const loopY = Math.max(source.y + nodeHeight, target.y + nodeHeight) + 13
  return [{
    ...edge,
    path: `M ${sourceCenterX} ${source.y + nodeHeight} V ${loopY} H ${targetCenterX} V ${target.y + nodeHeight}`,
    labelX: (sourceCenterX + targetCenterX) / 2,
    labelY: loopY - 6,
    loop: true,
  }]
}))

watch(() => props.selectedNodeId, async (id) => {
  if (!id) return
  await nextTick()
  const item = positionById.value.get(id)
  const host = scrollHost.value
  if (!item || !host) return
  const desired = item.x - host.clientWidth / 2 + nodeWidth / 2
  const reduceMotion = typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  host.scrollTo({
    left: Math.max(0, desired),
    behavior: reduceMotion ? 'auto' : 'smooth',
  })
})

function nodeClass(node: TraceNode): string[] {
  const type = String(node.nodeType || 'code')
  const status = String(node.status || '')
  return [`is-${type}`, status === 'failed' || status === 'error' ? 'is-failed' : '']
}

function shortTitle(node: TraceNode): string {
  const value = String(node.nodeTitle || node.nodeName || '未命名节点')
  return value.length > 12 ? `${value.slice(0, 12)}…` : value
}

function laneTitle(id: string): string {
  return id === 'root' ? '主任务' : id.replace(/^SUB_/, '子任务 ')
}

function markerId(edge: PositionedEdge): string {
  if (edge.loop) return 'url(#flow-arrow-loop)'
  if (String(edge.edgeType || '') === 'replan') return 'url(#flow-arrow-replan)'
  if (String(edge.edgeType || '') === 'failure') return 'url(#flow-arrow-failure)'
  return 'url(#flow-arrow)'
}
</script>

<template>
  <div ref="scrollHost" class="trace-flow-scroll" aria-label="Agent 分层架构运行图">
    <svg class="trace-flow-svg" :width="canvasWidth" :height="canvasHeight"
      :viewBox="`0 0 ${canvasWidth} ${canvasHeight}`" role="img">
      <defs>
        <marker id="flow-arrow" viewBox="0 0 10 10" refX="9" refY="5"
          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#718a83" />
        </marker>
        <marker id="flow-arrow-replan" viewBox="0 0 10 10" refX="9" refY="5"
          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#7655ad" />
        </marker>
        <marker id="flow-arrow-failure" viewBox="0 0 10 10" refX="9" refY="5"
          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#bc493c" />
        </marker>
        <marker id="flow-arrow-loop" viewBox="0 0 10 10" refX="9" refY="5"
          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#a06d2c" />
        </marker>
      </defs>

      <g v-for="(stage, index) in stages" :key="stage.id" class="architecture-stage">
        <rect
          :x="marginX + laneLabelWidth + index * (stageWidth + stageGap)"
          :y="marginY"
          :width="stageWidth"
          :height="canvasHeight - marginY * 2"
          rx="6"
        />
        <text class="stage-order"
          :x="marginX + laneLabelWidth + index * (stageWidth + stageGap) + 16"
          :y="marginY + 23">
          {{ String(stage.order).padStart(2, '0') }}
        </text>
        <text class="stage-title"
          :x="marginX + laneLabelWidth + index * (stageWidth + stageGap) + 16"
          :y="marginY + 43">
          {{ stage.title }}
        </text>
        <text class="stage-count"
          :x="marginX + laneLabelWidth + index * (stageWidth + stageGap) + stageWidth - 16"
          :y="marginY + 28">
          {{ stageCounts.get(stage.id) || 0 }}
        </text>
      </g>

      <g v-for="lane in laneMetrics" :key="lane.id" class="architecture-lane">
        <rect :x="marginX" :y="lane.y" :width="laneLabelWidth - 10" :height="lane.height" rx="4" />
        <text :x="marginX + 12" :y="lane.y + 25">{{ laneTitle(lane.id) }}</text>
        <line :x1="marginX + laneLabelWidth" :x2="canvasWidth - marginX"
          :y1="lane.y + lane.height" :y2="lane.y + lane.height" />
      </g>

      <g class="flow-edges">
        <g v-for="(edge, index) in visibleEdges"
          :key="`${String(edge.fromNodeId)}-${String(edge.toNodeId)}-${index}`"
          :class="[`edge-${String(edge.edgeType || 'sequence')}`, { 'is-loop': edge.loop }]">
          <path :d="edge.path" :marker-end="markerId(edge)" />
          <text v-if="edge.label" :x="edge.labelX" :y="edge.labelY">
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
        <rect :width="nodeWidth" :height="nodeHeight"
          :rx="String(item.node.nodeType || '') === 'llm' ? 13 : 3" />
        <circle cx="16" cy="17" r="5" />
        <text class="flow-sequence" x="29" y="21">
          {{ String(item.node.sequence || '').padStart(2, '0') }}
        </text>
        <text class="flow-status" :x="nodeWidth - 12" y="21" text-anchor="end">
          {{ String(item.node.status || '') === 'success' ? '成功' : String(item.node.status || '') }}
        </text>
        <text class="flow-title" x="14" y="47">{{ shortTitle(item.node) }}</text>
        <text class="flow-meta" x="14" y="67">
          {{ String(item.node.nodeName || '') }} · {{ Number(item.node.durationMs || 0) }}ms
        </text>
      </g>
    </svg>
  </div>
</template>
