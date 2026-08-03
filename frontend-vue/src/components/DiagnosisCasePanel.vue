<script setup lang="ts">
import { computed, ref } from 'vue'

import type { DiagnosisCaseSnapshot } from '../api/agent'

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
    requestAiAnalysis: true,
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
function successFactSummary(): string {
  return baseSteps.map((item) => String(gate(item.gate)?.message || '')).filter(Boolean).join('；')
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
    <article class="message is-agent diagnosis-context-message">
      <div class="message-avatar">AI</div>
      <div class="message-card diagnosis-context-card">
        <header class="diagnosis-case-head"><div><span class="diagnosis-kicker">异常排查任务 · {{ snapshot.caseId }}</span><h3>{{ snapshot.caliberSnapshot.ruleName || snapshot.ruleId }}</h3><p>{{ snapshot.caseInput.symptom || '先完成异常排查基础校验；全部通过后，系统再引导登记具体案例。' }}</p></div><span class="diagnosis-release">口径版本 {{ snapshot.knowledgeReleaseId }}</span></header>
        <section v-if="baseChecksStarted" class="diagnosis-base-flow" aria-label="异常排查基础校验"><strong>异常排查基础校验</strong><ol><li v-for="item in baseSteps" :key="item.key" :data-state="stepState(item.gate)"><i>{{ stepState(item.gate) === 'PASSED' ? '✓' : stepState(item.gate) === 'BLOCKED' ? '!' : item.gate }}</i><span>{{ item.label }}</span><em>{{ stepStateText(item.gate) }}</em></li></ol></section>
      </div>
    </article>

    <article v-if="snapshot.currentStep === 'CALIBER_CONFIRMATION'" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 准备校验</strong></div><h4>先确认当前生效统计口径</h4><dl class="caliber-facts"><div><dt>分子</dt><dd>{{ snapshot.caliberSnapshot.numeratorRule || '知识库未单独描述' }}</dd></div><div><dt>分母</dt><dd>{{ snapshot.caliberSnapshot.denominatorRule || '知识库未单独描述' }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'CONFIRM_CALIBER', { confirmed: true })">确认口径并开始基础校验</button></div>
    </article>

    <article v-if="snapshot.currentStep.startsWith('GATE_')" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 异常排查基础校验</strong><span>{{ blockedGateCount ? `${blockedGateCount}步需处理` : busy ? '自动检查中' : '等待继续' }}</span></div><p>系统按顺序自动校验；发现阻断问题时停在当前步骤，修复后只重跑这一项。</p><section v-for="item in baseSteps" :key="item.gate" class="diagnosis-gate-summary"><header><strong>{{ item.label }}</strong><em :data-state="stepState(item.gate)">{{ stepStateText(item.gate) }}</em></header><div class="diagnosis-result" :data-state="stepState(item.gate)"><strong>{{ gate(item.gate)?.message || (stepState(item.gate) === 'RUNNING' ? '正在检查，请稍候…' : '等待前一步完成') }}</strong><code v-if="gate(item.gate)?.errorCode">{{ gate(item.gate)?.errorCode }}</code></div><div v-if="gate(item.gate)?.repairSuggestion" class="diagnosis-repair"><strong>建议怎么处理</strong><p>{{ gate(item.gate)?.repairSuggestion }}</p></div><details v-if="gate(item.gate)" class="diagnosis-technical"><summary>查看校验 SQL 与原始证据</summary><pre>{{ pretty(gate(item.gate)?.facts) }}</pre></details></section><button v-if="blockedGateCount" type="button" class="diagnosis-primary" :disabled="busy" @click="retryCurrentGate">修复后重新校验当前步骤</button><button v-else-if="!currentGateHasResult && !busy" type="button" class="diagnosis-primary" @click="retryCurrentGate">继续基础校验</button></div>
    </article>

    <template v-if="snapshot.currentStep === 'CASE_INPUT'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 基础校验结论</strong><span>可以继续</span></div><p>{{ successFactSummary() }}。现在请提供一条具体案例。</p><section v-for="item in baseSteps" :key="item.gate" class="diagnosis-gate-summary compact"><header><strong>{{ item.label }}</strong><em data-state="PASSED">已通过</em></header><div class="diagnosis-result" data-state="PASSED"><strong>{{ gate(item.gate)?.message }}</strong></div><details class="diagnosis-technical"><summary>查看 SQL 与原始证据</summary><pre>{{ pretty(gate(item.gate)?.facts) }}</pre></details></section><pre class="diagnosis-template">{{ caseTemplate }}</pre><button type="button" class="diagnosis-text-action" @click="copyCaseTemplate">{{ templateCopied ? '已复制' : '复制填写模板' }}</button></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 提供具体案例</strong></div><div class="diagnosis-case-grid"><label>记录类型<select v-model="recordField"><option value="ENCOUNTER_ID">就诊号</option><option value="EVENT_ID">事件号</option><option value="ORDER_ID">医嘱号</option><option value="SURGERY_ID">手术号</option></select></label><label>记录标识<input v-model="recordId" maxlength="100" placeholder="输入现场可定位的编号" /></label><label class="wide">异常现象<textarea v-model="symptom" rows="2" maxlength="1000" placeholder="例如：这条作废会诊被计入了分子"></textarea></label><label class="wide">医院认为的正确结果<textarea v-model="expectedResult" rows="2" maxlength="1000" placeholder="例如：该记录不应进入分子和分母"></textarea></label></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>三项填写完整后发送，系统会单独回复案例口径澄清。</p><button type="button" class="diagnosis-primary" :disabled="busy || !recordId.trim() || !symptom.trim() || !expectedResult.trim()" @click="submitCase">发送案例</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 具体案例</strong></div><p>{{ snapshot.caseInput.recordField }}={{ snapshot.caseInput.recordId }}</p><p>{{ snapshot.caseInput.symptom }}</p><strong>医院认为：{{ snapshot.caseInput.expectedResult }}</strong></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 案例口径澄清</strong><span>等待确认</span></div><p>这条案例必须按固定顺序判断：先看统计时间，再看是否进入分母，最后看是否命中分子。不能因为命中某个分子条件就跳过分母和排除条件。</p><dl class="caliber-facts"><div><dt>分母范围</dt><dd>{{ snapshot.caseExpectedClassification.denominatorRule || '知识库未单独描述' }}</dd></div><div><dt>分子条件</dt><dd>{{ snapshot.caseExpectedClassification.numeratorRule || '知识库未单独描述' }}</dd></div></dl><p><strong>本轮产物：</strong>案例使用的口径版本、统计窗口和判断顺序已经冻结。下一轮请按这些条件查询原业务。</p><label>医院认为该记录应该归入哪里<select v-model="expectedMembership"><option value="UNKNOWN">暂不确定，先查询</option><option value="NUMERATOR">应进入分子和分母</option><option value="DENOMINATOR_ONLY">只进入分母</option><option value="EXCLUDED">分子分母都不应进入</option></select></label><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>确认已理解口径，下一轮必须提供业务查询结果或启动系统取证。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="confirmCaseCaliber">确认澄清，进入原业务查因</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_INVESTIGATION'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请查询原业务</strong></div><p>请核对业务表是否存在该记录、关键状态和时间、抽取或中间表是否存在、是否重复，以及按当前口径应归入哪里。</p><p><strong>本轮产物：</strong>业务记录 → 抽取结果 → 真实库中间表 → 分子分母判定的证据链，以及一条有证据支持的具体原因。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="collectAutomaticEvidence">系统先沿数据链路自动取证</button></div></article>
      <template v-for="item in snapshot.evidence" :key="String(item.evidenceId)"><article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 查询结果</strong></div><p>{{ item.summary }}</p></div></article><article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ item.modelId ? '系统 · AI分析' : '系统 · 程序证据' }}</strong></div><p>{{ item.aiAnalysis || item.summary }}</p><details v-if="item.stages" class="diagnosis-technical"><summary>查看业务库、真实库和统计结果证据</summary><pre>{{ pretty(item.stages) }}</pre></details></div></article></template>
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
