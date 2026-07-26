<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { loadAgentRun } from '../api/agent'
import TraceFlowGraph from './TraceFlowGraph.vue'

type TraceNode = Record<string, unknown>

const props = defineProps<{ token: string; traceId: string }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const trace = ref<Record<string, unknown> | null>(null)
const typeFilter = ref('all')
const statusFilter = ref('all')
const viewMode = ref<'flow' | 'list'>('flow')
const selectedNode = ref<TraceNode | null>(null)

const nodes = computed(() => Array.isArray(trace.value?.nodes)
  ? trace.value.nodes as TraceNode[]
  : [])
const flowEdges = computed(() => Array.isArray(trace.value?.flow_edges)
  ? trace.value.flow_edges as Record<string, unknown>[]
  : [])
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
const evidence = computed(() => Array.isArray(trace.value?.evidence)
  ? trace.value.evidence as Record<string, unknown>[]
  : [])

const isPlannerNode = computed(() =>
  ['planner_llm', 'plan_replan'].includes(String(selectedNode.value?.node_name || '')),
)
const isDeterministicPlanNode = computed(() =>
  String(selectedNode.value?.node_name || '') === 'followup_plan_resolve',
)
const plannerInput = computed(() =>
  (selectedNode.value?.input_data || {}) as Record<string, unknown>,
)
const plannerOutput = computed(() =>
  (selectedNode.value?.output_data || {}) as Record<string, unknown>,
)
const selectedReadiness = computed(() =>
  (selectedNode.value?.capability_readiness || {}) as Record<string, unknown>,
)
const normalizedPlan = computed(() =>
  (plannerOutput.value.normalized_plan || plannerOutput.value.request_plan || {}) as Record<string, unknown>,
)
const compiledImpact = computed(() => {
  const compile = nodes.value.find((node) => String(node.node_name || '') === 'plan_compile')
  const output = (compile?.output_data || {}) as Record<string, unknown>
  return {
    intent: humanIntent(String(normalizedPlan.value.intent || plannerOutput.value.intent || '')),
    outputs: Array.isArray(normalizedPlan.value.requested_outputs)
      ? normalizedPlan.value.requested_outputs.map((item) => humanOutput(String(item)))
      : [],
    facts: Array.isArray(output.required_facts) ? output.required_facts : [],
    capabilities: Array.isArray(output.capabilities) ? output.capabilities : [],
  }
})

watch(() => props.traceId, async (traceId) => {
  if (!traceId) return
  loading.value = true
  error.value = ''
  selectedNode.value = null
  viewMode.value = 'flow'
  try {
    trace.value = await loadAgentRun(props.token, traceId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '链路加载失败。'
  } finally {
    loading.value = false
  }
}, { immediate: true })

function nodeTitle(node: TraceNode): string {
  return String(node.node_title || node.node_name || '未命名节点')
}

function barStyle(node: TraceNode) {
  const left = Math.max(0, Number(node.started_offset_ms || 0) / duration.value * 100)
  const width = Math.max(.8, Number(node.duration_ms || 0) / duration.value * 100)
  return { left: `${Math.min(99, left)}%`, width: `${Math.min(100 - left, width)}%` }
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function humanIntent(value: string): string {
  const labels: Record<string, string> = {
    RULE_EXPLANATION: '解释当前生效口径',
    INDICATOR_SQL_PREPARE: '展示指标概览 SQL',
    INDICATOR_TRIAL_RUN: '计算指标实际结果',
    INDICATOR_CALIBER_QUERY: '查询可用口径列表',
    INDICATOR_CALIBER_SIMULATION: '按指定候选口径试算',
    INDICATOR_DIAGNOSIS: '诊断指标异常',
    INDICATOR_DIFFERENCE_DIAGNOSIS: '诊断双方结果差异',
    rule_explanation: '解释当前生效口径',
    indicator_sql_prepare: '展示指标概览 SQL',
    indicator_trial_run: '计算指标实际结果',
    indicator_caliber_query: '查询可用口径列表',
    indicator_caliber_simulation: '按指定候选口径试算',
  }
  return labels[value] || value || '未提供'
}

function humanOutput(value: string): string {
  const labels: Record<string, string> = {
    DEFINITION: '指标定义',
    FORMULA: '计算公式',
    PREPARED_SQL_HANDLE: '概览 SQL',
    TRIAL_RESULT: '实际数值',
    CALIBER_OPTIONS: '候选口径列表',
    CALIBER_EXPLANATION: '候选口径说明',
    CALIBER_PREPARED_SQL_HANDLE: '候选口径 SQL',
    CALIBER_TRIAL_RESULT: '候选口径试算结果',
    DIAGNOSIS: '诊断结论',
    DIFFERENCE_DIAGNOSIS_REPORT: '差异诊断报告',
  }
  return labels[value] || value
}

function planValue(name: string): unknown {
  return normalizedPlan.value[name]
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
          <div class="trace-view-tabs">
            <button type="button" :class="{ active: viewMode === 'flow' }" @click="viewMode = 'flow'">流程图</button>
            <button type="button" :class="{ active: viewMode === 'list' }" @click="viewMode = 'list'">节点列表</button>
          </div>
          <label>节点类型<select v-model="typeFilter"><option value="all">全部</option><option value="llm">LLM</option><option value="code">代码</option><option value="tool">工具</option><option value="database">数据库</option><option value="storage">存储</option></select></label>
          <label>状态<select v-model="statusFilter"><option value="all">全部</option><option value="success">成功</option><option value="failed">失败</option></select></label>
          <span>{{ nodes.length }} 个节点 · {{ evidence.length }} 条 Evidence</span>
        </section>
        <section v-if="slowest.length" class="trace-slowest">
          <strong>最慢节点</strong>
          <button v-for="node in slowest" :key="`slow-${String(node.node_id)}`"
            type="button" @click="selectedNode = node; viewMode = 'flow'">
            {{ nodeTitle(node) }} {{ Number(node.duration_ms || 0) }}ms
          </button>
        </section>

        <section v-if="viewMode === 'flow'" class="trace-flow-layout">
          <div class="trace-flow-stage">
            <TraceFlowGraph
              :nodes="filteredNodes"
              :edges="flowEdges"
              :selected-node-id="String(selectedNode?.node_id || '')"
              @select="selectedNode = $event"
            />
            <p v-if="!filteredNodes.length" class="drawer-state">当前筛选下没有可展示的节点。</p>
          </div>
          <aside class="trace-inspector">
            <template v-if="selectedNode">
              <p class="eyebrow">{{ String(selectedNode.node_type || 'code') }} 节点</p>
              <h3>{{ nodeTitle(selectedNode) }}</h3>
              <code>{{ String(selectedNode.node_name || '') }}</code>
              <p>{{ String(selectedNode.processing_summary || '') }}</p>
              <dl class="inspector-facts">
                <div><dt>状态</dt><dd>{{ String(selectedNode.status || '-') }}</dd></div>
                <div><dt>耗时</dt><dd>{{ Number(selectedNode.duration_ms || 0) }}ms</dd></div>
                <div><dt>泳道</dt><dd>{{ String(selectedNode.subtask_id || 'root') }}</dd></div>
                <div><dt>模型 / 工具</dt><dd>{{ String(selectedNode.model_id || selectedNode.tool_name || '-') }}</dd></div>
              </dl>

              <section v-if="isDeterministicPlanNode" class="planner-readable">
                <h4>本轮未调用 LLM Planner</h4>
                <p>{{ String(plannerInput.planner_skip_reason || '指标和目标可由会话状态唯一确定。') }}</p>
                <div><strong>确定性意图</strong><span>{{ humanIntent(String(plannerOutput.intent || '')) }}</span></div>
                <div><strong>需要输出</strong><span>{{ pretty(plannerOutput.requested_outputs) }}</span></div>
              </section>

              <section v-if="isPlannerNode" class="planner-readable">
                <h4>Planner 计划字段</h4>
                <div><strong>intent · 想做什么</strong><span>{{ humanIntent(String(planValue('intent') || '')) }}</span></div>
                <div><strong>goal · 本轮目标</strong><span>{{ String(planValue('goal') || '未提供') }}</span></div>
                <div><strong>target_indicator · 指标</strong><pre>{{ pretty(planValue('target_indicator')) }}</pre></div>
                <div><strong>target_caliber · 替代口径</strong><pre>{{ pretty(planValue('target_caliber')) }}</pre></div>
                <div><strong>time_expression · 统计区间</strong><pre>{{ pretty(planValue('time_expression')) }}</pre></div>
                <div><strong>requested_outputs · 最终输出</strong><span>{{ compiledImpact.outputs.join('、') || '未提供' }}</span></div>
                <div><strong>constraints · 用户限制</strong><pre>{{ pretty(planValue('constraints')) }}</pre></div>
                <div><strong>semantic_ambiguities · 未决项</strong><pre>{{ pretty(planValue('semantic_ambiguities')) }}</pre></div>
                <div><strong>confidence · 意图置信度</strong><span>{{ String(planValue('confidence') ?? '未提供') }}</span></div>
                <div><strong>repaired · JSON 是否修复</strong><span>{{ plannerOutput.repaired ? '是' : '否' }}</span></div>
                <h4>计划影响</h4>
                <p class="plan-impact">
                  {{ compiledImpact.intent }} → {{ compiledImpact.outputs.join('、') || '无显式输出' }}
                  → {{ compiledImpact.facts.join('、') || '无目标事实' }}
                  → {{ compiledImpact.capabilities.join(' → ') || '尚未编译' }}
                </p>
              </section>

              <section v-if="Object.keys(selectedReadiness).length" class="readiness-grid">
                <h4>内部能力状态</h4>
                <div v-for="(value, key) in selectedReadiness"
                  :key="String(key)">
                  <span>{{ key }}</span>
                  <strong>{{ typeof value === 'boolean' ? (value ? '可用' : '不可用') : String(value) }}</strong>
                </div>
              </section>

              <details class="trace-data" :open="isPlannerNode || isDeterministicPlanNode">
                <summary>完整输入参数</summary>
                <pre>{{ pretty(selectedNode.input_data) }}</pre>
              </details>
              <details class="trace-data" :open="isPlannerNode || isDeterministicPlanNode">
                <summary>完整输出参数</summary>
                <pre>{{ pretty(selectedNode.output_data) }}</pre>
              </details>
              <p class="inspector-config">
                能力：{{ String(selectedNode.capability || '-') }}<br>
                FailureClass：{{ String(selectedNode.failure_class || '-') }}
              </p>
            </template>
            <div v-else class="inspector-empty">
              <strong>选择一个节点</strong>
              <p>点击流程图节点查看完整输入、输出、处理说明和配置。</p>
            </div>
          </aside>
        </section>

        <div v-else class="trace-list">
          <article v-for="(node, index) in filteredNodes" :key="String(node.node_id || index)"
            class="trace-node" :data-type="String(node.node_type || 'code')"
            :data-status="String(node.status || '')">
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
