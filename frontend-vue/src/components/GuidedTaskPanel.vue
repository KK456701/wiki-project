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
const recordField = ref('ENCOUNTER_ID')
const recordId = ref('')
const symptom = ref('')
const expectedResult = ref('')

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
  && Boolean(timeText.value)
  && (task.value !== 'diagnose' || Boolean(
    recordId.value.trim() && symptom.value.trim() && expectedResult.value.trim())))

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
  recordId.value = ''
  symptom.value = ''
  expectedResult.value = ''
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
      caseInput: {
        recordField: recordField.value,
        recordId: recordId.value.trim(),
        symptom: symptom.value.trim(),
        expectedResult: expectedResult.value.trim(),
        businessUniqueKey: recordField.value,
      },
      expectedClassification: { status: 'WAITING_CONFIRMATION' },
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
        <strong>算指标</strong><span>选择一个或多个指标，按时间范围计算结果</span>
      </button>
      <button type="button" class="guided-task" :class="{ 'is-active': task === 'diagnose' }" :disabled="disabled" @click="pickTask('diagnose')">
        <strong>开始异常排查</strong><span>登记一条具体案例，按三个步骤顺序核实原因</span>
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

      <div v-if="task === 'diagnose'" class="guided-step diagnosis-case-step">
        <h4>第三步：登记一个具体案例 <small>三个校验步骤会围绕这条记录展开</small></h4>
        <div class="diagnosis-case-grid">
          <label>记录类型<select v-model="recordField"><option value="ENCOUNTER_ID">就诊号</option><option value="EVENT_ID">事件号</option><option value="ORDER_ID">医嘱号</option><option value="SURGERY_ID">手术号</option></select></label>
          <label>记录标识<input v-model="recordId" maxlength="100" placeholder="输入现场可定位的编号" /></label>
          <label class="wide">异常现象<textarea v-model="symptom" rows="2" maxlength="1000" placeholder="例如：这条作废会诊被计入了分子"></textarea></label>
          <label class="wide">医院认为的正确结果<textarea v-model="expectedResult" rows="2" maxlength="1000" placeholder="例如：该记录不应进入分子和分母"></textarea></label>
        </div>
        <p class="diagnosis-order-note">系统将依次检查：表和字段 → 事件与抽取脚本 → 数值与现场常量。前三步通过后才进入案例查因。</p>
      </div>

      <button type="button" class="guided-submit" :disabled="!canSubmit" @click="submit">{{ task === 'calc' ? '开始计算' : '开始异常排查' }}</button>
    </div>
  </section>
</template>
