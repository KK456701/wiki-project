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

const steps = [
  { key: 'CALIBER_CONFIRMATION', label: '确认统计口径', short: '准备' },
  { key: 'GATE_1_SCHEMA', label: '表和字段校验', short: '第一步' },
  { key: 'GATE_2_EVENT', label: '事件和抽取脚本校验（暂未启用）', short: '第二步' },
  { key: 'GATE_3_VALUE', label: '数值和现场常量校验', short: '第三步' },
  { key: 'CASE_INPUT', label: '登记具体案例', short: '案例' },
  { key: 'CASE_CALIBER_CLARIFICATION', label: '澄清案例统计口径', short: '澄清' },
  { key: 'CASE_INVESTIGATION', label: '具体案例查因', short: '查因' },
  { key: 'CHANGE_PROPOSAL', label: '修改要求与候选 SQL', short: '修改' },
  { key: 'SHADOW_TRIAL', label: '影子重跑与对账', short: '试跑' },
  { key: 'RELEASE_APPROVAL', label: '审批并发布医院口径', short: '发布' },
]

const currentIndex = computed(() => {
  if (props.snapshot.currentStep === 'COMPLETED') return steps.length
  if (props.snapshot.currentStep === 'BASE_CHECKS_RESULT' || props.snapshot.currentStep.startsWith('GATE_')) return 3
  return Math.max(0, steps.findIndex((item) => item.key === props.snapshot.currentStep))
})

const blockedGateCount = computed(() => props.snapshot.gateResults.filter((item) => String(item.status) === 'BLOCKED').length)
const caseTemplate = `记录类型：就诊号 / 事件号 / 医嘱号 / 手术号
记录编号：
异常现象：
医院认为的正确结果：
已有查询、SQL或截图（可选）：`

function gate(number: number): Record<string, unknown> | undefined {
  return props.snapshot.gateResults.find((item) => Number(item.gate) === number)
}

function gateState(number: number): string {
  return String(gate(number)?.status || 'WAITING')
}

function runBaseChecks() {
  emit('action', 'RUN_BASE_CHECKS', {})
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
        <header class="diagnosis-case-head"><div><span class="diagnosis-kicker">异常排查任务 · {{ snapshot.caseId }}</span><h3>{{ snapshot.caliberSnapshot.ruleName || snapshot.ruleId }}</h3><p>{{ snapshot.caseInput.symptom || '系统将先完成三步基础检查，再引导登记具体案例。' }}</p></div><span class="diagnosis-release">口径版本 {{ snapshot.knowledgeReleaseId }}</span></header>
        <ol class="diagnosis-rail" aria-label="排查步骤"><li v-for="(step, index) in steps" :key="step.key" :class="{ active: index === currentIndex, done: index < currentIndex }"><i>{{ index < currentIndex ? '✓' : index + 1 }}</i><span>{{ step.short }}</span></li></ol>
      </div>
    </article>

    <article v-if="snapshot.currentStep === 'CALIBER_CONFIRMATION'" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 准备检查</strong></div><h4>先确认当前生效统计口径</h4><dl class="caliber-facts"><div><dt>分子</dt><dd>{{ snapshot.caliberSnapshot.numeratorRule || '知识库未单独描述' }}</dd></div><div><dt>分母</dt><dd>{{ snapshot.caliberSnapshot.denominatorRule || '知识库未单独描述' }}</dd></div><div><dt>统计窗口</dt><dd>{{ snapshot.caseInput.statStart }} 至 {{ snapshot.caseInput.statEnd }}</dd></div></dl><button type="button" class="diagnosis-primary" :disabled="busy" @click="emit('action', 'CONFIRM_CALIBER', { confirmed: true })">确认口径并自动完成三步基础检查</button></div>
    </article>

    <article v-if="snapshot.currentStep === 'BASE_CHECKS_RESULT' || snapshot.currentStep.startsWith('GATE_')" class="message is-agent">
      <div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 三步基础检查回复</strong><span>{{ blockedGateCount }} 步需要处理</span></div><p>三步已经自动执行。请先处理标红问题；第二步暂未启用，不查询数据库，也不作为异常。</p><section v-for="number in [1, 2, 3]" :key="number" class="diagnosis-gate-summary"><header><strong>第{{ ['一', '二', '三'][number - 1] }}步 · {{ steps[number].label }}</strong><em :data-state="gateState(number)">{{ gate(number)?.skipped ? '暂未启用' : gateState(number) === 'BLOCKED' ? '需要处理' : '已通过' }}</em></header><div class="diagnosis-result" :data-state="gateState(number)"><strong>{{ gate(number)?.message || '未生成检查结果' }}</strong><code v-if="gate(number)?.errorCode">{{ gate(number)?.errorCode }}</code></div><div v-if="gate(number)?.repairSuggestion" class="diagnosis-repair"><strong>下一步怎么处理</strong><p>{{ gate(number)?.repairSuggestion }}</p></div><details v-if="gate(number)" class="diagnosis-technical"><summary>查看校验 SQL 与原始证据</summary><pre>{{ pretty(gate(number)?.facts) }}</pre></details></section><button type="button" class="diagnosis-primary" :disabled="busy" @click="runBaseChecks">修复后重新执行全部三步</button></div>
    </article>

    <template v-if="snapshot.currentStep === 'CASE_INPUT'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 三步基础检查回复</strong><span>可以继续</span></div><p>第一步已通过，第二步暂未启用并已安全跳过，第三步已通过。下一步请提供一条医院认为结果不对的记录。</p><pre class="diagnosis-template">{{ caseTemplate }}</pre><button type="button" class="diagnosis-text-action" @click="copyCaseTemplate">{{ templateCopied ? '已复制' : '复制填写模板' }}</button><details class="diagnosis-technical"><summary>查看三步基础检查产物</summary><div class="diagnosis-gate-list"><p v-for="number in [1, 2, 3]" :key="number"><strong>第{{ ['一', '二', '三'][number - 1] }}步：</strong>{{ gate(number)?.skipped ? '暂未启用' : gate(number)?.message }}</p></div></details></div></article>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 提供具体案例</strong></div><div class="diagnosis-case-grid"><label>记录类型<select v-model="recordField"><option value="ENCOUNTER_ID">就诊号</option><option value="EVENT_ID">事件号</option><option value="ORDER_ID">医嘱号</option><option value="SURGERY_ID">手术号</option></select></label><label>记录标识<input v-model="recordId" maxlength="100" placeholder="输入现场可定位的编号" /></label><label class="wide">异常现象<textarea v-model="symptom" rows="2" maxlength="1000" placeholder="例如：这条作废会诊被计入了分子"></textarea></label><label class="wide">医院认为的正确结果<textarea v-model="expectedResult" rows="2" maxlength="1000" placeholder="例如：该记录不应进入分子和分母"></textarea></label></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>三项填写完整后发送，系统会单独回复案例口径澄清。</p><button type="button" class="diagnosis-primary" :disabled="busy || !recordId.trim() || !symptom.trim() || !expectedResult.trim()" @click="submitCase">发送案例</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_CALIBER_CLARIFICATION'">
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 具体案例</strong></div><p>{{ snapshot.caseInput.recordField }}={{ snapshot.caseInput.recordId }}</p><p>{{ snapshot.caseInput.symptom }}</p><strong>医院认为：{{ snapshot.caseInput.expectedResult }}</strong></div></article>
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 案例口径澄清</strong><span>等待确认</span></div><p>这条案例必须按固定顺序判断：先看统计时间，再看是否进入分母，最后看是否命中分子。不能因为命中某个分子条件就跳过分母和排除条件。</p><dl class="caliber-facts"><div><dt>分母范围</dt><dd>{{ snapshot.caseExpectedClassification.denominatorRule || '知识库未单独描述' }}</dd></div><div><dt>分子条件</dt><dd>{{ snapshot.caseExpectedClassification.numeratorRule || '知识库未单独描述' }}</dd></div></dl><p><strong>本轮产物：</strong>案例使用的口径版本、统计窗口和判断顺序已经冻结。下一轮请按这些条件查询原业务。</p><label>医院认为该记录应该归入哪里<select v-model="expectedMembership"><option value="UNKNOWN">暂不确定，先查询</option><option value="NUMERATOR">应进入分子和分母</option><option value="DENOMINATOR_ONLY">只进入分母</option><option value="EXCLUDED">分子分母都不应进入</option></select></label><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>确认已理解口径，下一轮必须提供业务查询结果或启动系统取证。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="confirmCaseCaliber">确认澄清，进入原业务查因</button></div></article>
    </template>

    <template v-if="snapshot.currentStep === 'CASE_INVESTIGATION'">
      <article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 请查询原业务</strong></div><p>请核对业务表是否存在该记录、关键状态和时间、抽取或中间表是否存在、是否重复，以及按当前口径应归入哪里。</p><p><strong>本轮产物：</strong>业务记录 → 抽取结果 → 真实库中间表 → 分子分母判定的证据链，以及一条有证据支持的具体原因。</p><button type="button" class="diagnosis-primary" :disabled="busy" @click="collectAutomaticEvidence">系统先沿数据链路自动取证</button></div></article>
      <template v-for="item in snapshot.evidence" :key="String(item.evidenceId)"><article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 查询结果</strong></div><p>{{ item.summary }}</p></div></article><article class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>{{ item.modelId ? '系统 · AI分析' : '系统 · 程序证据' }}</strong></div><p>{{ item.aiAnalysis || item.summary }}</p><details v-if="item.stages" class="diagnosis-technical"><summary>查看业务库、真实库和统计结果证据</summary><pre>{{ pretty(item.stages) }}</pre></details></div></article></template>
      <article class="message is-user"><div class="message-avatar">我</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>实施人员 · 原业务查询结果</strong></div><textarea v-model="evidenceText" rows="4" placeholder="业务表查询结果：…&#10;抽取或中间表结果：…&#10;按当前口径的判断：…&#10;证据 SQL（可选）：…"></textarea><button type="button" class="diagnosis-secondary" :disabled="busy || !evidenceText.trim()" @click="submitEvidence">发送查询结果</button><div class="diagnosis-confirm-cause"><textarea v-model="causeText" rows="3" placeholder="证据对上后，填写已确认的具体原因"></textarea><button type="button" class="diagnosis-primary" :disabled="busy || !causeText.trim() || !snapshot.evidence.length" @click="confirmCause">确认原因，进入修改</button></div><p class="diagnosis-pass-rule"><strong>进入下一步：</strong>至少一条查询证据，且具体原因能被证据支持。</p></div></article>
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
    <article v-if="snapshot.currentStep === 'COMPLETED'" class="message is-agent"><div class="message-avatar">AI</div><div class="message-card diagnosis-turn-card"><div class="message-head"><strong>系统 · 医院口径已发布</strong></div><pre>{{ pretty(snapshot.releaseResult) }}</pre></div></article>
  </div>
</template>
