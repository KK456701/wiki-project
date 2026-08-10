<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
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
import { buildDiagnosisSqlExport } from '../utils/standardDiagnosisExport'

type WorkspaceStep = StandardWorkspaceStep
type FlowNode = Record<string, unknown>

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

const detailGroup = ref<'numerator' | 'denominator'>('denominator')
const detailPage = ref<IndicatorDetailResult | null>(null)
const detailLoading = ref(false)
const detailSearch = ref('')
const detailDepartment = ref('')
const selectedRows = ref(new Map<string, Record<string, unknown>>())
const screening = ref<DiagnosisDataScreening | null>(null)
const screeningLoading = ref(false)
const overIncludedNote = ref('')
const underIncludedNote = ref('')

const selectedNodeId = ref('')
const editMode = ref<'requirement' | 'sql'>('requirement')
const requirement = ref('')
const directSql = ref('')
const copiedKey = ref('')
const diffType = ref<DiagnosisShadowDiffPage['type']>('REMOVED')
const diffSearch = ref('')
const diffPage = ref<DiagnosisShadowDiffPage | null>(null)
const diffLoading = ref(false)
const draftDescription = ref({ issueSummary: '', changeSummary: '', expectedImpact: '', verificationSummary: '' })

const filteredIndicators = computed(() => {
  const keyword = indicatorSearch.value.trim().toLowerCase()
  return keyword ? indicators.value.filter((item) =>
    `${item.ruleId} ${item.ruleName}`.toLowerCase().includes(keyword)) : indicators.value
})
const selectedIndicator = computed(() => indicators.value.find((item) => item.ruleId === selectedRuleId.value))
const selectedProfile = computed(() => profiles.value.find((item) => item.profileId === selectedProfileId.value))
const allGatesPassed = computed(() => [1, 2, 3].every((number) => gateStatus(number) === 'PASSED'))
const gateBlocked = computed(() => [1, 2, 3].some((number) => gateStatus(number) === 'BLOCKED'))
const calculation = computed(() => {
  const gate = snapshot.value?.gateResults.find((item) => Number(item.gate) === 2)
  return record(record(gate?.facts).executionEvidence)
})
const numeratorCount = computed(() => number(calculation.value.numeratorCount))
const denominatorCount = computed(() => number(calculation.value.denominatorCount))
const resultValue = computed(() => calculation.value.resultValue ?? '—')
const flow = computed(() => record(snapshot.value?.caliberSnapshot.dataFlow || effectiveRule.value.dataFlow))
const flowNodes = computed<FlowNode[]>(() => Array.isArray(flow.value.nodes) ? flow.value.nodes as FlowNode[] : [])
const selectedNode = computed(() => flowNodes.value.find((node) => String(node.id) === selectedNodeId.value) || flowNodes.value[0] || null)
const selectedNodeLayer = computed<'SOURCE_EXTRACT' | 'OVERVIEW' | ''>(() => {
  const type = String(selectedNode.value?.nodeType || '')
  return type === 'SOURCE_EXTRACT_SQL' ? 'SOURCE_EXTRACT' : type === 'OVERVIEW_SQL' ? 'OVERVIEW' : ''
})
const selectedNodeEditable = computed(() => Boolean(selectedNodeLayer.value))
const modificationAllowed = computed(() => allGatesPassed.value
  && Boolean(snapshot.value?.dataConfirmation && Object.keys(snapshot.value.dataConfirmation).length)
  && ['CASE_INVESTIGATION', 'SHADOW_TRIAL', 'DRAFT_SAVE'].includes(String(snapshot.value?.currentStep || '')))
const detailColumns = computed(() => {
  const rows = detailPage.value?.rows || []
  const priority = ['ENCOUNTER_ID', 'encounterId', 'IMRN', 'imrn', 'PERSON_NAME', 'FULL_NAME', 'personName', 'CURRENT_DEPT_NAME', 'currentDeptName', 'CURRENT_WARD_NAME', 'currentWardName']
  const keys = [...new Set(rows.flatMap((row) => Object.keys(row)))]
  return [...priority.filter((key) => keys.includes(key)), ...keys.filter((key) => !priority.includes(key))].slice(0, 10)
})
const detailsReconciled = computed(() => Boolean(detailPage.value)
  && number(detailPage.value?.cardNumerator) === number(detailPage.value?.detailNumerator)
  && number(detailPage.value?.cardDenominator) === number(detailPage.value?.detailDenominator))
const currentClarification = computed(() => record(snapshot.value?.dataConfirmation.clarification))
const shadow = computed(() => snapshot.value?.shadowTrial || {})
const candidate = computed(() => snapshot.value?.candidateSql || {})
const shadowRecordDiff = computed(() => record(shadow.value.recordSetDiff))
const shadowOriginalRow = computed(() => firstResultRow(shadow.value.originalResult))
const shadowCandidateRow = computed(() => firstResultRow(shadow.value.candidateResult))
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
const trialPassed = computed(() => Boolean(shadow.value.passed))

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
      hydrateDataConfirmation()
      initializeNode()
      if (snapshot.value.currentStep === 'CALIBER_CONFIRMATION' || currentGateNumber(snapshot.value.currentStep)) {
        void startOrResumeChecks()
      } else if (currentStep.value === 'data' && allGatesPassed.value) {
        await Promise.all([loadDetails(detailGroup.value, 1), loadScreening()])
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
}

function normalizeStep(value: string): WorkspaceStep {
  return ['selection', 'checks', 'data', 'lineage'].includes(value) ? value as WorkspaceStep : 'selection'
}

function stepFromSnapshot(value: DiagnosisCaseSnapshot): WorkspaceStep {
  if (value.currentStep.startsWith('GATE_') || value.currentStep === 'CALIBER_CONFIRMATION') return 'checks'
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
    rememberCase(created.caseId)
    currentStep.value = 'checks'
    await router.replace({ name: 'standard-diagnosis', params: { caseId: created.caseId }, query: { step: 'checks' } })
    await startOrResumeChecks()
  } catch (cause) {
    error.value = message(cause, '异常排查任务创建失败。')
  } finally {
    busy.value = ''
  }
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
  currentStep.value = 'checks'
  if (snapshot.value.currentStep === 'CALIBER_CONFIRMATION') {
    const confirmed = await act('CONFIRM_CALIBER', { confirmed: true })
    if (!confirmed) return
  }
  await advanceGates()
}

async function advanceGates() {
  while (snapshot.value) {
    const gate = currentGateNumber(snapshot.value.currentStep)
    if (!gate) return
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

function gateLabel(status: string): string {
  return ({ PASSED: '已通过', BLOCKED: '需处理', RUNNING: '检查中', WAITING: '等待前置' } as Record<string, string>)[status] || status
}

async function goStep(step: WorkspaceStep) {
  if (step !== 'selection' && !snapshot.value) return
  currentStep.value = step
  if (snapshot.value) await router.replace({ name: 'standard-diagnosis', params: { caseId: snapshot.value.caseId }, query: { step } })
  if (step === 'data' && allGatesPassed.value) {
    await Promise.all([loadDetails(detailGroup.value, 1), loadScreening()])
  }
  if (step === 'lineage') initializeNode()
}

async function loadDetails(group = detailGroup.value, page = 1) {
  if (!snapshot.value || !allGatesPassed.value) return
  detailGroup.value = group
  detailLoading.value = true
  try {
    detailPage.value = await fetchDiagnosisCaseDetails(store.token, snapshot.value.caseId,
      group, page, 50, detailSearch.value, detailDepartment.value)
  } catch (cause) {
    detailPage.value = null
    error.value = message(cause, '分子分母明细加载失败。')
  } finally {
    detailLoading.value = false
  }
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
  else copy.set(key, row)
  selectedRows.value = copy
}

function clearSelectedRows() {
  selectedRows.value = new Map()
}

function findingSelect(finding: DiagnosisDataScreening['findings'][number]) {
  if (finding.row && Object.keys(finding.row).length) {
    const copy = new Map(selectedRows.value)
    copy.set(finding.rowKey, finding.row)
    selectedRows.value = copy
  }
}

function confirmationRows() {
  return [...selectedRows.value.entries()].map(([key, row]) => ({
    rowKey: key,
    label: displayRowLabel(row),
    recordId: key.includes(':') ? key.slice(key.indexOf(':') + 1) : key,
  }))
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

async function submitDataConfirmation(noIssue = false, openLineage = false) {
  const updated = await act('SUBMIT_DATA_CONFIRMATION', {
    overIncludedRows: confirmationRows(),
    overIncludedNote: overIncludedNote.value.trim(),
    underIncludedNote: underIncludedNote.value.trim(),
    confirmedNoIssue: noIssue,
  })
  if (!updated) return
  if (noIssue) {
    const closed = await act('CLOSE_AS_CORRECT', { conclusion: '实施人员已核对本次分子、分母明细，确认当前结果无异议。' })
    if (closed) await goStep('data')
  } else if (openLineage) {
    await goStep('lineage')
  }
}

function initializeNode() {
  if (!selectedNodeId.value || !flowNodes.value.some((node) => String(node.id) === selectedNodeId.value)) {
    selectedNodeId.value = String(flowNodes.value.find((node) => ['SOURCE_EXTRACT_SQL', 'OVERVIEW_SQL'].includes(String(node.nodeType)))?.id || flowNodes.value[0]?.id || '')
  }
}

function databaseLabel(value: unknown): string {
  return ({ BUSINESS: '业务库', REAL: '真实库', SYNC: '业务库 · 同步任务', KNOWLEDGE: '知识库' } as Record<string, string>)[String(value || '')] || String(value || '未登记')
}

function strings(value: unknown): string[] { return Array.isArray(value) ? value.map(String).filter(Boolean) : [] }

async function submitCandidate() {
  if (!snapshot.value || !selectedNodeLayer.value) {
    error.value = '请先选择源表抽取 SQL 或概览统计 SQL 节点。'
    return
  }
  if (!modificationAllowed.value) {
    error.value = '基础检查和数据确认完成后，才能生成候选 SQL 并执行影子试跑。'
    return
  }
  const candidateSql = editMode.value === 'sql' ? directSql.value.trim() : ''
  const requirementText = requirement.value.trim() || (candidateSql ? '实施人员提供完整候选 SQL' : '')
  if (!requirementText) return
  await act('SUBMIT_EVIDENCE', {
    type: 'IMPLEMENTER_SQL_REQUIREMENT',
    suspectedLayer: selectedNodeLayer.value,
    summary: `${selectedNodeLayer.value === 'SOURCE_EXTRACT' ? '抽取 SQL' : '概览 SQL'}修改要求：${requirementText}`,
    requirement: requirementText,
    candidateSql,
    patchConditions: [],
    requestAiAnalysis: false,
  })
}

async function reviseCandidate() {
  const updated = await act('REVISE_CANDIDATE', {})
  if (!updated) return
  requirement.value = ''
  directSql.value = ''
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

async function saveDraft() {
  await act('SAVE_HOSPITAL_DRAFT', { confirmed: true, ...draftDescription.value })
}

function exportSql() {
  const blob = new Blob([buildDiagnosisSqlExport(flowNodes.value, candidate.value, databaseLabel)],
    { type: 'text/sql;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${snapshot.value?.profileId || 'indicator'}-diagnosis.sql`
  anchor.click()
  URL.revokeObjectURL(url)
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
        <span>指标异常排查 · 标准模式</span>
        <strong>{{ snapshot?.caliberSnapshot.ruleName || selectedIndicator?.ruleName || '建立一个可追溯的排查任务' }}</strong>
        <small v-if="snapshot">{{ snapshot.profileId }} · {{ snapshot.caseId }}</small>
      </div>
      <button v-if="snapshot" type="button" class="workspace-export" @click="exportSql">导出本次 SQL</button>
    </header>

    <StandardDiagnosisStepper :current-step="currentStep" :has-case="Boolean(snapshot)" @navigate="goStep" />

    <p v-if="error" class="workspace-error">{{ error }}</p>
    <section v-if="loading" class="workspace-loading">正在加载真实指标口径与排查证据…</section>

    <section v-else-if="currentStep === 'selection'" class="workspace-page selection-page">
      <header class="page-heading"><span>01 · 冻结排查范围</span><h1>选择指标、时间与本次口径</h1><p>这里的选择会写入排查快照；后续证据和 SQL 都绑定同一版本。</p></header>
      <div class="selection-layout">
        <section class="selection-indicators">
          <label class="workspace-field"><span>查找指标</span><input v-model="indicatorSearch" type="search" placeholder="输入指标名称或编码" /></label>
          <div class="indicator-picker">
            <button v-for="item in filteredIndicators" :key="item.ruleId" type="button"
              :class="{ selected: selectedRuleId === item.ruleId }" @click="chooseIndicator(item.ruleId)">
              <strong>{{ item.ruleName }}</strong><small>{{ item.ruleId }}</small>
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
              <span><strong>{{ profile.profileName }}</strong><small>{{ profile.profileId }}</small></span>
              <em>{{ profile.overviewRuntimeEligible ? '可执行' : '仅文档' }}</em>
              <p><b>分子</b>{{ profile.numeratorRule || '知识库未单独登记' }}</p>
              <p><b>分母</b>{{ profile.denominatorRule || '知识库未单独登记' }}</p>
            </button>
          </div>
          <article v-if="selectedProfile" class="profile-evidence">
            <span>当前选择</span><strong>{{ selectedIndicator?.ruleName }} · {{ selectedProfile.profileName }}</strong>
            <p>{{ String(effectiveRule.definition || '知识库未单独登记指标定义') }}</p>
          </article>
          <button type="button" class="workspace-primary" :disabled="busy !== '' || !selectedProfileId || !selectedProfile?.overviewRuntimeEligible || !statStart || !statEnd" @click="createCase">
            {{ busy === 'create' ? '正在创建排查任务…' : '开始排查' }}
          </button>
        </section>
      </div>
    </section>

    <section v-else-if="currentStep === 'checks'" class="workspace-page checks-page">
      <header class="page-heading"><span>02 · 三项真实检查</span><h1>基础检查</h1><p>系统自动顺序检查；发现阻断问题时停在当前项，修复后只重跑这一项。</p></header>
      <div class="gate-list">
        <article v-for="item in [{ n: 1, title: '数据结构校验', text: '核对双库连接、表、字段和真实库计算结构' }, { n: 2, title: '事件配置校验', text: '核对候选事件、现场启用状态和本次抽取执行' }, { n: 3, title: '现场数据校验', text: '确认当前统计窗口是否存在可计算数据' }]" :key="item.n" :data-state="gateStatus(item.n)">
          <b>{{ item.n }}</b><div><header><strong>{{ item.title }}</strong><em>{{ gateLabel(gateStatus(item.n)) }}</em></header><p>{{ item.text }}</p>
            <div class="gate-result">{{ String(gate(item.n)?.message || (gateStatus(item.n) === 'RUNNING' ? '正在检查真实数据库…' : '等待前一项完成')) }}</div>
            <code v-if="gate(item.n)?.errorCode">{{ String(gate(item.n)?.errorCode) }}</code>
            <aside v-if="gate(item.n)?.repairSuggestion"><strong>怎么处理</strong><p>{{ String(gate(item.n)?.repairSuggestion) }}</p></aside>
            <button v-if="gateStatus(item.n) === 'BLOCKED'" type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="retryGate(item.n)">修复后重新检查</button>
          </div>
        </article>
      </div>
      <footer class="gate-conclusion" :data-state="gateBlocked ? 'BLOCKED' : allGatesPassed ? 'PASSED' : 'RUNNING'">
        <strong>{{ gateBlocked ? '基础检查发现需要处理的问题' : allGatesPassed ? '三项基础检查均已通过' : '基础检查正在进行' }}</strong>
        <span>{{ allGatesPassed ? '可以继续确认本次分子和分母明细。' : gateBlocked ? '修复当前问题后再继续修改 SQL。' : '你可以先查看后续页面，但修改操作仍被锁定。' }}</span>
        <button v-if="allGatesPassed" type="button" class="workspace-primary" @click="goStep('data')">进入数据确认</button>
      </footer>
    </section>

    <section v-else-if="currentStep === 'data'" class="workspace-page data-page">
      <header class="page-heading"><span>03 · 核对真实记录集合</span><h1>指标统计数据 · 确认是否缺失或增多</h1><p>分子、分母来自同一统计 SQL 版本，并在返回前重新聚合对账。</p></header>
      <div v-if="!allGatesPassed" class="locked-page"><strong>真实数据证据尚未准备完成</strong><p>可以查看本页结构；基础检查通过前不会加载或修改患者级明细。</p><button type="button" class="workspace-secondary" @click="goStep('checks')">返回基础检查</button></div>
      <template v-else>
        <div class="metric-strip"><article><span>指标结果</span><strong>{{ resultValue }}</strong></article><button type="button" :class="{ active: detailGroup === 'numerator' }" @click="loadDetails('numerator', 1)"><span>统计 SQL 分子</span><strong>{{ numeratorCount }}</strong></button><button type="button" :class="{ active: detailGroup === 'denominator' }" @click="loadDetails('denominator', 1)"><span>统计 SQL 分母</span><strong>{{ denominatorCount }}</strong></button><article><span>执行状态</span><strong>{{ String(calculation.status || 'SUCCESS') }}</strong></article></div>
        <div class="data-run-context">
          <div><span>诊断计算编号</span><strong>{{ detailPage?.batchRunId || '正在读取执行证据…' }}</strong></div>
          <div><span>统计窗口</span><strong>{{ String(snapshot?.caseInput.statStart || '—') }} 至 {{ String(snapshot?.caseInput.statEnd || '—') }}</strong></div>
          <div><span>明细证据</span><strong>{{ detailPage ? (detailsReconciled ? '分子分母重新聚合一致' : '对账不一致，已停止展示') : '加载中' }}</strong></div>
          <div><span>证据复用</span><strong>{{ detailPage?.snapshotReused ? '本任务内复用已对账快照' : detailPage ? '本次重新生成' : '—' }}</strong></div>
        </div>
        <div class="data-confirm-layout">
          <section class="detail-browser">
            <header><div><span>{{ detailGroup === 'numerator' ? '分子明细' : '分母明细' }}</span><strong>{{ detailPage?.rowCount ?? 0 }} 条</strong></div><small v-if="detailPage">汇总 {{ detailPage.cardNumerator }}/{{ detailPage.cardDenominator }} · 明细 {{ detailPage.detailNumerator }}/{{ detailPage.detailDenominator }} · 对账通过</small></header>
            <div class="detail-toolbar"><input v-model="detailSearch" type="search" placeholder="搜索姓名、就诊号或任意字段" @keyup.enter="loadDetails(detailGroup, 1)" /><select v-model="detailDepartment" @change="loadDetails(detailGroup, 1)"><option value="">全部科室/病区</option><option v-for="dept in detailPage?.departments || []" :key="dept" :value="dept">{{ dept }}</option></select><button type="button" @click="loadDetails(detailGroup, 1)">查询</button></div>
            <p v-if="detailLoading" class="workspace-loading">正在读取并重新对账明细…</p>
            <p v-else-if="detailPage && !detailsReconciled" class="workspace-error">卡片分子分母与明细重新聚合不一致，本页拒绝把这些记录展示为可信明细。</p>
            <div v-else class="detail-table-wrap"><table><thead><tr><th>标记多算</th><th v-for="column in detailColumns" :key="column"><span>{{ detailFieldLabel(column) }}</span><small v-if="detailFieldLabel(column) !== column">{{ column }}</small></th></tr></thead><tbody><tr v-for="row in detailPage?.rows || []" :key="rowKey(row)" :class="{ selected: selectedRows.has(rowKey(row)) }"><td><input type="checkbox" :checked="selectedRows.has(rowKey(row))" aria-label="标记为疑似多算" @change="toggleRow(row)" /></td><td v-for="column in detailColumns" :key="column">{{ formatDetailValue(column, row[column]) }}</td></tr></tbody></table><p v-if="!detailPage?.rows?.length" class="workspace-empty">当前筛选条件下没有明细。</p></div>
            <nav v-if="detailPage" class="pager"><button type="button" :disabled="(detailPage.page || 1) <= 1" @click="loadDetails(detailGroup, (detailPage.page || 1) - 1)">上一页</button><span>第 {{ detailPage.page || 1 }} / {{ Math.max(1, Math.ceil((detailPage.rowCount || 0) / (detailPage.pageSize || 50))) }} 页</span><button type="button" :disabled="(detailPage.page || 1) * (detailPage.pageSize || 50) >= (detailPage.rowCount || 0)" @click="loadDetails(detailGroup, (detailPage.page || 1) + 1)">下一页</button></nav>
          </section>
          <aside class="confirmation-panel">
            <section class="screening-panel"><header><strong>系统初筛</strong><span>不调用模型</span></header><p v-if="screeningLoading">正在按确定性规则检查…</p><p v-else-if="!screening?.findingCount">未发现明确测试字样或重复业务编号。</p><button v-for="finding in screening?.findings || []" :key="finding.findingId" type="button" @click="findingSelect(finding)"><strong>{{ finding.reason }}</strong><small>{{ finding.rowKey }}</small></button></section>
            <StandardDataConfirmationEditor
              v-model:over-note="overIncludedNote"
              v-model:under-note="underIncludedNote"
              :selected-count="selectedRows.size"
              :busy="Boolean(busy)"
              @clear-selection="clearSelectedRows"
              @submit="options => submitDataConfirmation(options.noIssue, options.openLineage)"
            />
          </aside>
        </div>
        <article v-if="Object.keys(currentClarification).length" class="clarification-result"><span>数据确认结论</span><strong>{{ String(currentClarification.summary || '') }}</strong><p>{{ String(currentClarification.nextAction || '') }}</p><button v-if="!snapshot?.dataConfirmation.confirmedNoIssue" type="button" class="workspace-primary" @click="goStep('lineage')">不满意，进入链路核查</button></article>
      </template>
    </section>

    <section v-else class="workspace-page lineage-page">
      <header class="lineage-toolbar"><div><span>04 · 数据链路核查</span><strong>{{ snapshot?.caliberSnapshot.ruleName }} · {{ snapshot?.caliberSnapshot.profileName }}</strong></div><div><button type="button" class="workspace-secondary" @click="selectedNodeId = String(flowNodes[0]?.id || '')">重置节点</button><button type="button" class="workspace-primary" @click="exportSql">导出 SQL</button></div></header>
      <div class="lineage-layout">
        <aside class="flow-rail"><header><strong>当前生效数据链路</strong><span>{{ flowNodes.length }} 个节点</span></header><button v-for="node in flowNodes" :key="String(node.id)" type="button" :class="{ active: String(node.id) === selectedNodeId }" @click="selectedNodeId = String(node.id)"><b>{{ String(node.sequence || '').padStart(2, '0') }}</b><span><strong>{{ String(node.title || '未命名节点') }}</strong><small>{{ databaseLabel(node.databaseRole) }} · {{ String(node.description || '') }}</small></span><em v-if="['SOURCE_EXTRACT_SQL', 'OVERVIEW_SQL'].includes(String(node.nodeType))">可核查</em></button><p v-if="!flowNodes.length" class="workspace-empty">当前知识库没有可展示的数据链路。</p></aside>
        <section v-if="selectedNode" class="node-inspector">
          <header class="node-heading"><div><span>{{ String(selectedNode.nodeType || 'NODE') }}</span><h1>{{ String(selectedNode.title || '未命名节点') }}</h1><p>{{ String(selectedNode.description || '') }}</p></div><em>{{ databaseLabel(selectedNode.databaseRole) }}</em></header>
          <div class="node-facts"><div><span>输入表</span><strong>{{ strings(selectedNode.tableNames).join('、') || '未登记' }}</strong></div><div><span>模板参数</span><strong>{{ strings(selectedNode.parameters).join('、') || '无' }}</strong></div><div><span>SQL 哈希</span><strong>{{ String(selectedNode.sqlHash || '未生成') }}</strong></div><div><span>知识版本</span><strong>{{ snapshot?.knowledgeReleaseId || '未登记' }}</strong></div><div><span>节点权限</span><strong>{{ selectedNodeEditable ? '可生成候选并影子试跑' : '只读核查' }}</strong></div></div>
          <ul v-if="strings(selectedNode.tableNames).length" class="node-table-list"><li v-for="table in strings(selectedNode.tableNames)" :key="table"><strong>{{ table }}</strong><span>{{ String(record(selectedNode.tableDescriptions)[table] || '知识库未登记中文用途') }}</span></li></ul>
          <details v-if="String(selectedNode.templateSql || '').trim()" class="sql-template-evidence"><summary>查看当前知识库正式模板 SQL</summary><section class="sql-panel"><header><strong>正式模板 SQL · 不绑定统计时间</strong><button type="button" @click="copyText(`template-${String(selectedNode.id)}`, selectedNode.templateSql)">{{ copiedKey === `template-${String(selectedNode.id)}` ? '已复制' : '复制模板 SQL' }}</button></header><pre>{{ String(selectedNode.templateSql) }}</pre></section></details>
          <section v-if="String(selectedNode.sql || '').trim()" class="sql-panel"><header><strong>当前统计窗口可直接执行 SQL</strong><button type="button" @click="copyText(`node-${String(selectedNode.id)}`, selectedNode.sql)">{{ copiedKey === `node-${String(selectedNode.id)}` ? '已复制' : '复制执行 SQL' }}</button></header><pre>{{ String(selectedNode.sql) }}</pre></section>
          <div v-if="!allGatesPassed" class="node-lock"><strong>基础检查尚未通过</strong><p>可以查看当前知识和SQL，但不能生成候选、执行影子试跑或保存医院草稿。</p></div>
          <div v-else-if="!snapshot?.dataConfirmation || !Object.keys(snapshot.dataConfirmation).length" class="node-lock"><strong>请先完成数据确认</strong><p>先说明哪些记录多算或少算，候选SQL才有可验证的预期变化。</p><button type="button" class="workspace-secondary" @click="goStep('data')">去确认数据</button></div>
          <section v-else-if="selectedNodeEditable && snapshot?.currentStep === 'CASE_INVESTIGATION'" class="candidate-editor"><header><strong>生成候选{{ selectedNodeLayer === 'SOURCE_EXTRACT' ? '抽取' : '概览' }} SQL</strong><span>只在影子环境执行</span></header><div class="edit-tabs"><button type="button" :class="{ active: editMode === 'requirement' }" @click="editMode = 'requirement'">描述修改要求</button><button type="button" :class="{ active: editMode === 'sql' }" @click="editMode = 'sql'">提供完整 SELECT</button></div><textarea v-if="editMode === 'requirement'" v-model="requirement" rows="5" placeholder="写清楚需要纳入或排除什么数据，以及使用哪个已有字段判断。"></textarea><textarea v-else v-model="directSql" rows="12" class="sql-editor" placeholder="粘贴一条完整、只读的候选 SELECT"></textarea><p v-if="!modificationAllowed" class="workspace-warning">基础检查和数据确认完成后，才能生成候选 SQL 并执行影子试跑。</p><article v-else-if="latestRequirementAnalysis.failureReason && !Object.keys(candidate).length" class="workspace-stop"><strong>本轮未生成候选 SQL</strong><p>{{ latestRequirementAnalysis.failureReason }}</p><p>{{ latestRequirementAnalysis.nextAction }}</p></article><button type="button" class="workspace-primary" :disabled="Boolean(busy) || !modificationAllowed || (editMode === 'requirement' ? !requirement.trim() : !directSql.trim())" @click="submitCandidate">生成候选并自动影子试跑</button></section>
          <section v-if="Object.keys(candidate).length" class="candidate-result"><header><strong>候选 SQL</strong><span>{{ String(candidate.generationMethod || '') }}</span></header><div class="sql-panel"><header><small>安全校验：{{ String(record(candidate.validation).message || '已通过') }} · 哈希 {{ String(candidate.candidateSqlHash || '未提供') }}</small><button type="button" @click="copyText('candidate', candidateExecutable)">{{ copiedKey === 'candidate' ? '已复制' : '复制 SQL' }}</button></header><pre>{{ candidateExecutable }}</pre></div></section>
          <section v-if="Object.keys(shadow).length" class="shadow-result" :data-state="trialPassed ? 'PASSED' : 'FAILED'">
            <header><div><span>影子试跑</span><strong>{{ trialPassed ? '验收通过' : '未通过验收' }}</strong></div><em>{{ String(shadow.trialId || '') }}</em></header>
            <p>{{ String(shadow.message || (trialPassed ? '候选数据已完成隔离试跑，正式表和当前卡片未被修改。' : '请根据执行错误或记录差异调整候选条件。')) }}</p>
            <button v-if="!trialPassed && snapshot?.currentStep === 'SHADOW_TRIAL'" type="button" class="workspace-secondary" :disabled="Boolean(busy)" @click="reviseCandidate">调整候选并重新试跑</button>
            <div v-if="String(shadow.layer || '') === 'SOURCE_EXTRACT'" class="shadow-record-strip">
              <article><span>正式记录</span><strong>{{ metricText(shadow.formalRows ?? shadowRecordDiff.originalCount) }}</strong></article>
              <article><span>候选记录</span><strong>{{ metricText(shadow.shadowRows ?? shadowRecordDiff.candidateCount) }}</strong></article>
              <article><span>新增</span><strong>{{ metricText(shadowRecordDiff.addedCount) }}</strong></article>
              <article><span>减少</span><strong>{{ metricText(shadowRecordDiff.removedCount) }}</strong></article>
              <article><span>变化</span><strong>{{ metricText(shadowRecordDiff.changedCount) }}</strong></article>
              <article><span>新增重复</span><strong>{{ metricText(shadowRecordDiff.duplicateCount) }}</strong></article>
            </div>
            <article v-else class="overview-record-note">
              <strong>正式记录集保持不变</strong>
              <p>本轮只对候选概览 SQL 做只读试算，不重新抽取、不写入中间表，也不会修改正式记录。</p>
            </article>
            <table class="shadow-result-table"><thead><tr><th>对比项</th><th>当前正式结果</th><th>候选试跑结果</th><th>变化</th></tr></thead><tbody>
              <tr><th>分子</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'numerator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'numerator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'numerator')) - number(aggregateValue(shadowOriginalRow, 'numerator')) }}</td></tr>
              <tr><th>分母</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'denominator')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'denominator')) }}</td><td>{{ number(aggregateValue(shadowCandidateRow, 'denominator')) - number(aggregateValue(shadowOriginalRow, 'denominator')) }}</td></tr>
              <tr><th>结果值</th><td>{{ metricText(aggregateValue(shadowOriginalRow, 'result')) }}</td><td>{{ metricText(aggregateValue(shadowCandidateRow, 'result')) }}</td><td>{{ metricText(number(aggregateValue(shadowCandidateRow, 'result')) - number(aggregateValue(shadowOriginalRow, 'result'))) }}</td></tr>
            </tbody></table>
            <details class="shadow-raw-evidence"><summary>查看完整聚合证据（实施排查用）</summary><div class="shadow-metrics"><article><span>当前正式结果</span><pre>{{ JSON.stringify(shadow.originalResult || {}, null, 2) }}</pre></article><article><span>候选试跑结果</span><pre>{{ JSON.stringify(shadow.candidateResult || {}, null, 2) }}</pre></article></div></details>
            <div class="diff-toolbar"><select v-model="diffType"><option value="ADDED">新增记录</option><option value="REMOVED">减少记录</option><option value="CHANGED">字段变化</option><option value="DUPLICATE">新增重复</option></select><input v-model="diffSearch" placeholder="搜索业务编号" /><button type="button" :disabled="diffLoading" @click="loadDiff(1)">查看差异</button></div>
            <div v-if="diffPage" class="diff-list"><p>共 {{ diffPage.total }} 个业务编号</p><details v-for="item in diffPage.items" :key="item.businessKey"><summary>{{ item.businessKey }}<span v-if="item.changedFields.length"> · {{ item.changedFields.join('、') }}</span></summary><div><section><strong>修改前</strong><pre>{{ JSON.stringify(item.beforeRows, null, 2) }}</pre></section><section><strong>修改后</strong><pre>{{ JSON.stringify(item.afterRows, null, 2) }}</pre></section></div></details><nav v-if="diffPage.total > diffPage.pageSize" class="pager"><button type="button" :disabled="diffPage.page <= 1" @click="loadDiff(diffPage.page - 1)">上一页</button><span>第 {{ diffPage.page }} / {{ Math.ceil(diffPage.total / diffPage.pageSize) }} 页</span><button type="button" :disabled="diffPage.page * diffPage.pageSize >= diffPage.total" @click="loadDiff(diffPage.page + 1)">下一页</button></nav></div>
          </section>
          <section v-if="snapshot?.currentStep === 'DRAFT_SAVE'" class="draft-save"><header><strong>保存为医院草稿版本</strong><span>不会影响正式计算</span></header><label><span>问题说明</span><textarea v-model="draftDescription.issueSummary" rows="2"></textarea></label><label><span>本次修改</span><textarea v-model="draftDescription.changeSummary" rows="2"></textarea></label><label><span>预期影响</span><textarea v-model="draftDescription.expectedImpact" rows="2"></textarea></label><label><span>影子验证结论</span><textarea v-model="draftDescription.verificationSummary" rows="2"></textarea></label><button type="button" class="workspace-primary" :disabled="Boolean(busy) || Object.values(draftDescription).some((value) => !value.trim())" @click="saveDraft">保存为医院草稿版本</button></section>
          <section v-if="snapshot?.draftResult && Object.keys(snapshot.draftResult).length" class="draft-complete"><strong>医院草稿已保存</strong><p>草稿编号：{{ String(snapshot.draftResult.draftId || '') }}</p><p>未发布，不影响当前正式计算。</p></section>
        </section>
      </div>
    </section>
  </main>
</template>

<style>
@import '../styles/standard-diagnosis.css';
</style>
