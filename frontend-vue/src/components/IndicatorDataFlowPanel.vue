<script setup lang="ts">
import { computed } from 'vue'

type FlowNode = Record<string, unknown>
type FlowEdge = Record<string, unknown>

const props = defineProps<{ flow?: unknown }>()

const value = computed<Record<string, unknown>>(() =>
  props.flow && typeof props.flow === 'object' && !Array.isArray(props.flow)
    ? props.flow as Record<string, unknown> : {},
)
const nodes = computed<FlowNode[]>(() => Array.isArray(value.value.nodes)
  ? value.value.nodes as FlowNode[] : [])
const edges = computed<FlowEdge[]>(() => Array.isArray(value.value.edges)
  ? value.value.edges as FlowEdge[] : [])
const warnings = computed<string[]>(() => Array.isArray(value.value.warnings)
  ? value.value.warnings.map(String) : [])

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}

function databaseLabel(value: unknown): string {
  const role = String(value || '')
  if (role === 'BUSINESS') return '业务库'
  if (role === 'REAL') return '真实库'
  if (role === 'SYNC') return '同步任务'
  if (role === 'KNOWLEDGE') return '知识库'
  return role || '—'
}

function incomingLabel(nodeId: unknown): string {
  return edges.value
    .filter((edge) => String(edge.to || '') === String(nodeId || ''))
    .map((edge) => String(edge.label || ''))
    .filter(Boolean)
    .join(' / ')
}

function descriptions(node: FlowNode): Record<string, string> {
  return node.tableDescriptions && typeof node.tableDescriptions === 'object'
    ? node.tableDescriptions as Record<string, string> : {}
}

function isTableNode(node: FlowNode): boolean {
  return String(node.nodeType || '') === 'TABLE'
}

function inputTables(node: FlowNode): Array<{ table: string; role: 'data' | 'parameter' }> {
  const parameters = new Set(strings(node.parameterTables))
  return strings(node.tableNames).map((table) => ({
    table,
    role: parameters.has(table) ? 'parameter' : 'data',
  }))
}

function sqlSummary(node: FlowNode): string {
  if (String(node.nodeType || '') === 'SOURCE_EXTRACT_SQL') return '查看可直接执行的源表查询脚本'
  return `查看可直接执行的${String(node.title || '节点').replace(/\s*SQL$/i, '')} SQL`
}
</script>

<template>
  <section class="indicator-flow-shell" :data-status="String(value.status || '')">
    <header class="indicator-flow-head">
      <div>
        <span>数据流向</span>
        <strong>{{ String(value.templateLabel || '链路尚未生成') }}</strong>
      </div>
      <em>{{ value.status === 'complete' ? '链路完整' : '配置不完整' }}</em>
    </header>

    <div v-if="warnings.length" class="indicator-flow-warnings">
      <p v-for="warning in warnings" :key="warning">{{ warning }}</p>
    </div>

    <ol v-if="nodes.length" class="indicator-flow-list">
      <li
        v-for="node in nodes"
        :key="String(node.id)"
        class="indicator-flow-node"
        :data-node-role="String(node.id) === 'statistic-parameters' ? 'parameter' : 'process'"
      >
        <span v-if="incomingLabel(node.id)" class="indicator-flow-edge">
          {{ incomingLabel(node.id) }}
        </span>
        <article>
          <header>
            <b>{{ String(node.sequence || '').padStart(2, '0') }}</b>
            <div>
              <strong>{{ String(node.title || '未命名节点') }}</strong>
              <small>{{ String(node.description || '') }}</small>
            </div>
            <em>{{ databaseLabel(node.databaseRole) }}</em>
          </header>

          <div v-if="isTableNode(node) && strings(node.tableNames).length" class="indicator-flow-tables">
            <code v-for="table in strings(node.tableNames)" :key="table">
              <strong>{{ table }}</strong>
              <small v-if="descriptions(node)[table]">{{ descriptions(node)[table] }}</small>
            </code>
          </div>
          <div v-else-if="inputTables(node).length" class="indicator-flow-inputs">
            <span>本节点实际输入</span>
            <code
              v-for="input in inputTables(node)"
              :key="`${String(node.id)}-${input.table}`"
              :data-role="input.role"
            >
              <small>{{ input.role === 'parameter' ? '统计参数' : '统计数据' }}</small>
              <strong>{{ input.table }}</strong>
              <em v-if="descriptions(node)[input.table]">{{ descriptions(node)[input.table] }}</em>
            </code>
          </div>

          <details v-if="String(node.sql || '').trim()" class="indicator-flow-sql">
            <summary>{{ sqlSummary(node) }}</summary>
            <p v-if="node.sqlExecutable" class="indicator-flow-sql-help">
              可直接复制到 Navicat 的 SQL Server 查询窗口执行。数据库、schema 和本次统计时间已写进脚本；脚本只复现查询，不执行系统清表和同步写入。
            </p>
            <div v-if="node.sqlExecutable" class="indicator-flow-sql-target">
              <span>数据库：<code>{{ String(node.databaseName || '—') }}</code></span>
              <span>schema：<code>{{ String(node.schemaName || '—') }}</code></span>
            </div>
            <div v-if="strings(node.parameters).length" class="indicator-flow-params">
              脚本变量：<code v-for="parameter in strings(node.parameters)" :key="parameter">@{{ parameter }}</code>
            </div>
            <pre>{{ String(node.sql) }}</pre>
          </details>
        </article>
      </li>
    </ol>
    <p v-else class="indicator-flow-empty">当前口径没有可安全展示的数据链路。</p>
  </section>
</template>
