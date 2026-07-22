<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  advanceIndicatorDraft, confirmDraftMetadata, generateDraftSql, loadDraftMetadataSuggestions,
  loadHospitalDefinedVersions, loadIndicatorDrafts, loginAdmin, logoutAdmin,
  restoreHospitalDefinedVersion, reviewIndicatorDraft, trialRunDraftSql, updateIndicatorDraft,
  type DraftMetadataCandidate, type DraftMetadataSuggestions, type IndicatorDraft,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const drafts = ref<IndicatorDraft[]>([])
const selectedId = ref('')
const loading = ref(false)
const error = ref('')
const message = ref('')
const suggestions = ref<DraftMetadataSuggestions | null>(null)
const mappingChoice = ref<Record<string, number>>({})
const trialStart = ref(`${new Date().getFullYear()}-01-01T00:00`)
const trialEnd = ref(localDateTimeValue(new Date()))
const adminToken = ref(sessionStorage.getItem('vueAdminToken') || '')
const adminPassword = ref('')
const rejectReason = ref('')
const publishedVersions = ref<Array<Record<string, unknown>>>([])
const form = ref({ index_name: '', index_desc: '', numerator_rule: '', denominator_rule: '',
  filter_rule: '', exclude_rule: '', stat_cycle: 'month', metric_type: 'ratio' as 'ratio' | 'count',
  metadata_requirements: '' })
const selected = computed(() => drafts.value.find((item) => item.draft_id === selectedId.value) || null)
const phases = ['requirements_pending', 'metadata_pending', 'metadata_ready', 'sql_ready', 'trial_passed', 'pending_approval', 'published']

function localDateTimeValue(value: Date) {
  return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

onMounted(async () => {
  if (!store.isAuthenticated || !store.user) { await router.replace('/'); return }
  await refresh()
})

async function refresh(preferred = '') {
  if (!store.user) return
  loading.value = true; error.value = ''
  try {
    drafts.value = await loadIndicatorDrafts(store.token, store.user.hospitalId)
    selectedId.value = preferred || selectedId.value || drafts.value[0]?.draft_id || ''
    select(selected.value)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '实施任务加载失败。' }
  finally { loading.value = false }
}

function select(draft: IndicatorDraft | null) {
  if (!draft) return
  selectedId.value = draft.draft_id
  form.value = {
    index_name: draft.index_name, index_desc: draft.index_desc,
    numerator_rule: draft.numerator_rule, denominator_rule: draft.denominator_rule,
    filter_rule: draft.filter_rule || '', exclude_rule: draft.exclude_rule || '',
    stat_cycle: draft.stat_cycle, metric_type: draft.metric_type,
    metadata_requirements: draft.metadata_requirements.join('\n'),
  }
  suggestions.value = null; mappingChoice.value = {}; publishedVersions.value = []
  if (draft.status === 'metadata_pending') void refreshSuggestions()
  if (draft.status === 'published' && !draft.base_index_code && adminToken.value && draft.formal_index_code) void refreshVersions()
}

async function save() {
  if (!selected.value || !store.user) return
  await operate(async () => updateIndicatorDraft(store.token, selected.value!.draft_id,
    selected.value!.current_version, {
      ...form.value,
      metadata_requirements: form.value.metadata_requirements.split(/\r?\n/).map((item) => item.trim()).filter(Boolean),
    }, store.user!.userId), '设计稿已保存，旧 SQL 和试运行证据已失效。')
}

async function advance(action: 'requirements-confirm' | 'submit') {
  if (!selected.value || !store.user) return
  await operate(() => advanceIndicatorDraft(store.token, selected.value!.draft_id,
    selected.value!.current_version, action, store.user!.userId),
  action === 'submit' ? '实施任务已提交审批。' : '取数要求已确认，进入字段映射阶段。')
}

async function refreshSuggestions() {
  if (!selected.value) return
  loading.value = true; error.value = ''
  try {
    suggestions.value = await loadDraftMetadataSuggestions(store.token, selected.value.draft_id)
    const choices: Record<string, number> = {}
    Object.entries(suggestions.value.suggestions).forEach(([field, values]) => { if (values.length) choices[field] = 0 })
    mappingChoice.value = choices
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '字段建议加载失败。' }
  finally { loading.value = false }
}

async function confirmMappings() {
  if (!selected.value || !suggestions.value) return
  const mappings: Record<string, DraftMetadataCandidate> = {}
  for (const field of selected.value.metadata_requirements) {
    const candidate = suggestions.value.suggestions[field]?.[mappingChoice.value[field]]
    if (!candidate) { error.value = `字段 ${field} 尚未选择映射。`; return }
    mappings[field] = candidate
  }
  await operate(() => confirmDraftMetadata(store.token, selected.value!.draft_id,
    selected.value!.current_version, mappings), '字段映射已确认。')
}

async function generateSql() {
  if (!selected.value) return
  await operate(() => generateDraftSql(store.token, selected.value!.draft_id,
    selected.value!.current_version), '确定性 SQL 已生成并通过只读校验。')
}

async function trialRun() {
  if (!selected.value) return
  await operate(() => trialRunDraftSql(store.token, selected.value!.draft_id,
    selected.value!.current_version, trialStart.value, trialEnd.value), 'DBHub 只读试运行通过。')
}

async function adminLogin() {
  loading.value = true; error.value = ''
  try {
    const result = await loginAdmin(adminPassword.value)
    adminToken.value = result.token; sessionStorage.setItem('vueAdminToken', result.token); adminPassword.value = ''
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '管理员登录失败。' }
  finally { loading.value = false }
}

async function adminLogout() {
  await logoutAdmin(adminToken.value).catch(() => undefined)
  adminToken.value = ''; sessionStorage.removeItem('vueAdminToken'); publishedVersions.value = []
}

async function review(action: 'approve' | 'reject') {
  if (!selected.value || !store.user || !adminToken.value) return
  loading.value = true; error.value = ''; message.value = ''
  try {
    await reviewIndicatorDraft(adminToken.value, store.token, store.user.hospitalId,
      selected.value.draft_id, selected.value.current_version, action, rejectReason.value)
    rejectReason.value = ''; await refresh(selected.value.draft_id)
    message.value = action === 'approve' ? '指标已批准并发布。' : '实施任务已驳回。'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '审批操作失败。' }
  finally { loading.value = false }
}

async function refreshVersions() {
  if (!selected.value?.formal_index_code || !store.user || !adminToken.value) return
  try {
    const result = await loadHospitalDefinedVersions(adminToken.value, store.token,
      store.user.hospitalId, selected.value.formal_index_code)
    publishedVersions.value = result.versions
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '版本记录加载失败。' }
}

async function restoreVersion(version: number) {
  if (!selected.value?.formal_index_code || !store.user || !adminToken.value) return
  loading.value = true; error.value = ''
  try {
    await restoreHospitalDefinedVersion(adminToken.value, store.token, store.user.hospitalId,
      selected.value.formal_index_code, version)
    await refreshVersions(); message.value = `已将 v${version} 恢复为新的生效版本。`
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '版本恢复失败。' }
  finally { loading.value = false }
}

async function operate(action: () => Promise<IndicatorDraft>, success: string) {
  loading.value = true; error.value = ''; message.value = ''
  try { const value = await action(); await refresh(value.draft_id); message.value = success }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '实施操作失败。' }
  finally { loading.value = false }
}

function phaseState(status: string, phase: string) {
  if (status === 'rejected') return phase === 'pending_approval' ? 'failed' : 'done'
  const current = phases.indexOf(status); const target = phases.indexOf(phase)
  return target < current ? 'done' : target === current ? 'active' : 'pending'
}
</script>

<template>
  <main class="monitor-shell implementation-shell">
    <header class="monitor-head">
      <div><p class="eyebrow">Implementation governance</p><h1>指标实施控制台</h1><p>以版本化设计稿推进取数要求、字段映射、SQL 试运行和审批发布。</p></div>
      <nav><RouterLink class="quiet-button" to="/">返回对话</RouterLink><RouterLink class="quiet-button" to="/monitoring">指标监控</RouterLink><RouterLink class="quiet-button" to="/metadata">数据库元数据</RouterLink><button class="quiet-button" :disabled="loading" @click="refresh()">刷新</button></nav>
    </header>

    <p class="monitor-boundary">Java 已接管完整实施闭环：字段映射只能选取本院元数据快照，SQL 由结构化计划确定性生成并通过 DBHub 只读试运行，审批发布采用版本化事务。</p>
    <p v-if="message" class="monitor-message">{{ message }}</p><p v-if="error" class="runs-error">{{ error }}</p>

    <section class="implementation-layout">
      <aside class="implementation-list">
        <header><div><p class="eyebrow">Task queue</p><h2>实施任务</h2></div><span>{{ drafts.length }}</span></header>
        <button v-for="draft in drafts" :key="draft.draft_id" :class="{ active: draft.draft_id === selectedId }" @click="select(draft)">
          <strong>{{ draft.index_name }}</strong><code>{{ draft.proposed_index_code }}</code><span>{{ draft.status }} · v{{ draft.current_version }}</span>
        </button>
        <p v-if="!drafts.length">当前医院还没有实施任务，可先在 AI 对话中描述需要新增或适配的指标。</p>
      </aside>

      <section v-if="selected" class="implementation-detail">
        <header><div><p class="eyebrow">{{ selected.draft_id }}</p><h2>{{ selected.index_name }}</h2></div><span class="run-status" :data-status="selected.status">{{ selected.status }}</span></header>
        <ol class="implementation-phases"><li v-for="phase in phases" :key="phase" :data-state="phaseState(selected.status, phase)">{{ phase }}</li></ol>

        <form class="implementation-form" @submit.prevent="save">
          <label>指标名称<input v-model="form.index_name" /></label><label>统计周期<select v-model="form.stat_cycle"><option value="day">日</option><option value="month">月</option><option value="quarter">季</option><option value="year">年</option></select></label>
          <label class="wide">指标说明<textarea v-model="form.index_desc" rows="3" /></label>
          <label class="wide">分子规则<textarea v-model="form.numerator_rule" rows="3" /></label><label class="wide">分母规则<textarea v-model="form.denominator_rule" rows="3" /></label>
          <label>过滤规则<textarea v-model="form.filter_rule" rows="3" /></label><label>排除规则<textarea v-model="form.exclude_rule" rows="3" /></label>
          <label>指标类型<select v-model="form.metric_type"><option value="ratio">比例</option><option value="count">计数</option></select></label><label>字段要求（每行一个）<textarea v-model="form.metadata_requirements" rows="5" /></label>
          <p class="wide implementation-actions"><button class="primary-button" :disabled="loading">保存设计</button><button v-if="selected.status === 'requirements_pending'" type="button" @click="advance('requirements-confirm')">确认取数要求</button><button v-if="selected.status === 'trial_passed'" type="button" @click="advance('submit')">提交审批</button></p>
        </form>

        <section v-if="selected.status === 'metadata_pending'" class="implementation-stage">
          <header><div><p class="eyebrow">Metadata mapping</p><h3>字段映射确认</h3></div><button type="button" :disabled="loading" @click="refreshSuggestions">重新匹配</button></header>
          <p v-if="suggestions?.missing_fields.length" class="runs-error">未找到字段：{{ suggestions.missing_fields.join('、') }}</p>
          <div v-for="field in selected.metadata_requirements" :key="field" class="implementation-mapping-row">
            <strong>{{ field }}</strong>
            <select v-model="mappingChoice[field]">
              <option v-for="(candidate, index) in suggestions?.suggestions[field] || []" :key="`${candidate.db_name}.${candidate.column_name}`" :value="index">
                {{ candidate.db_name }}.{{ candidate.table_name }}.{{ candidate.column_name }} · {{ candidate.reason }}
              </option>
            </select>
          </div>
          <button class="primary-button" type="button" :disabled="loading || !suggestions?.ready_for_confirmation" @click="confirmMappings">确认全部映射</button>
        </section>

        <section v-if="selected.status === 'metadata_ready'" class="implementation-stage">
          <p class="eyebrow">Deterministic SQL</p><h3>生成受控 SQL</h3><p>服务端根据 SQL Plan 与已确认字段编译，不接受浏览器提交任意 SQL。</p>
          <button class="primary-button" type="button" :disabled="loading" @click="generateSql">生成并校验 SQL</button>
        </section>

        <section v-if="selected.status === 'sql_ready'" class="implementation-stage">
          <p class="eyebrow">DBHub trial</p><h3>只读试运行</h3>
          <div class="implementation-period"><label>开始时间<input v-model="trialStart" type="datetime-local" /></label><label>结束时间<input v-model="trialEnd" type="datetime-local" /></label></div>
          <button class="primary-button" type="button" :disabled="loading" @click="trialRun">通过 DBHub 试运行</button>
        </section>

        <section v-if="selected.status === 'pending_approval' || selected.status === 'published'" class="implementation-stage implementation-review">
          <header><div><p class="eyebrow">Governance</p><h3>管理员审批与版本治理</h3></div><button v-if="adminToken" type="button" @click="adminLogout">退出管理员</button></header>
          <form v-if="!adminToken" class="implementation-admin-login" @submit.prevent="adminLogin"><input v-model="adminPassword" type="password" autocomplete="current-password" placeholder="管理员密码" required /><button :disabled="loading">登录管理员</button></form>
          <template v-else-if="selected.status === 'pending_approval'">
            <textarea v-model="rejectReason" rows="2" placeholder="驳回时必须填写原因" />
            <div class="implementation-actions"><button class="primary-button" type="button" :disabled="loading" @click="review('approve')">批准并发布</button><button type="button" :disabled="loading || !rejectReason.trim()" @click="review('reject')">驳回</button></div>
          </template>
          <p v-else-if="selected.base_index_code">该任务发布为国标指标的本院覆盖口径，版本治理沿用本院口径版本链。</p>
          <template v-else-if="selected.formal_index_code">
            <button type="button" :disabled="loading" @click="refreshVersions">加载版本历史</button>
            <div v-for="version in publishedVersions" :key="String(version.version)" class="implementation-version">
              <span>v{{ version.version }} · {{ version.change_type }} <b v-if="version.active">当前生效</b></span>
              <button v-if="!version.active" type="button" :disabled="loading" @click="restoreVersion(Number(version.version))">恢复为新版本</button>
            </div>
          </template>
        </section>

        <details><summary>当前映射、SQL 与试运行证据</summary><div class="implementation-evidence"><pre>{{ JSON.stringify(selected.field_mapping, null, 2) }}</pre><pre>{{ selected.current_sql || '尚未生成 SQL' }}</pre><pre>{{ JSON.stringify(selected.trial_result, null, 2) }}</pre></div></details>
      </section>
      <section v-else class="implementation-empty"><h2>请选择实施任务</h2><p>任务详情会显示版本、状态和可执行动作。</p></section>
    </section>
  </main>
</template>
