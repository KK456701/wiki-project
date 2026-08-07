<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import {
  fetchDiagnosisCaseDetails,
  fetchDiagnosisScopeClarification,
  loadDiagnosisShadowDiffs,
  type DiagnosisShadowDiffPage,
  type DiagnosisCaseSnapshot,
  type DiagnosisScopeClarification,
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
type CapabilityExample = {
  id: string
  title: string
  explanation: string
  requirement: string
  treatment: 'EXCLUDE' | 'INCLUDE'
  condition: InvestigationCondition
}
type UnsupportedCapability = {
  id: string
  title: string
  reason: string
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
const scopeType = ref<'RECORD' | 'DEPARTMENT' | 'TIME_RANGE' | 'DATA_CATEGORY' | 'OVERALL'>('OVERALL')
const scopeField = ref('')
const scopeValue = ref('')
const scopeStart = ref('')
const scopeEnd = ref('')
const standardAction = ref<'REFRESH' | 'MODIFY_SOURCE' | 'MODIFY_OVERVIEW'>('REFRESH')
const detailGroup = ref<'numerator' | 'denominator'>('numerator')
const detailPages = ref<Partial<Record<'numerator' | 'denominator', IndicatorDetailResult>>>({})
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const scopeClarification = ref<DiagnosisScopeClarification | null>(null)
const scopeClarificationLoading = ref(false)
const scopeClarificationError = ref('')
const scopeClarificationKey = ref('')
const pendingRequirement = ref<Record<string, string> | null>(null)
const copiedSqlKey = ref('')
const selectedMode = ref<'' | 'STANDARD' | 'AUTONOMOUS'>('')
const autonomousProblem = ref('')
const autonomousAnswer = ref('')
const diffType = ref<DiagnosisShadowDiffPage['type']>('REMOVED')
const diffSearch = ref('')
const diffPage = ref<DiagnosisShadowDiffPage | null>(null)
const diffLoading = ref(false)
const diffError = ref('')
const draftIssueSummary = ref('')
const draftChangeSummary = ref('')
const draftExpectedImpact = ref('')
const draftVerificationSummary = ref('')
const optimisticAutonomousTurns = ref<Array<Record<string, unknown>>>([])
const expandedAutonomousTurns = ref<Set<string>>(new Set())
const autonomousClock = ref(Date.now())
let autonomousClockTimer = 0

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
const sqlLayerTitle = computed(() => String(props.snapshot.candidateSql.layer || '') === 'OVERVIEW'
  ? '目标表概览 SQL'
  : '源表抽取 SQL')
const resultComparisonRows = computed(() => {
  const originalTrialResult = props.snapshot.shadowTrial.originalResult
  const baseline = normalizedMetricResult(
    Array.isArray(originalTrialResult) && originalTrialResult.length
      ? originalTrialResult : props.snapshot.candidateSql.baselineResult,
    'SUCCESS',
  )
  const candidateRows = props.snapshot.shadowTrial.candidateResult
  const candidate = normalizedMetricResult(candidateRows,
    Array.isArray(candidateRows) && candidateRows.length ? 'SUCCESS' : '')
  return [
    { key: 'numeratorCount', label: '分子' },
    { key: 'denominatorCount', label: '分母' },
    { key: 'resultValue', label: '结果值' },
    { key: 'status', label: '执行状态' },
  ].map((item) => ({
    ...item,
    baseline: displayResult(baseline[item.key]),
    candidate: displayResult(candidate[item.key]),
    change: resultChange(baseline[item.key], candidate[item.key]),
  }))
})
const shadowTrialState = computed(() => {
  const trial = props.snapshot.shadowTrial
  if (!Object.keys(trial).length) {
    return { text: '等待试跑', state: 'waiting' }
  }
  if (shadowTrialExecutionFailed.value) {
    return { text: '试跑执行失败', state: 'failed' }
  }
  return Boolean(trial.passed)
    ? { text: '试跑验收通过', state: 'passed' }
    : { text: '试跑完成，验收未通过', state: 'failed' }
})
const shadowTrialExecutionFailed = computed(() => {
  const trial = props.snapshot.shadowTrial
  if (!Object.keys(trial).length) return false
  if (trial.completed === false || String(trial.failureStage || '') === 'EXECUTION') return true
  // 兼容修复前已保存的任务：旧数据只有 FAILED 和 message，没有完成标记。
  return String(trial.status || '') === 'FAILED'
    && Boolean(trial.message)
    && !Array.isArray(trial.candidateResult)
})
const shadowTrialFailureMessage = computed(() => String(props.snapshot.shadowTrial.message || '影子试跑执行失败，未生成可供比较的结果。'))
// 差异明细按记录逐行预览：一个业务编号可能对应多条记录，
// 因此每条记录单独占一行，并标出这一行来自正式中间表还是候选影子表。
const diffRowLabels: Record<string, { before: string; after: string }> = {
  ADDED: { before: '', after: '候选新增记录' },
  REMOVED: { before: '正式已有、候选缺失', after: '' },
  CHANGED: { before: '修改前', after: '修改后' },
  DUPLICATE: { before: '正式记录', after: '候选重复记录' },
}
const diffMinorField = /^(EXTRACT_AT|CREATED_AT|MODIFIED_AT|UPDATED_AT|VERSION|MRAS_TARGET_DEFINITION_ID)$/
const diffChangedColumns = computed(() => {
  const fields = new Set<string>()
  const items = diffPage.value?.items || []
  items.forEach((item) => (item.changedFields || []).forEach((field) => fields.add(field)))
  return fields
})
const diffTableRows = computed(() => {
  const page = diffPage.value
  if (!page) return []
  const labels = diffRowLabels[page.type] || diffRowLabels.CHANGED
  const rows: Array<{
    id: string
    businessKey: string
    side: string
    sideKind: 'BEFORE' | 'AFTER'
    changedFields: string[]
    row: Record<string, unknown>
    first: boolean
  }> = []
  page.items.forEach((item) => {
    const changedFields = item.changedFields || []
    const sides: Array<['BEFORE' | 'AFTER', string, Array<Record<string, unknown>>]> = [
      ['BEFORE', labels.before, item.beforeRows || []],
      ['AFTER', labels.after, item.afterRows || []],
    ]
    let index = 0
    sides.forEach(([sideKind, label, source]) => {
      if (!label) return
      source.forEach((row) => {
        rows.push({
          id: `${item.businessKey}-${sideKind}-${index}`,
          businessKey: item.businessKey,
          side: label,
          sideKind,
          changedFields,
          row,
          first: index === 0,
        })
        index += 1
      })
    })
    if (!index) {
      rows.push({
        id: `${item.businessKey}-empty`,
        businessKey: item.businessKey,
        side: '没有保存字段快照',
        sideKind: 'BEFORE',
        changedFields,
        row: {},
        first: true,
      })
    }
  })
  return rows
})
const diffTableColumns = computed(() => {
  const fields = new Set<string>()
  diffTableRows.value.forEach((item) => Object.keys(item.row).forEach((field) => fields.add(field)))
  const changed = diffChangedColumns.value
  return [...fields].sort((left, right) => diffColumnRank(left, changed) - diffColumnRank(right, changed)
    || left.localeCompare(right))
})
const extractionComparisonRows = computed(() => {
  if (String(props.snapshot.candidateSql.layer || '') !== 'SOURCE_EXTRACT') return []
  const trial = props.snapshot.shadowTrial
  const diff = record(trial.recordSetDiff)
  return [
    {
      key: 'rows', label: '中间表总记录数',
      baseline: displayResult(trial.formalRows),
      candidate: displayResult(trial.shadowRows),
      change: resultChange(trial.formalRows, trial.shadowRows),
    },
    {
      key: 'keys', label: '去重业务编号数',
      baseline: displayResult(diff.originalCount),
      candidate: displayResult(diff.candidateCount),
      change: resultChange(diff.originalCount, diff.candidateCount),
    },
    {
      key: 'removed', label: '候选抽取减少的业务编号',
      baseline: '—',
      candidate: displayResult(diff.removedCount),
      change: diff.removedCount === undefined ? '待试跑' : `-${diff.removedCount}`,
    },
    {
      key: 'added', label: '候选抽取新增的业务编号',
      baseline: '—',
      candidate: displayResult(diff.addedCount),
      change: diff.addedCount === undefined ? '待试跑' : `+${diff.addedCount}`,
    },
  ]
})
const caseValidationRows = computed(() => {
  const validation = record(props.snapshot.shadowTrial.caseValidation)
  const baseline = record(validation.baselineCounts)
  const candidate = record(validation.candidateCounts)
  const expectedAction = String(validation.expectedAction || '')
  return stringList(validation.requestedIds).map((id) => {
    const before = Number(baseline[id] || 0)
    const after = Number(candidate[id] || 0)
    const passed = expectedAction === 'EXCLUDE' ? before > 0 && after < before : after > 0
    return {
      id,
      before,
      after,
      change: after - before,
      expected: expectedAction === 'EXCLUDE' ? '应减少或排除' : '应被纳入',
      result: passed ? '符合预期' : '未符合预期',
      passed,
    }
  })
})
const submittedScopeSummary = computed(() => {
  const input = props.snapshot.caseInput
  const type = String(input.scopeType || 'RECORD')
  if (type === 'DEPARTMENT') return `科室/病区：${input.scopeValue || '—'}（${input.scopeField || '未指定字段'}）`
  if (type === 'TIME_RANGE') return `时间范围：${input.scopeStart || '—'} 至 ${input.scopeEnd || '—'}（${input.scopeField || '未指定字段'}）`
  if (type === 'DATA_CATEGORY') return `数据范围：${input.scopeValue || '—'}（${input.scopeField || '未指定字段'}）`
  if (type === 'OVERALL') return `整体结果：${input.caseDescription || input.symptom || '核对整体结果差异'}`
  const ids = Array.isArray(input.recordIds) ? input.recordIds.join('、') : input.recordId
  return `${input.recordField || '记录编号'}=${ids || '—'}`
})
const submittedScopeType = computed(() => String(props.snapshot.caseInput.scopeType || 'RECORD'))
const caliberClarificationTitle = computed(() => (
  submittedScopeType.value === 'RECORD' ? '系统 · 案例口径澄清' : '系统 · 排查范围口径澄清'
))
const scopeClarificationTitle = computed(() => submittedScopeType.value === 'RECORD'
  ? '系统 · 这个患者为什么在明细里'
  : '系统 · 这个科室是怎么算的')
const scopeClarificationStatus = computed(() => ({
  IN_NUMERATOR_AND_DENOMINATOR: '进入分子和分母',
  IN_DENOMINATOR_ONLY: '只进入分母',
  NOT_IN_DETAIL: '当前明细未找到',
} as Record<string, string>)[scopeClarification.value?.status || ''] || '正在核对')
const caliberJudgementOrder = computed(() => {
  if (submittedScopeType.value === 'DEPARTMENT') return '先确认科室或病区编码及统计窗口，再核对哪些业务记录进入中间表，最后对照科室汇总与总指标结果。'
  if (submittedScopeType.value === 'TIME_RANGE') return '先确认时间字段和起止边界，再核对该时间范围内的分母母集，最后检查分子命中与最终汇总。'
  if (submittedScopeType.value === 'DATA_CATEGORY') return '先确认这类数据对应的业务字段和值，再核对抽取前后记录集合，最后检查分子分母和结果变化。'
  if (submittedScopeType.value === 'OVERALL') return '先核对正式批次、统计窗口和分母母集，再逐层比较抽取记录、分子分母与医院预期结果。'
  return '先确认记录处于统计窗口，再判断是否进入分母，最后判断是否命中分子。'
})
const dataRefreshCompleted = computed(() => Boolean(props.snapshot.shadowTrial.completed))
const dataRefreshOutcome = computed(() => String(props.snapshot.releaseResult.outcome || ''))
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
const scopeCapabilities = computed(() => record(props.snapshot.caliberSnapshot.diagnosisScopeCapabilities))
const recordKeyCandidates = computed(() => objectList(scopeCapabilities.value.recordKeyCandidates))
const departmentCandidates = computed(() => objectList(scopeCapabilities.value.departmentCandidates))
const timeFieldCandidates = computed(() => objectList(scopeCapabilities.value.timeFieldCandidates))
const conditionFieldCandidates = computed(() => objectList(scopeCapabilities.value.conditionFieldCandidates))
const scopeFieldOptions = computed(() => {
  if (scopeType.value === 'RECORD') return recordKeyCandidates.value
  if (scopeType.value === 'DEPARTMENT') return departmentCandidates.value
  if (scopeType.value === 'TIME_RANGE') return timeFieldCandidates.value
  if (scopeType.value === 'DATA_CATEGORY') return conditionFieldCandidates.value
  return []
})
const canSubmitScope = computed(() => {
  if (scopeType.value === 'RECORD') return Boolean(recordId.value.trim())
  if (scopeType.value === 'DEPARTMENT' || scopeType.value === 'DATA_CATEGORY') return Boolean(scopeValue.value.trim())
  if (scopeType.value === 'TIME_RANGE') return Boolean(scopeStart.value && scopeEnd.value)
  return Boolean(caseDescription.value.trim())
})
const sourceTemplateSql = computed(() => {
  const flow = record(props.snapshot.caseExpectedClassification.dataFlow)
  const nodes = Array.isArray(flow.nodes)
    ? flow.nodes.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
    : []
  const source = nodes.find((node) => (
    String(node.id || '') === 'source-extract-sql'
    || String(node.nodeType || '') === 'SOURCE_EXTRACT_SQL'
  ))
  return String(source?.templateSql || source?.sql || '')
})
const automaticCapabilityExamples = computed<CapabilityExample[]>(() => {
  const result: CapabilityExample[] = []
  const usedKinds = new Set<string>()
  for (const item of candidateRuleFields.value) {
    const field = String(item.field || '').toUpperCase()
    const qualified = String(item.value || '').trim()
    if (!field || !qualified) continue
    const example = exampleForField(field, qualified)
    if (!example || usedKinds.has(example.id)) continue
    usedKinds.add(example.id)
    result.push(example)
    if (result.length >= 4) break
  }
  return result
})
const unsupportedCapabilities = computed<UnsupportedCapability[]>(() => {
  const sql = sourceTemplateSql.value.toUpperCase()
  const result: UnsupportedCapability[] = []
  const add = (id: string, title: string, reason: string) => {
    if (!result.some((item) => item.id === id)) result.push({ id, title, reason })
  }
  if (/\bJOIN\b/.test(sql)) {
    add('join', '新增数据表，或修改现有表的关联条件', '关联方式会决定一条业务记录被匹配成几条，改错后可能造成重复或漏数。')
  }
  if (/\(\s*SELECT\b/.test(sql)) {
    add('subquery', '新增子查询，或改变子查询的取数范围', '子查询属于独立的查询层，条件放在内层或外层会得到不同记录。')
  }
  if (/\bROW_NUMBER\s*\(|\bRANK\s*\(|\bDENSE_RANK\s*\(/.test(sql)) {
    add('window', '修改“第一条、最后一条”或排序取数规则', '窗口函数同时依赖分组字段和排序字段，局部追加条件不能证明仍选中了正确记录。')
  }
  if (/\bDISTINCT\b/.test(sql)) {
    add('distinct', '修改去重字段或去重范围', '去重规则直接改变记录数量，必须重新证明分子、分母和业务唯一键都能对账。')
  }
  if (/\bGROUP\s+BY\b|\bHAVING\b|\bCOUNT\s*\(|\bSUM\s*\(|\bAVG\s*\(/.test(sql)) {
    add('aggregate', '修改分组、汇总或分子分母计算', '聚合结构决定最终数字，简单增加过滤条件无法安全代替公式和分组改写。')
  }
  if (/\bUNION\b|\bEXCEPT\b|\bINTERSECT\b/.test(sql)) {
    add('set', '修改多段查询的合并、排除或交集关系', '多段查询必须分别确定字段和去重规则，不能只修改其中一段后假定整体口径正确。')
  }
  if (/\bCASE\b/.test(sql)) {
    add('formula', '修改计算字段、判定公式或输出字段', '这些字段通常会直接写入中间表，修改后还要校验字段类型、长度和后续统计兼容性。')
  }
  if (!result.length) {
    add('structure', '新增表、子查询，或修改去重和计算结构', '这类修改会改变数据如何组合和计数，当前程序只能安全追加已有字段的筛选条件。')
  }
  return result.slice(0, 5)
})
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
  if (scopeType.value === 'RECORD' && !recordIds.length) return
  emit('action', 'SUBMIT_CASE', {
    scopeType: scopeType.value,
    scopeField: scopeField.value,
    scopeValue: scopeValue.value.trim(),
    scopeStart: scopeStart.value,
    scopeEnd: scopeEnd.value,
    recordField: recordField.value,
    recordId: recordIds[0] || '',
    recordIds,
    // The historical API field is retained as an empty compatibility value.
    // Candidate SQL is driven only by the later implementation requirement.
    symptom: caseDescription.value.trim(),
    expectedResult: '',
    businessUniqueKey: recordField.value,
    expectedClassification: { status: 'WAITING_CONFIRMATION' },
  })
}

function startAutonomous() {
  if (!autonomousProblem.value.trim()) return
  const clientMessageId = `CLIENT_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  optimisticAutonomousTurns.value.push({ clientMessageId, userMessage: autonomousProblem.value.trim(), status: 'SENDING' })
  emit('action', 'START_AUTONOMOUS_INVESTIGATION', { problem: autonomousProblem.value.trim(), clientMessageId })
  autonomousProblem.value = ''
}

function respondAutonomous() {
  sendAutonomousText(autonomousAnswer.value)
  autonomousAnswer.value = ''
}

function sendAutonomousText(text: string) {
  const normalized = text.trim()
  if (!normalized) return
  const clientMessageId = `CLIENT_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  optimisticAutonomousTurns.value.push({ clientMessageId, userMessage: normalized, status: 'SENDING' })
  emit('action', 'SEND_AUTONOMOUS_MESSAGE', { message: normalized, clientMessageId })
}

defineExpose({ sendAutonomousText })

// 变化字段排在最前，业务编号类字段紧随其后，雪花主键和审计时间列排到最后。
function diffColumnRank(field: string, changed: Set<string>): number {
  if (changed.has(field)) return 0
  if (/^MRAS_BUSINESS_.*_ID$/.test(field) || diffMinorField.test(field)) return 3
  if (/(_ID|_NO|_CODE)$/.test(field)) return 1
  return 2
}

function diffCellText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function loadShadowDiffs(page = 1) {
  const trialId = String(props.snapshot.shadowTrial.trialId || '')
  if (!trialId) return
  diffLoading.value = true
  diffError.value = ''
  try {
    diffPage.value = await loadDiagnosisShadowDiffs(
      props.token, props.snapshot.caseId, trialId, diffType.value, page, 50, diffSearch.value,
    )
  } catch (cause) {
    diffError.value = cause instanceof Error ? cause.message : '差异明细加载失败。'
  } finally {
    diffLoading.value = false
  }
}

function saveHospitalDraft() {
  emit('action', 'SAVE_HOSPITAL_DRAFT', {
    confirmed: true,
    issueSummary: draftIssueSummary.value.trim(),
    changeSummary: draftChangeSummary.value.trim(),
    expectedImpact: draftExpectedImpact.value.trim(),
    verificationSummary: draftVerificationSummary.value.trim(),
  })
}

const draftDescriptionIncomplete = computed(() => !draftIssueSummary.value.trim()
  || !draftChangeSummary.value.trim()
  || !draftExpectedImpact.value.trim()
  || !draftVerificationSummary.value.trim())

const autonomousEvents = computed(() => {
  const values = props.snapshot.autonomousRun?.toolEvents
  return Array.isArray(values) ? values.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object') : []
})

const autonomousTurns = computed(() => {
  const saved = objectList(props.snapshot.autonomousRun?.turns)
  const savedIds = new Set(saved.map((turn) => String(turn.clientMessageId || '')))
  const optimistic = optimisticAutonomousTurns.value.filter((turn) => !savedIds.has(String(turn.clientMessageId || '')))
  return [...saved, ...optimistic]
})

function turnEvents(turn: Record<string, unknown>): Array<Record<string, unknown>> {
  const values = objectList(turn.processEvents)
  if (values.length) return values
  const turnId = String(turn.turnId || '')
  return autonomousEvents.value.filter((event) => String(event.turnId || '') === turnId)
}

function turnProcessEvents(turn: Record<string, unknown>): Array<Record<string, unknown>> {
  return turnEvents(turn).filter((event) => !['RESPONSE', 'STAGE_REPLY', 'QUESTION', 'CONCLUSION']
    .includes(autonomousEventType(event)))
}

function mergedTurnProcessEvents(turn: Record<string, unknown>): Array<Record<string, unknown>> {
  const rows: Array<Record<string, unknown>> = []
  const tools = new Map<string, number>()
  for (const event of turnProcessEvents(turn)) {
    const type = autonomousEventType(event)
    const callId = String(event.toolCallId || '')
    if (type === 'TOOL_CALL' && callId) {
      tools.set(callId, rows.length)
      rows.push({ ...event })
    } else if (type === 'OBSERVATION' && callId && tools.has(callId)) {
      const index = tools.get(callId) as number
      rows[index] = { ...rows[index], ...event, eventType: 'TOOL', startedStatus: rows[index].status }
    } else {
      rows.push(event)
    }
  }
  return rows
}

function mergedTurnEvents(turn: Record<string, unknown>): Array<Record<string, unknown>> {
  return [...mergedTurnProcessEvents(turn), ...turnReplyEvents(turn)]
    .sort((left, right) => Number(left.seq || 0) - Number(right.seq || 0))
}

function autonomousEventKind(event: Record<string, unknown>): string {
  const type = autonomousEventType(event)
  if (type === 'ANALYSIS') return 'analysis'
  if (type === 'MODEL_STARTED') return 'model'
  if (['RESPONSE', 'STAGE_REPLY', 'QUESTION', 'CONCLUSION'].includes(type)) return 'reply'
  if (type === 'STOP') return 'stop'
  if (event.tool || type === 'TOOL_CALL' || type === 'OBSERVATION') return 'tool'
  return 'process'
}

function replyDataKind(event: Record<string, unknown>): string {
  const type = autonomousEventType(event)
  if (type === 'CONCLUSION') return 'conclusion'
  if (type === 'QUESTION') return 'question'
  return 'response'
}

const hasPendingAutonomousQuestion = computed(() => (
  String(props.snapshot.autonomousRun?.status || '') === 'WAITING_USER'
))

function isPendingQuestion(turn: Record<string, unknown>, event: Record<string, unknown>): boolean {
  if (!hasPendingAutonomousQuestion.value) return false
  const savedTurns = objectList(props.snapshot.autonomousRun?.turns)
  const lastSaved = savedTurns[savedTurns.length - 1]
  if (!lastSaved || String(lastSaved.turnId || '') !== String(turn.turnId || '')) return false
  const questions = turnReplyEvents(turn).filter((item) => autonomousEventType(item) === 'QUESTION')
  const last = questions[questions.length - 1]
  return Boolean(last) && Number(last.seq || -1) === Number(event.seq || -2)
}

function turnReplyEvents(turn: Record<string, unknown>): Array<Record<string, unknown>> {
  return turnEvents(turn).filter((event) => ['RESPONSE', 'STAGE_REPLY', 'QUESTION', 'CONCLUSION', 'STOP']
    .includes(autonomousEventType(event)))
}

function turnIsExpanded(turn: Record<string, unknown>, index: number): boolean {
  const id = String(turn.turnId || turn.clientMessageId || index)
  return expandedAutonomousTurns.value.has(id)
}

function toggleTurn(turn: Record<string, unknown>, index: number, open: boolean) {
  const id = String(turn.turnId || turn.clientMessageId || index)
  const copy = new Set(expandedAutonomousTurns.value)
  if (open) copy.add(id); else copy.delete(id)
  expandedAutonomousTurns.value = copy
}

function toggleTurnEvent(turn: Record<string, unknown>, index: number, event: Event) {
  toggleTurn(turn, index, Boolean((event.currentTarget as HTMLDetailsElement).open))
}

function objectList(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
    : []
}

function autonomousTurnSeconds(turn: Record<string, unknown>): string {
  const started = Date.parse(String(turn.submittedAt || ''))
  const events = turnEvents(turn)
  const ended = events.length && String(turn.status || '') !== 'RUNNING'
    ? Date.parse(String(events[events.length - 1].createdAt || '')) : autonomousClock.value
  if (!Number.isFinite(started) || !Number.isFinite(ended)) return '0s'
  return `${Math.max(0, (ended - started) / 1000).toFixed(1)}s`
}

function autonomousTurnTitle(turn: Record<string, unknown>): string {
  return String(turn.status || '') === 'RUNNING'
    ? `思考中 · ${autonomousTurnSeconds(turn)}`
    : `详细分析过程 · ${autonomousTurnSeconds(turn)}`
}

function analysisItems(event: Record<string, unknown>): Array<{ label: string, value: unknown }> {
  return [
    { label: '当前问题理解', value: event.problemUnderstanding || event.analysisSummary },
    { label: '当前判断与候选原因', value: event.hypotheses },
    { label: '已有依据', value: event.evidenceRefs },
    { label: '本轮验证目标', value: event.verificationGoal },
    { label: '工具选择理由', value: event.toolChoiceReason },
    { label: '判断更新', value: event.judgementUpdate },
    { label: '下一步动作', value: event.nextStep || event.publicPlan },
  ].filter((item) => Array.isArray(item.value) ? item.value.length : String(item.value || '').trim())
}

function displayAnalysisValue(value: unknown): string {
  return Array.isArray(value) ? value.map(String).join('；') : String(value || '')
}

onMounted(() => {
  autonomousClockTimer = window.setInterval(() => { autonomousClock.value = Date.now() }, 500)
})

onUnmounted(() => window.clearInterval(autonomousClockTimer))

function autonomousEventType(event: Record<string, unknown>): string {
  return String(event.eventType || (event.tool ? 'OBSERVATION' : 'ANALYSIS')).toUpperCase()
}

function autonomousEventTitle(event: Record<string, unknown>): string {
  const title = String(event.title || '').trim()
  if (title) return title
  return ({
    ANALYSIS: '公开分析', TOOL_CALL: '执行工具', OBSERVATION: '工具观察',
    RESPONSE: '直接回答', QUESTION: '需要现场补充', CONCLUSION: '排查结论', STOP: '循环已停止',
  } as Record<string, string>)[autonomousEventType(event)] || '执行记录'
}

function autonomousEventDisplayTitle(
  event: Record<string, unknown>,
  turn: Record<string, unknown>,
): string {
  if (autonomousEventType(event) !== 'MODEL_STARTED') return autonomousEventTitle(event)
  const status = autonomousEventStatus(event, turn)
  if (status === 'FAILED') return '分析失败'
  if (status === 'SUCCEEDED') return '已分析'
  return '思考中'
}

function autonomousStatusText(status: unknown): string {
  return ({
    RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败',
    WAITING_USER: '等待回复', STOPPED: '已停止', CANCELLED: '已停止',
  } as Record<string, string>)[String(status || '').toUpperCase()] || String(status || '')
}

function autonomousEventStatus(event: Record<string, unknown>, turn: Record<string, unknown>): string {
  if (autonomousEventType(event) === 'MODEL_STARTED' && String(turn.status || '') !== 'RUNNING') {
    return String(turn.status || '') === 'FAILED' ? 'FAILED' : 'SUCCEEDED'
  }
  return String(event.status || '')
}

function hasAutonomousDetails(event: Record<string, unknown>): boolean {
  return Boolean(event.arguments || event.resultPreview || event.error)
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

function exampleForField(field: string, qualified: string): CapabilityExample | null {
  if (/(FULL_NAME|PERSON_NAME|PATIENT_NAME|PERSON_NM)$/.test(field)) {
    return {
      id: 'patient-name',
      title: '排除测试患者',
      explanation: `使用当前脚本已有字段 ${qualified}，排除姓名等于指定测试名称的记录。`,
      requirement: '排除姓名为测试患者的记录',
      treatment: 'EXCLUDE',
      condition: { field: qualified, operator: 'EQ', value: '测试患者' },
    }
  }
  if (/(IS_DEL|DELETE_FLAG|DELETED|DEL_FLAG)$/.test(field)) {
    return {
      id: 'deleted',
      title: '只保留未删除的数据',
      explanation: `使用当前脚本已有字段 ${qualified}，只纳入删除标志为0的记录。`,
      requirement: '只保留未删除的数据',
      treatment: 'INCLUDE',
      condition: { field: qualified, operator: 'EQ', value: '0' },
    }
  }
  if (/(STATUS|STATE|STATUS_CODE|STATE_CODE)$/.test(field)) {
    return {
      id: 'status',
      title: '排除指定状态的数据',
      explanation: `使用当前脚本已有字段 ${qualified}，例如排除已作废、已取消或无效状态。`,
      requirement: '排除医院确认的无效状态记录',
      treatment: 'EXCLUDE',
      condition: { field: qualified, operator: 'EQ', value: '' },
    }
  }
  if (/(_AT|_TIME|_DATE|DATETIME|TIMESTAMP)$/.test(field)) {
    return {
      id: 'time',
      title: '只保留关键时间已经填写的数据',
      explanation: `使用当前脚本已有字段 ${qualified}，只纳入该时间不为空的记录。`,
      requirement: '只保留关键业务时间不为空的数据',
      treatment: 'INCLUDE',
      condition: { field: qualified, operator: 'IS_NOT_NULL', value: '' },
    }
  }
  if (/(DEPT|WARD).*(_ID|_NO|_CODE)$/.test(field)) {
    return {
      id: 'organization',
      title: '排除指定科室或病区',
      explanation: `使用当前脚本已有字段 ${qualified}，排除医院确认不参与统计的科室或病区。`,
      requirement: '排除医院确认不参与统计的科室或病区',
      treatment: 'EXCLUDE',
      condition: { field: qualified, operator: 'IN', value: '' },
    }
  }
  if (/(EVENT_NO|EVENT_CODE)$/.test(field)) {
    return {
      id: 'event',
      title: '只纳入指定事件编码',
      explanation: `使用当前脚本已有字段 ${qualified}，只纳入医院确认启用的事件编码。`,
      requirement: '只纳入医院确认启用的事件编码',
      treatment: 'INCLUDE',
      condition: { field: qualified, operator: 'IN', value: '' },
    }
  }
  if (/(_ID|_NO|_CODE)$/.test(field)) {
    return {
      id: 'business-key',
      title: '排除指定业务编号',
      explanation: `使用当前脚本已有字段 ${qualified}，排除医院已确认不应参与统计的编号。`,
      requirement: '排除医院确认不应参与统计的业务编号',
      treatment: 'EXCLUDE',
      condition: { field: qualified, operator: 'IN', value: '' },
    }
  }
  return null
}

function applyCapabilityExample(example: CapabilityExample) {
  investigationLayer.value = 'SOURCE_EXTRACT'
  investigationTreatment.value = example.treatment
  investigationRequirement.value = example.requirement
  investigationSql.value = ''
  investigationCandidateSql.value = ''
  investigationConditions.value = [{ ...example.condition }]
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

watch(
  () => props.snapshot.autonomousRun?.turns,
  () => {
    const saved = new Set(objectList(props.snapshot.autonomousRun?.turns)
      .map((turn) => String(turn.clientMessageId || '')))
    optimisticAutonomousTurns.value = optimisticAutonomousTurns.value
      .filter((turn) => !saved.has(String(turn.clientMessageId || '')))
  },
  { deep: true },
)

watch(
  () => autonomousTurns.value.map((turn, index) => String(turn.turnId || turn.clientMessageId || index)).join('|'),
  () => {
    const last = autonomousTurns.value[autonomousTurns.value.length - 1]
    if (!last) return
    const id = String(last.turnId || last.clientMessageId || autonomousTurns.value.length - 1)
    const copy = new Set(expandedAutonomousTurns.value)
    copy.add(id)
    expandedAutonomousTurns.value = copy
  },
  { immediate: true },
)

watch(
  () => `${props.snapshot.investigationMode}:${props.snapshot.currentStep}:${String(props.snapshot.caseInput.scopeType || '')}`,
  () => {
    const mode = props.snapshot.investigationMode
    if (mode === 'AUTONOMOUS') selectedMode.value = 'AUTONOMOUS'
    else if (String(props.snapshot.caseInput.scopeType || '').trim()
      || String(props.snapshot.caseInput.recordId || '').trim()
      || ['CASE_CALIBER_CLARIFICATION', 'CASE_INVESTIGATION', 'CHANGE_PROPOSAL',
        'SHADOW_TRIAL', 'DATA_REFRESH_REVIEW', 'DRAFT_SAVE', 'COMPLETED']
        .includes(props.snapshot.currentStep)) selectedMode.value = 'STANDARD'
  },
  { immediate: true },
)

watch(scopeFieldOptions, (values) => {
  if (!values.length) return
  const available = values.some((item) => String(item.value || item.field || '') === scopeField.value)
  if (!available) scopeField.value = String(values[0].value || values[0].field || '')
}, { immediate: true })

watch(recordKeyCandidates, (values) => {
  if (!values.length) return
  const available = values.some((item) => String(item.field || item.value || '') === recordField.value)
  if (!available) recordField.value = String(values[0].field || values[0].value || 'ENCOUNTER_ID')
}, { immediate: true })

function confirmCaseCaliber() {
  emit('action', 'CONFIRM_CASE_CALIBER', {
    confirmed: true,
  })
}

function selectStandardAction(action: 'REFRESH' | 'MODIFY_SOURCE' | 'MODIFY_OVERVIEW') {
  standardAction.value = action
  if (action === 'MODIFY_SOURCE') investigationLayer.value = 'SOURCE_EXTRACT'
  if (action === 'MODIFY_OVERVIEW') investigationLayer.value = 'OVERVIEW'
}

function runCurrentSqlShadow() {
  emit('action', 'RUN_CURRENT_SQL_SHADOW', {})
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

async function loadScopeClarification(force = false) {
  const type = String(props.snapshot.caseInput.scopeType || '')
  if (!['RECORD', 'DEPARTMENT'].includes(type)) return
  const key = [
    props.snapshot.caseId,
    type,
    props.snapshot.caseInput.recordField,
    props.snapshot.caseInput.recordId,
    JSON.stringify(props.snapshot.caseInput.recordIds || []),
    props.snapshot.caseInput.scopeField,
    props.snapshot.caseInput.scopeValue,
  ].join(':')
  if (!force && scopeClarificationKey.value === key && scopeClarification.value) return
  scopeClarificationLoading.value = true
  scopeClarificationError.value = ''
  try {
    scopeClarification.value = await fetchDiagnosisScopeClarification(
      props.token, props.snapshot.caseId,
    )
    scopeClarificationKey.value = key
  } catch (cause) {
    scopeClarification.value = null
    scopeClarificationError.value = cause instanceof Error
      ? cause.message : '患者或科室明细澄清失败。'
  } finally {
    scopeClarificationLoading.value = false
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
  'DATA_REFRESH_REVIEW',
  'DRAFT_SAVE',
  'COMPLETED',
]
const stageRank = computed(() => Math.max(0, stageOrder.indexOf(props.snapshot.currentStep)))
const caseInputReached = computed(() => stageRank.value >= stageOrder.indexOf('CASE_INPUT'))
const caseSubmitted = computed(() => props.snapshot.investigationMode !== 'AUTONOMOUS'
  && (Boolean(String(props.snapshot.caseInput.scopeType || '').trim())
    || Boolean(String(props.snapshot.caseInput.recordId || '').trim())))
watch(
  () => `${caseSubmitted.value}:${props.snapshot.caseId}:${String(props.snapshot.caseInput.scopeType || '')}:${String(props.snapshot.caseInput.recordId || '')}:${String(props.snapshot.caseInput.scopeValue || '')}`,
  () => {
    if (caseSubmitted.value
      && ['RECORD', 'DEPARTMENT'].includes(String(props.snapshot.caseInput.scopeType || ''))) {
      void loadScopeClarification()
    }
  },
  { immediate: true },
)
const investigationReached = computed(() => (
  props.snapshot.investigationMode !== 'AUTONOMOUS'
  && selectedMode.value === 'STANDARD'
  && (stageRank.value >= stageOrder.indexOf('CASE_INVESTIGATION')
    || props.snapshot.evidence.some((item) => String(item.type || '') !== 'AUTONOMOUS_TOOL'))
))
const currentBaseGate = computed(() => baseSteps.find((item) => item.key === props.snapshot.currentStep)?.gate || 0)
const currentGateHasResult = computed(() => currentBaseGate.value > 0 && Boolean(gate(currentBaseGate.value)))
const allBaseChecksPassed = computed(() => baseSteps.every((item) => stepState(item.gate) === 'PASSED'))
const baseConclusion = computed(() => {
  if (blockedGateCount.value) return '基础校验发现需要处理的问题，修复当前步骤后才能继续。'
  if (allBaseChecksPassed.value) return '三项基础校验均已通过，可以选择标准排查或 DeepSeek 自主排查。'
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

function normalizedResult(value: unknown): Record<string, unknown> {
  return Array.isArray(value) ? record(value[0]) : record(value)
}

function normalizedMetricResult(value: unknown, fallbackStatus = ''): Record<string, unknown> {
  const source = normalizedResult(value)
  const entries = Object.entries(source)
  const find = (predicate: (key: string) => boolean) => entries.find(([key]) => predicate(key))?.[1]
  return {
    numeratorCount: source.numeratorCount ?? find((key) => key.startsWith('分子')),
    denominatorCount: source.denominatorCount ?? find((key) => key.startsWith('分母')),
    resultValue: source.resultValue ?? find((key) => key === '监测情况' || key.includes('结果值')),
    status: source.status ?? fallbackStatus,
  }
}

function displayResult(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

function resultChange(before: unknown, after: unknown): string {
  if (after === null || after === undefined || after === '') return '待试跑'
  const beforeNumber = Number(before)
  const afterNumber = Number(after)
  if (before !== '' && before !== null && before !== undefined
    && Number.isFinite(beforeNumber) && Number.isFinite(afterNumber)) {
    const difference = afterNumber - beforeNumber
    return difference > 0 ? `+${difference}` : String(difference)
  }
  return String(before) === String(after) ? '无变化' : `${displayResult(before)} → ${displayResult(after)}`
}

async function copySql(key: string, value: unknown) {
  const sql = String(value || '').trim()
  if (!sql) return
  await navigator.clipboard.writeText(sql)
  copiedSqlKey.value = key
  window.setTimeout(() => {
    if (copiedSqlKey.value === key) copiedSqlKey.value = ''
  }, 1600)
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

    <article v-if="snapshot.currentStep === 'CASE_INPUT' && snapshot.investigationMode !== 'AUTONOMOUS'" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 选择排查方式</strong></div>
        <div class="diagnosis-mode-grid">
          <button type="button" :class="{ active: selectedMode === 'STANDARD' }" @click="selectedMode = 'STANDARD'"><strong>标准模式</strong><span>按字段和条件由程序安全改写</span></button>
          <button type="button" :class="{ active: selectedMode === 'AUTONOMOUS' }" @click="selectedMode = 'AUTONOMOUS'"><strong>DeepSeek 自主排查</strong><span>自主阅读 Wiki、查询双库并组织证据</span></button>
        </div>
        <template v-if="selectedMode === 'AUTONOMOUS'">
          <p>请直接描述异常现象。DeepSeek V4 Pro 会自主选择 Wiki 页面和只读查询；程序仍负责 SQL 安全、表范围、影子试跑和对账。</p>
          <textarea v-model="autonomousProblem" rows="5" maxlength="3000" placeholder="例如：骨伤一科在科室明细中没有手术患者，请定位数据在哪一步消失。"></textarea>
          <button type="button" class="diagnosis-primary" :disabled="busy || !autonomousProblem.trim()" @click="startAutonomous">开始 DeepSeek 自主排查</button>
        </template>
      </div>
    </article>

    <template v-if="caseInputReached && snapshot.investigationMode !== 'AUTONOMOUS' && selectedMode === 'STANDARD'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 选择排查范围</strong></div><p>先说明问题发生在哪个范围。具体患者或业务编号只在排查某条记录时需要；科室漏数、时间异常和整体结果偏差不要求患者编号。</p><p class="diagnosis-help">系统根据当前指标 SQL 和字段字典列出可用范围，不由前端猜测记录类型。</p></div></article>
      <article v-if="snapshot.currentStep === 'CASE_INPUT'" class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 排查范围</strong></div>
        <div class="diagnosis-scope-options" role="radiogroup" aria-label="排查范围">
          <button type="button" :class="{ active: scopeType === 'RECORD' }" @click="scopeType = 'RECORD'">某条记录</button>
          <button type="button" :class="{ active: scopeType === 'DEPARTMENT' }" :disabled="!departmentCandidates.length" @click="scopeType = 'DEPARTMENT'">某个科室/病区</button>
          <button type="button" :class="{ active: scopeType === 'TIME_RANGE' }" :disabled="!timeFieldCandidates.length" @click="scopeType = 'TIME_RANGE'">某个时间段</button>
          <button type="button" :class="{ active: scopeType === 'DATA_CATEGORY' }" :disabled="!conditionFieldCandidates.length" @click="scopeType = 'DATA_CATEGORY'">某类数据</button>
          <button type="button" :class="{ active: scopeType === 'OVERALL' }" @click="scopeType = 'OVERALL'">整体结果</button>
        </div>
        <div class="diagnosis-case-grid">
          <template v-if="scopeType === 'RECORD'"><label>记录类型<select v-model="recordField"><option v-for="field in recordKeyCandidates" :key="String(field.value || field.field)" :value="String(field.field || field.value)">{{ field.displayName || field.label || field.field }}</option><option v-if="!recordKeyCandidates.length" value="ENCOUNTER_ID">就诊号</option></select></label><label>记录编号（最多20个）<textarea v-model="recordId" rows="3" maxlength="2100" placeholder="多个编号用逗号或换行分隔" /></label></template>
          <template v-else-if="scopeType === 'DEPARTMENT'"><label>科室/病区字段<select v-model="scopeField"><option v-for="field in departmentCandidates" :key="String(field.value)" :value="String(field.value)">{{ field.displayName || field.label }}</option></select></label><label>科室名称或代码<input v-model="scopeValue" placeholder="例如：骨伤一科" /></label></template>
          <template v-else-if="scopeType === 'TIME_RANGE'"><label>时间字段<select v-model="scopeField"><option v-for="field in timeFieldCandidates" :key="String(field.value)" :value="String(field.value)">{{ field.displayName || field.label }}</option></select></label><label>开始时间<input v-model="scopeStart" type="datetime-local" /></label><label>结束时间<input v-model="scopeEnd" type="datetime-local" /></label></template>
          <template v-else-if="scopeType === 'DATA_CATEGORY'"><label>数据字段<select v-model="scopeField"><option v-for="field in conditionFieldCandidates" :key="String(field.value)" :value="String(field.value)">{{ field.displayName || field.label }}</option></select></label><label>要核对的数据范围<input v-model="scopeValue" placeholder="例如：已作废会诊、ICU病区" /></label></template>
          <label class="wide">{{ scopeType === 'OVERALL' ? '结果差异说明' : '补充说明（可选）' }}<textarea v-model="caseDescription" rows="3" maxlength="1200" :placeholder="scopeType === 'OVERALL' ? '例如：医院同期约500人次，系统只有417人次' : '补充现场看到的异常；不知道可以不填。'"></textarea></label>
        </div>
        <p class="diagnosis-pass-rule"><strong>进入下一步：</strong>系统会结合当前口径说明分母、分子和数据链路，再让你选择重新抽取或修改 SQL。</p><button type="button" class="diagnosis-primary" :disabled="busy || !canSubmitScope" @click="submitCase">确认排查范围</button>
      </div></article>
    </template>

    <template v-if="snapshot.investigationMode === 'AUTONOMOUS'">
      <template v-for="(turn, turnIndex) in autonomousTurns" :key="String(turn.turnId || turn.clientMessageId || turnIndex)">
        <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员</strong><span v-if="turn.status === 'SENDING'">发送中</span><span v-else-if="turn.status === 'QUEUED'">已排队</span><span v-else-if="turn.status === 'FAILED'">发送失败</span></div><p>{{ turn.userMessage }}</p><p v-if="turn.errorMessage" class="diagnosis-template-warning">{{ turn.errorMessage }}</p></div></article>
        <article v-if="mergedTurnEvents(turn).length" class="message is-agent diagnosis-process-message"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card diagnosis-process-card diagnosis-connected-reply">
          <div class="message-head"><strong>DeepSeek · 排查回复</strong><span>{{ autonomousTurnTitle(turn) }} · {{ autonomousStatusText(turn.status) }}</span></div>
          <p class="diagnosis-agent-disclosure">本条回复按顺序连接模型的分析、工具证据、阶段性结论和总结果；不把不可验证的内部 token 当作事实。</p>
          <ol class="diagnosis-agent-timeline diagnosis-evidence-track">
            <li v-for="event in mergedTurnEvents(turn)" :key="String(event.seq || event.toolCallId)" :data-kind="autonomousEventKind(event) === 'reply' ? replyDataKind(event) : autonomousEventKind(event)">
              <details v-if="autonomousEventKind(event) === 'analysis'" class="diagnosis-thinking" :open="turnIsExpanded(turn, turnIndex)" @toggle="toggleTurnEvent(turn, turnIndex, $event)">
                <summary><span class="diagnosis-agent-step">{{ event.iteration || '—' }}.{{ event.seq }}</span><span><strong>{{ autonomousEventTitle(event) }}</strong><small>公开、可审计</small></span><em>{{ turnIsExpanded(turn, turnIndex) ? '收起' : '展开' }} · {{ autonomousStatusText(autonomousEventStatus(event, turn)) }}</em></summary>
                <dl class="diagnosis-public-analysis"><div v-for="item in analysisItems(event)" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ displayAnalysisValue(item.value) }}</dd></div></dl>
              </details>
              <template v-else-if="autonomousEventKind(event) === 'model'">
                <div class="diagnosis-agent-event-head"><span class="diagnosis-agent-step">{{ event.iteration || '—' }}.{{ event.seq }}</span><strong>{{ autonomousEventDisplayTitle(event, turn) }}</strong><span :data-state="autonomousEventStatus(event, turn).toLowerCase()">{{ autonomousStatusText(autonomousEventStatus(event, turn)) }}</span></div>
                <p v-if="event.summary">{{ event.summary }}</p>
              </template>
              <template v-else-if="autonomousEventKind(event) === 'tool'">
                <div class="diagnosis-agent-event-head"><span class="diagnosis-agent-step">{{ event.iteration || '—' }}.{{ event.seq }}</span><strong>调用工具：{{ event.toolDisplayName || event.tool }}</strong><span :data-state="autonomousEventStatus(event, turn).toLowerCase()">{{ autonomousStatusText(autonomousEventStatus(event, turn)) }}<template v-if="event.durationMs !== undefined"> · {{ event.durationMs }}ms</template></span></div>
                <p v-if="event.summary" class="diagnosis-agent-observation"><strong>观察：</strong>{{ event.summary }}</p>
                <details v-if="hasAutonomousDetails(event)" class="diagnosis-technical diagnosis-agent-details"><summary>查看工具输入、结果和证据</summary><section v-if="event.arguments"><strong>输入参数</strong><pre>{{ pretty(event.arguments) }}</pre></section><section v-if="event.resultPreview"><strong>返回结果（最多预览10行）</strong><pre>{{ pretty(event.resultPreview) }}</pre></section><section v-if="event.error"><strong>错误</strong><pre>{{ event.error }}</pre></section><small v-if="event.evidenceId">证据编号：{{ event.evidenceId }}</small></details>
              </template>
              <template v-else-if="autonomousEventKind(event) === 'reply'">
                <div class="diagnosis-agent-event-head"><span class="diagnosis-agent-step">{{ event.iteration || '—' }}.{{ event.seq }}</span><strong>{{ autonomousEventType(event) === 'CONCLUSION' ? '排查总结果' : autonomousEventType(event) === 'QUESTION' ? '需要现场补充' : '阶段性回复' }}</strong><span :data-state="autonomousEventStatus(event, turn).toLowerCase()">{{ autonomousStatusText(autonomousEventStatus(event, turn)) }}</span></div>
                <p v-if="event.answer || event.conclusion" class="diagnosis-agent-answer">{{ event.answer || event.conclusion }}</p>
                <small v-if="event.conclusionLevel">结论等级：{{ event.conclusionLevel }}</small>
                <template v-if="autonomousEventType(event) === 'QUESTION'">
                  <p class="diagnosis-agent-question">{{ event.question }}</p>
                  <div v-if="isPendingQuestion(turn, event)" class="diagnosis-inline-answer">
                    <label for="autonomous-inline-answer"><strong>在这里填写或补充现场确认结果，本条回复会继续排查：</strong></label>
                    <textarea id="autonomous-inline-answer" v-model="autonomousAnswer" rows="3" maxlength="3000" placeholder="填写医院现场确认结果"></textarea>
                    <div class="diagnosis-composer-actions"><button type="button" class="diagnosis-primary" :disabled="busy || !autonomousAnswer.trim()" @click="respondAutonomous">发送回答并继续本轮排查</button></div>
                  </div>
                </template>
              </template>
              <template v-else-if="autonomousEventKind(event) === 'stop'">
                <div class="diagnosis-agent-event-head"><span class="diagnosis-agent-step">{{ event.iteration || '—' }}.{{ event.seq }}</span><strong>循环已停止</strong><span :data-state="autonomousEventStatus(event, turn).toLowerCase()">{{ autonomousStatusText(autonomousEventStatus(event, turn)) }}</span></div>
                <p v-if="event.summary">{{ event.summary }}</p>
              </template>
              <p v-else-if="event.summary" class="diagnosis-agent-observation">{{ event.summary }}</p>
            </li>
          </ol>
          <button v-if="snapshot.autonomousRun.status === 'RUNNING' && turnIndex === autonomousTurns.length - 1" type="button" class="diagnosis-secondary" :disabled="busy" @click="emit('action', 'CANCEL_AUTONOMOUS_INVESTIGATION', {})">停止本轮</button>
        </div></article>
      </template>
    </template>

    <template v-if="caseSubmitted">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 排查范围</strong></div><p>{{ submittedScopeSummary }}</p><p v-if="snapshot.caseInput.caseDescription && String(snapshot.caseInput.scopeType || '') !== 'OVERALL'">{{ snapshot.caseInput.caseDescription }}</p></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ caliberClarificationTitle }}</strong><span>{{ snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION' ? '等待确认' : '已确认' }}</span></div><section class="diagnosis-caliber-section"><h4>当前生效口径：{{ snapshot.caseExpectedClassification.profileName || snapshot.profileId }}</h4><p>{{ snapshot.caseExpectedClassification.definition || '知识库未单独描述指标定义' }}</p><dl class="caliber-facts"><div><dt>计算公式</dt><dd>{{ snapshot.caseExpectedClassification.formula || '知识库未单独描述' }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl></section><section class="diagnosis-caliber-section"><h4>本次分子和分母</h4><dl class="caliber-facts"><div><dt>分子 {{ snapshot.caseExpectedClassification.numeratorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.numeratorRule || '知识库未单独描述' }}</dd></div><div><dt>分母 {{ snapshot.caseExpectedClassification.denominatorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.denominatorRule || '知识库未单独描述' }}</dd></div></dl><div class="indicator-detail-groups diagnosis-detail-actions"><button type="button" :class="{ active: detailOpen && detailGroup === 'numerator' }" :disabled="detailLoading" @click="loadAggregateDetail('numerator')">查看分子明细（{{ snapshot.caseExpectedClassification.numeratorCount ?? '—' }}条）</button><button type="button" :class="{ active: detailOpen && detailGroup === 'denominator' }" :disabled="detailLoading" @click="loadAggregateDetail('denominator')">查看分母明细（{{ snapshot.caseExpectedClassification.denominatorCount ?? '—' }}条）</button></div><div v-if="detailOpen" class="diagnosis-detail-panel"><p v-if="detailLoading" class="indicator-loading">正在按本次口径重新对账并加载明细…</p><p v-else-if="detailError" class="indicator-error">{{ detailError }}</p><template v-else-if="detailPages[detailGroup]"><div class="detail-contract-summary"><span>汇总 {{ detailPages[detailGroup]?.cardNumerator ?? 0 }}/{{ detailPages[detailGroup]?.cardDenominator ?? 0 }}</span><span>明细 {{ detailPages[detailGroup]?.detailNumerator ?? 0 }}/{{ detailPages[detailGroup]?.detailDenominator ?? 0 }}</span><strong>对账通过</strong><small v-if="detailPages[detailGroup]?.snapshotReused">已复用本次排查明细</small></div><p class="indicator-detail-summary">{{ detailGroup === 'numerator' ? '分子' : '分母' }}共 {{ detailPages[detailGroup]?.rowCount || 0 }} 条 · 第 {{ detailPages[detailGroup]?.page || 1 }}/{{ detailPageCount(detailPages[detailGroup]) }} 页</p><DetailRowsTable :rows="detailPages[detailGroup]?.rows || []" empty-text="本次统计窗口没有对应明细。" /><nav v-if="detailPageCount(detailPages[detailGroup]) > 1" class="detail-pagination" aria-label="排查明细分页"><button type="button" :disabled="(detailPages[detailGroup]?.page || 1) <= 1" @click="loadAggregateDetail(detailGroup, (detailPages[detailGroup]?.page || 1) - 1)">上一页</button><span>{{ detailPages[detailGroup]?.page || 1 }} / {{ detailPageCount(detailPages[detailGroup]) }}</span><button type="button" :disabled="(detailPages[detailGroup]?.page || 1) >= detailPageCount(detailPages[detailGroup])" @click="loadAggregateDetail(detailGroup, (detailPages[detailGroup]?.page || 1) + 1)">下一页</button></nav></template></div><p class="diagnosis-help">明细必须重新聚合为上面的分子和分母才会展示；数量对不上时系统会拒绝返回。</p></section><section class="diagnosis-caliber-section"><h4>当前口径数据链路</h4><p class="diagnosis-flow-path">{{ flowPath(snapshot.caseExpectedClassification.dataFlow) }}</p><details class="diagnosis-technical"><summary>展开数据链路、涉及表和 SQL</summary><IndicatorDataFlowPanel :flow="snapshot.caseExpectedClassification.dataFlow" /></details></section><p><strong>判断顺序：</strong>{{ caliberJudgementOrder }}</p><template v-if="snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION'"><p class="diagnosis-pass-rule"><strong>下一步：</strong>确认当前口径后，实施人员提交要纳入或排除的数据条件；系统将自动生成候选 SQL并进行影子试跑。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="confirmCaseCaliber">确认澄清，进入抽取数据核对</button></template></div></article>
    </template>

    <article
      v-if="caseSubmitted && ['RECORD', 'DEPARTMENT'].includes(String(snapshot.caseInput.scopeType || ''))"
      class="message is-agent"
    >
      <div class="message-avatar">AI</div>
      <div class="message-card diagnosis-turn-card diagnosis-scope-clarification">
        <div class="message-head">
          <strong>{{ scopeClarificationTitle }}</strong>
          <span>{{ scopeClarificationStatus }}</span>
        </div>
        <p v-if="scopeClarificationLoading" class="indicator-loading">
          正在用本次已对账的分子、分母明细核对这个对象…
        </p>
        <template v-else-if="scopeClarification">
          <section class="diagnosis-scope-verdict" :data-status="scopeClarification.status">
            <h4>{{ scopeClarification.summary }}</h4>
            <dl>
              <div><dt>分母明细</dt><dd>{{ scopeClarification.denominatorCount }} 条</dd></div>
              <div><dt>分子明细</dt><dd>{{ scopeClarification.numeratorCount }} 条</dd></div>
              <div><dt>统计时间</dt><dd>{{ scopeClarification.statStart }} 至 {{ scopeClarification.statEnd }}</dd></div>
            </dl>
          </section>
          <section class="diagnosis-scope-reasons">
            <h4>{{ submittedScopeType === 'RECORD' ? '为什么会在明细里' : '这个科室如何计数' }}</h4>
            <ol><li v-for="reason in scopeClarification.reasons" :key="reason">{{ reason }}</li></ol>
            <p v-if="scopeClarification.matchedFields.length" class="diagnosis-help">
              实际匹配字段：{{ scopeClarification.matchedFields.join('、') }}
            </p>
          </section>
          <details v-if="scopeClarification.sampleRows.length" class="diagnosis-technical diagnosis-scope-samples">
            <summary>查看命中的明细样例（{{ scopeClarification.sampleRows.length }}条）</summary>
            <DetailRowsTable :rows="scopeClarification.sampleRows" empty-text="没有命中的明细。" />
            <p v-if="scopeClarification.sampleTruncated" class="diagnosis-help">这里只展示前10条，完整记录仍在分子、分母明细中查看。</p>
          </details>
          <p class="diagnosis-scope-proof">
            <strong>说明依据：</strong>同一统计 SQL版本、同一统计窗口，并且明细重新聚合后与卡片分子分母一致。
          </p>
        </template>
        <template v-else>
          <p class="indicator-error">{{ scopeClarificationError || '目前无法生成该对象的明细说明。' }}</p>
          <p class="diagnosis-help">这不代表对象一定不应纳入，只表示当前口径暂不能安全生成可对账明细。</p>
        </template>
        <button
          type="button"
          class="diagnosis-secondary"
          :disabled="busy || scopeClarificationLoading"
          @click="loadScopeClarification(true)"
        >重新核对</button>
      </div>
    </article>

    <template v-if="investigationReached">
      <article class="message is-agent">
        <div class="message-avatar">AI</div>
        <div class="message-card diagnosis-turn-card">
          <div class="message-head"><strong>系统 · 选择本轮处理方式</strong><span>三选一</span></div>
          <p>如果怀疑只是上次同步不完整，先用当前正式口径重新抽取；只有证据证明规则本身有问题时再修改 SQL。</p>
          <div class="diagnosis-mode-grid diagnosis-standard-actions">
            <button type="button" :class="{ active: standardAction === 'REFRESH' }" @click="selectStandardAction('REFRESH')"><strong>重新抽取并核对</strong><span>SQL 不变，核对最新业务数据与当前正式结果</span></button>
            <button type="button" :class="{ active: standardAction === 'MODIFY_SOURCE' }" @click="selectStandardAction('MODIFY_SOURCE')"><strong>修改抽取 SQL</strong><span>处理多抽、少抽、重复或业务筛选问题</span></button>
            <button type="button" :class="{ active: standardAction === 'MODIFY_OVERVIEW' }" @click="selectStandardAction('MODIFY_OVERVIEW')"><strong>修改统计 SQL</strong><span>仅用于中间表正确、分子分母判定错误</span></button>
          </div>
          <section v-if="snapshot.currentStep === 'CASE_INVESTIGATION' && standardAction === 'REFRESH'" class="diagnosis-input-template diagnosis-refresh-option">
            <h4>使用当前正式口径重新抽取</h4>
            <p>系统会用完全相同的正式抽取 SQL 写入隔离影子表，对比记录数、分子、分母和结果。不会修改正式中间表，也不需要填写业务规则。</p>
            <button type="button" class="diagnosis-primary" :disabled="busy" @click="runCurrentSqlShadow">开始重新抽取核对</button>
          </section>
          <details v-if="standardAction !== 'REFRESH'" class="diagnosis-capability" open>
            <summary>当前指标能自动修改什么</summary>
            <div class="diagnosis-capability-grid">
              <section>
                <strong>可以自动处理：当前抽取 SQL 的具体示例</strong>
                <p>以下示例只使用当前脚本中已经存在、且程序能确定查询位置的字段。点击后会自动填入修改条件，你只需核对字段和值。</p>
                <div v-if="automaticCapabilityExamples.length" class="diagnosis-capability-examples">
                  <button
                    v-for="example in automaticCapabilityExamples"
                    :key="example.id"
                    type="button"
                    @click="applyCapabilityExample(example)"
                  >
                    <strong>{{ example.title }}</strong>
                    <span>{{ example.explanation }}</span>
                    <em>填入这个示例</em>
                  </button>
                </div>
                <p v-else>当前抽取 SQL没有识别出能安全自动填写的字段。程序不会猜字段，应由实施人员核对脚本后填写完整候选 SELECT。</p>
              </section>
              <section>
                <strong>当前抽取 SQL 暂时不能自动处理</strong>
                <p>下面这些修改会改变记录怎样关联、去重或计算，放错查询层就可能多算或少算，因此当前程序会停止自动改写。</p>
                <ul class="diagnosis-unsupported-list">
                  <li v-for="item in unsupportedCapabilities" :key="item.id">
                    <strong>{{ item.title }}</strong>
                    <span>{{ item.reason }}</span>
                  </li>
                </ul>
              </section>
            </div>
            <p><strong>以后怎么解决：</strong>需要增加能识别 JOIN、子查询、去重、窗口函数和聚合层级的 T-SQL 结构解析，再为每类修改建立确定性改写规则，并继续通过影子试跑验证记录数、分子、分母和输出结构。自主模式中的 DeepSeek 可以提出完整候选 SQL，但仍不能绕过程序校验。</p>
          </details>
          <template v-if="snapshot.currentStep === 'CASE_INVESTIGATION' && standardAction !== 'REFRESH'">
            <div class="diagnosis-change-grid">
              <label>本轮修改脚本<input :value="standardAction === 'MODIFY_OVERVIEW' ? '目标表概览统计 SQL' : '源表抽取 SQL'" disabled /></label>
              <label>处理方式<select v-model="investigationTreatment"><option value="EXCLUDE">排除符合条件的数据</option><option value="INCLUDE">只纳入符合条件的数据</option></select></label>
            </div>
            <section class="diagnosis-input-template">
              <header><strong>1. 填写医院确认的业务规则（必填）</strong><button type="button" class="diagnosis-text-action" @click="fillInvestigationTemplates">清空重填</button></header>
              <p>用大白话说明医院要改什么，例如“排除姓名为测试患者的记录”。这句话用于记录医院的修改依据和生成草稿说明；真正改写 SQL 时，系统以下面的字段、条件和值为准。</p>
              <textarea v-model="investigationRequirement" rows="3" aria-label="医院确认的业务规则" placeholder="例如：排除姓名为测试患者的记录"></textarea>
            </section>
            <section class="diagnosis-input-template">
              <header><strong>2. 填写判断条件</strong><button type="button" class="diagnosis-text-action" @click="addInvestigationCondition">增加条件</button></header>
              <p>优先显示中文业务名称，第二行保留程序实际使用的表和技术字段。提交时仍会重新校验字段所属查询层。</p>
              <div v-for="(condition, index) in investigationConditions" :key="index" class="diagnosis-condition-row">
                <label>判断字段<select v-model="condition.field" :aria-label="`判断字段${index + 1}`"><option value="">请选择字段</option><option v-for="field in candidateRuleFields" :key="String(field.value)" :value="String(field.value)">{{ field.displayName || field.field }}｜{{ field.tableName }} · {{ field.technicalName || field.value }}</option></select></label>
                <label>判断条件<select v-model="condition.operator"><option v-for="option in operatorOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
                <label v-if="!operatorHasNoValue(condition.operator)">判断值<input v-model="condition.value" :aria-label="`判断值${index + 1}`" placeholder="多个值用逗号分隔" /></label>
                <div v-else class="diagnosis-condition-empty">该条件不需要填写值</div>
                <button type="button" class="diagnosis-text-action" @click="removeInvestigationCondition(index)">删除</button>
              </div>
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

    <template v-if="snapshot.currentStep === 'DATA_REFRESH_REVIEW'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 原口径重新抽取核对</strong><span :data-state="dataRefreshCompleted ? 'passed' : 'failed'">{{ dataRefreshCompleted ? '已完成影子核对' : '执行未完成' }}</span></div>
        <p>本轮 SQL 没有任何修改。下表只比较“当前正式快照”和“使用同一份正式 SQL 重新查询业务库后”的差异，用于判断是否只是数据后来补齐或同步不完整。</p>
        <section v-if="extractionComparisonRows.length" class="diagnosis-result-compare"><header><div><h4>抽取数据变化</h4><p>候选影子中间表实际代表本次重新抽取结果。</p></div><span :data-state="shadowTrialState.state">{{ shadowTrialState.text }}</span></header><div class="diagnosis-compare-table"><div class="is-head"><span>对比项</span><span>当前正式中间表</span><span>本次重新抽取</span><span>变化</span></div><div v-for="row in extractionComparisonRows" :key="row.key"><strong>{{ row.label }}</strong><span>{{ row.baseline }}</span><span>{{ row.candidate }}</span><em>{{ row.change }}</em></div></div></section>
        <section class="diagnosis-result-compare"><header><div><h4>指标结果变化</h4><p>两侧均使用同一份正式概览 SQL，区别只在抽取数据的新旧。</p></div></header><div class="diagnosis-compare-table"><div class="is-head"><span>对比项</span><span>当前正式结果</span><span>本次重新抽取结果</span><span>变化</span></div><div v-for="row in resultComparisonRows" :key="row.key"><strong>{{ row.label }}</strong><span>{{ row.baseline }}</span><span>{{ row.candidate }}</span><em>{{ row.change }}</em></div></div></section>
        <p v-if="!dataRefreshCompleted" class="diagnosis-template-warning">影子重新抽取没有完成，不能更新正式结果。请查看执行错误后重试。</p>
        <p v-else class="diagnosis-pass-rule"><strong>下一步：</strong>如果新结果符合现场情况，点击正式重新计算；系统才会使用当前正式口径更新正式中间表和卡片结果。</p>
        <button v-if="dataRefreshCompleted" type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'FORMAL_RECALCULATE_CURRENT', {})">确认并正式重新计算</button>
      </div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CHANGE_PROPOSAL'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 已确认原因</strong></div><p>{{ snapshot.causeConclusion.conclusion }}</p></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请提供修改要求</strong></div><p>说明要改抽取还是统计、增加或删除什么条件、使用什么字段和值，以及这条案例修改后应该怎样变化。最好附完整只读 SQL。</p></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 修改要求</strong></div><div class="diagnosis-change-grid"><label>问题分支<select v-model="changeType"><option value="DATA_REPAIR">数据修复</option><option value="EVENT_CONFIG">事件配置</option><option value="SQL_CHANGE">SQL 修改</option><option value="CALIBER_CHANGE">医院口径变更</option></select></label><label v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'">只修改一层<select v-model="changeLayer"><option value="SOURCE_EXTRACT">抽取 SQL</option><option value="OVERVIEW">统计 SQL</option></select></label></div><textarea v-model="requirements" rows="3" placeholder="写清新增/删除条件、字段、操作符和值，以及对案例的预期影响"></textarea><textarea v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'" v-model="candidateSql" class="diagnosis-sql-input" rows="8" placeholder="复杂结构修改必须由实施人员粘贴一条完整候选 SELECT；模型不会重写整段 SQL"></textarea><p class="diagnosis-pass-rule"><strong>本轮产物：</strong>原 SQL、候选 SQL、差异说明和程序校验结果。校验不通过不能试跑。</p><button type="button" class="diagnosis-primary" :disabled="busy || !requirements.trim()" @click="buildCandidate">发送要求，由系统组装 SQL</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'SHADOW_TRIAL' || snapshot.currentStep === 'DRAFT_SAVE'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 候选语句已生成</strong></div><p>候选语句已通过安全校验，系统随后会自动执行影子试跑，无需再点击单独的“试跑”按钮。正式中间表和当前卡片不会被覆盖。</p><p v-if="snapshot.candidateSql.generationMethod" class="diagnosis-help">生成方式：{{ snapshot.candidateSql.generationMethod }}</p>
        <section v-if="shadowTrialExecutionFailed" class="diagnosis-result-compare"><header><div><h4>影子试跑执行失败</h4><p>系统已经尝试执行候选语句，但在生成影子结果前失败，因此本轮没有可比较的记录数、分子、分母或结果值。</p></div><span data-state="failed">试跑执行失败</span></header><p class="diagnosis-template-warning"><strong>失败原因：</strong>{{ shadowTrialFailureMessage }}</p></section>
        <section v-if="!shadowTrialExecutionFailed && extractionComparisonRows.length" class="diagnosis-result-compare"><header><div><h4>第一层：抽取数据变化</h4><p>对比当前正式中间表和候选抽取 SQL 写入的影子中间表。这里直接说明候选抽取到底多了或少了哪些记录。</p></div><span :data-state="shadowTrialState.state">{{ shadowTrialState.text }}</span></header><div class="diagnosis-compare-table"><div class="is-head"><span>对比项</span><span>当前正式中间表</span><span>候选影子中间表</span><span>变化</span></div><div v-for="row in extractionComparisonRows" :key="row.key"><strong>{{ row.label }}</strong><span>{{ row.baseline }}</span><span>{{ row.candidate }}</span><em>{{ row.change }}</em></div></div></section>
        <section v-if="!shadowTrialExecutionFailed && extractionComparisonRows.length" class="diagnosis-diff-browser"><header><div><h4>抽取前后差异明细</h4><p>逐行预览新增、减少、字段变化和新增重复的记录，直接看到每条记录的字段值；可按业务编号搜索。</p></div></header><div class="diagnosis-diff-toolbar"><select v-model="diffType"><option value="ADDED">新增记录</option><option value="REMOVED">减少记录</option><option value="CHANGED">字段变化</option><option value="DUPLICATE">新增重复</option></select><input v-model="diffSearch" placeholder="搜索业务编号" /><button type="button" class="diagnosis-secondary" :disabled="diffLoading" @click="loadShadowDiffs(1)">查看差异</button></div><p v-if="diffError" class="diagnosis-template-warning">{{ diffError }}</p><template v-if="diffPage"><p class="diagnosis-help">共 {{ diffPage.total }} 个业务编号；本页逐条记录展开为 {{ diffTableRows.length }} 行。变化字段列排在最前并高亮，表格可左右滚动查看全部字段。</p><div v-if="diffTableRows.length" class="diagnosis-diff-preview"><table><thead><tr><th>业务编号</th><th>记录来源</th><th v-for="field in diffTableColumns" :key="field" :data-changed="diffChangedColumns.has(field)">{{ field }}</th></tr></thead><tbody><tr v-for="item in diffTableRows" :key="item.id" :data-side="item.sideKind"><th scope="row"><code v-if="item.first">{{ item.businessKey }}</code></th><td>{{ item.side }}</td><td v-for="field in diffTableColumns" :key="field" :data-changed="item.changedFields.includes(field)">{{ diffCellText(item.row[field]) }}</td></tr></tbody></table></div><p v-else class="diagnosis-help">本页没有可展开的记录字段快照。</p><details class="diagnosis-technical"><summary>按业务编号查看原始 JSON 证据</summary><details v-for="item in diffPage.items" :key="item.businessKey" class="diagnosis-technical"><summary>{{ item.businessKey }}<span v-if="item.changedFields.length"> · {{ item.changedFields.join('、') }}</span></summary><div class="diagnosis-before-after"><section><strong>修改前</strong><pre>{{ pretty(item.beforeRows) }}</pre></section><section><strong>修改后</strong><pre>{{ pretty(item.afterRows) }}</pre></section></div></details></details><div class="diagnosis-pagination"><button type="button" :disabled="diffPage.page <= 1 || diffLoading" @click="loadShadowDiffs(diffPage.page - 1)">上一页</button><span>第 {{ diffPage.page }} 页</span><button type="button" :disabled="diffPage.page * diffPage.pageSize >= diffPage.total || diffLoading" @click="loadShadowDiffs(diffPage.page + 1)">下一页</button></div></template></section>
        <section v-if="!shadowTrialExecutionFailed" class="diagnosis-result-compare"><header><div><h4>{{ extractionComparisonRows.length ? '第二层：最终指标结果变化' : '正式结果与候选试跑对比' }}</h4><p v-if="extractionComparisonRows.length">抽取 SQL 修改前后都使用同一份正式概览 SQL 计算。概览 SQL 在这里只负责测量抽取数据变化后的分子、分母和结果，本身没有被修改。</p><p v-else>左侧是当前公版正式结果，右侧是候选 {{ sqlLayerTitle }} 在影子环境的结果。</p></div><span :data-state="shadowTrialState.state">{{ shadowTrialState.text }}</span></header><div class="diagnosis-compare-table"><div class="is-head"><span>对比项</span><span>当前正式结果</span><span>候选试跑结果</span><span>变化</span></div><div v-for="row in resultComparisonRows" :key="row.key"><strong>{{ row.label }}</strong><span>{{ row.baseline }}</span><span>{{ row.candidate }}</span><em>{{ row.change }}</em></div></div></section>
        <section v-if="!shadowTrialExecutionFailed && caseValidationRows.length" class="diagnosis-case-reconcile"><header><div><h4>案例编号验收</h4><p>“正式记录数”表示该编号在当前正式中间表中查到几条；不代表这些记录业务上一定正确。</p></div></header><div class="diagnosis-case-table"><div class="is-head"><span>案例编号</span><span>正式记录数</span><span>候选记录数</span><span>变化</span><span>预期</span><span>验收</span></div><div v-for="row in caseValidationRows" :key="row.id"><code>{{ row.id }}</code><span>{{ row.before }}</span><span>{{ row.after }}</span><em>{{ row.change > 0 ? `+${row.change}` : row.change }}</em><span>{{ row.expected }}</span><strong :data-pass="row.passed">{{ row.result }}</strong></div></div><p class="diagnosis-base-conclusion"><strong>结论：</strong>{{ caseValidationMessage }}</p></section>
        <details v-if="Object.keys(snapshot.candidateSql).length" class="diagnosis-technical diagnosis-sql-disclosure"><summary><span>当前正式{{ sqlLayerTitle }}（可复制到 Navicat）</span><button type="button" class="diagnosis-copy-button" @click.prevent.stop="copySql('original', snapshot.candidateSql.originalSqlExecutable)">{{ copiedSqlKey === 'original' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ executableSqlDisplay(String(snapshot.candidateSql.originalSqlExecutable || '')) }}</pre></details>
        <details v-if="Object.keys(snapshot.candidateSql).length" class="diagnosis-technical diagnosis-sql-disclosure"><summary><span>候选{{ sqlLayerTitle }}（影子试跑版）</span><button type="button" class="diagnosis-copy-button" @click.prevent.stop="copySql('candidate', snapshot.candidateSql.candidateSqlExecutable)">{{ copiedSqlKey === 'candidate' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ executableSqlDisplay(String(snapshot.candidateSql.candidateSqlExecutable || '')) }}</pre></details><p class="diagnosis-help"><strong>可执行版说明：</strong>脚本不写死数据库名和 Schema；请先在 Navicat 中选择正确数据库后再执行。保存医院草稿时仍写入知识库模板 SQL。</p><p class="diagnosis-help">{{ snapshot.candidateSql.rawSqlNotice }}</p>
        <details v-if="Object.keys(snapshot.shadowTrial).length" class="diagnosis-technical"><summary>技术对账明细（实施排查用）</summary><p class="diagnosis-technical-purpose">用于追溯影子表写入行数、输出结构、重复记录、记录集差异和 SQL 哈希。一般验收只需看上方两张对比表。</p><pre>{{ pretty(snapshot.shadowTrial) }}</pre></details></div></article>
      <article v-if="snapshot.currentStep === 'SHADOW_TRIAL' && Object.keys(snapshot.shadowTrial).length" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ shadowTrialExecutionFailed ? '系统 · 影子试跑执行失败' : '系统 · 本轮候选未通过验收' }}</strong></div><p v-if="shadowTrialExecutionFailed">候选 SQL 已通过静态安全校验，但实际影子执行没有完成。请根据上方失败原因修正数据源、SQL 或目标结构后重新生成；本轮没有产生对比结果。</p><p v-else>候选 SQL 已完成影子执行，但记录变化或案例验收没有满足实施要求。请根据上方“抽取数据变化”和“案例编号验收”修正字段、条件或判断值。</p><button type="button" class="diagnosis-secondary" :disabled="busy" @click="emit('action', 'REVISE_CANDIDATE', {})">返回修改排查条件</button></div></article>
    </template>

    <article v-if="snapshot.currentStep === 'DRAFT_SAVE'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 影子对账通过</strong></div><p>保存前请把本次修改说明写清楚，审批人员会在“知识库回收与审批”中直接看到这些内容。</p><div class="diagnosis-draft-description"><label>问题说明<textarea v-model="draftIssueSummary" rows="2" placeholder="现场发现了什么问题"></textarea></label><label>本次修改<textarea v-model="draftChangeSummary" rows="2" placeholder="候选 SQL 具体修改了什么"></textarea></label><label>预期影响<textarea v-model="draftExpectedImpact" rows="2" placeholder="哪些记录、科室或结果应该变化"></textarea></label><label>影子验证结论<textarea v-model="draftVerificationSummary" rows="2" placeholder="本次试跑和对账证明了什么"></textarea></label></div><p>草稿不会影响正式指标计算，也不会替换公司公版口径。</p><button type="button" class="diagnosis-primary" :disabled="busy || draftDescriptionIncomplete" @click="saveHospitalDraft">提交为待审批医院草稿</button></div></article>
    <article v-if="snapshot.currentStep === 'COMPLETED' && Object.keys(snapshot.draftResult || {}).length" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 医院草稿已保存</strong><span>未发布</span></div><dl class="caliber-facts"><div><dt>草稿编号</dt><dd>{{ snapshot.draftResult.draftId }}</dd></div><div><dt>医院</dt><dd>{{ snapshot.draftResult.hospitalId }}</dd></div><div><dt>指标</dt><dd>{{ snapshot.draftResult.profileId }}</dd></div><div><dt>修改层级</dt><dd>{{ snapshot.draftResult.changeLayer === 'SOURCE_EXTRACT' ? '抽取 SQL' : '统计 SQL' }}</dd></div><div><dt>影子验证</dt><dd>{{ snapshot.draftResult.revalidationPassed === false ? '重新验证失败' : '通过' }}</dd></div><div><dt>正式状态</dt><dd>未发布，不影响当前计算</dd></div></dl><details class="diagnosis-technical"><summary>查看草稿实体</summary><pre>{{ snapshot.draftResult.entityMarkdown }}</pre></details><details class="diagnosis-technical diagnosis-sql-disclosure"><summary><span>草稿保存的当前正式{{ snapshot.draftResult.changeLayer === 'SOURCE_EXTRACT' ? '源表抽取 SQL' : '目标表概览 SQL' }}</span><button type="button" class="diagnosis-copy-button" @click.prevent.stop="copySql('saved-original', snapshot.draftResult.originalSql)">{{ copiedSqlKey === 'saved-original' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ snapshot.draftResult.originalSql }}</pre></details><details class="diagnosis-technical diagnosis-sql-disclosure"><summary><span>草稿保存的候选{{ snapshot.draftResult.changeLayer === 'SOURCE_EXTRACT' ? '源表抽取 SQL' : '目标表概览 SQL' }}</span><button type="button" class="diagnosis-copy-button" @click.prevent.stop="copySql('saved-candidate', snapshot.draftResult.candidateSql)">{{ copiedSqlKey === 'saved-candidate' ? '已复制' : '复制 SQL' }}</button></summary><pre>{{ snapshot.draftResult.candidateSql }}</pre></details><details class="diagnosis-technical"><summary>查看影子对账</summary><pre>{{ pretty(snapshot.draftResult.shadowTrial) }}</pre></details><details class="diagnosis-technical"><summary>查看草稿磁盘校验</summary><pre>{{ pretty(snapshot.draftResult.verification) }}</pre></details><p v-if="snapshot.draftResult.baselineExpired" class="diagnosis-template-warning">公司公版已经变化，草稿基线已过期，需要重新开始排查。</p><button type="button" class="diagnosis-primary" :disabled="busy || Boolean(snapshot.draftResult.baselineExpired)" @click="emit('action', 'REVALIDATE_HOSPITAL_DRAFT', {})">重新验证草稿</button></div></article>
    <article v-else-if="snapshot.currentStep === 'COMPLETED' && dataRefreshOutcome === 'DATA_REFRESHED'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 已按当前正式口径重新计算</strong><span>已更新</span></div><p>{{ snapshot.releaseResult.message }}</p><div class="diagnosis-before-after"><section><strong>重新计算前</strong><pre>{{ pretty(snapshot.releaseResult.beforeResult) }}</pre></section><section><strong>重新计算后</strong><pre>{{ pretty(snapshot.releaseResult.afterResult) }}</pre></section></div></div></article>
    <article v-else-if="snapshot.currentStep === 'COMPLETED'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 本次排查已完成</strong></div><pre>{{ pretty(snapshot.releaseResult) }}</pre></div></article>
  </div>
</template>
