<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  actOnDiagnosisCase,
  createDiagnosisCase,
  fetchDiagnosisCaseDetails,
  fetchDiagnosisDataScreening,
  fetchEffectiveRule,
  listIndicatorProfiles,
  listIndicators,
  loadDiagnosisCase,
  loadDiagnosisShadowDiffs,
  type DiagnosisCaseSnapshot,
  type DiagnosisDataScreening,
  type DiagnosisShadowDiffPage,
  type IndicatorDetailResult,
  type IndicatorItem,
  type IndicatorProfile,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'
import StandardDiagnosisStepper, {
  type StandardWorkspaceStep,
} from '../components/standard-diagnosis/StandardDiagnosisStepper.vue'
import StandardDataConfirmationEditor from '../components/standard-diagnosis/StandardDataConfirmationEditor.vue'

type WorkspaceStep = StandardWorkspaceStep
type FlowNode = Record<string, unknown>
type AiScopeOption = { value: string; label: string; field: string }
type SqlDiffLine = { kind: 'same' | 'added' | 'removed'; text: string; oldLine?: number; newLine?: number }
type ExecutionKind = '' | 'overall' | 'candidate-generate' | 'candidate-run' | 'baseline-refresh' | 'rule-preview' | 'rule-run'

function buildSqlDiff(originalSql: string, candidateSql: string): SqlDiffLine[] {
  const original = originalSql.replace(/\r\n/g, '\n').split('\n')
  const candidate = candidateSql.replace(/\r\n/g, '\n').split('\n')
  if (!originalSql) return candidate.map((text, index) => ({ kind: 'added', text, newLine: index + 1 }))
  if (!candidateSql) return original.map((text, index) => ({ kind: 'removed', text, oldLine: index + 1 }))

  // 常规实体 SQL 只有数十至数百行，LCS 可以准确区分新增、删除和未改动内容。
  // 超长 SQL 使用轻量回退，避免差异展示占用过多浏览器内存。
  if (original.length * candidate.length > 1_500_000) {
    const originalLines = new Set(original)
    return candidate.map((text, index) => ({
      kind: originalLines.has(text) ? 'same' : 'added', text, newLine: index + 1,
    }))
  }

  const matrix = Array.from({ length: original.length + 1 }, () => new Uint32Array(candidate.length + 1))
  for (let left = original.length - 1; left >= 0; left -= 1) {
    for (let right = candidate.length - 1; right >= 0; right -= 1) {
      matrix[left][right] = original[left] === candidate[right]
        ? matrix[left + 1][right + 1] + 1
        : Math.max(matrix[left + 1][right], matrix[left][right + 1])
    }
  }
  const result: SqlDiffLine[] = []
  let left = 0
  let right = 0
  while (left < original.length || right < candidate.length) {
    if (left < original.length && right < candidate.length && original[left] === candidate[right]) {
      result.push({ kind: 'same', text: original[left], oldLine: left + 1, newLine: right + 1 })
      left += 1
      right += 1
    } else if (right < candidate.length && (left >= original.length || matrix[left][right + 1] >= matrix[left + 1][right])) {
      result.push({ kind: 'added', text: candidate[right], newLine: right + 1 })
      right += 1
    } else {
      result.push({ kind: 'removed', text: original[left], oldLine: left + 1 })
      left += 1
    }
  }
  return result
}

const route = useRoute()
const router = useRouter()
const store = useAgentStore()
const currentStep = ref<WorkspaceStep>('selection')
const snapshot = ref<DiagnosisCaseSnapshot | null>(null)
const indicators = ref<IndicatorItem[]>([])
const profiles = ref<IndicatorProfile[]>([])
const selectedRuleId = ref('')
const selectedProfileId = ref('')
const indicatorSearch = ref('')
const statStart = ref('')
const statEnd = ref('')
const effectiveRule = ref<Record<string, unknown>>({})
const loading = ref(false)
const busy = ref('')
const error = ref('')

const detailGroup = ref<'' | 'numerator' | 'denominator'>('')
const detailPage = ref<IndicatorDetailResult | null>(null)
const detailLoading = ref(false)
const detailSearch = ref('')
const detailDepartment = ref('')
const selectedRows = ref(new Map<string, Record<string, unknown>>())
const screening = ref<DiagnosisDataScreening | null>(null)
const screeningLoading = ref(false)
const screeningExpanded = ref(false)
const selectedPublicRuleIds = ref<string[]>([])
const repairDialogOpen = ref(false)
const repairRuleId = ref('')
const repairPreviewLoading = ref(false)
const repairRunLoading = ref(false)
const repairError = ref('')
const repairSqlExpanded = ref(false)
const overIncludedNote = ref('')
const underIncludedNote = ref('')
const selectedDepartments = ref<string[]>([])
const underTargetType = ref<'RECORD' | 'DEPARTMENT'>('RECORD')
const underRecordIds = ref('')
const underDepartments = ref<string[]>([])
const underDepartmentManual = ref('')

const selectedNodeId = ref('')
const editMode = ref<'direct' | 'ai'>('ai')
const requirement = ref('')
const directSql = ref('')
const aiPatientSearch = ref('')
const aiPatientOptions = ref<AiScopeOption[]>([])
const aiSelectedPatients = ref<AiScopeOption[]>([])
const aiDepartmentSearch = ref('')
const aiSelectedDepartments = ref<string[]>([])
const aiScopeMode = ref<'PATIENT' | 'DEPARTMENT'>('PATIENT')
const aiScopeLoading = ref(false)
const copiedKey = ref('')
const diffType = ref<DiagnosisShadowDiffPage['type']>('REMOVED')
const diffSearch = ref('')
const diffPage = ref<DiagnosisShadowDiffPage | null>(null)
const diffLoading = ref(false)
const draftDescription = ref({ issueSummary: '', changeSummary: '', expectedImpact: '', verificationSummary: '' })
const draftFormOpen = ref(false)
const trialProgress = ref('')
const trialError = ref('')
const trialNodeId = ref('')
const candidatePreparedSignature = ref('')
const activeExecution = ref<ExecutionKind>('')
const executionStage = ref('')
let executionProgressTimer: ReturnType<typeof setInterval> | null = null
const clarifyingDirection = ref<'' | 'OVER_INCLUDED' | 'UNDER_INCLUDED' | 'ALL'>('')
const refreshProgress = ref('')
const refreshError = ref('')

const filteredIndicators = computed(() => {
  const keyword = indicatorSearch.value.trim().toLowerCase()
  return keyword ? indicators.value.filter((item) =>
    `${item.ruleId} ${item.ruleName}`.toLowerCase().includes(keyword)) : indicators.value
})
const selectedIndicator = computed(() => indicators.value.find((item) => item.ruleId === selectedRuleId.value))
const selectedProfile = computed(() => profiles.value.find((item) => item.profileId === selectedProfileId.value))
const currentStepLabel = computed(() => ({ selection: '选择指标与口径', data: '数据确认', lineage: '数据链路核查' }[currentStep.value]))
const allGatesPassed = computed(() => [1, 2, 3].every((number) => gateStatus(number) === 'PASSED'))
const gateBlocked = computed(() => [1, 2, 3].some((number) => gateStatus(number) === 'BLOCKED'))
const blockedGate = computed(() => [1, 2, 3].map((number) => gate(number)).find((item) => String(item?.status || '') === 'BLOCKED'))
const checksPreparing = computed(() => Boolean(snapshot.value) && !allGatesPassed.value && !gateBlocked.value)
const preparationSteps = computed(() => [
  { gate: 1, title: '数据结构校验', description: '核对业务库、真实库及当前口径所需表字段' },
  { gate: 2, title: '事件与抽取校验', description: '核对事件配置并重新计算当前口径' },
  { gate: 3, title: '数据可用性校验', description: '确认当前统计窗口存在可计算数据' },
].map((item) => ({ ...item, status: gateStatus(item.gate), message: String(gate(item.gate)?.message || '') })))
const calculation = computed(() => {
  const gate = snapshot.value?.gateResults.find((item) => Number(item.gate) === 2)
  return record(record(gate?.facts).executionEvidence)
})
const numeratorCount = computed(() => number(calculation.value.numeratorCount))
const denominatorCount = computed(() => number(calculation.value.denominatorCount))
const resultValue = computed(() => calculation.value.resultValue ?? '—')
const calculationFailed = computed(() => !['', 'SUCCESS'].includes(String(calculation.value.status || '')))
const calculationFailureMessage = computed(() => String(calculation.value.errorMessage
  || calculation.value.message || '本次指标计算失败'))
const flow = computed(() => record(snapshot.value?.caliberSnapshot.dataFlow || effectiveRule.value.dataFlow))
const flowNodes = computed<FlowNode[]>(() => Array.isArray(flow.value.nodes)
  ? (flow.value.nodes as FlowNode[]).filter((node) => String(node.nodeType || '') !== 'RESULT')
  : [])
const selectedNode = computed(() => flowNodes.value.find((node) => String(node.id) === selectedNodeId.value) || flowNodes.value[0] || null)
const selectedNodeLayer = computed<'SOURCE_EXTRACT' | 'OVERVIEW' | ''>(() => {
  const type = String(selectedNode.value?.nodeType || '')
  return type === 'SOURCE_EXTRACT_SQL' ? 'SOURCE_EXTRACT' : type === 'OVERVIEW_SQL' ? 'OVERVIEW' : ''
})
const selectedNodeEditable = computed(() => Boolean(selectedNodeLayer.value))
const overallExecutionReady = computed(() => modificationAllowed.value && !busy.value)
const caseCompleted = computed(() => snapshot.value?.status === 'COMPLETED'
  || snapshot.value?.currentStep === 'COMPLETED')
const modificationAllowed = computed(() => allGatesPassed.value
  && ['CASE_INPUT', 'CASE_INVESTIGATION', 'SHADOW_TRIAL', 'DRAFT_SAVE'].includes(String(snapshot.value?.currentStep || '')))
const detailColumns = computed(() => {
  const rows = detailPage.value?.rows || []
  const priority = ['ENCOUNTER_ID', 'encounterId', 'IMRN', 'imrn', 'PERSON_NAME', 'FULL_NAME', 'personName', 'CURRENT_DEPT_NAME', 'currentDeptName', 'CURRENT_WARD_NAME', 'currentWardName']
  const keys = [...new Set(rows.flatMap((row) => Object.keys(row)))]
  return [...priority.filter((key) => keys.includes(key)), ...keys.filter((key) => !priority.includes(key))].slice(0, 10)
})
const detailsReconciled = computed(() => Boolean(detailPage.value)
  && number(detailPage.value?.cardNumerator) === number(detailPage.value?.detailNumerator)
  && number(detailPage.value?.cardDenominator) === number(detailPage.value?.detailDenominator))
const currentClarifications = computed(() => record(snapshot.value?.dataConfirmation.clarifications))
const visibleScreeningFindings = computed(() => {
  const findings = screening.value?.findings || []
  return findings
})
const screeningPreviewFindings = computed(() => screeningExpanded.value
  ? visibleScreeningFindings.value : visibleScreeningFindings.value.slice(0, 3))
const shadow = computed(() => snapshot.value?.shadowTrial || {})
const candidate = computed(() => snapshot.value?.candidateSql || {})
const candidateOwnedBySelectedNode = computed(() => Object.keys(candidate.value).length > 0
  && String(candidate.value.nodeId || defaultNodeId(String(candidate.value.layer || ''))) === selectedNodeId.value)
const shadowOwnedBySelectedNode = computed(() => Object.keys(shadow.value).length > 0
  && String(shadow.value.nodeId || candidate.value.nodeId || defaultNodeId(String(shadow.value.layer || ''))) === selectedNodeId.value)
const shadowRecordDiff = computed(() => record(shadow.value.recordSetDiff))
const diffCategories = computed<Array<{ type: DiagnosisShadowDiffPage['type']; label: string; count: number }>>(() => {
  const values: Array<{ type: DiagnosisShadowDiffPage['type']; label: string; count: number }> = [
    { type: 'ADDED', label: '新增记录', count: number(shadowRecordDiff.value.addedCount) },
    { type: 'REMOVED', label: '减少记录', count: number(shadowRecordDiff.value.removedCount) },
    { type: 'CHANGED', label: '字段变化', count: number(shadowRecordDiff.value.changedCount) },
    { type: 'DUPLICATE', label: '新增重复', count: number(shadowRecordDiff.value.duplicateCount) },
  ]
  return values.filter((item) => item.count > 0)
})
const hasRecordDiff = computed(() => diffCategories.value.length > 0)
const shadowOriginalRow = computed(() => firstResultRow(shadow.value.originalResult))
const shadowCandidateRow = computed(() => firstResultRow(shadow.value.candidateResult))
const shadowResultChanged = computed(() =>
  number(aggregateValue(shadowOriginalRow.value, 'result')) !== number(aggregateValue(shadowCandidateRow.value, 'result')))
const repairResultDelta = computed(() => number(aggregateValue(shadowCandidateRow.value, 'result'))
  - number(aggregateValue(shadowOriginalRow.value, 'result')))
const repairResultDeltaPercent = computed(() => {
  const original = number(aggregateValue(shadowOriginalRow.value, 'result'))
  return original === 0 ? null : repairResultDelta.value / Math.abs(original) * 100
})
const latestRequirementAnalysis = computed(() => {
  const evidence = Array.isArray(snapshot.value?.evidence) ? snapshot.value.evidence : []
  for (let index = evidence.length - 1; index >= 0; index -= 1) {
    const item = record(evidence[index])
    if (String(item.type || '') !== 'IMPLEMENTER_SQL_REQUIREMENT') continue
    if (String(item.suspectedLayer || '') !== selectedNodeLayer.value) continue
    return record(item.requirementAnalysis)
  }
  return {}
})
const candidateExecutable = computed(() => String(
  candidate.value.candidateSqlExecutable || candidate.value.sql || ''))
const candidateOriginalExecutable = computed(() => String(
  candidate.value.originalSqlExecutable || candidate.value.originalSql || ''))
const candidateDiffLines = computed(() => buildSqlDiff(
  candidateOriginalExecutable.value, candidateExecutable.value))
const candidateChangedLines = computed(() => candidateDiffLines.value.filter((line) => line.kind !== 'same'))
const trialPassed = computed(() => Boolean(shadow.value.passed))
const candidatePendingRun = computed(() => candidateOwnedBySelectedNode.value && !shadowOwnedBySelectedNode.value)
const candidateContentChanged = computed(() => Boolean(candidateExecutable.value.trim())
  && candidateExecutable.value.trim() !== candidateOriginalExecutable.value.trim())
const candidateMatchesInput = computed(() => Boolean(candidatePreparedSignature.value)
  && candidatePreparedSignature.value === candidateInputSignature())
const candidateCanExecute = computed(() => candidatePendingRun.value
  && candidateContentChanged.value
  && Boolean(record(candidate.value.validation).ok)
  && candidateMatchesInput.value
  && !busy.value)
const directSqlChanged = computed(() => directSql.value.trim() !== String(selectedNode.value?.templateSql || selectedNode.value?.sql || '').trim())
const filteredAiDepartments = computed(() => {
  const keyword = aiDepartmentSearch.value.trim().toLowerCase()
  const values = screening.value?.departmentOptions || []
  return keyword ? values.filter((item) => `${item.label} ${item.value}`.toLowerCase().includes(keyword)) : values
})
const overallButtonLabel = computed(() => {
  if (checksPreparing.value) return '正在准备当前链路…'
  if (activeExecution.value === 'overall') return executionStage.value
  return '整体执行'
})
const candidateGenerateLabel = computed(() => activeExecution.value === 'candidate-generate'
  ? executionStage.value : editMode.value === 'ai' ? 'AI 生成对应 SQL' : '生成手动候选 SQL')
const candidateRunLabel = computed(() => activeExecution.value === 'candidate-run'
  ? executionStage.value : '用该候选 SQL 整体执行')
const refreshButtonLabel = computed(() => activeExecution.value === 'baseline-refresh'
  ? executionStage.value
  : selectedNodeLayer.value === 'OVERVIEW' ? '↻ 重新统计并计算' : '↻ 重新抽取并计算')
const repairRunButtonLabel = computed(() => activeExecution.value === 'rule-run'
  ? executionStage.value : '用该 SQL 整体执行')

function startExecutionFlow(kind: ExecutionKind, stages: string[]) {
  stopExecutionFlow()
  activeExecution.value = kind
  let index = 0
  executionStage.value = stages[index]
  executionProgressTimer = setInterval(() => {
    if (index < stages.length - 1) executionStage.value = stages[++index]
  }, 1400)
}

function stopExecutionFlow() {
  if (executionProgressTimer) clearInterval(executionProgressTimer)
  executionProgressTimer = null
  activeExecution.value = ''
  executionStage.value = ''
}

onBeforeUnmount(stopExecutionFlow)

watch(selectedNodeId, () => {
  const node = selectedNode.value
  if (!node) return
  directSql.value = String(node.templateSql || node.sql || '')
  requirement.value = clarificationRequirement()
  candidatePreparedSignature.value = ''
}, { immediate: true })

watch(() => String(shadow.value.trialId || ''), () => {
  diffPage.value = null
  diffSearch.value = ''
  if (diffCategories.value.length) diffType.value = diffCategories.value[0].type
})

onMounted(async () => {
  defaultPeriod()
  loading.value = true
  try {
    if (!store.capabilities) await store.refreshCapabilities()
    indicators.value = await listIndicators(store.token)
    const routeCaseId = String(route.params.caseId || '')
    if (routeCaseId) {
      snapshot.value = await loadDiagnosisCase(store.token, routeCaseId)
      selectedRuleId.value = snapshot.value.ruleId
      selectedProfileId.value = snapshot.value.profileId
      statStart.value = String(snapshot.value.caseInput.statStart || '').slice(0, 10)
      statEnd.value = String(snapshot.value.caseInput.statEnd || '').slice(0, 10)
      profiles.value = await listIndicatorProfiles(store.token, snapshot.value.ruleId)
      effectiveRule.value = await fetchEffectiveRule(store.token, snapshot.value.ruleId,
        snapshot.value.profileId, String(snapshot.value.caseInput.statStart || ''), String(snapshot.value.caseInput.statEnd || ''))
      currentStep.value = normalizeStep(String(route.query.step || stepFromSnapshot(snapshot.value)))
      if (String(route.query.step || '') === 'checks') {
        await router.replace({ name: 'standard-diagnosis', params: { caseId: snapshot.value.caseId }, query: { step: 'data' } })
      }
      hydrateDataConfirmation()
      initializeNode()
      if (currentStep.value === 'lineage') importConfirmationScope()
      if (snapshot.value.currentStep === 'CALIBER_CONFIRMATION' || currentGateNumber(snapshot.value.currentStep)) {
        void startOrResumeChecks()
      } else if (currentStep.value === 'data' && allGatesPassed.value) {
        await loadScreening()
      } else if (currentStep.value === 'lineage' && allGatesPassed.value) {
        await loadScreening()
      }
    }
  } catch (cause) {
    error.value = message(cause, '标准排查工作区加载失败。')
  } finally {
    loading.value = false
  }
})

function defaultPeriod() {
  const now = new Date()
  statStart.value = `${now.getFullYear() - 1}-01-01`
  statEnd.value = `${now.getFullYear()}-01-01`
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function number(value: unknown): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function firstResultRow(value: unknown): Record<string, unknown> {
  if (Array.isArray(value)) return record(value[0])
  return record(value)
}

function aggregateValue(row: Record<string, unknown>, kind: 'numerator' | 'denominator' | 'result'): unknown {
  const marker = kind === 'numerator' ? '分子' : kind === 'denominator' ? '分母' : '监测情况'
  const key = Object.keys(row).find((name) => name.includes(marker))
  return key ? row[key] : '—'
}

function metricText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(6)))
  return String(value)
}

function message(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback
}

function hydrateDataConfirmation() {
  const confirmation = record(snapshot.value?.dataConfirmation)
  overIncludedNote.value = String(confirmation.overIncludedNote || '')
  underIncludedNote.value = String(confirmation.underIncludedNote || '')
  const restored = new Map<string, Record<string, unknown>>()
  const rows = Array.isArray(confirmation.overIncludedRows) ? confirmation.overIncludedRows : []
  for (const raw of rows) {
    const row = record(raw)
    const key = String(row.rowKey || '')
    if (key) restored.set(key, row)
  }
  selectedRows.value = restored
  const departments = Array.isArray(confirmation.overIncludedDepartments)
    ? confirmation.overIncludedDepartments : []
  selectedDepartments.value = departments.flatMap((item) => {
    const target = record(item)
    return Array.isArray(target.values) ? target.values.map(String) : []
  })
  selectedPublicRuleIds.value = Array.isArray(confirmation.publicRuleIds)
    ? confirmation.publicRuleIds.map(String) : []
  const underTargets = Array.isArray(confirmation.underIncludedTargets)
    ? confirmation.underIncludedTargets.map(record) : []
  const underRecord = underTargets.find((item) => String(item.targetType) === 'RECORD')
  const underDepartment = underTargets.filter((item) => String(item.targetType) === 'DEPARTMENT')
  if (underRecord) {
    underTargetType.value = 'RECORD'
    underRecordIds.value = Array.isArray(underRecord.values) ? underRecord.values.map(String).join('\\n') : ''
  } else if (underDepartment.length) {
    underTargetType.value = 'DEPARTMENT'
    underDepartments.value = underDepartment.flatMap((item) => Array.isArray(item.values) ? item.values.map(String) : [])
  }
}

function clarificationRequirement(): string {
  const confirmation = record(snapshot.value?.dataConfirmation)
  const rows = Array.isArray(confirmation.overIncludedRows) ? confirmation.overIncludedRows : []
  const labels = rows.map((item) => String(record(item).label || record(item).recordId || '')).filter(Boolean)
  const departments = Array.isArray(confirmation.overIncludedDepartments)
    ? confirmation.overIncludedDepartments.map(record) : []
  const parts: string[] = []
  if (labels.length) parts.push(`排除这些疑似多算记录：${labels.join('、')}`)
  const departmentLabels = departments.flatMap((item) => Array.isArray(item.labels) ? item.labels.map(String) : [])
  if (departmentLabels.length) parts.push('核对并排除科室范围：' + departmentLabels.join('、'))
  if (confirmation.overIncludedNote) parts.push(`数据多了：${String(confirmation.overIncludedNote)}`)
  if (confirmation.underIncludedNote) parts.push(`数据少了：${String(confirmation.underIncludedNote)}`)
  const publicRules = selectedPublicRuleIds.value.length ? selectedPublicRuleIds.value
    : Array.isArray(confirmation.publicRuleIds) ? confirmation.publicRuleIds.map(String) : []
  if (publicRules.includes('PUBLIC_001')) parts.push('按公共规则排除患者姓名包含“测试”或“test”的数据')
  if (publicRules.includes('PUBLIC_002')) parts.push('按公共规则排除当前科室名称包含“测试”“test”或“血液透析门诊”的数据')
  if (publicRules.includes('PUBLIC_003')) parts.push('最终明细存在重复业务编号，请人工核对当前指标相关事件是否重复启用')
  return parts.join('；')
}

function publicRuleLabel(ruleId: string): string {
  if (ruleId === 'PUBLIC_001') return '排除测试患者'
  if (ruleId === 'PUBLIC_002') return '排除测试及血液透析门诊科室'
  return '检查重复明细与事件启用情况'
}

function publicRuleRepairable(ruleId: string): boolean {
  return ruleId === 'PUBLIC_001' || ruleId === 'PUBLIC_002'
}

function publicRuleRepairDescription(ruleId: string): string {
  if (ruleId === 'PUBLIC_001') return '在当前指标源表抽取 SQL 的患者姓名字段上追加“测试 / test”排除条件。'
  if (ruleId === 'PUBLIC_002') return '在当前指标源表抽取 SQL 的科室名称字段上追加“测试 / test / 血液透析门诊”排除条件。'
  return '该规则只提示人工检查相关事件是否重复启用，不自动修改 SQL。'
}

function clearPublicRules() {
  selectedPublicRuleIds.value = []
}

function normalizeStep(value: string): WorkspaceStep {
  if (value === 'checks') return 'data'
  return ['selection', 'data', 'lineage'].includes(value) ? value as WorkspaceStep : 'selection'
}

function stepFromSnapshot(value: DiagnosisCaseSnapshot): WorkspaceStep {
  if (value.currentStep.startsWith('GATE_') || value.currentStep === 'CALIBER_CONFIRMATION') return 'data'
  if (['CASE_INPUT', 'CASE_CALIBER_CLARIFICATION'].includes(value.currentStep)) return 'data'
  return 'lineage'
}

function isoStart(value: string): string { return `${value}T00:00:00` }

async function chooseIndicator(ruleId: string) {
  selectedRuleId.value = ruleId
  selectedProfileId.value = ''
  effectiveRule.value = {}
  profiles.value = []
  if (!ruleId) return
  try {
    profiles.value = await listIndicatorProfiles(store.token, ruleId)
    const executable = profiles.value.find((item) => item.overviewRuntimeEligible) || profiles.value[0]
    if (executable) await chooseProfile(executable.profileId)
  } catch (cause) {
    error.value = message(cause, '口径列表加载失败。')
  }
}

async function chooseProfile(profileId: string) {
  selectedProfileId.value = profileId
  if (!selectedRuleId.value || !profileId) return
  try {
    effectiveRule.value = await fetchEffectiveRule(store.token, selectedRuleId.value, profileId,
      isoStart(statStart.value), isoStart(statEnd.value))
  } catch (cause) {
    error.value = message(cause, '口径详情加载失败。')
  }
}

async function createCase() {
  if (!selectedRuleId.value || !selectedProfileId.value || !statStart.value || !statEnd.value) return
  busy.value = 'create'
  error.value = ''
  try {
    const created = await createDiagnosisCase(store.token, {
      sessionId: store.sessionId,
      ruleId: selectedRuleId.value,
      profileId: selectedProfileId.value,
      statStart: isoStart(statStart.value),
      statEnd: isoStart(statEnd.value),
      modelId: store.selectedModel,
      caseInput: { entryMode: 'STANDARD_WORKSPACE' },
      expectedClassification: {},
    })
    snapshot.value = created
    resetCaseWorkingState()
    rememberCase(created.caseId)
    currentStep.value = 'data'
    await router.replace({ name: 'standard-diagnosis', params: { caseId: created.caseId }, query: { step: 'data' } })
    await startOrResumeChecks()
  } catch (cause) {
    error.value = message(cause, '异常排查任务创建失败。')
  } finally {
    busy.value = ''
  }
}

function resetCaseWorkingState() {
  detailGroup.value = ''
  detailPage.value = null
  detailSearch.value = ''
  detailDepartment.value = ''
  selectedRows.value = new Map()
  screening.value = null
  screeningExpanded.value = false
  selectedPublicRuleIds.value = []
  overIncludedNote.value = ''
  underIncludedNote.value = ''
  selectedDepartments.value = []
  underTargetType.value = 'RECORD'
  underRecordIds.value = ''
  underDepartments.value = []
  underDepartmentManual.value = ''
  selectedNodeId.value = ''
  editMode.value = 'ai'
  requirement.value = ''
  directSql.value = ''
  aiPatientSearch.value = ''
  aiPatientOptions.value = []
  aiSelectedPatients.value = []
  aiDepartmentSearch.value = ''
  aiSelectedDepartments.value = []
  aiScopeMode.value = 'PATIENT'
  diffPage.value = null
  draftFormOpen.value = false
  trialProgress.value = ''
  trialError.value = ''
  trialNodeId.value = ''
}

function rememberCase(id: string) {
  const key = `diagnosisCases:${store.sessionId}`
  const ids = JSON.parse(localStorage.getItem(key) || '[]') as string[]
  if (!ids.includes(id)) localStorage.setItem(key, JSON.stringify([...ids, id]))
}

async function act(action: string, payload: Record<string, unknown> = {}) {
  if (!snapshot.value) return null
  busy.value = action
  error.value = ''
  try {
    snapshot.value = await actOnDiagnosisCase(store.token, snapshot.value.caseId, action, payload)
    initializeNode()
    return snapshot.value
  } catch (cause) {
    error.value = message(cause, '排查步骤执行失败。')
    return null
  } finally {
    busy.value = ''
  }
}

async function startOrResumeChecks() {
  if (!snapshot.value) return
  if (snapshot.value.currentStep === 'CALIBER_CONFIRMATION') {
    const confirmed = await act('CONFIRM_CALIBER', { confirmed: true })
    if (!confirmed) return
  }
  await advanceGates()
}

async function advanceGates() {
  while (snapshot.value) {
    const gate = currentGateNumber(snapshot.value.currentStep)
    if (!gate) {
      if (allGatesPassed.value && currentStep.value === 'data') await loadScreening()
      return
    }
    const existing = snapshot.value.gateResults.find((item) => Number(item.gate) === gate)
    if (String(existing?.status || '') === 'BLOCKED') return
    const updated = await act('RUN_GATE', { gate })
    if (!updated) return
    const result = updated.gateResults.find((item) => Number(item.gate) === gate)
    if (String(result?.status || '') === 'BLOCKED') return
    await nextTick()
  }
}

async function retryGate(number: number) {
  const updated = await act('RECHECK_GATE', { gate: number })
  if (updated && String(updated.gateResults.find((item) => Number(item.gate) === number)?.status) === 'PASSED') {
    await advanceGates()
  }
}

function currentGateNumber(step: string): number {
  if (step === 'GATE_1_SCHEMA') return 1
  if (step === 'GATE_2_EVENT') return 2
  if (step === 'GATE_3_VALUE') return 3
  return 0
}

function gate(number: number): Record<string, unknown> | undefined {
  return snapshot.value?.gateResults.find((item) => Number(item.gate) === number)
}

function gateStatus(number: number): string {
  const result = gate(number)
  if (result) return String(result.status || '')
  if (busy.value && currentGateNumber(snapshot.value?.currentStep || '') === number) return 'RUNNING'
  return 'WAITING'
}

function preparationStatusLabel(status: string): string {
  if (status === 'PASSED') return '已通过'
  if (status === 'BLOCKED') return '需处理'
  if (status === 'RUNNING') return '检查中'
  return '等待前一步'
}

async function goStep(step: WorkspaceStep) {
  if (step !== 'selection' && !snapshot.value) return
  currentStep.value = step
  if (snapshot.value) await router.replace({ name: 'standard-diagnosis', params: { caseId: snapshot.value.caseId }, query: { step } })
  if (step === 'data' && allGatesPassed.value) {
    await loadScreening()
  }
  if (step === 'lineage') {
    initializeNode()
    requirement.value = clarificationRequirement()
    importConfirmationScope()
    if (allGatesPassed.value) await loadScreening()
  }
}

async function toggleDetails(group: 'numerator' | 'denominator') {
  if (detailGroup.value === group) {
    detailGroup.value = ''
    detailPage.value = null
    return
  }
  await loadDetails(group, 1)
}

async function loadDetails(group: 'numerator' | 'denominator', page = 1) {
  if (!snapshot.value || !allGatesPassed.value) return
  detailGroup.value = group
  detailLoading.value = true
  try {
    detailPage.value = await fetchDiagnosisCaseDetails(store.token, snapshot.value.caseId,
      group, page, 10, detailSearch.value, detailDepartment.value)
  } catch (cause) {
    detailPage.value = null
    error.value = message(cause, '分子分母明细加载失败。')
  } finally {
    detailLoading.value = false
  }
}

async function reloadDetails(page = 1) {
  if (detailGroup.value) await loadDetails(detailGroup.value, page)
}

async function loadScreening() {
  if (!snapshot.value || screening.value) return
  screeningLoading.value = true
  try {
    screening.value = await fetchDiagnosisDataScreening(store.token, snapshot.value.caseId)
  } catch (cause) {
    error.value = message(cause, '系统初筛失败。')
  } finally {
    screeningLoading.value = false
  }
}

function rowKey(row: Record<string, unknown>): string {
  for (const key of ['ENCOUNTER_ID', 'encounterId', 'BIZ_ID', 'bizId', 'ORDER_ID', 'orderId', 'IMRN', 'imrn']) {
    if (row[key] !== undefined && row[key] !== null && String(row[key])) return `${key}:${String(row[key])}`
  }
  return `ROW:${JSON.stringify(row)}`
}

function toggleRow(row: Record<string, unknown>) {
  const copy = new Map(selectedRows.value)
  const key = rowKey(row)
  if (copy.has(key)) copy.delete(key)
  else {
    copy.set(key, { ...row, __sourceGroup: detailGroup.value })
    selectedDepartments.value = []
  }
  selectedRows.value = copy
}

function clearSelectedRows() {
  selectedRows.value = new Map()
}

function findingSelect(finding: DiagnosisDataScreening['findings'][number]) {
  const ruleId = String(finding.ruleCode || '')
  selectedPublicRuleIds.value = selectedPublicRuleIds.value.includes(ruleId)
    ? selectedPublicRuleIds.value.filter((item) => item !== ruleId)
    : [...selectedPublicRuleIds.value, ruleId]
  // 公共规则代表“当前指标全量应用该规则”，不能把一条命中样例误当成
  // 只排除这一名患者。精确排除单条患者仍从分子/分母明细勾选。
  if (ruleId) return
  if (finding.row && Object.keys(finding.row).length) {
    const copy = new Map(selectedRows.value)
    if (copy.has(finding.rowKey)) copy.delete(finding.rowKey)
    else {
      copy.set(finding.rowKey, {
        ...finding.row,
        __sourceGroup: String(finding.sourceGroup
          || (finding.row as Record<string, unknown>).__sourceGroup || 'screening'),
      })
      selectedDepartments.value = []
    }
    selectedRows.value = copy
  }
}

async function openRuleRepair(finding: DiagnosisDataScreening['findings'][number]) {
  const ruleId = String(finding.ruleCode || '')
  repairRuleId.value = ruleId
  repairDialogOpen.value = true
  repairError.value = ''
  repairSqlExpanded.value = false
  if (!publicRuleRepairable(ruleId)) return
  repairPreviewLoading.value = true
  startExecutionFlow('rule-preview', ['正在读取公共规则…', '正在匹配当前 SQL 字段…', '正在生成一键修复方案…', '正在校验候选 SQL…'])
  try {
    const prepared = await act('PREVIEW_PUBLIC_RULE_FIX', { publicRuleIds: [ruleId] })
    if (!prepared || !Object.keys(prepared.candidateSql || {}).length) {
      repairError.value = error.value || '未能生成公共规则候选 SQL。'
    }
  } finally {
    stopExecutionFlow()
    repairPreviewLoading.value = false
  }
}

function closeRuleRepair() {
  repairDialogOpen.value = false
  repairRuleId.value = ''
  repairError.value = ''
  repairSqlExpanded.value = false
}

async function toggleRepairSql() {
  repairSqlExpanded.value = !repairSqlExpanded.value
  if (!repairSqlExpanded.value) return
  await nextTick()
  document.querySelector('.repair-sql-body')?.scrollIntoView({ block: 'nearest' })
}

async function runRuleRepair() {
  repairError.value = ''
  repairRunLoading.value = true
  startExecutionFlow('rule-run', ['正在创建隔离影子环境…', '正在执行修复候选 SQL…', '正在计算候选分子分母…', '正在生成候选明细…', '正在完成结果对账…'])
  try {
    const completed = await act('RUN_PUBLIC_RULE_FIX', {})
    if (!completed) {
      repairError.value = error.value || '公共规则候选 SQL 整体执行失败。'
      return
    }
    const result = record(completed.shadowTrial)
    if (String(result.status || '') === 'FAILED' || result.passed === false) {
      repairError.value = `${String(result.failureStage || '执行阶段')}：${String(result.message || '试跑未通过')}`
    }
  } finally {
    stopExecutionFlow()
    repairRunLoading.value = false
  }
}

function screeningFindingCell(
  finding: DiagnosisDataScreening['findings'][number],
  kind: 'name' | 'record' | 'department',
): string {
  const row = finding.row || {}
  if (kind === 'name') return String(row.FULL_NAME || row.PERSON_NAME || row.personName || '未登记姓名')
  if (kind === 'record') return String(row.ENCOUNTER_ID || row.encounterId || row.BIZ_ID || row.bizId || finding.rowKey)
  return String(row.CURRENT_DEPT_NAME || row.currentDeptName
    || row.CURRENT_WARD_NAME || row.currentWardName || '未登记科室')
}

function confirmationRows() {
  return [...selectedRows.value.entries()].map(([key, row]) => ({
    rowKey: key,
    label: displayRowLabel(row),
    recordId: key.includes(':') ? key.slice(key.indexOf(':') + 1) : key,
    sourceGroup: String(row.__sourceGroup || ''),
  }))
}

function removeSelectedRow(rowKeyValue: string) {
  const copy = new Map(selectedRows.value)
  copy.delete(rowKeyValue)
  selectedRows.value = copy
}

function removeSelectedRows(rowKeys: string[]) {
  const copy = new Map(selectedRows.value)
  rowKeys.forEach((key) => copy.delete(key))
  selectedRows.value = copy
}

function displayRowLabel(row: Record<string, unknown>): string {
  if (row.label) return String(row.label)
  const name = row.FULL_NAME || row.personName || row.PERSON_NAME || '未登记姓名'
  const dept = row.CURRENT_DEPT_NAME || row.currentDeptName || row.DEPT_NAME || ''
  return `${String(name)}${dept ? ` · ${String(dept)}` : ''}`
}

const DETAIL_FIELD_LABELS: Record<string, string> = {
  ENCOUNTER_ID: '就诊号', encounterId: '就诊号',
  IMRN: '住院号', imrn: '住院号',
  PERSON_NAME: '患者姓名', FULL_NAME: '患者姓名', personName: '患者姓名',
  CURRENT_DEPT_ID: '科室标识', currentDeptId: '科室标识',
  CURRENT_DEPT_NAME: '科室名称', currentDeptName: '科室名称',
  CURRENT_WARD_ID: '病区标识', currentWardId: '病区标识',
  CURRENT_WARD_NAME: '病区名称', currentWardName: '病区名称',
  ADMITTED_TO_WARD_AT: '入区时间', WARD_DISCHARGED_AT: '出区时间',
  TRANSFER_WITHIN_TWO_DAY: '48小时内转科判定',
  BIZ_ID: '业务编号', VERSION: '口径版本', HOSPITAL_AREA_ID: '院区标识',
  IS_DEL: '删除标记', __meets_numerator: '是否命中分子',
}

function detailFieldLabel(field: string): string {
  return DETAIL_FIELD_LABELS[field] || field
}

function formatDetailValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = typeof value === 'number' ? value : Number.NaN
  if ((field.endsWith('_AT') || field.toLowerCase().endsWith('at'))
    && Number.isFinite(numeric) && numeric > 1_000_000_000_000) {
    return new Date(numeric).toLocaleString('zh-CN', { hour12: false })
  }
  if (field === '__meets_numerator') return number(value) > 0 ? '是' : '否'
  return String(value)
}

function departmentTargets(values: string[]) {
  const options = screening.value?.departmentOptions || []
  const byField = new Map<string, { values: string[]; labels: string[] }>()
  for (const value of values) {
    const option = options.find((item) => item.value === value)
    const field = option?.field || 'CURRENT_DEPT_NAME'
    const group = byField.get(field) || { values: [], labels: [] }
    group.values.push(value)
    group.labels.push(option?.label || value)
    byField.set(field, group)
  }
  return [...byField.entries()].map(([field, group]) => ({
    targetType: 'DEPARTMENT',
    field,
    values: group.values,
    labels: group.labels,
    sourceGroup: 'DEPARTMENT_SELECTION',
  }))
}

function candidatePatientValue(row: Record<string, unknown>): string {
  for (const key of ['ENCOUNTER_ID', 'encounterId', 'BIZ_ID', 'bizId', 'ORDER_ID', 'orderId', 'IMRN', 'imrn']) {
    if (row[key] !== undefined && row[key] !== null && String(row[key])) return String(row[key])
  }
  return ''
}

async function searchAiPatients() {
  if (!snapshot.value || !allGatesPassed.value) return
  aiScopeLoading.value = true
  try {
    const pages = await Promise.all([
      fetchDiagnosisCaseDetails(store.token, snapshot.value.caseId, 'denominator', 1, 20, aiPatientSearch.value, ''),
      fetchDiagnosisCaseDetails(store.token, snapshot.value.caseId, 'numerator', 1, 20, aiPatientSearch.value, ''),
    ])
    const unique = new Map<string, AiScopeOption>()
    pages.flatMap((page) => page.rows || []).forEach((row) => {
      const value = candidatePatientValue(row)
      const field = rowKey(row).split(':')[0] || 'ENCOUNTER_ID'
      if (value) unique.set(value, { value, field, label: `${displayRowLabel(row)}（${value}）` })
    })
    aiPatientOptions.value = [...unique.values()]
  } catch (cause) {
    error.value = message(cause, '可选患者加载失败。')
  } finally {
    aiScopeLoading.value = false
  }
}

function toggleAiPatient(option: AiScopeOption) {
  aiScopeMode.value = 'PATIENT'
  aiSelectedDepartments.value = []
  aiSelectedPatients.value = aiSelectedPatients.value.some((item) => item.value === option.value)
    ? aiSelectedPatients.value.filter((item) => item.value !== option.value)
    : [...aiSelectedPatients.value, option]
}

function toggleAiDepartment(value: string) {
  aiScopeMode.value = 'DEPARTMENT'
  aiSelectedPatients.value = []
  aiSelectedDepartments.value = aiSelectedDepartments.value.includes(value)
    ? aiSelectedDepartments.value.filter((item) => item !== value)
    : [...aiSelectedDepartments.value, value]
}

function importConfirmationScope() {
  const publicRules = Array.isArray(snapshot.value?.dataConfirmation.publicRuleIds)
    ? snapshot.value?.dataConfirmation.publicRuleIds.map(String) : []
  if (publicRules.length) {
    selectedPublicRuleIds.value = [...new Set(publicRules)]
    requirement.value = clarificationRequirement()
    return
  }
  const rows = confirmationRows()
  if (rows.length) {
    aiScopeMode.value = 'PATIENT'
    aiSelectedDepartments.value = []
    const patientMap = new Map(aiSelectedPatients.value.map((item) => [item.value, item]))
    rows.forEach((item) => patientMap.set(item.recordId, {
      value: item.recordId,
      field: String(item.rowKey).split(':')[0] || 'ENCOUNTER_ID',
      label: `${item.label}（${item.recordId}）`,
    }))
    aiSelectedPatients.value = [...patientMap.values()]
  } else if (selectedDepartments.value.length) {
    aiScopeMode.value = 'DEPARTMENT'
    aiSelectedPatients.value = []
    aiSelectedDepartments.value = [...new Set(selectedDepartments.value)]
  }
}

function aiRequirementText(): string {
  const entered = requirement.value.trim()
  const parts = [entered]
  if (aiSelectedPatients.value.length) {
    parts.push(`针对这些患者或业务记录核查并修改：${aiSelectedPatients.value.map((item) => item.label).join('、')}`)
  }
  if (aiSelectedDepartments.value.length) {
    const options = screening.value?.departmentOptions || []
    const labels = aiSelectedDepartments.value.map((value) => options.find((item) => item.value === value)?.label || value)
    parts.push(`针对这些科室范围核查并修改：${labels.join('、')}`)
  }
  return parts.filter(Boolean).join('；')
}

function candidateInputSignature(): string {
  return JSON.stringify({
    nodeId: selectedNodeId.value,
    layer: selectedNodeLayer.value,
    mode: editMode.value,
    sql: editMode.value === 'direct' ? directSql.value.trim() : '',
    requirement: editMode.value === 'ai' ? aiRequirementText() : '',
    targets: editMode.value === 'ai' ? aiScopeTargets() : [],
    publicRuleIds: selectedPublicRuleIds.value,
  })
}

function aiScopeTargets() {
  if (aiScopeMode.value === 'PATIENT' && aiSelectedPatients.value.length) {
    const grouped = new Map<string, AiScopeOption[]>()
    aiSelectedPatients.value.forEach((item) => grouped.set(item.field, [...(grouped.get(item.field) || []), item]))
    return [...grouped.entries()].map(([field, items]) => ({
      targetType: 'RECORD', field,
      values: items.map((item) => item.value), labels: items.map((item) => item.label),
    }))
  }
  if (aiScopeMode.value === 'DEPARTMENT' && aiSelectedDepartments.value.length) {
    const options = screening.value?.departmentOptions || []
    return aiSelectedDepartments.value.map((value) => {
      const option = options.find((item) => item.value === value)
      return { targetType: 'DEPARTMENT', field: option?.field || 'CURRENT_DEPT_ID',
        values: [value], labels: [option?.label || value] }
    })
  }
  return []
}

function clarificationTargets(direction: 'OVER_INCLUDED' | 'UNDER_INCLUDED') {
  if (direction === 'OVER_INCLUDED') {
    const rows = confirmationRows()
    const rowsByField = new Map<string, typeof rows>()
    for (const row of rows) {
      const field = String(row.rowKey).split(':')[0] || 'ENCOUNTER_ID'
      rowsByField.set(field, [...(rowsByField.get(field) || []), row])
    }
    const recordTargets = [...rowsByField.entries()].map(([field, fieldRows]) => ({
      targetType: 'RECORD',
      field,
      values: fieldRows.map((row) => row.recordId),
      labels: fieldRows.map((row) => row.label),
      sourceGroup: [...new Set(fieldRows.map((row) => row.sourceGroup))].join(','),
    }))
    return [...recordTargets, ...departmentTargets(selectedDepartments.value)]
  }
  return []
}

function dataConfirmationPayload(noIssue = false) {
  return {
    overIncludedRows: confirmationRows(),
    overIncludedNote: overIncludedNote.value.trim(),
    overIncludedDepartments: departmentTargets(selectedDepartments.value),
    underIncludedNote: underIncludedNote.value.trim(),
    underIncludedTargets: clarificationTargets('UNDER_INCLUDED'),
    publicRuleIds: selectedPublicRuleIds.value,
    confirmedNoIssue: noIssue,
  }
}

async function clarifyData() {
  const hasOver = confirmationRows().length > 0 || selectedDepartments.value.length > 0
    || overIncludedNote.value.trim().length > 0
  const hasUnder = underIncludedNote.value.trim().length > 0
  if (!hasOver && !hasUnder) return
  clarifyingDirection.value = 'ALL'
  try {
    if (hasOver) {
      await act('CLARIFY_DATA_CONFIRMATION', {
        direction: 'OVER_INCLUDED',
        targets: clarificationTargets('OVER_INCLUDED'),
        description: overIncludedNote.value.trim(),
      })
    }
    if (hasUnder) {
      await act('CLARIFY_DATA_CONFIRMATION', {
        direction: 'UNDER_INCLUDED',
        targets: clarificationTargets('UNDER_INCLUDED'),
        description: underIncludedNote.value.trim(),
      })
    }
  } finally {
    clarifyingDirection.value = ''
  }
}

async function checkLatestExtraction() {
  refreshError.value = ''
  const overview = selectedNodeLayer.value === 'OVERVIEW'
  startExecutionFlow('baseline-refresh', overview
    ? ['正在读取当前正式概览 SQL…', '正在重新统计分子分母…', '正在计算正式结果值…', '正在核对最新结果…']
    : ['正在读取当前正式抽取 SQL…', '正在重新抽取业务数据…', '正在计算正式分子分母…', '正在核对最新结果…'])
  try {
    const updated = await act('RUN_LINEAGE_BASELINE', {
      layer: selectedNodeLayer.value, nodeId: selectedNodeId.value,
    })
    if (!updated) {
      refreshError.value = error.value || (overview ? '重新统计并计算失败。' : '重新抽取并计算失败。')
      refreshProgress.value = ''
      return
    }
    const oldRow = firstResultRow(updated.shadowTrial?.originalResult)
    const newRow = firstResultRow(updated.shadowTrial?.candidateResult)
    const oldValue = number(aggregateValue(oldRow, 'result'))
    const newValue = number(aggregateValue(newRow, 'result'))
    refreshProgress.value = oldValue === newValue
      ? `${overview ? '重新统计' : '重新抽取'}和计算完成，指标结果无变化。`
      : `${overview ? '重新统计' : '重新抽取'}和计算完成：指标值由 ${metricText(oldValue)} 变为 ${metricText(newValue)}。`
  } finally {
    stopExecutionFlow()
  }
}

async function proceedToLineage() {
  const payload = dataConfirmationPayload(false)
  const hasIssue = payload.overIncludedRows.length > 0
    || Boolean(payload.overIncludedNote)
    || payload.overIncludedDepartments.length > 0
    || Boolean(payload.underIncludedNote)
    || payload.underIncludedTargets.length > 0
    || payload.publicRuleIds.length > 0
  const updated = await act('SUBMIT_DATA_CONFIRMATION', {
    ...payload,
    confirmedNoIssue: !hasIssue,
  })
  if (!updated) return
  await goStep('lineage')
}

async function finishAsCorrect() {
  if (!window.confirm('确认当前结果正确并结束本次排查？结束后本任务将变为只读。')) return
  const updated = await act('SUBMIT_DATA_CONFIRMATION', dataConfirmationPayload(true))
  if (!updated) return
  await act('CLOSE_AS_CORRECT', { conclusion: '实施人员已核对本次分子、分母明细，确认当前结果无异议。' })
}

function initializeNode() {
  if (!selectedNodeId.value || !flowNodes.value.some((node) => String(node.id) === selectedNodeId.value)) {
    selectedNodeId.value = String(flowNodes.value.find((node) => ['SOURCE_EXTRACT_SQL', 'OVERVIEW_SQL'].includes(String(node.nodeType)))?.id || flowNodes.value[0]?.id || '')
  }
}

function defaultNodeId(layer: string) {
  return layer === 'OVERVIEW' ? 'overview-sql' : 'source-extract-sql'
}

function selectNode(node: FlowNode) {
  const nextNodeId = String(node.id || '')
  selectedNodeId.value = nextNodeId
  if (trialNodeId.value && trialNodeId.value !== nextNodeId) {
    trialProgress.value = ''
    trialError.value = ''
  }
}

function databaseLabel(value: unknown): string {
  return ({ BUSINESS: '业务库', REAL: '真实库', SYNC: '业务库 · 同步任务', KNOWLEDGE: '知识库' } as Record<string, string>)[String(value || '')] || String(value || '未登记')
}

const TABLE_PURPOSES: Record<string, string> = {
  INPATIENT_ENCOUNTER: '住院患者主表：记录每次住院就诊、入出区时间、患者和当前科室等基础信息。',
  INPAT_TRANSFER: '转科转区明细表：记录患者每次转科或转区的时间、转出科室和转入科室。',
  ORGANIZATION: '科室病区字典表：把科室、病区编码转换为实施人员可读的名称。',
  INPATIENT_PARTICIPANT: '住院参与人员表：记录经治、主管等参与本次住院的医务人员。',
  EMPLOYEE_INFO: '员工信息表：把员工标识转换为医生或职工姓名。',
}

function tablePurpose(table: string, fallback: unknown): string {
  return TABLE_PURPOSES[table.toUpperCase()]
    || String(fallback || '知识库只登记了该表名称，尚未补充业务用途。')
}

function nodePurpose(node: FlowNode | null): string {
  if (!node) return ''
  const type = String(node.nodeType || '')
  const id = String(node.id || '')
  const tables = strings(node.tableNames)
  const tableText = tables.slice(0, 2).join('、')
  if (type === 'SOURCE_EXTRACT_SQL') {
    return `从${databaseLabel(node.databaseRole)}读取当前指标需要的原始记录，按统计窗口、排除和去重规则整理后，生成供正式统计使用的指标中间数据。`
  }
  if (type === 'EXTENDED_EVENT_SQL') {
    return `从医院业务表识别“${String(node.title || '当前事件').replace('拓展事件 SQL · ', '')}”事件，并写入患者事件表，供后续抽取 SQL继续关联。`
  }
  if (type === 'OVERVIEW_SQL') {
    return '读取当前指标中间数据，按照生效口径计算分子、分母、结果值和是否达标；这里决定指标卡片最终显示的合计结果。'
  }
  if (type === 'DEPARTMENT_SQL') {
    return '按科室重新聚合当前指标中间数据，用于查看每个科室的分子、分母和结果，帮助定位某个科室漏数或多算。'
  }
  if (type === 'PATIENT_SQL') {
    return '按当前口径查询进入分子或分母的患者记录，为数据确认、患者澄清和具体记录追溯提供明细。'
  }
  if (type === 'TABLE' && id === 'business-tables') {
    return `展示当前抽取链路在医院业务库实际读取的${tableText || '原始业务表'}，用于核对患者、医嘱、转科等原始记录是否存在。`
  }
  if (type === 'TABLE' && id === 'patient-event') {
    return '保存由各类拓展事件 SQL生成的标准患者事件；当前指标的抽取 SQL从这里读取已经整理好的事件记录。'
  }
  if (type === 'TABLE' && id === 'target-table') {
    return `保存当前指标抽取后的标准化记录${tableText ? `（${tableText}）` : ''}，概览、科室和患者明细 SQL都以这里的数据为统计基础。`
  }
  if (type === 'TABLE' && id === 'statistic-parameters') {
    return '提供目标值、比较方向等指标参数，供概览统计判断是否达标；这里不保存患者或业务明细。'
  }
  if (type === 'TABLE' && id === 'real-existing-tables') {
    return `展示当前指标直接使用的真实库现有数据表${tableText ? `（${tableText}）` : ''}；本指标不再单独执行源表抽取。`
  }
  return String(node.description || (tableText
    ? `当前节点使用${tableText}完成本环节的数据处理。`
    : '当前知识库尚未补充该节点的具体业务作用。'))
}

function nodeHint(node: FlowNode | null): string {
  if (!node) return ''
  const type = String(node.nodeType || '')
  if (type === 'SOURCE_EXTRACT_SQL') return '建议先查看当前正式 SQL，再按需重新抽取或修改 SQL。'
  if (type === 'OVERVIEW_SQL') return '只有中间数据正确、但分子分母计算不对时，才修改本节点。'
  if (type === 'DEPARTMENT_SQL') return '用于核对科室汇总，不在这里修改正式口径。'
  if (type === 'PATIENT_SQL') return '用于查看分子分母明细，不在这里修改正式口径。'
  if (type === 'EXTENDED_EVENT_SQL') return '用于核对业务事件怎样生成；当前标准模式只读查看。'
  return '这是只读数据节点，用于确认本环节实际使用的数据。'
}

function strings(value: unknown): string[] { return Array.isArray(value) ? value.map(String).filter(Boolean) : [] }

async function runCandidate() {
  if (!snapshot.value || !selectedNodeLayer.value) {
    error.value = '请先选择源表抽取 SQL 或概览统计 SQL 节点。'
    return
  }
  if (!modificationAllowed.value) {
    error.value = '后台数据准备完成后，才能生成候选 SQL 并执行影子试跑。'
    return
  }
  if (!candidateCanExecute.value) {
    error.value = candidateContentChanged.value
      ? '当前修改条件已变化，请重新生成并校验候选 SQL。'
      : '尚未生成与正式 SQL 不同的候选 SQL，不能执行。'
    return
  }
  trialError.value = ''
  trialNodeId.value = selectedNodeId.value
  trialProgress.value = '正在执行候选 SQL'
  startExecutionFlow('candidate-run', ['正在创建隔离影子环境…', '正在执行候选抽取 SQL…', '正在计算候选分子分母…', '正在生成差异明细…', '正在完成结果对账…'])
  try {
    const completed = await act('RUN_SHADOW_TRIAL', {})
    if (!completed) {
      trialError.value = error.value || '影子试跑请求失败。'
      trialProgress.value = '试跑失败'
      return
    }
    const result = record(completed.shadowTrial)
    if (String(result.status || '') === 'FAILED' || result.passed === false) {
      trialError.value = `${String(result.failureStage || '执行阶段')}：${String(result.message || '影子试跑未通过')}`
      trialProgress.value = '试跑失败'
    } else {
      trialProgress.value = '试跑完成'
    }
    await nextTick()
    document.querySelector('.shadow-result, .trial-inline-error')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  } catch (cause) {
    trialError.value = message(cause, '候选 SQL 影子试跑失败。')
  } finally {
    stopExecutionFlow()
    if (!trialError.value && trialProgress.value !== '试跑完成') trialProgress.value = ''
  }
}

async function generateCandidate() {
  if (!snapshot.value || !selectedNodeLayer.value || !modificationAllowed.value) return null
  const candidateSql = editMode.value === 'direct' ? directSql.value.trim() : ''
  const requirementText = editMode.value === 'ai' ? aiRequirementText() : '实施人员直接编辑当前正式 SQL'
  if (!requirementText || (editMode.value === 'direct' && !directSqlChanged.value)) return null
  if (editMode.value === 'ai'
    && requirementText.includes('排除这些疑似多算记录：')
    && aiScopeTargets().length === 0) {
    trialNodeId.value = selectedNodeId.value
    trialProgress.value = '带入条件不完整'
    trialError.value = '缺少原始记录字段和业务编号，请点击“带入数据确认”重新选择记录后再生成 SQL。'
    return null
  }
  const preparedSignature = candidateInputSignature()
  trialError.value = ''
  trialNodeId.value = selectedNodeId.value
  trialProgress.value = '正在生成候选 SQL'
  startExecutionFlow('candidate-generate', editMode.value === 'ai'
    ? ['正在读取带入条件…', '正在匹配当前 SQL 字段…', '正在生成对应 SQL…', '正在校验 SQL 安全性…']
    : ['正在比对手动 SQL…', '正在校验 SQL 安全性…', '正在确认输出结构…'])
  try {
    const prepared = await act('SUBMIT_EVIDENCE', {
      type: 'IMPLEMENTER_SQL_REQUIREMENT',
      suspectedLayer: selectedNodeLayer.value,
      nodeId: selectedNodeId.value,
      summary: `${selectedNodeLayer.value === 'SOURCE_EXTRACT' ? '抽取 SQL' : '概览 SQL'}修改要求：${requirementText}`,
      requirement: requirementText,
      candidateSql,
      patchConditions: [],
      scopeTargets: aiScopeTargets(),
      publicRuleIds: Array.isArray(snapshot.value?.dataConfirmation.publicRuleIds)
        ? snapshot.value?.dataConfirmation.publicRuleIds : [],
      generationMode: editMode.value === 'direct' ? 'DIRECT_EDIT' : 'AI_MODIFY',
      dataConfirmationRef: String(snapshot.value?.dataConfirmation.submittedAt || ''),
      requestAiAnalysis: editMode.value === 'ai',
      deferShadowTrial: true,
    })
    if (!prepared || !Object.keys(prepared.candidateSql || {}).length) {
      trialError.value = error.value || '候选 SQL 未通过静态校验，请核对修改要求。'
      trialProgress.value = 'SQL校验失败'
      return null
    }
    const preparedCandidate = record(prepared.candidateSql)
    const preparedSql = String(preparedCandidate.candidateSqlExecutable || preparedCandidate.sql || '').trim()
    const preparedOriginal = String(preparedCandidate.originalSqlExecutable || preparedCandidate.originalSql || '').trim()
    if (!preparedSql || preparedSql === preparedOriginal) {
      trialError.value = '候选 SQL 与当前正式 SQL 相同，请先修改条件或 SQL 内容。'
      trialProgress.value = '候选 SQL 未发生变化'
      candidatePreparedSignature.value = ''
      return null
    }
    trialProgress.value = 'SQL校验通过，候选 SQL 已生成'
    await nextTick()
    candidatePreparedSignature.value = preparedSignature
    document.querySelector('.candidate-result, .trial-inline-error')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    return prepared
  } catch (cause) {
    trialError.value = message(cause, '候选 SQL 生成或校验失败。')
    return null
  } finally {
    stopExecutionFlow()
    if (trialError.value && trialProgress.value !== 'SQL校验失败') trialProgress.value = 'SQL校验失败'
  }
}

async function executeWholeLineage() {
  if (!snapshot.value || !modificationAllowed.value) return
  trialError.value = ''
  trialNodeId.value = selectedNodeEditable.value ? selectedNodeId.value : ''
  startExecutionFlow('overall', ['正在准备当前正式链路…', '正在校验数据库方言…', '正在执行正式 SQL 基线…', '正在计算分子分母…', '正在核对执行结果…'])
  try {
    const completed = await act('RUN_LINEAGE_BASELINE', selectedNodeEditable.value
      ? { layer: selectedNodeLayer.value, nodeId: selectedNodeId.value } : {})
    if (!completed) {
      trialError.value = error.value || '当前链路基线试跑失败。'
      trialProgress.value = '执行失败'
      return
    }
    trialNodeId.value = String(completed.shadowTrial?.nodeId || completed.candidateSql?.nodeId || '')
    const result = record(completed.shadowTrial)
    if (String(result.status || '') === 'FAILED' || result.passed === false) {
      trialError.value = `${String(result.failureStage || '执行阶段')}：${String(result.message || '基线试跑未通过')}`
      trialProgress.value = '执行失败'
    } else {
      trialProgress.value = '执行完成'
    }
  } finally {
    stopExecutionFlow()
  }
  await nextTick()
  document.querySelector('.shadow-result, .trial-inline-error')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function reviseCandidate() {
  const updated = await act('REVISE_CANDIDATE', {})
  if (!updated) return
  requirement.value = ''
  directSql.value = String(selectedNode.value?.templateSql || selectedNode.value?.sql || '')
}

async function loadDiff(page = 1) {
  const trialId = String(shadow.value.trialId || '')
  if (!snapshot.value || !trialId) return
  diffLoading.value = true
  try {
    diffPage.value = await loadDiagnosisShadowDiffs(store.token, snapshot.value.caseId,
      trialId, diffType.value, page, 50, diffSearch.value)
  } catch (cause) {
    error.value = message(cause, '差异明细加载失败。')
  } finally {
    diffLoading.value = false
  }
}

async function toggleDiffCategory(event: Event, type: DiagnosisShadowDiffPage['type']) {
  if (!(event.currentTarget as HTMLDetailsElement).open) return
  diffType.value = type
  diffSearch.value = ''
  await loadDiff(1)
}

async function saveDraft() {
  await act('SAVE_HOSPITAL_DRAFT', { confirmed: true, ...draftDescription.value })
}

async function copyText(key: string, value: unknown) {
  const text = String(value || '')
  if (!text) return
  await navigator.clipboard.writeText(text)
  copiedKey.value = key
  window.setTimeout(() => { if (copiedKey.value === key) copiedKey.value = '' }, 1400)
}

function closeWorkspace() { void router.push('/') }
</script>

<template>
  <main class="standard-diagnosis-workspace">
    <header class="standard-diagnosis-header">
      <button type="button" class="workspace-close" aria-label="关闭标准排查工作区" @click="closeWorkspace">×</button>
      <div class="workspace-title">
        <span>指标异常排查 <b>标准模式</b></span>
        <strong>{{ snapshot?.caliberSnapshot.ruleName || selectedIndicator?.ruleName || '建立一个可追溯的排查任务' }}</strong>
        <small v-if="snapshot">{{ snapshot.profileId }} · {{ snapshot.caliberSnapshot.profileName }}</small>
      </div>
      <div class="workspace-current-step"><span>当前步骤</span><strong>{{ currentStepLabel }}</strong></div>
    </header>

    <StandardDiagnosisStepper :current-step="currentStep" :has-case="Boolean(snapshot)" @navigate="goStep" />

    <p v-if="error" class="workspace-error">{{ error }}</p>
    <section v-if="loading" class="workspace-loading">正在加载真实指标口径与排查证据…</section>

    <section v-else-if="currentStep === 'selection'" class="workspace-page selection-page">
      <header class="page-heading compact-heading selection-title"><span class="selection-title-icon" aria-hidden="true"><i></i><i></i><i></i></span><h1>选择指标、时间与本次口径</h1></header>
      <div class="selection-layout">
        <section class="selection-indicators">
          <label class="workspace-field"><span>查找指标</span><input v-model="indicatorSearch" type="search" placeholder="输入指标名称或编码" /></label>
          <div class="indicator-picker">
            <button v-for="item in filteredIndicators" :key="item.ruleId" type="button"
              :class="{ selected: selectedRuleId === item.ruleId }" @click="chooseIndicator(item.ruleId)">
              <span class="selection-dot" aria-hidden="true"></span><strong>{{ item.ruleName }}</strong><small>{{ item.ruleId }}</small>
            </button>
          </div>
        </section>
        <section class="selection-config">
          <div class="date-grid">
            <label class="workspace-field"><span>统计开始</span><input v-model="statStart" type="date" /></label>
            <label class="workspace-field"><span>统计结束（不含）</span><input v-model="statEnd" type="date" /></label>
          </div>
          <div class="profile-list">
            <header><strong>本指标可用口径</strong><span>{{ profiles.length }} 种</span></header>
            <p v-if="selectedRuleId && !profiles.length" class="workspace-empty">当前知识库没有可选口径。</p>
            <button v-for="profile in profiles" :key="profile.profileId" type="button"
              :class="{ selected: selectedProfileId === profile.profileId }" @click="chooseProfile(profile.profileId)">
              <span class="profile-choice-icon" aria-hidden="true">★</span>
              <span><strong>{{ profile.profileName }}</strong><small>{{ profile.profileId }}</small></span>
              <em>{{ profile.overviewRuntimeEligible ? '可执行' : '仅文档' }}</em>
              <p><b>分子</b>{{ profile.numeratorRule || '知识库未单独登记' }}</p>
              <p><b>分母</b>{{ profile.denominatorRule || '知识库未单独登记' }}</p>
            </button>
          </div>
          <button type="button" class="workspace-primary" :disabled="busy !== '' || !selectedProfileId || !selectedProfile?.overviewRuntimeEligible || !statStart || !statEnd" @click="createCase">
            <span aria-hidden="true">▶</span>{{ busy === 'create' ? '正在创建排查任务…' : '开始排查' }}
          </button>
        </section>
      </div>
      <footer class="selection-tip"><span aria-hidden="true">♧</span><strong>提示：</strong>确认统计时间范围与口径后，点击“开始排查”进入数据核对流程。</footer>
    </section>

    <section v-else-if="currentStep === 'data'" class="workspace-page data-page">
      <header class="data-review-hero">
        <div>
          <span class="data-review-title">统计结果与数据核对 <b>标准模式</b></span>
        </div>
        <em><span aria-hidden="true">▣</span>{{ String(snapshot?.caseInput.statStart || '—').slice(0, 10) }} 至 {{ String(snapshot?.caseInput.statEnd || '—').slice(0, 10) }}</em>
      </header>
      <section v-if="checksPreparing" class="background-check-status">
        <header><div><span class="trial-spinner" aria-hidden="true"></span><strong>正在准备本次统计数据</strong></div><p>三项基础校验会自动依次执行，通过后立即展示本次统计结果。</p></header>
        <ol class="preparation-flow">
          <li v-for="item in preparationSteps" :key="item.gate" :data-state="item.status">
            <b><span v-if="item.status === 'RUNNING'" class="trial-spinner" aria-hidden="true"></span><template v-else>{{ item.status === 'PASSED' ? '✓' : item.gate }}</template></b>
            <div><strong>{{ item.title }}</strong><p>{{ item.message || item.description }}</p></div>
            <em>{{ preparationStatusLabel(item.status) }}</em>
          </li>
        </ol>
      </section>
      <article v-else-if="gateBlocked" class="background-check-failure"><header><strong>本次统计数据暂时无法准备完成</strong><code>{{ String(blockedGate?.errorCode || '') }}</code></header><p>{{ String(blockedGate?.message || '后台校验发现需要处理的问题。') }}</p><section v-if="blockedGate?.repairSuggestion"><b>建议怎么处理</b><p>{{ String(blockedGate.repairSuggestion) }}</p></section><button type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="retryGate(number(blockedGate?.gate))">修复后重新准备</button></article>
      <template v-else>
        <section class="result-overview-grid without-status">
          <div class="metric-strip statistics-card">
            <article class="primary-result"><span>指标结果（当前口径计算值）</span><strong>{{ resultValue }}</strong><small>正式统计结果</small></article>
            <button type="button" :class="{ active: detailGroup === 'numerator' }" @click="toggleDetails('numerator')"><span>统计 SQL 分子</span><strong>{{ numeratorCount }}</strong><small>{{ detailGroup === 'numerator' ? '收起分子明细 ↑' : '查看分子明细 →' }}</small></button>
            <button type="button" :class="{ active: detailGroup === 'denominator' }" @click="toggleDetails('denominator')"><span>统计 SQL 分母</span><strong>{{ denominatorCount }}</strong><small>{{ detailGroup === 'denominator' ? '收起分母明细 ↑' : '查看分母明细 →' }}</small></button>
          </div>
        </section>
        <article v-if="calculationFailed" class="calculation-failure"><strong>指标计算失败</strong><span>{{ calculationFailureMessage }}</span></article>

        <section v-if="detailGroup" class="detail-browser is-expanded">
          <header><div><span>{{ detailGroup === 'numerator' ? '分子明细' : '分母明细' }}</span><strong>{{ detailPage?.rowCount ?? 0 }} 条</strong></div><button type="button" class="detail-close" @click="detailGroup = ''; detailPage = null"><span>收起明细</span><b aria-hidden="true">×</b></button></header>
          <div class="detail-toolbar"><input v-model="detailSearch" type="search" placeholder="搜索患者姓名、就诊号或住院号" @keyup.enter="reloadDetails(1)" /><label class="department-select"><span>科室</span><select v-model="detailDepartment" @change="reloadDetails(1)"><option value="">全部科室</option><option v-for="dept in detailPage?.departments || []" :key="dept" :value="dept">{{ dept }}</option></select></label><button type="button" @click="reloadDetails(1)">查询</button></div>
          <p v-if="detailLoading" class="workspace-loading">正在读取明细…</p>
          <p v-else-if="detailPage && !detailsReconciled" class="workspace-error">合计与明细对账不一致，当前明细暂不展示。</p>
          <div v-else class="detail-table-wrap"><table><thead><tr><th>勾选排查</th><th v-for="column in detailColumns" :key="column"><span>{{ detailFieldLabel(column) }}</span><small v-if="detailFieldLabel(column) !== column">{{ column }}</small></th></tr></thead><tbody><tr v-for="row in detailPage?.rows || []" :key="rowKey(row)" :class="{ selected: selectedRows.has(rowKey(row)) }"><td><input type="checkbox" :checked="selectedRows.has(rowKey(row))" aria-label="勾选这条记录进行排查" @change="toggleRow(row)" /></td><td v-for="column in detailColumns" :key="column">{{ formatDetailValue(column, row[column]) }}</td></tr></tbody></table><p v-if="!detailPage?.rows?.length" class="workspace-empty">当前筛选条件下没有明细。</p></div>
          <nav v-if="detailPage" class="pager"><button type="button" :disabled="(detailPage.page || 1) <= 1" @click="reloadDetails((detailPage.page || 1) - 1)">上一页</button><span>第 {{ detailPage.page || 1 }} / {{ Math.max(1, Math.ceil((detailPage.rowCount || 0) / (detailPage.pageSize || 10))) }} 页</span><button type="button" :disabled="(detailPage.page || 1) * (detailPage.pageSize || 10) >= (detailPage.rowCount || 0)" @click="reloadDetails((detailPage.page || 1) + 1)">下一页</button></nav>
        </section>

        <section class="screening-panel compact-screening" :class="{ 'has-findings': Boolean(screening?.findingCount) }">
          <header><div><span class="screening-alert" aria-hidden="true">!</span><strong>AI 初筛</strong><span v-if="screening?.findingCount" class="screening-count">发现 {{ screening.findingCount }} 条疑似测试或重复数据</span></div><button v-if="(screening?.findingCount || 0) > 3" type="button" class="screening-toggle" @click="screeningExpanded = !screeningExpanded">{{ screeningExpanded ? '收起' : `查看全部 ${screening?.findingCount} 条 ›` }}</button></header>
          <p v-if="screeningLoading">正在按公共规则检查患者姓名、科室名称和明细重复业务编号…</p>
          <p v-else-if="!screening?.findingCount">当前明细未命中测试患者、测试/血液透析门诊科室或重复业务编号规则。</p>
          <template v-else>
            <p class="screening-guidance">以下是公共规则命中的实际明细样例。点击“一键修复”，可按当前指标抽取 SQL 的实际字段生成候选语句并预览差异。</p>
            <div class="screening-table" role="table" aria-label="AI初筛结果">
              <div class="screening-table-head" role="row"><span>患者姓名</span><span>患者标识</span><span>科室</span><span>命中规则</span><span>一键修复</span></div>
              <div v-for="finding in screeningPreviewFindings" :key="finding.findingId" role="row" class="screening-table-row">
                <strong>{{ screeningFindingCell(finding, 'name') }}</strong><span>{{ screeningFindingCell(finding, 'record') }}</span><span>{{ screeningFindingCell(finding, 'department') }}</span><small>{{ finding.reason }}</small><div class="screening-row-actions"><button type="button" class="screening-repair-button" :disabled="repairPreviewLoading" @click="openRuleRepair(finding)">{{ publicRuleRepairable(finding.ruleCode) ? '一键修复' : '人工检查' }}</button></div>
              </div>
            </div>
          </template>
        </section>

        <section v-if="selectedPublicRuleIds.length" class="selected-public-rules">
          <header><strong>已选择公共处理规则</strong><button type="button" @click="clearPublicRules">清空</button></header>
          <div><button v-for="ruleId in selectedPublicRuleIds" :key="ruleId" type="button" @click="findingSelect({ ruleCode: ruleId } as DiagnosisDataScreening['findings'][number])">{{ publicRuleLabel(ruleId) }} ×</button></div>
          <p>进入数据链路后默认修改当前指标的源表抽取 SQL；程序会先解析当前脚本实际字段，再生成候选 SQL。</p>
        </section>

        <StandardDataConfirmationEditor
          v-model:over-note="overIncludedNote"
          v-model:under-note="underIncludedNote"
          :selected-rows="confirmationRows()"
          :department-options="screening?.departmentOptions || []"
          v-model:selected-departments="selectedDepartments"
          v-model:under-target-type="underTargetType"
          v-model:under-record-ids="underRecordIds"
          v-model:under-departments="underDepartments"
          v-model:under-department-manual="underDepartmentManual"
          :busy="Boolean(busy)"
          :completed="caseCompleted"
          :over-clarification="record(currentClarifications.OVER_INCLUDED)"
          :under-clarification="record(currentClarifications.UNDER_INCLUDED)"
          :clarifying-direction="clarifyingDirection"
          @clear-selection="clearSelectedRows"
          @remove-selection="removeSelectedRow"
          @remove-selections="removeSelectedRows"
          @clarify="clarifyData"
          @proceed="proceedToLineage"
          @finish="finishAsCorrect"
        />
      </template>
    </section>

    <section v-else class="workspace-page lineage-page">
      <header class="lineage-toolbar"><div class="lineage-actions"><button type="button" class="workspace-primary" title="使用当前正式 SQL 执行完整链路基线；不会自动使用尚未单独确认执行的候选 SQL" :disabled="!overallExecutionReady" aria-live="polite" @click="executeWholeLineage"><span v-if="activeExecution === 'overall' || checksPreparing" class="trial-spinner" aria-hidden="true"></span>{{ overallButtonLabel }}</button></div></header>
      <div class="lineage-layout">
        <aside class="flow-rail"><header><strong>当前生效数据链路</strong><span>{{ flowNodes.length }} 个节点</span></header><button v-for="node in flowNodes" :key="String(node.id)" type="button" :class="{ active: String(node.id) === selectedNodeId, editable: ['SOURCE_EXTRACT_SQL', 'OVERVIEW_SQL'].includes(String(node.nodeType)) }" @click="selectNode(node)"><span><strong>{{ String(node.title || '未命名节点') }}</strong><small>{{ databaseLabel(node.databaseRole) }}</small></span><em v-if="String(shadow.nodeId || candidate.nodeId || '') === String(node.id)" class="trial-badge">✓ 已试跑</em><em v-else>{{ ['SOURCE_EXTRACT_SQL', 'OVERVIEW_SQL'].includes(String(node.nodeType)) ? '✎ 可修改' : '◉ 只读' }}</em></button><p v-if="!flowNodes.length" class="workspace-empty">当前知识库没有可展示的数据链路。</p></aside>
        <section v-if="selectedNode" class="node-inspector">
          <header class="lineage-node-context">
            <div><strong>{{ String(selectedNode.title || '未命名节点') }}</strong><p>{{ nodePurpose(selectedNode) }}</p></div>
            <aside><span aria-hidden="true">♧</span>{{ nodeHint(selectedNode) }}</aside>
          </header>
          <section v-if="strings(selectedNode.tableNames).length" class="node-table-section lineage-work-card"><header><div><strong>这一环节用到的数据表</strong><span>先展示最主要的两张表，其余表可按需展开。</span></div></header><ul class="node-table-list"><li v-for="table in strings(selectedNode.tableNames).slice(0, 2)" :key="table"><i aria-hidden="true">▦</i><div><strong>{{ table }}</strong><span>{{ tablePurpose(table, record(selectedNode.tableDescriptions)[table]) }}</span></div></li></ul><details v-if="strings(selectedNode.tableNames).length > 2" class="more-node-tables"><summary>查看其余 {{ strings(selectedNode.tableNames).length - 2 }} 张表</summary><ul class="node-table-list"><li v-for="table in strings(selectedNode.tableNames).slice(2)" :key="table"><i aria-hidden="true">▦</i><div><strong>{{ table }}</strong><span>{{ tablePurpose(table, record(selectedNode.tableDescriptions)[table]) }}</span></div></li></ul></details></section>
          <details v-if="String(selectedNode.sql || '').trim()" class="sql-template-evidence lineage-work-card"><summary><span><strong>查看当前正式 SQL</strong><small>用当前正式 SQL {{ selectedNodeLayer === 'OVERVIEW' ? '重新统计并计算' : '重新抽取并计算' }}</small></span><em>展开查看与复制 ›</em></summary><section class="sql-panel"><header><strong>当前统计窗口可直接执行 SQL</strong><button type="button" @click="copyText(`node-${String(selectedNode.id)}`, selectedNode.sql)">{{ copiedKey === `node-${String(selectedNode.id)}` ? '已复制' : '复制 SQL' }}</button></header><pre>{{ String(selectedNode.sql) }}</pre></section></details>
          <section v-if="selectedNodeEditable" class="source-refresh-card lineage-work-card">
            <div><strong>{{ selectedNodeLayer === 'OVERVIEW' ? '怀疑是统计计算有问题？' : '怀疑是抽数据有问题？' }}</strong><p>使用当前正式{{ selectedNodeLayer === 'OVERVIEW' ? '概览统计' : '抽取' }} SQL {{ selectedNodeLayer === 'OVERVIEW' ? '重新统计指标结果' : '读取最新业务数据并重新计算' }}，不会使用页面中的候选 SQL。</p></div>
            <button type="button" class="workspace-secondary" :disabled="Boolean(busy) || !modificationAllowed" aria-live="polite" @click="checkLatestExtraction"><span v-if="activeExecution === 'baseline-refresh'" class="trial-spinner" aria-hidden="true"></span>{{ refreshButtonLabel }}</button>
            <p v-if="refreshProgress" class="source-refresh-status">{{ refreshProgress }}</p>
            <p v-if="refreshError" class="source-refresh-error">{{ refreshError }}</p>
          </section>
          <div v-if="checksPreparing" class="node-lock is-preparing"><strong>正在准备本次统计数据</strong><p>数据链路和正式 SQL 可以先查看；准备完成后即可整体执行或生成候选 SQL。</p></div>
          <div v-else-if="gateBlocked" class="node-lock is-blocked"><strong>{{ String(blockedGate?.message || '后台校验发现需要处理的问题') }}</strong><p>{{ String(blockedGate?.repairSuggestion || '修复现场数据或知识口径后重新准备。') }}</p><button type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="retryGate(number(blockedGate?.gate))">修复后重新准备</button></div>
          <section v-else-if="selectedNodeEditable && ['SHADOW_TRIAL', 'DRAFT_SAVE'].includes(String(snapshot?.currentStep)) && !candidateOwnedBySelectedNode" class="node-lock"><strong>当前试跑属于另一个 SQL 节点</strong><p>该节点不会显示其他节点的候选和影子结果。开始修改这里前，需要重新生成本节点候选。</p><button type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="reviseCandidate">开始修改当前节点</button></section>
          <section v-else-if="selectedNodeEditable && (['CASE_INPUT', 'CASE_INVESTIGATION'].includes(String(snapshot?.currentStep)) || candidatePendingRun)" class="candidate-editor lineage-work-card">
            <header><div><strong>修改{{ selectedNodeLayer === 'SOURCE_EXTRACT' ? '抽取' : '概览' }} SQL</strong><span>通过直接编辑或 AI辅助，修改当前节点；只在影子环境执行。</span></div></header>
            <div class="edit-tabs"><button type="button" :class="{ active: editMode === 'direct' }" @click="editMode = 'direct'">✎ 直接编辑 SQL</button><button type="button" :class="{ active: editMode === 'ai' }" @click="editMode = 'ai'">▣ AI 生成对应 SQL</button></div>
            <textarea v-if="editMode === 'direct'" v-model="directSql" rows="12" class="sql-editor" placeholder="编辑当前正式模板 SELECT"></textarea>
            <template v-else>
              <textarea v-model="requirement" rows="6" placeholder="写清楚需要纳入或排除什么数据，以及使用哪个已有字段判断。已完成的数据确认内容会自动带入；也可以直接在这里填写。"></textarea>
              <details class="ai-scope-picker">
                <summary><span><strong>选择排除对象</strong><small>{{ aiScopeMode === 'PATIENT' ? `按患者排除 · 已选 ${aiSelectedPatients.length} 位` : `按科室排除 · 已选 ${aiSelectedDepartments.length} 个` }}</small></span><em>展开选择</em></summary>
                <div class="ai-scope-actions"><button type="button" :disabled="!confirmationRows().length && !selectedDepartments.length" @click="importConfirmationScope">带入数据确认</button><button type="button" :disabled="!aiSelectedPatients.length && !aiSelectedDepartments.length" @click="aiSelectedPatients = []; aiSelectedDepartments = []">清空选择</button></div>
                <div class="ai-scope-mode"><button type="button" :class="{ active: aiScopeMode === 'PATIENT' }" @click="aiScopeMode = 'PATIENT'; aiSelectedDepartments = []">按患者排除</button><button type="button" :class="{ active: aiScopeMode === 'DEPARTMENT' }" @click="aiScopeMode = 'DEPARTMENT'; aiSelectedPatients = []">按科室排除</button></div>
                <section v-if="aiScopeMode === 'PATIENT'"><label><span>搜索患者</span><div><input v-model="aiPatientSearch" type="search" placeholder="输入姓名、就诊号或住院号" @keyup.enter="searchAiPatients" /><button type="button" :disabled="aiScopeLoading" @click="searchAiPatients">{{ aiScopeLoading ? '查询中…' : '查询' }}</button></div></label><div class="ai-scope-options"><label v-for="item in aiPatientOptions" :key="item.value"><input type="checkbox" :checked="aiSelectedPatients.some((selected) => selected.value === item.value)" @change="toggleAiPatient(item)" /><span>{{ item.label }}</span></label><p v-if="aiPatientOptions.length === 0">输入关键词后查询本次分子、分母明细。</p></div></section>
                <section v-else><label><span>选择科室</span><input v-model="aiDepartmentSearch" type="search" placeholder="搜索科室名称或编码" /></label><div class="ai-scope-options"><label v-for="item in filteredAiDepartments" :key="item.value"><input type="checkbox" :checked="aiSelectedDepartments.includes(item.value)" @change="toggleAiDepartment(item.value)" /><span>{{ item.label }}</span><small>分母 {{ item.denominatorCount }} · 分子 {{ item.numeratorCount }}</small></label></div></section>
              </details>
              <p class="candidate-helper">带入条件后不会自动执行。请先点击“AI 生成对应 SQL”完成生成和校验，再单独确认执行候选 SQL。</p>
            </template>
            <p v-if="!modificationAllowed" class="workspace-warning">后台数据准备通过后，即可生成候选 SQL 并执行影子试跑。</p>
            <article v-else-if="latestRequirementAnalysis.failureReason && !candidateOwnedBySelectedNode" class="workspace-stop"><strong>本轮未生成候选 SQL</strong><p>{{ latestRequirementAnalysis.failureReason }}</p><p>{{ latestRequirementAnalysis.nextAction }}</p></article>
            <p v-if="candidatePendingRun && !candidateMatchesInput" class="candidate-stale-warning">修改条件或编辑内容已经变化，请重新生成候选 SQL；旧候选不会执行。</p>
            <div class="candidate-action-row"><button type="button" class="workspace-secondary candidate-generate-action" :disabled="Boolean(busy) || !modificationAllowed || (editMode === 'ai' ? !aiRequirementText() : !directSqlChanged)" aria-live="polite" @click="generateCandidate"><span v-if="activeExecution === 'candidate-generate'" class="trial-spinner" aria-hidden="true"></span>{{ candidateGenerateLabel }}</button><button type="button" class="workspace-primary trial-action" :disabled="!candidateCanExecute" aria-live="polite" @click="runCandidate"><span v-if="activeExecution === 'candidate-run'" class="trial-spinner" aria-hidden="true"></span>{{ candidateRunLabel }}</button></div>
          </section>
          <article v-if="trialError && selectedNodeEditable && trialNodeId === selectedNodeId" class="trial-inline-error"><strong>{{ trialProgress || '执行失败' }}</strong><p>{{ trialError }}</p><p v-if="trialError.includes('模型')">本次失败发生在模型整理修改要求阶段，尚未修改正式 SQL，也没有执行影子写入。</p><div class="trial-error-actions"><button type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="generateCandidate">重新生成</button><button type="button" class="workspace-secondary" @click="editMode = 'direct'; trialError = ''; trialProgress = ''">切换为直接编辑 SQL</button></div></article>
          <details v-if="candidateOwnedBySelectedNode && !Boolean(candidate.baselineOnly)" class="candidate-result collapsible-result"><summary><span><strong>候选 SQL</strong><small>{{ String(candidate.generationMethod || '') }} · {{ String(candidate.databaseDialect || '当前数据库方言') }}</small></span><em>展开查看完整 SQL</em></summary><ul class="candidate-validation"><li v-for="stage in strings(candidate.validationStages)" :key="stage">✓ {{ stage }}</li></ul><div class="sql-diff-legend"><span class="added">绿色：新增或修改后的内容</span><span class="removed">红色：被删除或替换的原内容</span></div><div class="sql-panel"><header><small>{{ String(record(candidate.validation).message || '安全校验已通过') }}</small><button type="button" @click="copyText('candidate', candidateExecutable)">{{ copiedKey === 'candidate' ? '已复制' : '复制 SQL' }}</button></header><pre class="sql-diff"><code v-for="(line, index) in candidateDiffLines" :key="`${index}-${line.kind}`" :class="`is-${line.kind}`"><i>{{ line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' ' }}</i><b>{{ line.newLine || line.oldLine || '' }}</b><span>{{ line.text || ' ' }}</span></code></pre></div></details>
          <section v-if="shadowOwnedBySelectedNode && (!Boolean(shadow.baselineOnly) || shadowResultChanged)" class="shadow-result shadow-result-showcase" :data-state="trialPassed ? 'PASSED' : 'FAILED'">
            <header><div><span>{{ trialPassed ? '✓ 候选 SQL 隔离验证' : '候选 SQL 验证失败' }}</span><strong>{{ trialPassed ? '候选结果已生成' : '未通过验收' }}</strong></div><em>{{ String(shadow.trialId || '') }}</em></header>
            <p class="shadow-result-note">{{ String(shadow.message || (trialPassed ? '候选数据已完成隔离试跑，正式 SQL 和正式数据保持不变。' : '请根据执行错误或记录差异调整候选条件。')) }}</p>
            <button v-if="!trialPassed && snapshot?.currentStep === 'SHADOW_TRIAL'" type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="reviseCandidate">调整候选并重新试跑</button>
            <div v-if="trialPassed" class="repair-metrics lineage-result-metrics"><article class="primary-result"><span><i aria-hidden="true">↗</i>候选指标结果（当前值）</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'result')) }}</strong><div><small>正式值 <b>{{ metricText(aggregateValue(shadowOriginalRow, 'result')) }}</b></small><small :class="repairResultDelta >= 0 ? 'is-up' : 'is-down'">差异值 {{ repairResultDelta >= 0 ? '+' : '' }}{{ metricText(repairResultDelta) }} {{ repairResultDelta >= 0 ? '↑' : '↓' }}<b v-if="repairResultDeltaPercent !== null">{{ repairResultDeltaPercent >= 0 ? '+' : '' }}{{ repairResultDeltaPercent.toFixed(2) }}%</b></small></div></article><article><span><i aria-hidden="true">⌘</i>候选分子</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'numerator')) }}</strong><small>正式分子 {{ metricText(aggregateValue(shadowOriginalRow, 'numerator')) }}</small></article><article><span><i aria-hidden="true">▦</i>候选分母</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'denominator')) }}</strong><small>正式分母 {{ metricText(aggregateValue(shadowOriginalRow, 'denominator')) }}</small></article></div>
            <div v-if="trialPassed" class="repair-detail-entry"><strong>候选结果差异明细</strong><span>展开查看指标值或记录集合的具体差异</span></div>
            <details v-if="trialPassed" class="repair-detail-samples lineage-diff-samples"><summary><i aria-hidden="true">≠</i><span>指标结果差异明细</span><small>分子、分母、结果值共 3 项</small><em>展开查看⌄</em></summary><table class="shadow-result-table"><thead><tr><th>对比项</th><th>当前正式结果</th><th>候选试跑结果</th><th>变化</th></tr></thead><tbody><tr><th>分子</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'numerator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'numerator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'numerator')) - number(aggregateValue(shadowOriginalRow, 'numerator')) }}</td></tr><tr><th>分母</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'denominator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'denominator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'denominator')) - number(aggregateValue(shadowOriginalRow, 'denominator')) }}</td></tr><tr><th>结果值</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'result')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'result')) }}</td><td>{{ metricText(repairResultDelta) }}</td></tr></tbody></table></details>
            <details v-for="item in diffCategories" :key="item.type" class="repair-detail-samples lineage-diff-samples" @toggle="toggleDiffCategory($event, item.type)"><summary><i aria-hidden="true">{{ item.type === 'REMOVED' ? '−' : item.type === 'ADDED' ? '+' : '≠' }}</i><span>{{ item.label }}差异明细</span><small>共 {{ item.count }} 条</small><em>{{ diffLoading && diffType === item.type ? '加载中…' : '展开查看⌄' }}</em></summary><div v-if="diffPage && diffType === item.type" class="diff-list"><p>共 {{ diffPage.total }} 个业务编号</p><details v-for="row in diffPage.items" :key="row.businessKey"><summary>{{ row.businessKey }}<span v-if="row.changedFields.length"> · {{ row.changedFields.join('、') }}</span></summary><div><section><strong>修改前</strong><pre>{{ JSON.stringify(row.beforeRows, null, 2) }}</pre></section><section><strong>修改后</strong><pre>{{ JSON.stringify(row.afterRows, null, 2) }}</pre></section></div></details><nav v-if="diffPage.total > diffPage.pageSize" class="pager"><button type="button" :disabled="diffPage.page <= 1" @click="loadDiff(diffPage.page - 1)">上一页</button><span>第 {{ diffPage.page }} / {{ Math.ceil(diffPage.total / diffPage.pageSize) }} 页</span><button type="button" :disabled="diffPage.page * diffPage.pageSize >= diffPage.total" @click="loadDiff(diffPage.page + 1)">下一页</button></nav></div></details>
            <p v-if="trialPassed && !hasRecordDiff" class="no-record-diff">本次试跑未发现记录集合差异，指标结果差异仍可在上方展开查看。</p>
          </section>
          <section v-if="snapshot?.currentStep === 'DRAFT_SAVE' && shadowOwnedBySelectedNode && trialPassed && !Boolean(candidate.baselineOnly) && !Boolean(shadow.baselineOnly)" class="draft-save-entry"><button v-if="!draftFormOpen" type="button" class="workspace-primary draft-open-button" @click="draftFormOpen = true">保存为医院草稿版本</button><section v-else class="draft-save"><header><strong>填写医院草稿说明</strong><button type="button" class="draft-form-close" @click="draftFormOpen = false">暂不保存 ×</button></header><p>说明将随候选 SQL 一起进入“知识库回收与审批”，不会影响当前正式计算。</p><label><span>问题说明</span><textarea v-model="draftDescription.issueSummary" rows="2"></textarea></label><label><span>本次修改</span><textarea v-model="draftDescription.changeSummary" rows="2"></textarea></label><label><span>预期影响</span><textarea v-model="draftDescription.expectedImpact" rows="2"></textarea></label><label><span>影子验证结论</span><textarea v-model="draftDescription.verificationSummary" rows="2"></textarea></label><button type="button" class="workspace-primary" :disabled="Boolean(busy) || Object.values(draftDescription).some((value) => !value.trim())" @click="saveDraft">确认保存草稿</button></section></section>
          <section v-if="snapshot?.draftResult && Object.keys(snapshot.draftResult).length" class="draft-complete"><strong>医院草稿已保存</strong><p>草稿编号：{{ String(snapshot.draftResult.draftId || '') }}</p><p>未发布，不影响当前正式计算。</p></section>
        </section>
      </div>
    </section>
    <div v-if="repairDialogOpen" class="rule-repair-overlay" role="dialog" aria-modal="true" aria-labelledby="rule-repair-title" @click.self="closeRuleRepair">
      <section class="rule-repair-dialog" :class="{ 'has-result': Boolean(shadow.publicRuleFix) }">
        <header class="rule-repair-heading"><div><small><i aria-hidden="true">{{ shadow.publicRuleFix ? '✓' : '✦' }}</i>{{ shadow.publicRuleFix ? '公共规则候选验证' : '公共规则修复方案' }}</small><strong id="rule-repair-title">{{ shadow.publicRuleFix ? (trialPassed ? '候选结果已生成' : '候选执行未通过') : publicRuleLabel(repairRuleId) }}</strong><p v-if="!shadow.publicRuleFix">{{ publicRuleRepairDescription(repairRuleId) }}</p></div><button type="button" aria-label="关闭" @click="closeRuleRepair">×</button></header>
        <article v-if="!publicRuleRepairable(repairRuleId)" class="manual-rule-guidance"><strong>需要人工检查事件启用情况</strong><p>请核对当前指标相关事件是否重复启用，再根据确认的业务编号、去重字段和保留顺序修改源表抽取 SQL。程序不会猜测去重规则。</p></article>
        <p v-else-if="repairPreviewLoading" class="rule-repair-loading" aria-live="polite"><span class="trial-spinner" aria-hidden="true"></span>{{ executionStage || '正在解析当前指标抽取 SQL…' }}</p>
        <template v-else-if="publicRuleRepairable(repairRuleId) && Object.keys(candidate).length && !shadow.publicRuleFix">
          <section class="repair-candidate-card">
            <header><div><i aria-hidden="true">&lt;/&gt;</i><span><strong>修改后的候选 SQL</strong><small>已根据当前指标抽取 SQL 生成，仅预览，尚未执行</small></span></div><button type="button" @click="copyText('rule-repair', candidateExecutable)">{{ copiedKey === 'rule-repair' ? '✓ 已复制' : '▣ 复制 SQL' }}</button></header>
            <div class="repair-change-preview"><strong>本次修改 {{ candidateChangedLines.length }} 行</strong><code v-for="(line, index) in candidateChangedLines.slice(0, 12)" :key="`changed-${index}-${line.kind}`" :class="`is-${line.kind}`"><i>{{ line.kind === 'added' ? '+' : '−' }}</i><span>{{ line.text }}</span></code></div>
            <button type="button" class="repair-sql-toggle" :aria-expanded="repairSqlExpanded" @click="toggleRepairSql"><span><i aria-hidden="true">◉</i>查看完整候选 SQL</span><em>{{ repairSqlExpanded ? '点击收起' : '点击展开' }} <b aria-hidden="true">⌄</b></em></button>
            <section v-show="repairSqlExpanded" class="repair-sql-body"><div class="sql-diff-legend"><span class="added">绿色：新增或修改后的内容</span><span class="removed">红色：被删除或替换的原内容</span></div><pre class="sql-diff"><code v-for="(line, index) in candidateDiffLines" :key="`repair-${index}-${line.kind}`" :class="`is-${line.kind}`"><i>{{ line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' ' }}</i><b>{{ line.newLine || line.oldLine || '' }}</b><span>{{ line.text || ' ' }}</span></code></pre></section>
          </section>
          <button type="button" class="workspace-primary rule-repair-run" :disabled="repairRunLoading" aria-live="polite" @click="runRuleRepair"><span v-if="repairRunLoading" class="trial-spinner" aria-hidden="true"></span><i v-else aria-hidden="true">▶</i>{{ repairRunButtonLabel }}</button>
        </template>
        <section v-else-if="shadow.publicRuleFix" class="repair-trial-result" :data-state="trialPassed ? 'PASSED' : 'FAILED'">
          <p class="repair-result-note">{{ String(shadow.message || '公共规则修复 SQL 已完成隔离试跑，正式数据保持不变。') }}</p>
          <div class="repair-metrics"><article class="primary-result"><span><i aria-hidden="true">↗</i>候选指标结果（当前值）</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'result')) }}</strong><div><small>正式值 <b>{{ metricText(aggregateValue(shadowOriginalRow, 'result')) }}</b></small><small :class="repairResultDelta >= 0 ? 'is-up' : 'is-down'">差异值 {{ repairResultDelta >= 0 ? '+' : '' }}{{ metricText(repairResultDelta) }} {{ repairResultDelta >= 0 ? '↑' : '↓' }}<b v-if="repairResultDeltaPercent !== null">{{ repairResultDeltaPercent >= 0 ? '+' : '' }}{{ repairResultDeltaPercent.toFixed(2) }}%</b></small></div></article><article><span><i aria-hidden="true">⌘</i>候选分子</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'numerator')) }}</strong><small>正式分子 {{ metricText(aggregateValue(shadowOriginalRow, 'numerator')) }} · 下方仅展示差异</small></article><article><span><i aria-hidden="true">▦</i>候选分母</span><strong>{{ metricText(aggregateValue(shadowCandidateRow, 'denominator')) }}</strong><small>正式分母 {{ metricText(aggregateValue(shadowOriginalRow, 'denominator')) }} · 下方仅展示差异</small></article></div>
          <div class="repair-detail-entry"><strong>候选结果差异明细</strong><span>展开查看指标值或记录集合的具体差异</span></div>
          <details class="repair-detail-samples lineage-diff-samples"><summary><i aria-hidden="true">≠</i><span>指标结果差异明细</span><small>分子、分母、结果值共 3 项</small><em>展开查看⌄</em></summary><table class="shadow-result-table"><thead><tr><th>对比项</th><th>当前正式结果</th><th>候选试跑结果</th><th>变化</th></tr></thead><tbody><tr><th>分子</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'numerator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'numerator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'numerator')) - number(aggregateValue(shadowOriginalRow, 'numerator')) }}</td></tr><tr><th>分母</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'denominator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'denominator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'denominator')) - number(aggregateValue(shadowOriginalRow, 'denominator')) }}</td></tr><tr><th>结果值</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'result')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'result')) }}</td><td>{{ metricText(repairResultDelta) }}</td></tr></tbody></table></details>
          <details v-for="item in diffCategories" :key="item.type" class="repair-detail-samples lineage-diff-samples" @toggle="toggleDiffCategory($event, item.type)"><summary><i aria-hidden="true">{{ item.type === 'REMOVED' ? '−' : item.type === 'ADDED' ? '+' : '≠' }}</i><span>{{ item.label }}差异明细</span><small>共 {{ item.count }} 条</small><em>{{ diffLoading && diffType === item.type ? '加载中…' : '展开查看⌄' }}</em></summary><div v-if="diffPage && diffType === item.type" class="diff-list"><p>共 {{ diffPage.total }} 个业务编号</p><details v-for="row in diffPage.items" :key="row.businessKey"><summary>{{ row.businessKey }}<span v-if="row.changedFields.length"> · {{ row.changedFields.join('、') }}</span></summary><div><section><strong>修改前</strong><pre>{{ JSON.stringify(row.beforeRows, null, 2) }}</pre></section><section><strong>修改后</strong><pre>{{ JSON.stringify(row.afterRows, null, 2) }}</pre></section></div></details><nav v-if="diffPage.total > diffPage.pageSize" class="pager"><button type="button" :disabled="diffPage.page <= 1" @click="loadDiff(diffPage.page - 1)">上一页</button><span>第 {{ diffPage.page }} / {{ Math.ceil(diffPage.total / diffPage.pageSize) }} 页</span><button type="button" :disabled="diffPage.page * diffPage.pageSize >= diffPage.total" @click="loadDiff(diffPage.page + 1)">下一页</button></nav></div></details>
          <p v-if="!hasRecordDiff" class="no-record-diff">本次试跑未发现记录集合差异，指标结果差异仍可在上方展开查看。</p>
        </section>
        <p v-if="repairError" class="trial-inline-error">{{ repairError }}</p>
      </section>
    </div>
  </main>
</template>

<style>
@import '../styles/standard-diagnosis.css';
</style>
