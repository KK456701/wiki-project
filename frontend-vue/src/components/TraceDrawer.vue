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
const selectedNode = ref<TraceNode | null>(null)

const nodes = computed(() => Array.isArray(trace.value?.nodes)
  ? trace.value.nodes as TraceNode[]
  : [])
const flowEdges = computed(() => Array.isArray(trace.value?.flowEdges)
  ? trace.value.flowEdges as Record<string, unknown>[]
  : [])
const filteredNodes = computed(() => nodes.value.filter((node) =>
  (typeFilter.value === 'all' || String(node.nodeType || 'code') === typeFilter.value)
  && (statusFilter.value === 'all' || String(node.status || '') === statusFilter.value),
))
const timing = computed(() => (trace.value?.timingSummary || {}) as Record<string, number>)
const slowest = computed(() => [...nodes.value].sort((left, right) =>
  Number(right.durationMs || 0) - Number(left.durationMs || 0),
).slice(0, 3))
const evidence = computed(() => Array.isArray(trace.value?.evidence)
  ? trace.value.evidence as Record<string, unknown>[]
  : [])

const isPlannerNode = computed(() =>
  ['planner_llm', 'plan_replan'].includes(String(selectedNode.value?.nodeName || '')),
)
const isDeterministicPlanNode = computed(() =>
  String(selectedNode.value?.nodeName || '') === 'followup_plan_resolve',
)
const plannerInput = computed(() =>
  (selectedNode.value?.inputData || {}) as Record<string, unknown>,
)
const plannerOutput = computed(() =>
  (selectedNode.value?.outputData || {}) as Record<string, unknown>,
)
const selectedReadiness = computed(() =>
  (selectedNode.value?.capabilityReadiness || {}) as Record<string, unknown>,
)
const normalizedPlan = computed(() =>
  (plannerOutput.value.normalizedPlan || plannerOutput.value.requestPlan || {}) as Record<string, unknown>,
)
const compiledImpact = computed(() => {
  const compile = nodes.value.find((node) => String(node.nodeName || '') === 'plan_compile')
  const output = (compile?.outputData || {}) as Record<string, unknown>
  return {
    intent: humanIntent(String(normalizedPlan.value.intent || plannerOutput.value.intent || '')),
    outputs: Array.isArray(normalizedPlan.value.requestedOutputs)
      ? normalizedPlan.value.requestedOutputs.map((item) => humanOutput(String(item)))
      : [],
    facts: Array.isArray(output.requiredFacts) ? output.requiredFacts : [],
    capabilities: Array.isArray(output.capabilities) ? output.capabilities : [],
  }
})

watch(() => props.traceId, async (traceId) => {
  if (!traceId) return
  loading.value = true
  error.value = ''
  selectedNode.value = null
  try {
    trace.value = await loadAgentRun(props.token, traceId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '链路加载失败。'
  } finally {
    loading.value = false
  }
}, { immediate: true })

function nodeTitle(node: TraceNode): string {
  return String(node.nodeTitle || node.nodeName || '未命名节点')
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

function humanFocus(value: string): string {
  const labels: Record<string, string> = {
    OVERVIEW: '完整口径',
    DEFINITION: '指标定义',
    FORMULA: '计算公式',
    NUMERATOR: '分子口径',
    DENOMINATOR: '分母口径',
    TIME_DIMENSION: '统计时间',
    DEDUPLICATION: '去重规则',
    EXCLUSIONS: '排除条件',
    VERSION_SCOPE: '版本与适用范围',
  }
  return labels[value] || value
}

function focusLabels(value: unknown): string {
  return Array.isArray(value)
    ? value.map((item) => humanFocus(String(item))).join('、')
    : '完整口径'
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
          <article><span>总耗时</span><strong>{{ Number(trace?.durationMs || 0) }}ms</strong></article>
          <article><span>LLM</span><strong>{{ Number(timing.llmMs || 0) }}ms</strong></article>
          <article><span>工具 ／ 数据库</span><strong>{{ Number(timing.toolMs || 0) }}ms</strong></article>
          <article><span>代码 ／ 存储</span><strong>{{ Number(timing.codeMs || 0) + Number(timing.storageMs || 0) }}ms</strong></article>
        </section>
        <section class="trace-toolbar">
          <label>节点类型<select v-model="typeFilter"><option value="all">全部</option><option value="llm">LLM</option><option value="code">代码</option><option value="tool">工具</option><option value="database">数据库</option><option value="storage">存储</option></select></label>
          <label>状态<select v-model="statusFilter"><option value="all">全部</option><option value="success">成功</option><option value="failed">失败</option></select></label>
          <span>{{ nodes.length }} 个节点 · {{ evidence.length }} 条 Evidence</span>
        </section>
        <section v-if="slowest.length" class="trace-slowest">
          <strong>最慢节点</strong>
          <button v-for="node in slowest" :key="`slow-${String(node.nodeId)}`"
            type="button" @click="selectedNode = node">
            {{ nodeTitle(node) }} {{ Number(node.durationMs || 0) }}ms
          </button>
        </section>

        <section class="trace-flow-layout">
          <div class="trace-flow-stage">
            <TraceFlowGraph
              :nodes="filteredNodes"
              :edges="flowEdges"
              :selected-node-id="String(selectedNode?.nodeId || '')"
              @select="selectedNode = $event"
            />
            <p v-if="!filteredNodes.length" class="drawer-state">当前筛选下没有可展示的节点。</p>
          </div>
          <aside class="trace-inspector">
            <template v-if="selectedNode">
              <p class="eyebrow">{{ String(selectedNode.nodeType || 'code') }} 节点</p>
              <h3>{{ nodeTitle(selectedNode) }}</h3>
              <code>{{ String(selectedNode.nodeName || '') }}</code>
              <p>{{ String(selectedNode.processingSummary || '') }}</p>
              <dl class="inspector-facts">
                <div><dt>状态</dt><dd>{{ String(selectedNode.status || '-') }}</dd></div>
                <div><dt>耗时</dt><dd>{{ Number(selectedNode.durationMs || 0) }}ms</dd></div>
                <div><dt>泳道</dt><dd>{{ String(selectedNode.subtaskId || 'root') }}</dd></div>
                <div><dt>模型 ／ 工具</dt><dd>{{ String(selectedNode.modelId || selectedNode.toolName || '-') }}</dd></div>
              </dl>

              <section v-if="isDeterministicPlanNode" class="planner-readable">
                <h4>本轮未调用 LLM Planner</h4>
                <p>{{ String(plannerInput.plannerSkipReason || '指标和目标可由会话状态唯一确定。') }}</p>
                <div><strong>确定性意图</strong><span>{{ humanIntent(String(plannerOutput.intent || '')) }}</span></div>
                <div><strong>需要输出</strong><span>{{ pretty(plannerOutput.requestedOutputs) }}</span></div>
                <div><strong>回答关注点</strong><span>{{ focusLabels(plannerOutput.explanationFocuses) }}</span></div>
              </section>

              <section v-if="isPlannerNode" class="planner-readable">
                <h4>Planner 计划字段</h4>
                <div><strong>intent · 想做什么</strong><span>{{ humanIntent(String(planValue('intent') || '')) }}</span></div>
                <div><strong>goal · 本轮目标</strong><span>{{ String(planValue('goal') || '未提供') }}</span></div>
                <div><strong>target_indicator · 指标</strong><pre>{{ pretty(planValue('targetIndicator')) }}</pre></div>
                <div><strong>target_caliber · 替代口径</strong><pre>{{ pretty(planValue('targetCaliber')) }}</pre></div>
                <div><strong>time_expression · 统计区间</strong><pre>{{ pretty(planValue('timeExpression')) }}</pre></div>
                <div><strong>requested_outputs · 最终输出</strong><span>{{ compiledImpact.outputs.join('、') || '未提供' }}</span></div>
                <div><strong>explanation_focuses · 规则解释关注点</strong><span>{{ focusLabels(planValue('explanationFocuses')) }}</span></div>
                <div><strong>constraints · 用户限制</strong><pre>{{ pretty(planValue('constraints')) }}</pre></div>
                <div><strong>semantic_ambiguities · 未决项</strong><pre>{{ pretty(planValue('semanticAmbiguities')) }}</pre></div>
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
                <pre>{{ pretty(selectedNode.inputData) }}</pre>
              </details>
              <details class="trace-data" :open="isPlannerNode || isDeterministicPlanNode">
                <summary>完整输出参数</summary>
                <pre>{{ pretty(selectedNode.outputData) }}</pre>
              </details>
              <p class="inspector-config">
                能力：{{ String(selectedNode.capability || '-') }}<br>
                FailureClass：{{ String(selectedNode.failureClass || '-') }}
              </p>
            </template>
            <div v-else class="inspector-empty">
              <strong>选择一个节点</strong>
              <p>点击流程图节点查看完整输入、输出、处理说明和配置。</p>
            </div>
          </aside>
        </section>
        <details v-if="evidence.length" class="trace-evidence">
          <summary>Evidence 来源（{{ evidence.length }}）</summary>
          <pre>{{ pretty(evidence) }}</pre>
        </details>
      </div>
    </aside>
  </div>
</template>
