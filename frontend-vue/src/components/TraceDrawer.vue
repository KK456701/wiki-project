<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { loadAgentRun } from '../api/agent'

const props = defineProps<{ token: string; traceId: string }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const trace = ref<Record<string, unknown> | null>(null)

const nodes = computed(() => Array.isArray(trace.value?.nodes) ? trace.value.nodes as Record<string, unknown>[] : [])

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
      <div v-else class="trace-list">
        <article v-for="(node, index) in nodes" :key="String(node.node_id || index)" class="trace-node" :data-type="String(node.node_type || 'code')">
          <div class="node-sequence">{{ String(index + 1).padStart(2, '0') }}</div>
          <div>
            <div class="node-heading">
              <strong>{{ nodeTitle(node) }}</strong>
              <span>{{ Number(node.duration_ms || 0) }}ms</span>
            </div>
            <p>{{ String(node.processing_summary || node.node_name || '') }}</p>
            <small>{{ String(node.node_type || 'code') }} · {{ String(node.status || '-') }}</small>
          </div>
        </article>
        <p v-if="!nodes.length" class="drawer-state">这轮运行没有可展示的节点。</p>
      </div>
    </aside>
  </div>
</template>
