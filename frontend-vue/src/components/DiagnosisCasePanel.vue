<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import {
  fetchDiagnosisCaseDetails,
  type DiagnosisCaseSnapshot,
  type IndicatorDetailResult,
} from '../api/agent'
import DetailRowsTable from './DetailRowsTable.vue'
import IndicatorDataFlowPanel from './IndicatorDataFlowPanel.vue'

const props = defineProps<{
  snapshot: DiagnosisCaseSnapshot
  token: string
  busy?: boolean
}>()

const emit = defineEmits<{
  action: [action: string, payload: Record<string, unknown>]
}>()

type PredicateOperator = 'EQ' | 'NE' | 'IN' | 'NOT_IN' | 'IS_NULL' | 'IS_NOT_NULL' | 'CONTAINS' | 'NOT_CONTAINS'
type InvestigationCondition = {
  field: string
  operator: PredicateOperator
  value: string
}

const investigationLayer = ref('SOURCE_EXTRACT')
const investigationTreatment = ref<'EXCLUDE' | 'INCLUDE'>('EXCLUDE')
const investigationRequirement = ref('')
const investigationSql = ref('')
const investigationCandidateSql = ref('')
const investigationConditions = ref<InvestigationCondition[]>([emptyCondition()])
const changeType = ref('SQL_CHANGE')
const changeLayer = ref('SOURCE_EXTRACT')
const requirements = ref('')
const candidateSql = ref('')
const recordField = ref('ENCOUNTER_ID')
const recordId = ref('')
const caseDescription = ref('')
const templateCopied = ref(false)
const detailGroup = ref<'numerator' | 'denominator'>('numerator')
const detailPages = ref<Partial<Record<'numerator' | 'denominator', IndicatorDetailResult>>>({})
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const pendingRequirement = ref<Record<string, string> | null>(null)

const baseSteps = [
  { gate: 1, key: 'GATE_1_SCHEMA', label: '数据结构校验' },
  { gate: 2, key: 'GATE_2_EVENT', label: '事件与抽取校验' },
  { gate: 3, key: 'GATE_3_VALUE', label: '数据可用性校验' },
]

const blockedGateCount = computed(() => props.snapshot.gateResults.filter((item) => String(item.status) === 'BLOCKED').length)
const caseValidationMessage = computed(() => {
  const value = props.snapshot.shadowTrial.caseValidation
  return value && typeof value === 'object' ? String((value as Record<string, unknown>).message || '') : ''
})
const caseValidationIds = computed(() => {
  const value = props.snapshot.shadowTrial.caseValidation
  if (!value || typeof value !== 'object') return ''
  const ids = (value as Record<string, unknown>).requestedIds
  return Array.isArray(ids) ? ids.map(String).join('、') : ''
})
const caseTemplate = `记录类型：就诊号 / 事件号 / 医嘱号 / 手术号
记录编号（可填多个，用逗号或换行分隔，最多20个）：
补充说明（可选）：`
const isConsultationWordExample = computed(() => props.snapshot.ruleId === 'HXZD-003-004')
const wordRequirement = '排除会诊状态为399329839的已作废会诊；只抽取会诊完成时间不为空的数据；只抽取会诊后首条医嘱时间不为空的数据。'
const submittedRecordIds = computed(() => {
  const values = props.snapshot.caseInput.recordIds
  if (Array.isArray(values)) return values.map(String).filter(Boolean)
  const value = String(props.snapshot.caseInput.recordId || '').trim()
  return value ? [value] : []
})
const candidateRuleFields = computed(() => {
  const values = props.snapshot.caseExpectedClassification.candidateRuleFields
  return Array.isArray(values)
    ? values.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    : []
})
const candidateFieldListId = computed(() => `diagnosis-candidate-fields-${props.snapshot.caseId}`)
const operatorOptions: Array<{ value: PredicateOperator, label: string }> = [
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'IN', label: '属于这些值' },
  { value: 'NOT_IN', label: '不属于这些值' },
  { value: 'IS_NULL', label: '为空' },
  { value: 'IS_NOT_NULL', label: '不为空' },
  { value: 'CONTAINS', label: '包含' },
  { value: 'NOT_CONTAINS', label: '不包含' },
]
const investigationExpectedEffect = computed(() => investigationTreatment.value === 'EXCLUDE'
  ? '候选抽取后不再包含上述案例编号'
  : '候选抽取后应包含上述案例编号')
const investigationTemplateIncomplete = computed(() => {
  if (!investigationRequirement.value.trim()) return true
  if (investigationCandidateSql.value.trim()) return false
  if (!investigationConditions.value.length) return true
  return investigationConditions.value.some((item) => (
    !item.field.trim()
    || (!operatorHasNoValue(item.operator) && !item.value.trim())
  ))
})

function gate(number: number): Record<string, unknown> | undefined {
  return props.snapshot.gateResults.find((item) => Number(item.gate) === number)
}

function retryCurrentGate() {
  const number = baseSteps.find((item) => item.key === props.snapshot.currentStep)?.gate || 1
  emit('action', 'RECHECK_GATE', { gate: number })
}

function submitCase() {
  const recordIds = recordId.value.split(/[\s,，;；]+/).map((value) => value.trim()).filter(Boolean)
  if (!recordIds.length) return
  emit('action', 'SUBMIT_CASE', {
    recordField: recordField.value,
    recordId: recordIds[0],
    recordIds,
    // The historical API field is retained as an empty compatibility value.
    // Candidate SQL is driven only by the later implementation requirement.
    symptom: caseDescription.value.trim(),
    expectedResult: '',
    businessUniqueKey: recordField.value,
    expectedClassification: { status: 'WAITING_CONFIRMATION' },
  })
}

function fillWordExample() {
  recordField.value = 'ENCOUNTER_ID'
  recordId.value = '484625508177383425'
  caseDescription.value = '该患者存在已作废、未完成的普通会诊记录，但仍被抽取到普通会诊中间表；医院认为这类记录不应被抽取。'
}

function fillWordRequirement() {
  investigationLayer.value = 'SOURCE_EXTRACT'
  investigationTreatment.value = 'EXCLUDE'
  investigationRequirement.value = wordRequirement
  investigationSql.value = ''
  investigationCandidateSql.value = ''
  investigationConditions.value = [
    { field: 'A.CONSULT_STATUS_CODE', operator: 'EQ', value: '399329839' },
    { field: 'D.CONSULT_COMPLETED_AT', operator: 'IS_NULL', value: '' },
    { field: 't2.PRESCRIBED_AT', operator: 'IS_NULL', value: '' },
  ]
}

function fillInvestigationTemplates() {
  investigationLayer.value = 'SOURCE_EXTRACT'
  investigationTreatment.value = 'EXCLUDE'
  investigationRequirement.value = ''
  investigationSql.value = ''
  investigationCandidateSql.value = ''
  investigationConditions.value = [emptyCondition()]
}

function emptyCondition(): InvestigationCondition {
  return { field: '', operator: 'EQ', value: '' }
}

function addInvestigationCondition() {
  investigationConditions.value.push(emptyCondition())
}

function removeInvestigationCondition(index: number) {
  if (investigationConditions.value.length === 1) {
    investigationConditions.value = [emptyCondition()]
    return
  }
  investigationConditions.value.splice(index, 1)
}

function operatorHasNoValue(operator: PredicateOperator): boolean {
  return operator === 'IS_NULL' || operator === 'IS_NOT_NULL'
}

function operatorLabel(operator: PredicateOperator): string {
  return operatorOptions.find((item) => item.value === operator)?.label || operator
}

watch(
  () => `${props.snapshot.caseId}:${props.snapshot.currentStep}`,
  () => {
    if (props.snapshot.currentStep === 'CASE_INVESTIGATION'
      && !investigationRequirement.value.trim()
      && !investigationSql.value.trim()) {
      fillInvestigationTemplates()
    }
  },
  { immediate: true },
)

async function copyCaseTemplate() {
  await navigator.clipboard.writeText(caseTemplate)
  templateCopied.value = true
  window.setTimeout(() => { templateCopied.value = false }, 1500)
}

function confirmCaseCaliber() {
  emit('action', 'CONFIRM_CASE_CALIBER', {
    confirmed: true,
  })
}

async function loadAggregateDetail(
  group: 'numerator' | 'denominator',
  page = 1,
) {
  detailOpen.value = true
  detailGroup.value = group
  if (detailPages.value[group]?.page === page) return
  detailLoading.value = true
  detailError.value = ''
  try {
    detailPages.value[group] = await fetchDiagnosisCaseDetails(
      props.token, props.snapshot.caseId, group, page, 50,
    )
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : '分子分母明细加载失败。'
  } finally {
    detailLoading.value = false
  }
}

function detailPageCount(detail?: IndicatorDetailResult): number {
  return Math.max(1, Math.ceil((detail?.rowCount || 0) / (detail?.pageSize || 50)))
}

function submitEvidence() {
  if (!investigationRequirement.value.trim() || investigationTemplateIncomplete.value) return
  const layerLabel = {
    SOURCE_EXTRACT: '抽取 SQL 脚本',
    OVERVIEW: '目标表概览 SQL 脚本',
    UNKNOWN: '暂不确定',
  }[investigationLayer.value] || '暂不确定'
  const conditionSummary = investigationConditions.value.map((item, index) => (
    `${index + 1}. ${item.field.trim()} ${operatorLabel(item.operator)}`
      + (operatorHasNoValue(item.operator) ? '' : ` “${item.value.trim()}”`)
  )).join('\n')
  const summary = [
    `怀疑问题层级：${layerLabel}`,
    `处理方式：${investigationTreatment.value === 'EXCLUDE' ? '排除' : '纳入'}`,
    `实施提供的业务要求：${investigationRequirement.value.trim()}`,
    conditionSummary ? `结构化判断条件：\n${conditionSummary}` : '',
    `案例编号（仅用于影子验收）：${submittedRecordIds.value.join('、')}`,
    `预期结果：${investigationExpectedEffect.value}`,
    investigationSql.value.trim() ? `实施提供的验证 SQL：\n${investigationSql.value.trim()}` : '',
  ].filter(Boolean).join('\n\n')
  // Render this user turn immediately. The request can then complete without
  // making the implementer wait for a model response before seeing their input.
  pendingRequirement.value = {
    summary,
    layerLabel,
    requirement: investigationRequirement.value.trim(),
    validationSql: investigationSql.value.trim(),
  }
  emit('action', 'SUBMIT_EVIDENCE', {
    type: 'IMPLEMENTER_SQL_REQUIREMENT',
    suspectedLayer: investigationLayer.value,
    summary,
    requirement: investigationRequirement.value.trim(),
    treatment: investigationTreatment.value,
    patchConditions: investigationConditions.value.map((item) => ({
      treatment: investigationTreatment.value,
      field: item.field.trim(),
      operator: item.operator,
      value: item.value.trim(),
    })),
    expectedCaseEffect: investigationExpectedEffect.value,
    validationSql: investigationSql.value.trim(),
    candidateSql: investigationCandidateSql.value.trim(),
    requestAiAnalysis: false,
  })
  investigationRequirement.value = ''
  investigationSql.value = ''
  investigationCandidateSql.value = ''
  investigationConditions.value = [emptyCondition()]
}

function stepState(number: number): string {
  const result = gate(number)
  if (String(result?.status || '') === 'PASSED') return 'PASSED'
  if (String(result?.status || '') === 'BLOCKED') return 'BLOCKED'
  const step = baseSteps.find((item) => item.gate === number)
  if (props.busy && step?.key === props.snapshot.currentStep) return 'RUNNING'
  return 'WAITING'
}

function stepStateText(number: number): string {
  return { PASSED: '已通过', BLOCKED: '需处理', RUNNING: '检查中', WAITING: '等待前置' }[stepState(number)] || '等待前置'
}

const baseChecksStarted = computed(() => props.snapshot.currentStep !== 'CALIBER_CONFIRMATION')
const stageOrder = [
  'CALIBER_CONFIRMATION',
  'GATE_1_SCHEMA',
  'GATE_2_EVENT',
  'GATE_3_VALUE',
  'CASE_INPUT',
  'CASE_CALIBER_CLARIFICATION',
  'CASE_INVESTIGATION',
  'CHANGE_PROPOSAL',
  'SHADOW_TRIAL',
  'DRAFT_SAVE',
  'COMPLETED',
]
const stageRank = computed(() => Math.max(0, stageOrder.indexOf(props.snapshot.currentStep)))
const caseInputReached = computed(() => stageRank.value >= stageOrder.indexOf('CASE_INPUT'))
const caseSubmitted = computed(() => Boolean(String(props.snapshot.caseInput.recordId || '').trim()))
const investigationReached = computed(() => (
  stageRank.value >= stageOrder.indexOf('CASE_INVESTIGATION')
  || props.snapshot.evidence.length > 0
))
const currentBaseGate = computed(() => baseSteps.find((item) => item.key === props.snapshot.currentStep)?.gate || 0)
const currentGateHasResult = computed(() => currentBaseGate.value > 0 && Boolean(gate(currentBaseGate.value)))
const allBaseChecksPassed = computed(() => baseSteps.every((item) => stepState(item.gate) === 'PASSED'))
const baseConclusion = computed(() => {
  if (blockedGateCount.value) return '基础校验发现需要处理的问题，修复当前步骤后才能继续。'
  if (allBaseChecksPassed.value) return '三项基础校验均已通过，可以继续提供一条具体案例。'
  return props.busy ? '基础校验正在执行，请等待本轮结果。' : '基础校验尚未完成。'
})

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}

function evidenceDisplay(item: Record<string, unknown>): Record<string, unknown> {
  return item.display && typeof item.display === 'object' && !Array.isArray(item.display)
    ? item.display as Record<string, unknown> : {}
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
}

function isRequirementEvidence(item: Record<string, unknown>): boolean {
  return String(item.type || '') === 'IMPLEMENTER_SQL_REQUIREMENT'
}

function pendingRequirementVisible(): boolean {
  if (!pendingRequirement.value) return false
  return !props.snapshot.evidence.some((item) => String(item.summary || '') === pendingRequirement.value?.summary)
}

function resultEntries(value: unknown): Array<{ key: string, value: string }> {
  const source = Array.isArray(value) ? record(value[0]) : record(value)
  const named = [
    ['numeratorCount', '分子'],
    ['denominatorCount', '分母'],
    ['resultValue', '结果值'],
    ['status', '执行状态'],
  ].filter(([key]) => source[key] !== null && source[key] !== undefined && source[key] !== '')
    .map(([key, label]) => ({ key: label, value: String(source[key]) }))
  if (named.length) return named
  return Object.entries(source)
    .filter(([key, item]) => !['sql', 'errorMessage'].includes(key) && item !== null && item !== '')
    .slice(0, 6)
    .map(([key, item]) => ({ key, value: String(item) }))
}

function executableSqlDisplay(value: string): string {
  return value || '当前无法生成可直接复制的 SQL Server 查询脚本。'
}

function flowPath(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '当前知识库未生成可展示的数据链路'
  const nodes = Array.isArray((value as Record<string, unknown>).nodes)
    ? (value as Record<string, unknown>).nodes as Array<Record<string, unknown>> : []
  const names = nodes.map((node) => String(node.title || '')).filter(Boolean)
  return names.length ? names.join(' → ') : '当前知识库未生成可展示的数据链路'
}

function buildCandidate() {
  emit('action', 'BUILD_CANDIDATE', {
    type: changeType.value,
    layer: changeLayer.value,
    requirements: requirements.value.trim(),
    sql: candidateSql.value.trim(),
    expectedCaseEffect: requirements.value.trim(),
  })
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}
</script>

<template>
  <div class="diagnosis-case-thread" :data-status="snapshot.status">
    <article v-if="snapshot.currentStep === 'CALIBER_CONFIRMATION'" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 准备排查</strong></div><h4>{{ snapshot.caliberSnapshot.ruleName || snapshot.ruleId }}</h4><p>系统将按当前生效口径检查数据结构、事件与抽取、当前窗口数据是否可用。</p><dl class="caliber-facts"><div><dt>当前口径</dt><dd>{{ snapshot.caliberSnapshot.profileName || snapshot.profileId }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'CONFIRM_CALIBER', { confirmed: true })">异常排查基础校验</button></div>
    </article>

    <article v-if="baseChecksStarted" class="message is-user">
      <div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><strong>异常排查基础校验</strong></div>
    </article>
    <article v-if="baseChecksStarted" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 基础校验回复</strong><span>{{ blockedGateCount ? '需要处理' : allBaseChecksPassed ? '可以继续' : '检查中' }}</span></div><section class="diagnosis-caliber-section"><h4>当前排查指标：{{ snapshot.caliberSnapshot.ruleName || snapshot.ruleId }}</h4><dl class="caliber-facts"><div><dt>指标编码</dt><dd>{{ snapshot.ruleId }}</dd></div><div><dt>当前口径</dt><dd>{{ snapshot.caliberSnapshot.profileName || snapshot.profileId }}（{{ snapshot.profileId }}）</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl></section><section v-for="item in baseSteps" :key="item.gate" class="diagnosis-gate-summary"><header><strong>{{ item.label }}</strong><em :data-state="stepState(item.gate)">{{ stepStateText(item.gate) }}</em></header><div class="diagnosis-result" :data-state="stepState(item.gate)"><strong>{{ gate(item.gate)?.message || (stepState(item.gate) === 'RUNNING' ? '正在检查，请稍候…' : '等待前一步完成') }}</strong><code v-if="gate(item.gate)?.errorCode">{{ gate(item.gate)?.errorCode }}</code></div><div v-if="gate(item.gate)?.repairSuggestion" class="diagnosis-repair"><strong>建议怎么处理</strong><p>{{ gate(item.gate)?.repairSuggestion }}</p></div></section><p class="diagnosis-base-conclusion"><strong>结论：</strong>{{ baseConclusion }}</p><button v-if="blockedGateCount" type="button" class="diagnosis-primary" :disabled="busy" @click="retryCurrentGate">修复后重新校验当前步骤</button><button v-else-if="snapshot.currentStep.startsWith('GATE_') && !currentGateHasResult && !busy" type="button" class="diagnosis-primary" @click="retryCurrentGate">继续基础校验</button></div>
    </article>

    <template v-if="caseInputReached">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请提供具体案例</strong></div><p>记录编号是候选脚本的验收样本：系统会核对它在当前正式中间表中是否存在，以及候选抽取后是否按要求被纳入或排除。编号不会写进正式口径 SQL。</p><p>同一种问题可一次填最多20个编号；大量记录应写成下一步的业务筛选条件，不要逐个粘贴。</p><pre class="diagnosis-template">{{ caseTemplate }}</pre><button type="button" class="diagnosis-text-action" @click="copyCaseTemplate">{{ templateCopied ? '已复制' : '复制填写模板' }}</button><template v-if="isConsultationWordExample"><p class="diagnosis-help">Word 参考案例来自中江县人民医院；如果当前库没有这条就诊号，系统会提示无记录，不应据此判断本院抽取有问题。</p><button type="button" class="diagnosis-text-action" @click="fillWordExample">填入 Word 参考案例</button></template></div></article>
      <article v-if="snapshot.currentStep === 'CASE_INPUT'" class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 提供具体案例</strong></div><div class="diagnosis-case-grid"><label>记录类型<select v-model="recordField"><option value="ENCOUNTER_ID">就诊号</option><option value="EVENT_ID">事件号</option><option value="ORDER_ID">医嘱号</option><option value="SURGERY_ID">手术号</option></select></label><label>记录编号（最多20个）<textarea v-model="recordId" rows="3" maxlength="2100" placeholder="多个编号用逗号或换行分隔" /></label><label class="wide">补充说明（可选）<textarea v-model="caseDescription" rows="3" maxlength="1200" placeholder="用于审计和理解背景，不参与生成 SQL；不知道可不填。"></textarea></label></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>填写至少一个记录编号后发送，系统会单独回复当前口径澄清。</p><button type="button" class="diagnosis-primary" :disabled="busy || !recordId.trim()" @click="submitCase">发送案例</button></div></article>
    </template>

    <template v-if="caseSubmitted">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 具体案例</strong></div><p>{{ snapshot.caseInput.recordField }}={{ Array.isArray(snapshot.caseInput.recordIds) ? snapshot.caseInput.recordIds.join('、') : snapshot.caseInput.recordId }}</p><p v-if="snapshot.caseInput.caseDescription || snapshot.caseInput.symptom">{{ snapshot.caseInput.caseDescription || snapshot.caseInput.symptom }}</p></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 案例口径澄清</strong><span>{{ snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION' ? '等待确认' : '已确认' }}</span></div><section class="diagnosis-caliber-section"><h4>当前生效口径：{{ snapshot.caseExpectedClassification.profileName || snapshot.profileId }}</h4><p>{{ snapshot.caseExpectedClassification.definition || '知识库未单独描述指标定义' }}</p><dl class="caliber-facts"><div><dt>计算公式</dt><dd>{{ snapshot.caseExpectedClassification.formula || '知识库未单独描述' }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl></section><section class="diagnosis-caliber-section"><h4>本次分子和分母</h4><dl class="caliber-facts"><div><dt>分子 {{ snapshot.caseExpectedClassification.numeratorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.numeratorRule || '知识库未单独描述' }}</dd></div><div><dt>分母 {{ snapshot.caseExpectedClassification.denominatorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.denominatorRule || '知识库未单独描述' }}</dd></div></dl><div class="indicator-detail-groups diagnosis-detail-actions"><button type="button" :class="{ active: detailOpen && detailGroup === 'numerator' }" :disabled="detailLoading" @click="loadAggregateDetail('numerator')">查看分子明细（{{ snapshot.caseExpectedClassification.numeratorCount ?? '—' }}条）</button><button type="button" :class="{ active: detailOpen && detailGroup === 'denominator' }" :disabled="detailLoading" @click="loadAggregateDetail('denominator')">查看分母明细（{{ snapshot.caseExpectedClassification.denominatorCount ?? '—' }}条）</button></div><div v-if="detailOpen" class="diagnosis-detail-panel"><p v-if="detailLoading" class="indicator-loading">正在按本次口径重新对账并加载明细…</p><p v-else-if="detailError" class="indicator-error">{{ detailError }}</p><template v-else-if="detailPages[detailGroup]"><div class="detail-contract-summary"><span>汇总 {{ detailPages[detailGroup]?.cardNumerator ?? 0 }}/{{ detailPages[detailGroup]?.cardDenominator ?? 0 }}</span><span>明细 {{ detailPages[detailGroup]?.detailNumerator ?? 0 }}/{{ detailPages[detailGroup]?.detailDenominator ?? 0 }}</span><strong>对账通过</strong><small v-if="detailPages[detailGroup]?.snapshotReused">已复用本次排查明细</small></div><p class="indicator-detail-summary">{{ detailGroup === 'numerator' ? '分子' : '分母' }}共 {{ detailPages[detailGroup]?.rowCount || 0 }} 条 · 第 {{ detailPages[detailGroup]?.page || 1 }}/{{ detailPageCount(detailPages[detailGroup]) }} 页</p><DetailRowsTable :rows="detailPages[detailGroup]?.rows || []" empty-text="本次统计窗口没有对应明细。" /><nav v-if="detailPageCount(detailPages[detailGroup]) > 1" class="detail-pagination" aria-label="排查明细分页"><button type="button" :disabled="(detailPages[detailGroup]?.page || 1) <= 1" @click="loadAggregateDetail(detailGroup, (detailPages[detailGroup]?.page || 1) - 1)">上一页</button><span>{{ detailPages[detailGroup]?.page || 1 }} / {{ detailPageCount(detailPages[detailGroup]) }}</span><button type="button" :disabled="(detailPages[detailGroup]?.page || 1) >= detailPageCount(detailPages[detailGroup])" @click="loadAggregateDetail(detailGroup, (detailPages[detailGroup]?.page || 1) + 1)">下一页</button></nav></template></div><p class="diagnosis-help">明细必须重新聚合为上面的分子和分母才会展示；数量对不上时系统会拒绝返回。</p></section><section class="diagnosis-caliber-section"><h4>当前口径数据链路</h4><p class="diagnosis-flow-path">{{ flowPath(snapshot.caseExpectedClassification.dataFlow) }}</p><details class="diagnosis-technical"><summary>展开数据链路、涉及表和 SQL</summary><IndicatorDataFlowPanel :flow="snapshot.caseExpectedClassification.dataFlow" /></details></section><p><strong>判断顺序：</strong>先确认记录处于统计窗口，再判断是否进入分母，最后判断是否命中分子。</p><template v-if="snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION'"><p class="diagnosis-pass-rule"><strong>下一步：</strong>确认当前口径后，实施人员提交要纳入或排除的数据条件；系统将自动生成候选 SQL并进行影子试跑。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="confirmCaseCaliber">确认澄清，进入抽取数据核对</button></template></div></article>
    </template>

    <template v-if="investigationReached">
      <article class="message is-agent">
        <div class="message-avatar">AI</div>
        <div class="message-card diagnosis-turn-card">
          <div class="message-head"><strong>系统 · 请实施人员提供排查要求</strong><span>等待现场信息</span></div>
          <p><strong>默认改抽取 SQL：</strong>多抽、少抽或重复数据通常发生在业务数据进入中间表之前。只有已经确认中间表数据正确，但分子分母判定错误时，才选择统计 SQL。</p>
          <details class="diagnosis-capability" open>
            <summary>当前系统能自动修改哪些 SQL</summary>
            <div class="diagnosis-capability-grid">
              <section><strong>可以自动处理（包括新增过滤条件）</strong><p>可以在原 SQL 已有字段上增加“等于、不等于、属于、不属于、为空、不为空、包含、不包含”等筛选条件。例如：排除测试患者、排除已作废记录、只保留完成时间不为空的数据。</p></section>
              <section><strong>目前不能自动处理</strong><p>新增表或 JOIN、修改 JOIN 关系、增加子查询，或者修改去重、分组、窗口函数、UNION、计算公式和输出字段。遇到这些情况，系统会停止自动改写并提示实施人员提供一条完整的候选 SELECT。</p></section>
            </div>
            <p>字段必须已经存在于原 SQL 中，而且程序能确定筛选条件应该加在哪一层。小模型只帮助理解和解释，不负责重写整段复杂 SQL，也不能绕过程序校验。</p>
          </details>
          <template v-if="snapshot.currentStep === 'CASE_INVESTIGATION'">
            <div class="diagnosis-change-grid">
              <label>怀疑有问题的脚本<select v-model="investigationLayer"><option value="SOURCE_EXTRACT">抽取 SQL 脚本（默认）</option><option value="OVERVIEW">统计 SQL（仅中间表正确时）</option></select></label>
              <label>处理方式<select v-model="investigationTreatment"><option value="EXCLUDE">排除符合条件的数据</option><option value="INCLUDE">只纳入符合条件的数据</option></select></label>
            </div>
            <section class="diagnosis-input-template">
              <header><strong>1. 填写医院确认的业务规则（必填）</strong><button type="button" class="diagnosis-text-action" @click="fillInvestigationTemplates">清空重填</button></header>
              <p>用大白话说明医院要改什么，例如“排除姓名为测试患者的记录”。这句话用于记录医院的修改依据和生成草稿说明；真正改写 SQL 时，系统以下面的字段、条件和值为准。</p>
              <textarea v-model="investigationRequirement" rows="3" aria-label="医院确认的业务规则" placeholder="例如：排除姓名为测试患者的记录"></textarea>
            </section>
            <section class="diagnosis-input-template">
              <header><strong>2. 填写判断条件</strong><button type="button" class="diagnosis-text-action" @click="addInvestigationCondition">增加条件</button></header>
              <p>判断字段必须来自当前脚本，并填写为“表别名.字段名”。下拉建议只列出程序能唯一定位查询层的字段；也可以手工填写，提交时仍会重新校验。</p>
              <div v-for="(condition, index) in investigationConditions" :key="index" class="diagnosis-condition-row">
                <label>判断字段<input v-model="condition.field" :list="candidateFieldListId" :aria-label="`判断字段${index + 1}`" placeholder="输入或选择表别名.字段名" /></label>
                <label>判断条件<select v-model="condition.operator"><option v-for="option in operatorOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
                <label v-if="!operatorHasNoValue(condition.operator)">判断值<input v-model="condition.value" :aria-label="`判断值${index + 1}`" placeholder="多个值用逗号分隔" /></label>
                <div v-else class="diagnosis-condition-empty">该条件不需要填写值</div>
                <button type="button" class="diagnosis-text-action" @click="removeInvestigationCondition(index)">删除</button>
              </div>
              <datalist :id="candidateFieldListId"><option v-for="field in candidateRuleFields" :key="String(field.value)" :value="String(field.value)">{{ field.label }}</option></datalist>
              <p v-if="!candidateRuleFields.length" class="diagnosis-help">当前抽取 SQL没有识别出可安全推荐的字段。请展开数据链路核对实际别名，或提供完整候选 SQL。</p>
              <dl class="diagnosis-case-acceptance"><div><dt>案例编号</dt><dd>{{ submittedRecordIds.join('、') || '尚未填写' }}</dd></div><div><dt>预期效果</dt><dd>{{ investigationExpectedEffect }}</dd></div></dl>
              <p class="diagnosis-help">案例编号只用于验证修改前后是否按预期变化，不会写入正式口径 SQL。</p>
            </section>
            <section class="diagnosis-input-template">
              <header><strong>3. 现场核对 SQL（可选）</strong><span>不知道可以不填</span></header>
              <p>如果已经在 Navicat 查到哪些记录应该被排除或纳入，请粘贴对应的只读 SELECT。系统只用它确认实际表、字段和值，不会把整段查询替换成正式抽取 SQL；不要粘贴更新、删除或清表语句。</p>
              <textarea v-model="investigationSql" class="diagnosis-sql-input" rows="7" aria-label="现场核对 SQL" placeholder="SELECT 就诊号, 判断字段 FROM 业务表 WHERE 判断字段 = 实际值"></textarea>
            </section>
            <details class="diagnosis-input-template">
              <summary><strong>目前不能自动处理时：提供完整候选 SQL</strong></summary>
              <p>仅当需求涉及新增 JOIN、子查询、聚合、去重等结构变化，程序不能安全自动修改时才填写。必须是一条完整只读 SELECT；系统仍会检查结构并进行影子试跑。</p>
              <textarea v-model="investigationCandidateSql" class="diagnosis-sql-input" rows="9" aria-label="完整候选 SQL" placeholder="可选：由实施人员提供完整候选 SELECT"></textarea>
            </details>
            <p v-if="investigationTemplateIncomplete" class="diagnosis-template-warning">请填写业务规则，并至少提供一组完整的判断字段、条件和值；如果属于上面列出的“目前不能自动处理”情况，需要由实施人员提供完整候选 SELECT。</p>
            <template v-if="isConsultationWordExample"><p class="diagnosis-help">会诊参考条件可以自动填入：排除作废状态、完成时间为空和首条医嘱时间为空的数据。</p><button type="button" class="diagnosis-text-action" @click="fillWordRequirement">填入会诊参考条件</button></template>
            <p class="diagnosis-pass-rule"><strong>发送后：</strong>简单字段条件由程序在正确查询层改写并影子验收；超出安全范围时明确停止并要求完整候选 SQL，不再让模型重写复杂脚本。正式口径和正式结果不会被修改。</p>
            <button type="button" class="diagnosis-primary" :disabled="busy || investigationTemplateIncomplete" @click="submitEvidence">生成候选 SQL并影子试跑</button>
          </template>
        </div>
      </article>
      <template v-if="pendingRequirementVisible() && pendingRequirement">
        <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 排查要求</strong><span>{{ pendingRequirement.layerLabel }}</span></div><p>{{ pendingRequirement.requirement }}</p><details v-if="pendingRequirement.validationSql" class="diagnosis-technical"><summary>查看实施验证 SQL</summary><pre>{{ pendingRequirement.validationSql }}</pre></details></div></article>
        <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 正在生成候选 SQL</strong></div><p>已收到。正在基于当前正式脚本生成候选语句，并在影子环境试跑；正式结果不会被修改。</p></div></article>
      </template>
      <template v-for="item in snapshot.evidence" :key="String(item.evidenceId)">
        <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card">
          <template v-if="isRequirementEvidence(item)"><div class="message-head"><strong>实施人员 · 排查要求</strong><span>{{ record(item.sqlContext).layerLabel || '暂不确定' }}</span></div><p>{{ item.requirement }}</p><details v-if="item.validationSql" class="diagnosis-technical"><summary>查看实施验证 SQL</summary><pre>{{ item.validationSql }}</pre></details></template>
          <strong v-else>{{ item.type === 'AUTOMATIC_DATA_FLOW' ? '核对这条案例数据' : item.summary }}</strong>
        </div></article>
        <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ item.type === 'AUTOMATIC_DATA_FLOW' ? '系统 · 案例数据核对结果' : isRequirementEvidence(item) ? '系统 · 实施要求分析' : item.modelId ? '系统 · 实施要求分析' : '系统 · 程序证据' }}</strong></div>
          <template v-if="isRequirementEvidence(item)"><p><strong>判断：</strong>{{ record(item.requirementAnalysis).judgement }}</p><p><strong>本轮要求：</strong>{{ record(item.requirementAnalysis).requirement }}</p><p><strong>下一步：</strong>{{ record(item.requirementAnalysis).nextAction }}</p><details v-if="record(item.sqlContext).available" class="diagnosis-technical"><summary>{{ record(item.sqlContext).layerLabel }}（可直接复制到 Navicat）</summary><pre>{{ executableSqlDisplay(String(record(item.sqlContext).executableSql || '')) }}</pre></details><section v-if="resultEntries(record(item.sqlContext).currentResult).length" class="diagnosis-result-compare"><h4>当前正式结果</h4><dl><div v-for="entry in resultEntries(record(item.sqlContext).currentResult)" :key="entry.key"><dt>{{ entry.key }}</dt><dd>{{ entry.value }}</dd></div></dl></section></template>
          <template v-else-if="item.type === 'AUTOMATIC_DATA_FLOW'"><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).found).length"><h4>查到了什么</h4><p v-for="line in stringList(evidenceDisplay(item).found)" :key="line">✓ {{ line }}</p></section><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).notFound).length"><h4>哪些环节没找到记录</h4><p v-for="line in stringList(evidenceDisplay(item).notFound)" :key="line">• {{ line }}</p></section><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).unfinished).length"><h4>哪些查询没完成</h4><p v-for="line in stringList(evidenceDisplay(item).unfinished)" :key="line">! {{ line }}</p></section><p class="diagnosis-base-conclusion"><strong>结论：</strong>{{ evidenceDisplay(item).conclusion || item.summary }}</p><p><strong>下一步：</strong>{{ evidenceDisplay(item).nextAction || '根据证据继续核对。' }}</p></template>
          <p v-else>{{ item.aiAnalysis || item.summary }}</p><details v-if="item.stages" class="diagnosis-technical"><summary>查看取证详情（实施排查用）</summary><pre>{{ pretty(item.stages) }}</pre></details></div></article>
      </template>
      <article v-if="snapshot.currentStep === 'CASE_INVESTIGATION' && snapshot.evidence.length && record(snapshot.evidence[snapshot.evidence.length - 1].requirementAnalysis).failureReason" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 候选 SQL未能试跑</strong></div><p>{{ record(snapshot.evidence[snapshot.evidence.length - 1].requirementAnalysis).failureReason }}</p><p class="diagnosis-pass-rule">请修正或补充上方条件后重新发送。当前正式 SQL和正式结果没有改变。</p></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CHANGE_PROPOSAL'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 已确认原因</strong></div><p>{{ snapshot.causeConclusion.conclusion }}</p></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请提供修改要求</strong></div><p>说明要改抽取还是统计、增加或删除什么条件、使用什么字段和值，以及这条案例修改后应该怎样变化。最好附完整只读 SQL。</p></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 修改要求</strong></div><div class="diagnosis-change-grid"><label>问题分支<select v-model="changeType"><option value="DATA_REPAIR">数据修复</option><option value="EVENT_CONFIG">事件配置</option><option value="SQL_CHANGE">SQL 修改</option><option value="CALIBER_CHANGE">医院口径变更</option></select></label><label v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'">只修改一层<select v-model="changeLayer"><option value="SOURCE_EXTRACT">抽取 SQL</option><option value="OVERVIEW">统计 SQL</option></select></label></div><textarea v-model="requirements" rows="3" placeholder="写清新增/删除条件、字段、操作符和值，以及对案例的预期影响"></textarea><textarea v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'" v-model="candidateSql" class="diagnosis-sql-input" rows="8" placeholder="复杂结构修改必须由实施人员粘贴一条完整候选 SELECT；模型不会重写整段 SQL"></textarea><p class="diagnosis-pass-rule"><strong>本轮产物：</strong>原 SQL、候选 SQL、差异说明和程序校验结果。校验不通过不能试跑。</p><button type="button" class="diagnosis-primary" :disabled="busy || !requirements.trim()" @click="buildCandidate">发送要求，由系统组装 SQL</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'SHADOW_TRIAL' || snapshot.currentStep === 'DRAFT_SAVE'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 候选语句已生成</strong></div><p>候选语句已通过安全校验。正式中间表和当前卡片不会被覆盖。</p><p v-if="snapshot.candidateSql.generationMethod" class="diagnosis-help">生成方式：{{ snapshot.candidateSql.generationMethod }}</p><section class="diagnosis-result-compare"><h4>原结果与试跑结果</h4><dl><div v-for="entry in resultEntries(snapshot.candidateSql.baselineResult)" :key="`old-${entry.key}`"><dt>原结果 · {{ entry.key }}</dt><dd>{{ entry.value }}</dd></div><div v-for="entry in resultEntries(snapshot.shadowTrial.candidateResult)" :key="`new-${entry.key}`"><dt>试跑结果 · {{ entry.key }}</dt><dd>{{ entry.value }}</dd></div><div v-if="!Object.keys(snapshot.shadowTrial).length"><dt>试跑结果</dt><dd>尚未运行影子试跑</dd></div></dl></section><section v-if="caseValidationMessage" class="diagnosis-caliber-section"><h4>案例编号验收</h4><p>{{ caseValidationMessage }}</p><p class="diagnosis-help">验收编号：{{ caseValidationIds }}</p></section><details v-if="Object.keys(snapshot.candidateSql).length" class="diagnosis-technical"><summary>原 SQL（当前正式脚本，可直接复制到 Navicat）</summary><pre>{{ executableSqlDisplay(String(snapshot.candidateSql.originalSqlExecutable || '')) }}</pre></details><details v-if="Object.keys(snapshot.candidateSql).length" class="diagnosis-technical"><summary>候选 SQL（本次试跑脚本，可直接复制到 Navicat）</summary><pre>{{ executableSqlDisplay(String(snapshot.candidateSql.candidateSqlExecutable || '')) }}</pre></details><p class="diagnosis-help">{{ snapshot.candidateSql.rawSqlNotice }}</p><details v-if="Object.keys(snapshot.shadowTrial).length" class="diagnosis-technical"><summary>查看完整影子试跑对账</summary><pre>{{ pretty(snapshot.shadowTrial) }}</pre></details></div></article>
      <article v-if="snapshot.currentStep === 'SHADOW_TRIAL'" class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 确认影子重跑</strong></div><p class="diagnosis-pass-rule"><strong>允许保存草稿的条件：</strong>案例变化符合预期、没有无法解释的重复、输出结构兼容、分子分母可对账。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'RUN_SHADOW_TRIAL', {})">使用候选语句影子重跑</button></div></article>
    </template>

    <article v-if="snapshot.currentStep === 'DRAFT_SAVE'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 影子对账通过</strong></div><p>将当前候选 SQL、修改要求和影子试跑结果保存为医院草稿。草稿不会影响正式指标计算，也不会替换公司公版口径。</p><details class="diagnosis-technical"><summary>查看影子试跑报告</summary><pre>{{ pretty(snapshot.shadowTrial) }}</pre></details><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'SAVE_HOSPITAL_DRAFT', { confirmed: true })">保存为医院草稿版本</button></div></article>
    <article v-if="snapshot.currentStep === 'COMPLETED' && Object.keys(snapshot.draftResult || {}).length" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 医院草稿已保存</strong><span>未发布</span></div><dl class="caliber-facts"><div><dt>草稿编号</dt><dd>{{ snapshot.draftResult.draftId }}</dd></div><div><dt>医院</dt><dd>{{ snapshot.draftResult.hospitalId }}</dd></div><div><dt>指标</dt><dd>{{ snapshot.draftResult.profileId }}</dd></div><div><dt>修改层级</dt><dd>{{ snapshot.draftResult.changeLayer === 'SOURCE_EXTRACT' ? '抽取 SQL' : '统计 SQL' }}</dd></div><div><dt>影子验证</dt><dd>{{ snapshot.draftResult.revalidationPassed === false ? '重新验证失败' : '通过' }}</dd></div><div><dt>正式状态</dt><dd>未发布，不影响当前计算</dd></div></dl><details class="diagnosis-technical"><summary>查看草稿实体</summary><pre>{{ snapshot.draftResult.entityMarkdown }}</pre></details><details class="diagnosis-technical"><summary>查看原 SQL</summary><pre>{{ snapshot.draftResult.originalSql }}</pre></details><details class="diagnosis-technical"><summary>查看候选 SQL</summary><pre>{{ snapshot.draftResult.candidateSql }}</pre></details><details class="diagnosis-technical"><summary>查看影子对账</summary><pre>{{ pretty(snapshot.draftResult.shadowTrial) }}</pre></details><details class="diagnosis-technical"><summary>查看草稿磁盘校验</summary><pre>{{ pretty(snapshot.draftResult.verification) }}</pre></details><p v-if="snapshot.draftResult.baselineExpired" class="diagnosis-template-warning">公司公版已经变化，草稿基线已过期，需要重新开始排查。</p><button type="button" class="diagnosis-primary" :disabled="busy || Boolean(snapshot.draftResult.baselineExpired)" @click="emit('action', 'REVALIDATE_HOSPITAL_DRAFT', {})">重新验证草稿</button></div></article>
    <article v-else-if="snapshot.currentStep === 'COMPLETED'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 当前计算已确认正确</strong></div><pre>{{ pretty(snapshot.releaseResult) }}</pre></div></article>
  </div>
</template>
