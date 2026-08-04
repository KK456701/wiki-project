<script setup lang="ts">
import { computed, ref } from 'vue'

import type { DiagnosisCaseSnapshot } from '../api/agent'
import IndicatorDataFlowPanel from './IndicatorDataFlowPanel.vue'

const props = defineProps<{
  snapshot: DiagnosisCaseSnapshot
  busy?: boolean
}>()

const emit = defineEmits<{
  action: [action: string, payload: Record<string, unknown>]
}>()

const evidenceText = ref('')
const causeText = ref('')
const changeType = ref('SQL_CHANGE')
const changeLayer = ref('SOURCE_EXTRACT')
const requirements = ref('')
const candidateSql = ref('')
const recordField = ref('ENCOUNTER_ID')
const recordId = ref('')
const symptom = ref('')
const expectedResult = ref('')
const expectedMembership = ref('UNKNOWN')
const templateCopied = ref(false)

const baseSteps = [
  { gate: 1, key: 'GATE_1_SCHEMA', label: '数据结构校验' },
  { gate: 2, key: 'GATE_2_EVENT', label: '事件与抽取校验' },
  { gate: 3, key: 'GATE_3_VALUE', label: '数据可用性校验' },
]

const blockedGateCount = computed(() => props.snapshot.gateResults.filter((item) => String(item.status) === 'BLOCKED').length)
const caseTemplate = `记录类型：就诊号 / 事件号 / 医嘱号 / 手术号
记录编号：
异常现象：
医院认为的正确结果：
已有查询、SQL或截图（可选）：`

function gate(number: number): Record<string, unknown> | undefined {
  return props.snapshot.gateResults.find((item) => Number(item.gate) === number)
}

function retryCurrentGate() {
  const number = baseSteps.find((item) => item.key === props.snapshot.currentStep)?.gate || 1
  emit('action', 'RECHECK_GATE', { gate: number })
}

function submitCase() {
  if (!recordId.value.trim() || !symptom.value.trim() || !expectedResult.value.trim()) return
  emit('action', 'SUBMIT_CASE', {
    recordField: recordField.value,
    recordId: recordId.value.trim(),
    symptom: symptom.value.trim(),
    expectedResult: expectedResult.value.trim(),
    businessUniqueKey: recordField.value,
    expectedClassification: { status: 'WAITING_CONFIRMATION' },
  })
}

async function copyCaseTemplate() {
  await navigator.clipboard.writeText(caseTemplate)
  templateCopied.value = true
  window.setTimeout(() => { templateCopied.value = false }, 1500)
}

function confirmCaseCaliber() {
  emit('action', 'CONFIRM_CASE_CALIBER', {
    confirmed: true,
    hospitalExpectedMembership: expectedMembership.value,
  })
}

function submitEvidence() {
  if (!evidenceText.value.trim()) return
  emit('action', 'SUBMIT_EVIDENCE', {
    type: 'IMPLEMENTER_QUERY_RESULT',
    summary: evidenceText.value.trim(),
    requestAiAnalysis: true,
  })
  evidenceText.value = ''
}

function collectAutomaticEvidence() {
  emit('action', 'SUBMIT_EVIDENCE', {
    type: 'AUTOMATIC_DATA_FLOW',
    runAutomatic: true,
    requestAiAnalysis: false,
  })
}

function confirmCause() {
  if (!causeText.value.trim()) return
  emit('action', 'CONFIRM_CAUSE', {
    conclusion: causeText.value.trim(),
    evidenceIds: props.snapshot.evidence.map((item) => item.evidenceId),
  })
}

function closeAsCorrect() {
  emit('action', 'CLOSE_AS_CORRECT', {
    conclusion: causeText.value.trim() || '业务记录、真实库中间表和指标判定一致，当前计算正确。',
  })
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
    expectedCaseEffect: String(props.snapshot.caseInput.expectedResult || ''),
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
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 基础校验回复</strong><span>{{ blockedGateCount ? '需要处理' : allBaseChecksPassed ? '可以继续' : '检查中' }}</span></div><section v-for="item in baseSteps" :key="item.gate" class="diagnosis-gate-summary"><header><strong>{{ item.label }}</strong><em :data-state="stepState(item.gate)">{{ stepStateText(item.gate) }}</em></header><div class="diagnosis-result" :data-state="stepState(item.gate)"><strong>{{ gate(item.gate)?.message || (stepState(item.gate) === 'RUNNING' ? '正在检查，请稍候…' : '等待前一步完成') }}</strong><code v-if="gate(item.gate)?.errorCode">{{ gate(item.gate)?.errorCode }}</code></div><div v-if="gate(item.gate)?.repairSuggestion" class="diagnosis-repair"><strong>建议怎么处理</strong><p>{{ gate(item.gate)?.repairSuggestion }}</p></div><details v-if="gate(item.gate)" class="diagnosis-technical"><summary>查看校验 SQL 与原始证据</summary><pre>{{ pretty(gate(item.gate)?.facts) }}</pre></details></section><p class="diagnosis-base-conclusion"><strong>结论：</strong>{{ baseConclusion }}</p><button v-if="blockedGateCount" type="button" class="diagnosis-primary" :disabled="busy" @click="retryCurrentGate">修复后重新校验当前步骤</button><button v-else-if="snapshot.currentStep.startsWith('GATE_') && !currentGateHasResult && !busy" type="button" class="diagnosis-primary" @click="retryCurrentGate">继续基础校验</button></div>
    </article>

    <template v-if="snapshot.currentStep === 'CASE_INPUT'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请提供一条具体案例</strong></div><p>请填写一个能在业务库定位的编号、你看到的异常现象，以及医院认为的正确结果。</p><pre class="diagnosis-template">{{ caseTemplate }}</pre><button type="button" class="diagnosis-text-action" @click="copyCaseTemplate">{{ templateCopied ? '已复制' : '复制填写模板' }}</button></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 提供具体案例</strong></div><div class="diagnosis-case-grid"><label>记录类型<select v-model="recordField"><option value="ENCOUNTER_ID">就诊号</option><option value="EVENT_ID">事件号</option><option value="ORDER_ID">医嘱号</option><option value="SURGERY_ID">手术号</option></select></label><label>记录标识<input v-model="recordId" maxlength="100" placeholder="输入现场可定位的编号" /></label><label class="wide">异常现象<textarea v-model="symptom" rows="2" maxlength="1000" placeholder="例如：这条作废会诊被计入了分子"></textarea></label><label class="wide">医院认为的正确结果<textarea v-model="expectedResult" rows="2" maxlength="1000" placeholder="例如：该记录不应进入分子和分母"></textarea></label></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>三项填写完整后发送，系统会单独回复案例口径澄清。</p><button type="button" class="diagnosis-primary" :disabled="busy || !recordId.trim() || !symptom.trim() || !expectedResult.trim()" @click="submitCase">发送案例</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 具体案例</strong></div><p>{{ snapshot.caseInput.recordField }}={{ snapshot.caseInput.recordId }}</p><p>{{ snapshot.caseInput.symptom }}</p><strong>医院认为：{{ snapshot.caseInput.expectedResult }}</strong></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 案例口径澄清</strong><span>等待确认</span></div><section class="diagnosis-caliber-section"><h4>当前生效口径：{{ snapshot.caseExpectedClassification.profileName || snapshot.profileId }}</h4><p>{{ snapshot.caseExpectedClassification.definition || '知识库未单独描述指标定义' }}</p><dl class="caliber-facts"><div><dt>计算公式</dt><dd>{{ snapshot.caseExpectedClassification.formula || '知识库未单独描述' }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl></section><section class="diagnosis-caliber-section"><h4>本次分子和分母</h4><dl class="caliber-facts"><div><dt>分子 {{ snapshot.caseExpectedClassification.numeratorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.numeratorRule || '知识库未单独描述' }}</dd></div><div><dt>分母 {{ snapshot.caseExpectedClassification.denominatorCount ?? '—' }}</dt><dd>{{ snapshot.caseExpectedClassification.denominatorRule || '知识库未单独描述' }}</dd></div></dl><p class="diagnosis-help">这里先说明本次汇总数量和判断条件；下一步会按记录号核对这条患者明细是否进入分母、是否命中分子。</p></section><section class="diagnosis-caliber-section"><h4>当前口径数据链路</h4><p class="diagnosis-flow-path">{{ flowPath(snapshot.caseExpectedClassification.dataFlow) }}</p><details class="diagnosis-technical"><summary>展开数据链路、涉及表和 SQL</summary><IndicatorDataFlowPanel :flow="snapshot.caseExpectedClassification.dataFlow" /></details></section><p><strong>判断顺序：</strong>先确认记录处于统计窗口，再判断是否进入分母，最后判断是否命中分子。</p><label>医院认为该记录应该归入哪里<select v-model="expectedMembership"><option value="UNKNOWN">暂不确定，先查询</option><option value="NUMERATOR">应进入分子和分母</option><option value="DENOMINATOR_ONLY">只进入分母</option><option value="EXCLUDED">分子分母都不应进入</option></select></label><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>确认当前口径后，系统将沿上述数据链路查询这条记录。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="confirmCaseCaliber">确认澄清，进入原业务查因</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_INVESTIGATION'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请查询原业务</strong></div><p>请核对业务表是否存在该记录、关键状态和时间、抽取或中间表是否存在、是否重复，以及按当前口径应归入哪里。</p><p><strong>本轮产物：</strong>业务记录 → 抽取结果 → 真实库中间表 → 分子分母判定的证据链，以及一条有证据支持的具体原因。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="collectAutomaticEvidence">系统先沿数据链路自动取证</button></div></article>
      <template v-for="item in snapshot.evidence" :key="String(item.evidenceId)"><article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><strong>{{ item.type === 'AUTOMATIC_DATA_FLOW' ? '沿当前数据链路自动取证' : item.summary }}</strong></div></article><article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ item.type === 'AUTOMATIC_DATA_FLOW' ? '系统 · 自动取证回复' : item.modelId ? '系统 · AI分析' : '系统 · 程序证据' }}</strong></div><template v-if="item.type === 'AUTOMATIC_DATA_FLOW'"><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).found).length"><h4>查到了什么</h4><p v-for="line in stringList(evidenceDisplay(item).found)" :key="line">✓ {{ line }}</p></section><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).notFound).length"><h4>哪些环节没找到记录</h4><p v-for="line in stringList(evidenceDisplay(item).notFound)" :key="line">• {{ line }}</p></section><section class="diagnosis-evidence-group" v-if="stringList(evidenceDisplay(item).unfinished).length"><h4>哪些查询没完成</h4><p v-for="line in stringList(evidenceDisplay(item).unfinished)" :key="line">! {{ line }}</p></section><p class="diagnosis-base-conclusion"><strong>结论：</strong>{{ evidenceDisplay(item).conclusion || item.summary }}</p><p><strong>下一步：</strong>{{ evidenceDisplay(item).nextAction || '根据证据继续核对。' }}</p></template><p v-else>{{ item.aiAnalysis || item.summary }}</p><details v-if="item.stages" class="diagnosis-technical"><summary>查看SQL、原始错误和返回记录</summary><pre>{{ pretty(item.stages) }}</pre></details></div></article></template>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 原业务查询结果</strong></div><textarea v-model="evidenceText" rows="4" placeholder="业务表查询结果：…&#10;抽取或中间表结果：…&#10;按当前口径的判断：…&#10;证据 SQL（可选）：…"></textarea><button type="button" class="diagnosis-secondary" :disabled="busy || !evidenceText.trim()" @click="submitEvidence">发送查询结果</button><div class="diagnosis-confirm-cause"><textarea v-model="causeText" rows="3" placeholder="证据对上后，填写已确认的具体原因；若证据证明计算正确，可直接结束"></textarea><button type="button" class="diagnosis-primary" :disabled="busy || !causeText.trim() || !snapshot.evidence.length" @click="confirmCause">确认原因，进入修改</button><button type="button" class="diagnosis-secondary" :disabled="busy || !snapshot.evidence.length" @click="closeAsCorrect">确认结果正确，结束本次排查</button></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>至少一条查询证据，且具体原因能被证据支持；计算正确时无需修改 SQL。</p></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CHANGE_PROPOSAL'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 已确认原因</strong></div><p>{{ snapshot.causeConclusion.conclusion }}</p></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请提供修改要求</strong></div><p>说明要改抽取还是统计、增加或删除什么条件、使用什么字段和值，以及这条案例修改后应该怎样变化。最好附完整只读 SQL。</p></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 修改要求</strong></div><div class="diagnosis-change-grid"><label>问题分支<select v-model="changeType"><option value="DATA_REPAIR">数据修复</option><option value="EVENT_CONFIG">事件配置</option><option value="SQL_CHANGE">SQL 修改</option><option value="CALIBER_CHANGE">医院口径变更</option></select></label><label v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'">只修改一层<select v-model="changeLayer"><option value="SOURCE_EXTRACT">抽取 SQL</option><option value="OVERVIEW">统计 SQL</option></select></label></div><textarea v-model="requirements" rows="3" placeholder="写清新增/删除条件、字段、操作符和值，以及对案例的预期影响"></textarea><textarea v-if="changeType === 'SQL_CHANGE' || changeType === 'CALIBER_CHANGE'" v-model="candidateSql" class="diagnosis-sql-input" rows="8" placeholder="可选：粘贴完整 SELECT；留空时由当前选择的模型根据要求生成"></textarea><p class="diagnosis-pass-rule"><strong>本轮产物：</strong>原 SQL、候选 SQL、差异说明和程序校验结果。校验不通过不能试跑。</p><button type="button" class="diagnosis-primary" :disabled="busy || !requirements.trim()" @click="buildCandidate">发送要求，由系统组装 SQL</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'SHADOW_TRIAL'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 候选语句已生成</strong></div><p>候选语句已通过安全校验。正式中间表和当前卡片不会被覆盖。</p><details v-if="Object.keys(snapshot.candidateSql).length" class="diagnosis-technical"><summary>查看候选 SQL</summary><pre>{{ snapshot.candidateSql.sql }}</pre></details><details v-if="Object.keys(snapshot.shadowTrial).length" class="diagnosis-technical"><summary>查看试跑对账</summary><pre>{{ pretty(snapshot.shadowTrial) }}</pre></details></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 确认影子重跑</strong></div><p class="diagnosis-pass-rule"><strong>进入发布：</strong>案例变化符合预期、没有无法解释的重复、输出结构兼容、分子分母可对账。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'RUN_SHADOW_TRIAL', {})">使用候选语句影子重跑</button></div></article>
    </template>

    <article v-if="snapshot.currentStep === 'RELEASE_APPROVAL'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 影子对账通过</strong></div><p>发布将生成不可变医院增量包并原子切换 active 版本。</p><details class="diagnosis-technical"><summary>查看影子试跑报告</summary><pre>{{ pretty(snapshot.shadowTrial) }}</pre></details><button type="button" class="diagnosis-primary danger" :disabled="busy" @click="emit('action', 'APPROVE_RELEASE', { confirmed: true })">确认发布医院口径</button></div></article>
    <article v-if="snapshot.currentStep === 'COMPLETED'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · {{ snapshot.releaseResult.outcome === 'CALCULATION_CONFIRMED_CORRECT' ? '当前计算已确认正确' : '医院口径已发布' }}</strong></div><pre>{{ pretty(snapshot.releaseResult) }}</pre></div></article>
  </div>
</template>
