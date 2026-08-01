<script setup lang="ts">
import type { ChatMessage, ExecutionNode, ExecutionNodeCategory } from '../stores/agent'

defineProps<{ message: ChatMessage }>()
const emit = defineEmits<{ select: [node: ExecutionNode] }>()

const categoryLabels: Record<ExecutionNodeCategory, string> = {
  llm: 'LLM',
  rule: '规则',
  data: '数据',
  verification: '验证',
  summary: '汇总',
  failure: '异常',
}

const categoryMarks: Record<ExecutionNodeCategory, string> = {
  llm: 'AI',
  rule: '规',
  data: '数',
  verification: '验',
  summary: '结',
  failure: '!',
}

function formatDuration(durationMs?: number): string {
  if (durationMs === undefined) return ''
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(durationMs < 10_000 ? 2 : 1)} 秒`
}

function nodeMeta(node: ExecutionNode): string {
  return node.modelId || node.toolName || (node.subtaskId !== 'root' ? node.subtaskId || '' : '')
}
</script>

<template>
  <details
    v-if="message.status === 'running' || message.executionNodes?.length"
    class="execution-panel"
    :open="message.status === 'running'"
  >
    <summary>
      <span class="execution-summary-title">
        <i :data-state="message.status" aria-hidden="true"></i>
        <strong>本次执行</strong>
      </span>
      <span>
        {{ message.status === 'running' ? message.stageLabel || '正在处理' : `${message.executionNodes?.length || 0} 个关键节点` }}
      </span>
    </summary>
    <div v-if="message.executionNodes?.length" class="execution-nodes">
      <button
        v-for="node in message.executionNodes"
        :key="node.id"
        type="button"
        class="execution-node"
        :data-category="node.category"
        :data-state="node.status"
        @click="emit('select', node)"
      >
        <b>{{ categoryMarks[node.category] }}</b>
        <span class="execution-node-copy">
          <small>{{ categoryLabels[node.category] }}</small>
          <strong>{{ node.label }}<mark v-if="node.repeatCount && node.repeatCount > 1">×{{ node.repeatCount }}</mark></strong>
          <em v-if="nodeMeta(node)">{{ nodeMeta(node) }}</em>
          <em v-if="node.status === 'failed' && node.errorMessage" class="execution-node-error">
            {{ node.errorCode ? `${node.errorCode} · ` : '' }}{{ node.errorMessage }}
          </em>
        </span>
        <span class="execution-node-status">
          <time v-if="node.durationMs !== undefined">{{ formatDuration(node.durationMs) }}</time>
          <small>{{ node.status === 'running' ? '执行中' : node.status === 'failed' ? '失败' : node.status === 'warning' ? '需关注' : '已完成' }}</small>
        </span>
        <span class="execution-node-arrow" aria-hidden="true">›</span>
      </button>
    </div>
    <p v-else class="execution-empty">正在识别指标与任务类型…</p>
  </details>
</template>
