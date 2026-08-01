<script setup lang="ts">
import type { BatchIndicatorResult, ChatMessage, ExecutionNode, ExecutionNodeCategory } from '../stores/agent'

const props = defineProps<{ message: ChatMessage }>()
const emit = defineEmits<{
  select: [node: ExecutionNode]
  restore: [message: ChatMessage]
}>()

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
  if (['batch_indicator_enumerate', 'batch_data_initialization_validation',
    'source_data_extraction', 'real_snapshot_data_validation', 'real_database_overview',
    'batch_indicator', 'batch_result_merge'].includes(node.nodeName)) return ''
  if (node.repeatCount && node.repeatCount > 1) return ''
  return node.modelId || node.toolName || (node.subtaskId !== 'root' ? node.subtaskId || '' : '')
}

function resultCounts(results: BatchIndicatorResult[] = []) {
  return {
    success: results.filter((item) => item.status === 'SUCCESS').length,
    noSample: results.filter((item) => item.status === 'NO_SAMPLE').length,
    skipped: results.filter((item) => item.errorCode === 'PROFILE_NOT_IMPLEMENTED').length,
    failed: results.filter((item) => item.status === 'FAILED'
      && item.errorCode !== 'PROFILE_NOT_IMPLEMENTED').length,
  }
}

function nodeLabel(node: ExecutionNode): string {
  const results = props.message.batchResults || []
  const profileTotal = node.profileCount || results.length || 0
  const indicatorTotal = node.indicatorCount || new Set(results.map((item) => item.ruleId)).size || 0
  if (node.nodeName === 'batch_indicator_enumerate' && profileTotal) {
    return `确认本次指标清单：${indicatorTotal} 个指标 / ${profileTotal} 个口径`
  }
  if (node.nodeName === 'batch_data_initialization_validation') {
    if (node.status === 'running') {
      const progress = node.progressTotal
        ? `（${node.progressCompleted || 0}/${node.progressTotal} 个检查对象）` : ''
      return `数据初始化校验 · ${node.label.replace(/^数据初始化校验\s*[·：]?\s*/, '')}${progress}`
    }
    return `数据初始化校验：${profileTotal || node.profileCount || 0} 个口径已检查`
  }
  if (node.nodeName === 'source_data_extraction') {
    const count = node.repeatCount || 1
    return `抽取数据到真实库：${count}/${count} 个需抽取口径`
  }
  if (node.nodeName === 'real_snapshot_data_validation') {
    const total = node.repeatCount || 1
    const failed = node.failedCount || 0
    return `真实库快照校验：${total - failed}/${total} 个口径一致${failed ? `，${failed} 个失败` : ''}`
  }
  if (node.nodeName === 'batch_indicator' && profileTotal) {
    const counts = resultCounts(results)
    return `${profileTotal} 个口径已处理（成功 ${counts.success} / 无样本 ${counts.noSample} / 未实现 ${counts.skipped} / 失败 ${counts.failed}）`
  }
  return node.label
}

function nodeStatusText(node: ExecutionNode): string {
  if (node.nodeName === 'batch_indicator' && props.message.batchResults?.length) return '已处理'
  if (node.status === 'running') return '执行中'
  if (node.status === 'failed') return node.category === 'failure' ? '系统异常' : '有失败项'
  if (node.status === 'warning') return '需关注'
  return '已完成'
}

function handleToggle(event: Event) {
  const details = event.currentTarget as HTMLDetailsElement
  if (details.open && !props.message.executionNodes?.length) emit('restore', props.message)
}
</script>

<template>
  <details
    v-if="message.status === 'running' || message.executionNodes?.length || message.executionRef"
    class="execution-panel"
    :open="message.status === 'running'"
    @toggle="handleToggle"
  >
    <summary>
      <span class="execution-summary-title">
        <i :data-state="message.status" aria-hidden="true"></i>
        <strong>本次指标核算</strong>
      </span>
      <span>
        {{ message.status === 'running' ? message.stageLabel || '正在处理'
          : message.executionRestoreStatus === 'loading' ? '正在恢复运行证据'
            : message.executionRestoreStatus === 'expired' ? '运行证据已过期'
              : `${message.executionNodes?.length || 0} 个关键节点` }}
      </span>
    </summary>
    <div v-if="message.executionNodes?.length" class="execution-nodes">
      <button
        v-for="node in message.executionNodes"
        :key="node.id"
        type="button"
        class="execution-node"
        :class="{ 'is-initialization': node.nodeName === 'batch_data_initialization_validation' }"
        :data-category="node.category"
        :data-state="node.status"
        @click="emit('select', node)"
      >
        <b>{{ categoryMarks[node.category] }}</b>
        <span class="execution-node-copy">
          <small>{{ node.nodeName === 'batch_data_initialization_validation' ? '数据基础检查' : categoryLabels[node.category] }}</small>
          <strong>{{ nodeLabel(node) }}</strong>
          <em v-if="nodeMeta(node)">{{ nodeMeta(node) }}</em>
          <em v-if="node.status === 'failed' && node.errorMessage" class="execution-node-error">
            {{ node.errorCode ? `${node.errorCode} · ` : '' }}{{ node.errorMessage }}
          </em>
        </span>
        <span class="execution-node-status">
          <time v-if="node.durationMs !== undefined">{{ formatDuration(node.durationMs) }}</time>
          <small>{{ nodeStatusText(node) }}</small>
        </span>
        <span class="execution-node-arrow" aria-hidden="true">›</span>
      </button>
    </div>
    <p v-else-if="message.executionRestoreStatus === 'expired'" class="execution-empty">
      运行证据已过期，指标卡片仍保留；本次没有重新执行数据库查询。
    </p>
    <p v-else class="execution-empty">{{ message.executionRestoreStatus === 'loading' ? '正在读取已保存的批次与运行证据…' : '正在识别指标与任务类型…' }}</p>
  </details>
</template>
