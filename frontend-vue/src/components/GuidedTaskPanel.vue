<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { listIndicators, type IndicatorItem } from '../api/agent'

const props = defineProps<{
  token: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [query: string]
}>()

// 当前引导任务：空 = 未选择；calc = 算指标；diagnose = 排查故障
const task = ref<'' | 'calc' | 'diagnose'>('')
const indicators = ref<IndicatorItem[]>([])
const loading = ref(false)
const loadError = ref('')
const search = ref('')
const selected = ref<string[]>([])
type DiagnosisType = 'schema' | 'event' | 'value' | 'mismatch'
const diagnosisType = ref<DiagnosisType | ''>('')
const diagnosisTypes: Array<{
  value: DiagnosisType
  title: string
  description: string
  prompt: string
}> = [
  {
    value: 'schema',
    title: '表或字段有问题',
    description: '检查缺表、缺字段，以及字段类型或长度不符合脚本要求。',
    prompt: '按表和字段问题排查，重点检查缺表、缺字段、字段类型和字段长度',
  },
  {
    value: 'event',
    title: '事件或抽取脚本有问题',
    description: '检查脚本报错、事件多配少配、重复数据和写入失败。',
    prompt: '按事件和抽取脚本问题排查，重点检查脚本报错、事件多配或少配、重复数据以及写入失败',
  },
  {
    value: 'value',
    title: '结果没数据或偏高偏低',
    description: '检查关键字段空值、现场模板ID和元素ID是否与公版不同。',
    prompt: '按指标数值异常排查，重点检查无数据、结果偏高偏低、关键字段空值、病历模板ID和病历元素ID',
  },
  {
    value: 'mismatch',
    title: '结果与医院对不上',
    description: '启动双库验证，检查同步缺失、数据变化和统计口径差异。',
    prompt: '按结果与医院不一致排查并启动双库验证，重点检查数据未同步、数据被更改和统计口径不一致',
  },
]

// 时间范围下拉：预设常用范围 + 自定义月份区间。
// 预设生成的文本必须是后端 TimeRangeResolver 能确定解析的表达；
// “近三个月/近半年”后端不支持，前端直接换算成明确的月份区间。
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

/** 从当前月往前推 N 个月，生成“X年N月到Y年M月”的明确区间 */
function monthRangeText(monthsBack: number): string {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - monthsBack, 1)
  return `${start.getFullYear()}年${start.getMonth() + 1}月到${now.getFullYear()}年${now.getMonth() + 1}月`
}

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
  && (task.value !== 'diagnose' || Boolean(diagnosisType.value)))

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

/** 把 <input type="month"> 的 2025-03 转成后端时间解析器可识别的 2025年3月 */
function formatMonth(value: string): string {
  const [year, month] = value.split('-')
  return `${year}年${Number(month)}月`
}

function pickTask(value: 'calc' | 'diagnose') {
  task.value = task.value === value ? '' : value
  selected.value = []
  search.value = ''
  diagnosisType.value = ''
}

function toggleIndicator(ruleId: string) {
  if (task.value === 'diagnose') {
    // 排查故障一次聚焦一个指标，避免诊断结论混在一起
    selected.value = selected.value.includes(ruleId) ? [] : [ruleId]
    return
  }
  selected.value = selected.value.includes(ruleId)
    ? selected.value.filter((id) => id !== ruleId)
    : [...selected.value, ruleId]
}

function selectAll() {
  selected.value = selected.value.length === indicators.value.length
    ? []
    : indicators.value.map((item) => item.ruleId)
}

function submit() {
  if (!canSubmit.value) return
  const names = indicators.value
    .filter((item) => selected.value.includes(item.ruleId))
    .map((item) => item.ruleName)
  const allSelected = names.length === indicators.value.length && names.length > 1
  const selectedDiagnosis = diagnosisTypes.find((item) => item.value === diagnosisType.value)
  const query = task.value === 'calc'
    ? allSelected
      ? `计算${timeText.value}全部指标的结果`
      : `计算${timeText.value}${names.join('、')}的结果`
    : `排查${timeText.value}${names.join('、')}：${selectedDiagnosis?.prompt || '分析结果异常原因'}`
  emit('send', query)
  task.value = ''
  selected.value = []
}
</script>

<template>
  <section class="guided-panel" aria-label="快捷任务入口">
    <div class="guided-tasks">
      <button
        type="button"
        class="guided-task"
        :class="{ 'is-active': task === 'calc' }"
        :disabled="disabled"
        @click="pickTask('calc')"
      >
        <strong>算指标</strong>
        <span>选择一个或多个指标，按时间范围计算结果</span>
      </button>
      <button
        type="button"
        class="guided-task"
        :class="{ 'is-active': task === 'diagnose' }"
        :disabled="disabled"
        @click="pickTask('diagnose')"
      >
        <strong>排查故障</strong>
        <span>选择指标和时间范围，分析结果异常的原因</span>
      </button>
    </div>

    <div v-if="task" class="guided-steps">
      <div v-if="task === 'diagnose'" class="guided-step">
        <h4>
          第一步：选择遇到的问题
          <small>按现场现象选择，不需要判断技术原因</small>
        </h4>
        <div class="guided-diagnosis-grid">
          <button
            v-for="item in diagnosisTypes"
            :key="item.value"
            type="button"
            class="guided-diagnosis"
            :class="{ 'is-selected': diagnosisType === item.value }"
            @click="diagnosisType = item.value"
          >
            <strong>{{ item.title }}</strong>
            <span>{{ item.description }}</span>
          </button>
        </div>
      </div>

      <div class="guided-step">
        <h4>
          {{ task === 'diagnose' ? '第二步' : '第一步' }}：选择指标
          <small v-if="task === 'calc'">可多选，已选 {{ selected.length }} 个</small>
          <small v-else>排查故障一次只选一个</small>
        </h4>
        <p v-if="loading" class="guided-hint">正在加载指标列表…</p>
        <p v-else-if="loadError" class="guided-hint">{{ loadError }}</p>
        <template v-else>
          <div class="guided-toolbar">
            <input v-model="search" type="search" placeholder="搜索指标名称或编码" />
            <button
              v-if="task === 'calc'"
              type="button"
              class="guided-select-all"
              @click="selectAll"
            >{{ selected.length === indicators.length ? '取消全选' : '全选' }}</button>
          </div>
          <div class="guided-indicator-grid">
            <button
              v-for="item in filteredIndicators"
              :key="item.ruleId"
              type="button"
              class="guided-indicator"
              :class="{ 'is-selected': selected.includes(item.ruleId) }"
              @click="toggleIndicator(item.ruleId)"
            >
              <strong>{{ item.ruleName }}</strong>
              <span>{{ item.ruleId }}</span>
            </button>
          </div>
          <p v-if="!filteredIndicators.length" class="guided-hint">没有匹配的指标。</p>
        </template>
      </div>

      <div class="guided-step">
        <h4>{{ task === 'diagnose' ? '第三步' : '第二步' }}：选择时间范围</h4>
        <div class="guided-time-row">
          <select v-model="timeChoice">
            <option v-for="preset in timePresets" :key="preset.label" :value="preset.label">{{ preset.label }}</option>
            <option value="custom">自定义月份区间</option>
          </select>
          <template v-if="timeChoice === 'custom'">
            <input v-model="customStart" type="month" aria-label="开始月份" />
            <span class="guided-time-sep">至</span>
            <input v-model="customEnd" type="month" aria-label="结束月份" />
          </template>
        </div>
      </div>

      <button type="button" class="guided-submit" :disabled="!canSubmit" @click="submit">
        {{ task === 'calc' ? '开始计算' : '开始排查' }}
      </button>
    </div>
  </section>
</template>
