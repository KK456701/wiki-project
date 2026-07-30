<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { loadAgentRun } from '../api/agent'
import type { ExecutionNode } from '../stores/agent'

type TraceNode = Record<string, unknown>

const props = defineProps<{ token: string; traceId: string; node: ExecutionNode }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const traceNode = ref<TraceNode | null>(null)

const inputData = computed(() => redact(traceNode.value?.inputData ?? {}))
const outputData = computed(() => redact(traceNode.value?.outputData ?? {}))
const hasTraceDetails = computed(() =>
  traceNode.value !== null && ('inputData' in traceNode.value || 'outputData' in traceNode.value),
)
const hasError = computed(() =>
  props.node.status === 'failed'
  || Boolean(traceNode.value?.errorCode)
  || Boolean(traceNode.value?.errorMessage),
)

watch(
  () => [props.traceId, props.node.id],
  async () => {
    loading.value = true
    error.value = ''
    traceNode.value = fallbackNode()
    try {
      const trace = await loadAgentRun(props.token, props.traceId)
      const nodes = Array.isArray(trace.nodes) ? trace.nodes as TraceNode[] : []
      const sameName = nodes.filter((node) =>
        String(node.nodeName || '') === props.node.nodeName
        && String(node.subtaskId || 'root') === (props.node.subtaskId || 'root'),
      )
      traceNode.value = sameName[props.node.occurrence]
        || nodes.find((node) => String(node.nodeName || '') === props.node.nodeName)
        || fallbackNode()
      if (!hasTraceDetails.value) error.value = '该节点的详细参数尚未写入，请稍后再试。'
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : '节点明细加载失败。'
      error.value = `详细入出参暂不可读取：${message}`
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function fallbackNode(): TraceNode {
  return {
    nodeName: props.node.nodeName,
    nodeType: props.node.nodeType,
    nodeTitle: props.node.label,
    status: props.node.status,
    durationMs: props.node.durationMs,
    toolName: props.node.toolName,
    modelId: props.node.modelId,
    capability: props.node.capability,
    subtaskId: props.node.subtaskId || 'root',
    processingSummary: '以下为运行时实时摘要；详细参数需由 Trace 接口返回。',
  }
}

function redact(value: unknown, key = ''): unknown {
  const lower = key.toLowerCase()
  const blocked = lower.includes('prompt')
    || lower.includes('authorization')
    || lower.includes('password')
    || lower.includes('secret')
    || lower.includes('patient')
    || ['token', 'access_token', 'refresh_token', 'messages', 'conversation_history',
      'raw_rows', 'rows'].includes(lower)
  if (blocked) return '[不在对话页展示]'
  if (Array.isArray(value)) {
    const items = value.slice(0, 50).map((item) => redact(item))
    if (value.length > 50) items.push(`…其余 ${value.length - 50} 项已收起`)
    return items
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([name, item]) => [name, redact(item, name)]),
    )
  }
  return value
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function formatDuration(value?: unknown): string {
  const duration = Number(value ?? props.node.durationMs ?? 0)
  return duration < 1000 ? `${duration} ms` : `${(duration / 1000).toFixed(2)} 秒`
}

function statusText(value?: unknown): string {
  const status = String(value || props.node.status)
  if (status === 'success') return '已完成'
  if (status === 'failed') return '失败'
  if (status === 'warning' || status === 'incomplete') return '需关注'
  return status
}
</script>

<template>
  <div class="drawer-backdrop node-detail-backdrop" @click.self="emit('close')">
    <aside class="node-detail-drawer" aria-label="节点明细">
      <header class="node-detail-head">
        <div>
          <p class="eyebrow">{{ node.category }} · Node detail</p>
          <h2>节点明细</h2>
          <span>{{ node.label }}</span>
        </div>
        <button class="icon-button" type="button" aria-label="关闭节点明细" @click="emit('close')">×</button>
      </header>

      <p v-if="loading" class="drawer-state">正在读取节点参数…</p>

      <div v-else-if="traceNode" class="node-detail-body">
        <p v-if="error" class="node-detail-warning">{{ error }}</p>
        <section class="node-detail-overview">
          <div><span>状态</span><strong :data-state="String(traceNode.status || node.status)">{{ statusText(traceNode.status) }}</strong></div>
          <div><span>耗时</span><strong>{{ formatDuration(traceNode.durationMs) }}</strong></div>
          <div><span>模型 / 工具</span><strong>{{ String(traceNode.modelId || traceNode.toolName || '—') }}</strong></div>
          <div><span>子任务</span><strong>{{ String(traceNode.subtaskId || 'root') }}</strong></div>
        </section>

        <section class="node-detail-section">
          <p class="eyebrow">Overview</p>
          <h3>处理概览</h3>
          <p>{{ String(traceNode.processingSummary || '该节点已完成受控处理。') }}</p>
          <dl>
            <div><dt>节点标识</dt><dd><code>{{ String(traceNode.nodeName || node.nodeName) }}</code></dd></div>
            <div><dt>节点 ID</dt><dd><code>{{ String(traceNode.nodeId || '—') }}</code></dd></div>
            <div><dt>能力</dt><dd>{{ String(traceNode.capability || node.capability || '—') }}</dd></div>
            <div><dt>数据 / 运行引用</dt><dd>{{ String(traceNode.sqlId || traceNode.runId || traceNode.dbSource || '—') }}</dd></div>
          </dl>
        </section>

        <section v-if="hasError" class="node-detail-section is-error">
          <p class="eyebrow">Exception</p>
          <h3>异常信息</h3>
          <dl>
            <div><dt>错误码</dt><dd>{{ String(traceNode.errorCode || '未提供') }}</dd></div>
            <div><dt>失败原因</dt><dd>{{ String(traceNode.errorMessage || '节点未通过验证，请检查输入条件。') }}</dd></div>
          </dl>
        </section>

        <details v-if="hasTraceDetails" class="node-params">
          <summary><strong>输入参数</strong><span>已过滤敏感字段</span></summary>
          <pre>{{ pretty(inputData) }}</pre>
        </details>
        <details v-if="hasTraceDetails" class="node-params">
          <summary><strong>输出参数</strong><span>已过滤敏感字段</span></summary>
          <pre>{{ pretty(outputData) }}</pre>
        </details>
        <p v-if="!hasTraceDetails" class="node-detail-note">实时摘要仍可查看；详细参数需要运行时 Trace 接口正常返回。</p>
        <p v-else class="node-detail-note">患者原文、鉴权信息和模型提示词不会在对话页展示；SQL及大段参数默认收起。</p>
      </div>
    </aside>
  </div>
</template>
