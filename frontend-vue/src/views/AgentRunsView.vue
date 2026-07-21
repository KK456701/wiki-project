<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import TraceDrawer from '../components/TraceDrawer.vue'
import { loadAgentRunMetrics, loadAgentRuns, type AgentRunMetrics, type AgentRunSummary } from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const runs = ref<AgentRunSummary[]>([])
const metrics = ref<AgentRunMetrics | null>(null)
const selectedTraceId = ref('')
const status = ref('')
const modelId = ref('')
const maxTrend = computed(() => Math.max(1, ...(metrics.value?.trend || []).map((item) => item.requests)))

onMounted(async () => {
  if (!store.isAuthenticated) {
    await router.replace('/')
    return
  }
  if (!store.capabilities) {
    try { await store.refreshCapabilities() } catch { /* 运行数据仍可独立查看。 */ }
  }
  await refresh()
})

async function refresh() {
  loading.value = true
  error.value = ''
  const filters: Record<string, string> = {}
  if (status.value) filters.status = status.value
  if (modelId.value) filters.model_id = modelId.value
  try {
    const [runResult, metricResult] = await Promise.all([
      loadAgentRuns(store.token, { ...filters, limit: '100' }),
      loadAgentRunMetrics(store.token, filters),
    ])
    runs.value = runResult.items
    metrics.value = metricResult
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '运行观察数据加载失败。'
  } finally {
    loading.value = false
  }
}

function rate(value?: number) {
  return `${((value || 0) * 100).toFixed(1)}%`
}

function duration(value?: number) {
  const milliseconds = Number(value || 0)
  return milliseconds >= 1000 ? `${(milliseconds / 1000).toFixed(2)}s` : `${milliseconds}ms`
}
</script>

<template>
  <main class="runs-shell">
    <header class="runs-head">
      <div>
        <p class="eyebrow">Agent Runtime Observatory</p>
        <h1>Agent 运行观察</h1>
        <p>直接读取当前医院的 Trace 汇总，不包含完整上下文、SQL 正文或患者数据。</p>
      </div>
      <div class="runs-head-actions">
        <RouterLink class="quiet-button" to="/">返回对话</RouterLink>
        <RouterLink class="quiet-button" to="/metadata">数据库元数据</RouterLink>
        <RouterLink class="quiet-button" to="/terminology">医学术语</RouterLink>
        <RouterLink class="quiet-button" to="/monitoring">指标监控</RouterLink>
        <button class="primary-button" type="button" :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新数据' }}</button>
      </div>
    </header>

    <section class="runs-filters">
      <label>运行状态<select v-model="status" @change="refresh"><option value="">全部</option><option value="success">成功</option><option value="incomplete">未完成</option><option value="failed">失败</option></select></label>
      <label>模型<select v-model="modelId" @change="refresh"><option value="">全部模型</option><option v-for="model in store.capabilities?.models || []" :key="model.id" :value="model.id">{{ model.name }}</option></select></label>
      <span>医院 {{ store.user?.hospitalId }}</span>
    </section>

    <p v-if="error" class="runs-error">{{ error }}</p>
    <template v-if="metrics">
      <section class="metric-grid">
        <article><span>请求量</span><strong>{{ metrics.request_count }}</strong><small>当前筛选窗口</small></article>
        <article><span>成功率</span><strong>{{ rate(metrics.success_rate) }}</strong><small>未完成 {{ rate(metrics.incomplete_rate) }}</small></article>
        <article><span>p95 耗时</span><strong>{{ duration(metrics.latency_ms.p95) }}</strong><small>平均 {{ duration(metrics.latency_ms.average) }}</small></article>
        <article><span>复合请求</span><strong>{{ metrics.compound_request_count }}</strong><small>平均 {{ duration(metrics.compound_average_duration_ms) }}</small></article>
      </section>

      <section v-if="metrics.warnings.length" class="runtime-warnings">
        <article v-for="warning in metrics.warnings" :key="warning.code"><strong>{{ warning.code }}</strong><span>{{ warning.message }}</span></article>
      </section>

      <section class="runtime-panels">
        <article class="runtime-panel">
          <header><h2>请求趋势</h2><span>按日</span></header>
          <div class="trend-chart">
            <div v-for="item in metrics.trend" :key="item.date" class="trend-column">
              <i :style="{ height: `${Math.max(5, item.requests / maxTrend * 100)}%` }"></i>
              <strong>{{ item.requests }}</strong><small>{{ item.date.slice(5) }}</small>
            </div>
            <p v-if="!metrics.trend.length">暂无趋势数据</p>
          </div>
        </article>
        <article class="runtime-panel">
          <header><h2>模型表现</h2><span>调用 / 超时 / 耗时</span></header>
          <div class="runtime-ranking">
            <div v-for="model in metrics.models" :key="model.model_id"><strong>{{ model.model_id }}</strong><span>{{ model.calls }} 次 · {{ model.timeouts }} 超时 · {{ duration(model.duration_ms) }}</span></div>
            <p v-if="!metrics.models.length">当前窗口没有模型调用</p>
          </div>
        </article>
        <article class="runtime-panel">
          <header><h2>工具表现</h2><span>调用 / 失败 / 耗时</span></header>
          <div class="runtime-ranking">
            <div v-for="tool in metrics.tools" :key="tool.tool_name"><strong>{{ tool.tool_name }}</strong><span>{{ tool.calls }} 次 · {{ tool.failures }} 失败 · {{ duration(tool.duration_ms) }}</span></div>
            <p v-if="!metrics.tools.length">当前窗口没有工具调用</p>
          </div>
        </article>
        <article class="runtime-panel">
          <header><h2>稳定性边界</h2><span>确定性停止</span></header>
          <div class="stability-list">
            <p><span>重复调用停止率</span><strong>{{ rate(metrics.repeated_call_stop_rate) }}</strong></p>
            <p><span>Replan 率</span><strong>{{ rate(metrics.replan_rate) }}</strong></p>
            <p><span>p50 / p99</span><strong>{{ duration(metrics.latency_ms.p50) }} / {{ duration(metrics.latency_ms.p99) }}</strong></p>
          </div>
        </article>
      </section>
    </template>

    <section class="run-table-panel">
      <header><h2>最近运行</h2><span>{{ runs.length }} 条</span></header>
      <div class="run-table-wrap">
        <table>
          <thead><tr><th>开始时间</th><th>Trace</th><th>意图</th><th>状态</th><th>耗时</th><th>错误</th><th></th></tr></thead>
          <tbody>
            <tr v-for="run in runs" :key="run.trace_id">
              <td>{{ String(run.started_at || '-') }}</td><td><code>{{ run.trace_id }}</code></td><td>{{ run.intent || '-' }}</td>
              <td><span class="run-status" :data-status="run.final_status">{{ run.final_status || '-' }}</span></td>
              <td>{{ duration(run.duration_ms) }}</td><td>{{ run.error_count || 0 }}</td>
              <td><button type="button" @click="selectedTraceId = run.trace_id">查看链路</button></td>
            </tr>
            <tr v-if="!runs.length"><td colspan="7">当前筛选下暂无运行记录。</td></tr>
          </tbody>
        </table>
      </div>
    </section>
    <TraceDrawer v-if="selectedTraceId" :token="store.token" :trace-id="selectedTraceId" @close="selectedTraceId = ''" />
  </main>
</template>
