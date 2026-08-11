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
  clarify: [direction: 'OVER_INCLUDED' | 'UNDER_INCLUDED']
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
function updateDepartments(value: string, selected: string[], type: 'over' | 'under') {
  const next = selected.includes(value) ? selected.filter((item) => item !== value) : [...selected, value]
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
      <div><strong>数据澄清</strong><p>分别说明哪些数据多算、哪些数据少算。系统会先核对对象是否真的存在于分子或分母明细，再逐层解释。</p></div>
    </header>
    <div class="clarification-grid">
      <article class="clarification-kind is-over">
        <header><span>数据多了</span><strong>选择不应被统计的患者或科室</strong></header>
        <p>患者和科室可以同时选择；这里的科室范围与上方明细查询条件相互独立。</p>
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
        <button type="button" class="clarify-button" :disabled="!hasOverIssue || busy || completed" @click="emit('clarify', 'OVER_INCLUDED')">要求澄清</button>
        <section v-if="explanation(overClarification)" class="clarification-answer"><strong>澄清结果（数据多了）</strong><p>{{ explanation(overClarification) }}</p><footer><button type="button" class="clarification-disagree" :disabled="busy || completed" @click="emit('proceed')">不满意，进入链路核查 ›</button><button type="button" class="clarification-accept" :disabled="busy || completed" @click="emit('finish')">确认无异议</button></footer></section>
      </article>

      <article class="clarification-kind is-under">
        <header><span>数据少了</span><strong>登记应该出现、但当前页面没有的数据</strong></header>
        <p>不用从当前明细里选择。请在一个框里写清患者或科室、时间和你认为少了什么，系统会从医院业务源开始反查。</p>
        <textarea :value="underNote" rows="5" placeholder="例如：康复医学科在 2025 年 7 月有手术患者，但分母明细里没有；或填写就诊号、事件号及医院认为应被统计的原因。" @input="emit('update:underNote', ($event.target as HTMLTextAreaElement).value)" />
        <button type="button" class="clarify-button" :disabled="!hasUnderIssue || busy || completed" @click="emit('clarify', 'UNDER_INCLUDED')">要求澄清</button>
        <section v-if="explanation(underClarification)" class="clarification-answer"><strong>澄清结果（数据少了）</strong><p>{{ explanation(underClarification) }}</p><footer><button type="button" class="clarification-disagree" :disabled="busy || completed" @click="emit('proceed')">不满意，进入链路核查 ›</button><button type="button" class="clarification-accept" :disabled="busy || completed" @click="emit('finish')">确认无异议</button></footer></section>
      </article>
    </div>
    <p v-if="completed" class="completed-notice">本任务已经结束，当前内容只读。如需继续修改，请新建异常排查任务。</p>
    <footer class="confirmation-actions">
      <button type="button" class="workspace-primary" :disabled="busy || completed" @click="emit('proceed')">进入链路核查</button>
      <button type="button" class="workspace-secondary" :disabled="busy || completed || hasIssue" @click="emit('finish')">确认结果正确并结束排查</button>
    </footer>
  </section>
</template>
