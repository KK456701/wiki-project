<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { loadAgentRun } from '../api/agent'

const props = defineProps<{ token: string; traceId: string }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const trace = ref<Record<string, unknown> | null>(null)
const typeFilter = ref('all')
const statusFilter = ref('all')

const nodes = computed(() => Array.isArray(trace.value?.nodes) ? trace.value.nodes as Record<string, unknown>[] : [])
const filteredNodes = computed(() => nodes.value.filter((node) =>
  (typeFilter.value === 'all' || String(node.node_type || 'code') === typeFilter.value)
  && (statusFilter.value === 'all' || String(node.status || '') === statusFilter.value),
))
const duration = computed(() => Math.max(1, Number(trace.value?.duration_ms || 0), ...nodes.value.map((node) =>
  Number(node.started_offset_ms || 0) + Number(node.duration_ms || 0),
)))
const timing = computed(() => (trace.value?.timing_summary || {}) as Record<string, number>)
const slowest = computed(() => [...nodes.value].sort((left, right) =>
  Number(right.duration_ms || 0) - Number(left.duration_ms || 0),
).slice(0, 3))
const evidence = computed(() => Array.isArray(trace.value?.evidence) ? trace.value.evidence as Record<string, unknown>[] : [])

watch(() => props.traceId, async (traceId) => {
  if (!traceId) return
  loading.value = true
  error.value = ''
  try {
    trace.value = await loadAgentRun(props.token, traceId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '链路加载失败。'
  } finally {
    loading.value = false
  }
}, { immediate: true })

function nodeTitle(node: Record<string, unknown>): string {
  return String(node.node_title || node.node_name || '未命名节点')
}

function barStyle(node: Record<string, unknown>) {
  const left = Math.max(0, Number(node.started_offset_ms || 0) / duration.value * 100)
  const width = Math.max(.8, Number(node.duration_ms || 0) / duration.value * 100)
  return { left: `${Math.min(99, left)}%`, width: `${Math.min(100 - left, width)}%` }
}

function pretty(value: unknown): string {
  return JSON.stringify(value || {}, null, 2)
}
</script>

<template>
  <div class="drawer-backdrop" @click.self="emit('close')">
    <aside class="trace-drawer" aria-label="运行链路">
      <header>
        <div>
          <p class="eyebrow">本轮计算依据</p>
          <h2>运行链路</h2>
          <code>{{ traceId }}</code>
        </div>
        <button class="icon-button" type="button" aria-label="关闭链路" @click="emit('close')">×</button>
      </header>
      <p v-if="loading" class="drawer-state">正在读取节点…</p>
      <p v-else-if="error" class="drawer-state is-error">{{ error }}</p>
      <div v-else>
        <section class="trace-overview">
          <article><span>总耗时</span><strong>{{ Number(trace?.duration_ms || 0) }}ms</strong></article>
          <article><span>LLM</span><strong>{{ Number(timing.llm_ms || 0) }}ms</strong></article>
          <article><span>工具 / 数据库</span><strong>{{ Number(timing.tool_ms || 0) }}ms</strong></article>
          <article><span>代码 / 存储</span><strong>{{ Number(timing.code_ms || 0) + Number(timing.storage_ms || 0) }}ms</strong></article>
        </section>
        <section class="trace-toolbar">
          <label>节点类型<select v-model="typeFilter"><option value="all">全部</option><option value="llm">LLM</option><option value="code">代码</option><option value="tool">工具</option><option value="storage">存储</option></select></label>
          <label>状态<select v-model="statusFilter"><option value="all">全部</option><option value="success">成功</option><option value="failed">失败</option></select></label>
          <span>{{ nodes.length }} 个节点 · {{ evidence.length }} 条 Evidence</span>
        </section>
        <section v-if="slowest.length" class="trace-slowest">
          <strong>最慢节点</strong>
          <span v-for="node in slowest" :key="`slow-${String(node.node_id)}`">{{ nodeTitle(node) }} {{ Number(node.duration_ms || 0) }}ms</span>
        </section>
        <div class="trace-list">
        <article v-for="(node, index) in filteredNodes" :key="String(node.node_id || index)" class="trace-node" :data-type="String(node.node_type || 'code')" :data-status="String(node.status || '')">
          <div class="node-sequence">{{ String(index + 1).padStart(2, '0') }}</div>
          <div>
            <div class="node-heading">
              <strong>{{ nodeTitle(node) }} <code>{{ String(node.node_name || '') }}</code></strong>
              <span>{{ Number(node.duration_ms || 0) }}ms</span>
            </div>
            <p>{{ String(node.processing_summary || node.node_name || '') }}</p>
            <div class="trace-waterfall"><i :style="barStyle(node)"></i></div>
            <small>{{ String(node.node_type || 'code') }} · {{ String(node.status || '-') }} · 泳道 {{ String(node.subtask_id || 'root') }} · +{{ Number(node.started_offset_ms || 0) }}ms</small>
            <details class="trace-data">
              <summary>输入、输出与节点配置</summary>
              <div><strong>输入参数</strong><pre>{{ pretty(node.input_data) }}</pre></div>
              <div><strong>输出参数</strong><pre>{{ pretty(node.output_data) }}</pre></div>
              <p>能力：{{ String(node.capability || '-') }} · 工具：{{ String(node.tool_name || '-') }} · 模型：{{ String(node.model_id || node.llm_model || '-') }} · FailureClass：{{ String(node.failure_class || '-') }}</p>
            </details>
          </div>
        </article>
        <p v-if="!filteredNodes.length" class="drawer-state">当前筛选下没有可展示的节点。</p>
        </div>
        <details v-if="evidence.length" class="trace-evidence">
          <summary>Evidence 来源（{{ evidence.length }}）</summary>
          <pre>{{ pretty(evidence) }}</pre>
        </details>
      </div>
    </aside>
  </div>
</template>
