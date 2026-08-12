<script setup lang="ts">
import { computed, ref } from 'vue'

type SelectedRow = { rowKey: string; label: string; recordId: string; sourceGroup: string }
type DepartmentOption = { field: string; value: string; label: string; denominatorCount: number; numeratorCount: number }

const props = defineProps<{
  selectedRows: SelectedRow[]
  departmentOptions: DepartmentOption[]
  selectedDepartments: string[]
  underTargetType: 'RECORD' | 'DEPARTMENT'
  underRecordIds: string
  underDepartments: string[]
  underDepartmentManual: string
  overNote: string
  underNote: string
  busy?: boolean
  completed?: boolean
  overClarification?: Record<string, unknown>
  underClarification?: Record<string, unknown>
  clarifyingDirection?: '' | 'OVER_INCLUDED' | 'UNDER_INCLUDED' | 'ALL'
}>()

const emit = defineEmits<{
  'update:overNote': [value: string]
  'update:underNote': [value: string]
  'update:selectedDepartments': [value: string[]]
  'update:underTargetType': [value: 'RECORD' | 'DEPARTMENT']
  'update:underRecordIds': [value: string]
  'update:underDepartments': [value: string[]]
  'update:underDepartmentManual': [value: string]
  removeSelection: [rowKey: string]
  removeSelections: [rowKeys: string[]]
  clearSelection: []
  clarify: []
  proceed: []
  finish: []
}>()

const departmentSearch = ref('')
const markedRows = ref(new Set<string>())
const filteredDepartments = computed(() => filterDepartments(departmentSearch.value))
const hasOverIssue = computed(() => props.selectedRows.length > 0
  || props.selectedDepartments.length > 0 || props.overNote.trim().length > 0)
const hasUnderIssue = computed(() => props.underNote.trim().length > 0)
const hasIssue = computed(() => hasOverIssue.value || hasUnderIssue.value)

function filterDepartments(search: string) {
  const keyword = search.trim().toLowerCase()
  return keyword
    ? props.departmentOptions.filter((item) => (item.label + ' ' + item.value).toLowerCase().includes(keyword))
    : props.departmentOptions
}
function explanation(value?: Record<string, unknown>) {
  return value ? String(value.naturalLanguageExplanation || value.summary || '') : ''
}
function evidenceRows(value?: Record<string, unknown>): Record<string, unknown>[] {
  if (!value) return []
  if (Array.isArray(value.numeratorEvidenceRows)) return value.numeratorEvidenceRows as Record<string, unknown>[]
  if (!Array.isArray(value.targetResults)) return []
  return value.targetResults.flatMap((item) => {
    const target = item && typeof item === 'object' ? item as Record<string, unknown> : {}
    return Array.isArray(target.numeratorEvidenceRows)
      ? target.numeratorEvidenceRows as Record<string, unknown>[] : []
  })
}
function evidenceDate(value: unknown): Date | null {
  const numeric = Number(value)
  if (Number.isFinite(numeric) && numeric > 1_000_000_000_000) return new Date(numeric)
  const parsed = new Date(String(value || ''))
  return Number.isNaN(parsed.getTime()) ? null : parsed
}
function evidenceTime(value: unknown): string {
  const date = evidenceDate(value)
  return date ? date.toLocaleString('zh-CN', { hour12: false }) : String(value || '—')
}
function intervalText(row: Record<string, unknown>): string {
  const admitted = evidenceDate(row['入区时间'])
  const transferred = evidenceDate(row['转科时间'])
  if (!admitted || !transferred) return String(row['转科时间-入院时间'] ?? '—')
  const seconds = Math.max(0, Math.floor((transferred.getTime() - admitted.getTime()) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainder = seconds % 60
  const duration = hours ? `${hours}小时${minutes ? `${minutes}分钟` : ''}`
    : `${minutes}分钟${remainder ? `${remainder}秒` : ''}`
  return `${duration}（${seconds < 48 * 3600 ? '小于' : '不少于'}48小时）`
}
function evidenceItems(row: Record<string, unknown>) {
  const items = [
    { label: '患者姓名', value: row['患者姓名'] }, { label: '就诊号', value: row['就诊号'] },
    { label: '住院号', value: row['住院号'] }, { label: '入区时间', value: evidenceTime(row['入区时间']) },
    { label: '转科时间', value: evidenceTime(row['转科时间']) },
    { label: '入区至转科间隔', value: intervalText(row) }, { label: '流转类型', value: row['转科类型'] },
    { label: '转出科室', value: row['转出科室'] }, { label: '转入科室', value: row['转入科室'] },
    { label: '48小时内转科判定', value: row['是否48小时内转科'] },
  ]
  return items.filter((item) => item.value !== undefined && item.value !== null && String(item.value) !== '')
}
function updateDepartments(value: string, selected: string[], type: 'over' | 'under') {
  const next = selected.includes(value) ? selected.filter((item) => item !== value) : [...selected, value]
  if (type === 'over' && !selected.includes(value)) emit('clearSelection')
  type === 'over' ? emit('update:selectedDepartments', next) : emit('update:underDepartments', next)
}
function department(value: string) {
  return props.departmentOptions.find((item) => item.value === value)
}
function toggleMarked(rowKey: string) {
  const next = new Set(markedRows.value)
  next.has(rowKey) ? next.delete(rowKey) : next.add(rowKey)
  markedRows.value = next
}
function removeMarked() {
  emit('removeSelections', [...markedRows.value])
  markedRows.value = new Set()
}
</script>

<template>
  <section class="data-clarification-editor">
    <header class="clarification-heading">
      <span class="clarification-icon" aria-hidden="true">✓</span>
      <div><strong>数据澄清</strong><p>澄清只核对统计分子：先确认对象是否进入分子明细，再用该指标的具体业务数据和分子口径解释为什么计入或没有计入。</p></div>
    </header>
    <div class="clarification-grid">
      <article class="clarification-kind is-over">
        <header><span>数据多了</span><strong>选择不应被统计的患者或科室</strong></header>
        <p>患者和科室二选一：选择患者时按具体就诊记录排除；选择科室时按整个科室范围排除。</p>
        <details class="selected-scope-panel selected-scope-details">
          <summary><span><strong>已选择范围</strong><small>患者 {{ selectedRows.length }} 位 · 科室 {{ selectedDepartments.length }} 个</small></span><em>展开管理</em></summary>
          <header><strong>患者范围</strong><div><button v-if="markedRows.size" type="button" @click="removeMarked">删除所选</button><button v-if="selectedRows.length" type="button" @click="emit('clearSelection')">清空患者</button></div></header>
          <ul v-if="selectedRows.length" class="selected-patient-list">
            <li v-for="row in selectedRows" :key="row.rowKey">
              <input type="checkbox" :checked="markedRows.has(row.rowKey)" @change="toggleMarked(row.rowKey)" />
              <span><strong>{{ row.label }}</strong><small>{{ row.recordId }} · {{ row.sourceGroup === 'numerator' ? '来自分子明细' : row.sourceGroup === 'denominator' ? '来自分母明细' : '来自AI初筛' }}</small></span>
              <button type="button" aria-label="删除患者" @click="emit('removeSelection', row.rowKey)">×</button>
            </li>
          </ul>
          <p v-else class="scope-empty">还没有选择患者，可从上方明细或 AI 初筛中添加。</p>
          <div class="department-picker">
            <label><span>排除科室（可多选）</span><input v-model="departmentSearch" type="search" placeholder="搜索科室名称或编码" /></label>
            <div class="department-option-list">
              <label v-for="item in filteredDepartments" :key="item.value">
                <input type="checkbox" :checked="selectedDepartments.includes(item.value)" @change="updateDepartments(item.value, selectedDepartments, 'over')" />
                <span>{{ item.label }}</span><small>分母 {{ item.denominatorCount }} · 分子 {{ item.numeratorCount }}</small>
              </label>
            </div>
            <div v-if="selectedDepartments.length" class="scope-chips">
              <button v-for="value in selectedDepartments" :key="value" type="button" @click="updateDepartments(value, selectedDepartments, 'over')">{{ department(value)?.label || value }} ×</button>
              <button type="button" class="clear-all-chip" @click="emit('update:selectedDepartments', [])">清空科室</button>
            </div>
          </div>
        </details>
        <textarea :value="overNote" rows="3" placeholder="补充说明（可选），例如：医院确认这些患者属于测试数据。" @input="emit('update:overNote', ($event.target as HTMLTextAreaElement).value)" />
      </article>

      <article class="clarification-kind is-under">
        <header><span>数据少了</span><strong>登记应该出现、但当前页面没有的数据</strong></header>
        <p>不用从当前明细里选择。请在一个框里写清患者或科室、时间和你认为少了什么，系统会从医院业务源开始反查。</p>
        <textarea :value="underNote" rows="5" placeholder="例如：康复医学科在 2025 年 7 月有符合分子条件的手术患者，但分子明细里没有；或填写就诊号、事件号及医院认为应进入分子的原因。" @input="emit('update:underNote', ($event.target as HTMLTextAreaElement).value)" />
      </article>
    </div>
    <section class="unified-clarification-action">
      <div><strong>统一澄清本次数据问题</strong><p>“数据多了”和“数据少了”可以只填写其中一项，也可以同时填写；系统会分别核验，并把结果一起展示在下方。</p></div>
      <button type="button" class="clarify-button" :class="{ 'is-working': Boolean(clarifyingDirection) }" :disabled="!hasIssue || busy || completed" @click="emit('clarify')"><span v-if="clarifyingDirection" class="clarify-spinner" aria-hidden="true"></span>{{ clarifyingDirection ? '正在核对并整理澄清结果…' : '要求澄清' }}</button>
    </section>
    <section v-if="explanation(overClarification) || explanation(underClarification)" class="unified-clarification-results">
      <header><strong>本次澄清结果</strong><small>仅展示本次已填写并完成核验的方向</small></header>
      <div>
        <article v-if="explanation(overClarification)" class="clarification-answer">
          <header><strong>数据多了</strong><small>已核对所选患者或科室</small></header>
          <p>{{ explanation(overClarification) }}</p>
          <details v-if="evidenceRows(overClarification).length" class="clarification-evidence"><summary><span><strong>已核验的实际明细</strong><small>{{ evidenceRows(overClarification).length }} 条记录</small></span><em>点击查看</em></summary><section><article v-for="(row, index) in evidenceRows(overClarification)" :key="index"><dl><div v-for="item in evidenceItems(row)" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl></article></section></details>
        </article>
        <article v-if="explanation(underClarification)" class="clarification-answer is-under-result">
          <header><strong>数据少了</strong><small>已从医院业务源向统计分子反查</small></header>
          <p>{{ explanation(underClarification) }}</p>
        </article>
      </div>
    </section>
    <footer v-if="explanation(overClarification) || explanation(underClarification)" class="clarification-result-actions"><button type="button" class="clarification-disagree" :disabled="busy || completed" @click="emit('proceed')">不满意，进入链路核查 ›</button><button type="button" class="clarification-accept" :disabled="busy || completed" @click="emit('finish')">确认无异议</button></footer>
    <p v-if="completed" class="completed-notice">本任务已经结束，当前内容只读。如需继续修改，请新建异常排查任务。</p>
  </section>
</template>
