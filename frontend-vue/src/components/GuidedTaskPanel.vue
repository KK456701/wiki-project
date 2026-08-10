<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { listIndicators, type CreateDiagnosisCaseInput, type IndicatorItem } from '../api/agent'

const props = defineProps<{
  token: string
  sessionId: string
  modelId: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [query: string]
  startDiagnosis: [input: CreateDiagnosisCaseInput]
}>()

const task = ref<'' | 'calc' | 'diagnose'>('')
const indicators = ref<IndicatorItem[]>([])
const loading = ref(false)
const loadError = ref('')
const search = ref('')
const selected = ref<string[]>([])
const timeChoice = ref('本月')
const customStart = ref('')
const customEnd = ref('')
const timePresets = [
  { label: '本月', build: () => '本月' },
  { label: '上月', build: () => '上月' },
  { label: '近三个月', build: () => monthRangeText(2) },
  { label: '近半年', build: () => monthRangeText(5) },
  { label: '今年以来', build: () => '今年至今' },
  { label: '去年全年', build: () => '去年' },
]

const filteredIndicators = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return indicators.value
  return indicators.value.filter((item) =>
    `${item.ruleId} ${item.ruleName}`.toLowerCase().includes(keyword))
})

const timeText = computed(() => {
  if (timeChoice.value !== 'custom') {
    return timePresets.find((preset) => preset.label === timeChoice.value)?.build() || ''
  }
  if (!customStart.value || !customEnd.value) return ''
  return `${formatMonth(customStart.value)}到${formatMonth(customEnd.value)}`
})

const canSubmit = computed(() =>
  !props.disabled
  && selected.value.length > 0
  && Boolean(timeText.value))

onMounted(async () => {
  loading.value = true
  try {
    indicators.value = await listIndicators(props.token)
  } catch {
    loadError.value = '指标列表加载失败，可直接在下方输入框描述需求。'
  } finally {
    loading.value = false
  }
})

function monthRangeText(monthsBack: number): string {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - monthsBack, 1)
  return `${start.getFullYear()}年${start.getMonth() + 1}月到${now.getFullYear()}年${now.getMonth() + 1}月`
}

function formatMonth(value: string): string {
  const [year, month] = value.split('-')
  return `${year}年${Number(month)}月`
}

function pickTask(value: 'calc' | 'diagnose') {
  task.value = task.value === value ? '' : value
  selected.value = []
  search.value = ''
}

function toggleIndicator(ruleId: string) {
  if (task.value === 'diagnose') {
    selected.value = selected.value.includes(ruleId) ? [] : [ruleId]
    return
  }
  selected.value = selected.value.includes(ruleId)
    ? selected.value.filter((id) => id !== ruleId)
    : [...selected.value, ruleId]
}

function selectAll() {
  selected.value = selected.value.length === indicators.value.length
    ? [] : indicators.value.map((item) => item.ruleId)
}

function submit() {
  if (!canSubmit.value) return
  const names = indicators.value
    .filter((item) => selected.value.includes(item.ruleId))
    .map((item) => item.ruleName)
  if (task.value === 'diagnose') {
    const period = diagnosisPeriod()
    emit('startDiagnosis', {
      sessionId: props.sessionId,
      ruleId: selected.value[0],
      profileId: selected.value[0],
      statStart: period.start,
      statEnd: period.end,
      modelId: props.modelId,
      caseInput: {},
      expectedClassification: {},
    })
  } else {
    const allSelected = names.length === indicators.value.length && names.length > 1
    emit('send', allSelected
      ? `计算${timeText.value}全部指标的结果`
      : `计算${timeText.value}${names.join('、')}的结果`)
  }
  task.value = ''
  selected.value = []
}

function diagnosisPeriod(): { start: string; end: string } {
  const now = new Date()
  let start: Date
  let end: Date
  if (timeChoice.value === 'custom' && customStart.value && customEnd.value) {
    const [startYear, startMonth] = customStart.value.split('-').map(Number)
    const [endYear, endMonth] = customEnd.value.split('-').map(Number)
    start = new Date(startYear, startMonth - 1, 1)
    end = new Date(endYear, endMonth, 1)
  } else if (timeChoice.value === '去年全年') {
    start = new Date(now.getFullYear() - 1, 0, 1)
    end = new Date(now.getFullYear(), 0, 1)
  } else if (timeChoice.value === '今年以来') {
    start = new Date(now.getFullYear(), 0, 1)
    end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  } else if (timeChoice.value === '上月') {
    start = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    end = new Date(now.getFullYear(), now.getMonth(), 1)
  } else if (timeChoice.value === '近三个月') {
    start = new Date(now.getFullYear(), now.getMonth() - 2, 1)
    end = new Date(now.getFullYear(), now.getMonth() + 1, 1)
  } else if (timeChoice.value === '近半年') {
    start = new Date(now.getFullYear(), now.getMonth() - 5, 1)
    end = new Date(now.getFullYear(), now.getMonth() + 1, 1)
  } else {
    start = new Date(now.getFullYear(), now.getMonth(), 1)
    end = new Date(now.getFullYear(), now.getMonth() + 1, 1)
  }
  return { start: localIso(start), end: localIso(end) }
}

function localIso(value: Date): string {
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T00:00:00`
}
</script>

<template>
  <section class="guided-panel" aria-label="快捷任务入口">
    <div class="guided-tasks">
      <button type="button" class="guided-task" :class="{ 'is-active': task === 'calc' }" :disabled="disabled" @click="pickTask('calc')">
        <span class="guided-task-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M5 4.5h14v15H5z"/><path d="M8 8h8M8 12h3M8 16h3M14 12h2M14 16h2"/></svg>
        </span>
        <span class="guided-task-copy"><strong>计算指标</strong><span>选择一个或多个指标，按时间范围生成结果</span><small>选择指标 → 选择周期 → 查看结果</small></span>
        <span class="guided-task-arrow" aria-hidden="true">→</span>
      </button>
      <button type="button" class="guided-task" :class="{ 'is-active': task === 'diagnose' }" :disabled="disabled" @click="pickTask('diagnose')">
        <span class="guided-task-icon is-diagnosis" aria-hidden="true">
          <svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="5.5"/><path d="m15 15 4.5 4.5M10.5 8v5M8 10.5h5"/></svg>
        </span>
        <span class="guided-task-copy"><strong>异常排查</strong><span>结果对不上、科室漏数或患者判定异常，从这里开始</span><small>选择指标 → 基础校验 → 对话排查</small></span>
        <span class="guided-task-arrow" aria-hidden="true">→</span>
      </button>
    </div>

    <div v-if="task" class="guided-steps">
      <div class="guided-step">
        <h4>第一步：选择指标 <small v-if="task === 'calc'">可多选，已选 {{ selected.length }} 个</small><small v-else>一次只排查一个指标</small></h4>
        <p v-if="loading" class="guided-hint">正在加载指标列表…</p>
        <p v-else-if="loadError" class="guided-hint">{{ loadError }}</p>
        <template v-else>
          <div class="guided-toolbar">
            <input v-model="search" type="search" placeholder="搜索指标名称或编码" />
            <button v-if="task === 'calc'" type="button" class="guided-select-all" @click="selectAll">{{ selected.length === indicators.length ? '取消全选' : '全选' }}</button>
          </div>
          <div class="guided-indicator-grid">
            <button v-for="item in filteredIndicators" :key="item.ruleId" type="button" class="guided-indicator" :class="{ 'is-selected': selected.includes(item.ruleId) }" @click="toggleIndicator(item.ruleId)">
              <strong>{{ item.ruleName }}</strong><span>{{ item.ruleId }}</span>
            </button>
          </div>
          <p v-if="!filteredIndicators.length" class="guided-hint">没有匹配的指标。</p>
        </template>
      </div>

      <div class="guided-step">
        <h4>第二步：选择时间范围</h4>
        <div class="guided-time-row">
          <select v-model="timeChoice">
            <option v-for="preset in timePresets" :key="preset.label" :value="preset.label">{{ preset.label }}</option>
            <option value="custom">自定义月份区间</option>
          </select>
          <template v-if="timeChoice === 'custom'">
            <input v-model="customStart" type="month" aria-label="开始月份" /><span class="guided-time-sep">至</span><input v-model="customEnd" type="month" aria-label="结束月份" />
          </template>
        </div>
      </div>

      <p v-if="task === 'diagnose'" class="diagnosis-order-note">开始后系统将依次检查：表和字段 → 事件与抽取脚本 → 数值与现场常量。有问题会停在对应步骤并给出修复建议；全部通过后才要求填写具体案例。</p>

      <button type="button" class="guided-submit" :disabled="!canSubmit" @click="submit">{{ task === 'calc' ? '开始计算' : '开始异常排查' }}</button>
    </div>
  </section>
</template>
