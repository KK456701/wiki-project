<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  approveTerminologyAlias,
  approveTerminologyMapping,
  createTerminologyAlias,
  createTerminologyMapping,
  loginAdmin,
  loadTerminologyConcept,
  loadTerminologyConcepts,
  loadTerminologyReleases,
  logoutAdmin,
  publishTerminology,
  restoreTerminology,
  testTerminologyRecognition,
  type TerminologyConcept,
  type TerminologyConceptDetail,
  type TerminologyNormalization,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const concepts = ref<TerminologyConcept[]>([])
const detail = ref<TerminologyConceptDetail | null>(null)
const releases = ref<Array<Record<string, unknown>>>([])
const search = ref('')
const conceptType = ref('')
const loading = ref(false)
const error = ref('')
const recognitionText = ref('帮我查急会诊到位率')
const recognizing = ref(false)
const recognition = ref<TerminologyNormalization | null>(null)
const adminToken = ref(sessionStorage.getItem('vueAdminToken') || '')
const adminPassword = ref('')
const adminBusy = ref(false)
const operationMessage = ref('')
const aliasText = ref('')
const aliasRelation = ref('abbreviation')
const aliasSqlSafe = ref(false)
const mappingSystem = ref('hospital_business')
const mappingCode = ref('')
const mappingName = ref('')
const mappingValue = ref('')
let searchTimer: number | undefined

const typeNames: Record<string, string> = {
  indicator: '指标', diagnosis: '诊断', department: '科室', staff_role: '人员角色',
  procedure: '操作', time_window: '时间范围', status: '状态', data_value: '数据值',
  business_concept: '业务概念',
}
const relationNames: Record<string, string> = {
  exact: '完全同义', abbreviation: '简称', colloquial: '口语表达', related: '相关词',
  value_mapping: '本院映射', forbidden: '禁止替换',
}

onMounted(async () => {
  if (!store.isAuthenticated || !store.user) {
    await router.replace('/')
    return
  }
  await Promise.all([refresh(), loadReleases()])
})

watch([search, conceptType], () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => refresh(), 250)
})

async function refresh(preferred = detail.value?.conceptCode || '') {
  loading.value = true
  error.value = ''
  try {
    const result = await loadTerminologyConcepts(store.token, {
      query: search.value.trim(), conceptType: conceptType.value,
    })
    concepts.value = result.items
    const selected = result.items.find((item) => item.conceptCode === preferred) || result.items[0]
    detail.value = selected ? await loadDetail(selected.conceptCode) : null
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '医学术语读取失败。'
  } finally {
    loading.value = false
  }
}

async function loadDetail(code: string) {
  if (!store.user) return null
  const result = await loadTerminologyConcept(store.token, code, store.user.hospitalId)
  detail.value = result
  return result
}

async function loadReleases() {
  try { releases.value = (await loadTerminologyReleases(store.token)).items } catch { releases.value = [] }
}

async function recognize() {
  if (!store.user || !recognitionText.value.trim()) return
  recognizing.value = true
  error.value = ''
  try {
    recognition.value = await testTerminologyRecognition(
      store.token, store.user.hospitalId, recognitionText.value.trim(),
    )
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '术语识别测试失败。'
  } finally {
    recognizing.value = false
  }
}

async function enterAdminMode() {
  if (!adminPassword.value) return
  adminBusy.value = true
  error.value = ''
  try {
    const result = await loginAdmin(adminPassword.value)
    adminToken.value = result.token
    sessionStorage.setItem('vueAdminToken', result.token)
    adminPassword.value = ''
    operationMessage.value = '维护模式已开启。'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '管理员登录失败。' }
  finally { adminBusy.value = false }
}

async function leaveAdminMode() {
  await logoutAdmin(adminToken.value).catch(() => undefined)
  adminToken.value = ''
  sessionStorage.removeItem('vueAdminToken')
  operationMessage.value = '维护模式已关闭。'
}

async function addAlias() {
  if (!detail.value || !store.user || !aliasText.value.trim()) return
  await adminOperation(async () => {
    await createTerminologyAlias(adminToken.value, store.token, {
      hospitalId: store.user?.hospitalId, conceptCode: detail.value?.conceptCode,
      aliasText: aliasText.value.trim(), relationType: aliasRelation.value,
      retrievalEnabled: aliasRelation.value !== 'forbidden', sqlSafe: aliasSqlSafe.value,
      sourceReference: 'vue-terminology-workbench', createdBy: store.user?.accountId,
    })
    aliasText.value = ''; aliasSqlSafe.value = false
  }, '候选词已保存，等待审批。')
}

async function approveAlias(id: unknown) {
  if (!store.user) return
  await adminOperation(() => approveTerminologyAlias(adminToken.value, store.token, Number(id)), '候选词已批准。')
}

async function addMapping() {
  if (!detail.value || !store.user) return
  await adminOperation(async () => {
    await createTerminologyMapping(adminToken.value, store.token, {
      hospitalId: store.user?.hospitalId, conceptCode: detail.value?.conceptCode,
      codeSystem: mappingSystem.value.trim(), localCode: mappingCode.value.trim(),
      localName: mappingName.value.trim(), localValue: mappingValue.value.trim(),
      createdBy: store.user?.accountId,
    })
    mappingCode.value = ''; mappingName.value = ''; mappingValue.value = ''
  }, '本院映射已保存，等待审批。')
}

async function approveMapping(id: unknown) {
  await adminOperation(() => approveTerminologyMapping(adminToken.value, store.token, Number(id)), '本院映射已批准。')
}

async function publish() {
  if (!window.confirm('确认发布全部已审核公司术语？发布后将进入识别链路。')) return
  await adminOperation(() => publishTerminology(adminToken.value), '术语版本已发布。', true)
}

async function restore(releaseId: unknown) {
  if (!window.confirm(`确认回退到 ${String(releaseId)}？历史版本不会删除。`)) return
  await adminOperation(() => restoreTerminology(adminToken.value, String(releaseId)), '术语版本已恢复。', true)
}

async function adminOperation(action: () => Promise<unknown>, message: string, reloadAll = false) {
  adminBusy.value = true
  error.value = ''; operationMessage.value = ''
  try {
    await action()
    operationMessage.value = message
    if (reloadAll) await Promise.all([refresh(detail.value?.conceptCode), loadReleases()])
    else if (detail.value) await loadDetail(detail.value.conceptCode)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '术语维护操作失败。' }
  finally { adminBusy.value = false }
}

function bool(value: unknown) { return value === true || value === 1 }
</script>

<template>
  <main class="term-shell">
    <header class="term-head">
      <div><p class="eyebrow">Medical language contract</p><h1>医学术语工作台</h1><p>标准概念、同义词、本院编码和指标引用在这里保持可审阅。</p></div>
      <nav><RouterLink class="quiet-button" to="/">返回对话</RouterLink><RouterLink class="quiet-button" to="/metadata">数据库元数据</RouterLink><RouterLink class="quiet-button" to="/runs">运行观察</RouterLink></nav>
    </header>

    <section class="term-toolbar">
      <label>检索概念或同义词<input v-model="search" placeholder="例如：急会诊、ICU、48小时" /></label>
      <label>概念类型<select v-model="conceptType"><option value="">全部类型</option><option v-for="(label, value) in typeNames" :key="value" :value="value">{{ label }}</option></select></label>
      <span>医院 {{ store.user?.hospitalId }} · {{ concepts.length }} 个概念</span>
    </section>
    <section class="term-admin-bar">
      <template v-if="!adminToken">
        <div><strong>管理员维护</strong><span>候选、审批、发布和回退需要独立管理员会话。</span></div>
        <form @submit.prevent="enterAdminMode"><input v-model="adminPassword" type="password" autocomplete="current-password" placeholder="管理员密码" /><button class="quiet-button" :disabled="adminBusy">开启维护模式</button></form>
      </template>
      <template v-else>
        <div><strong>维护模式已开启</strong><span>{{ operationMessage || '写操作将记录版本和审计日志。' }}</span></div>
        <button class="quiet-button" type="button" @click="leaveAdminMode">退出维护模式</button>
      </template>
    </section>
    <p v-if="error" class="runs-error term-error">{{ error }}</p>

    <section class="term-layout">
      <aside class="term-index">
        <header><h2>标准概念</h2><span>{{ loading ? '读取中' : concepts.length }}</span></header>
        <button v-for="item in concepts" :key="item.conceptCode" type="button" :class="{ active: detail?.conceptCode === item.conceptCode }" @click="loadDetail(item.conceptCode)">
          <strong>{{ item.canonicalName }}</strong><small>{{ item.conceptCode }} · {{ typeNames[item.conceptType] || item.conceptType }}</small><em>{{ item.aliasCount || 0 }} 个同义词</em>
        </button>
        <p v-if="!loading && !concepts.length">没有符合条件的标准概念。</p>
      </aside>

      <article v-if="detail" class="term-detail">
        <header><div><p class="eyebrow">{{ detail.conceptCode }}</p><h2>{{ detail.canonicalName }}</h2><p>{{ detail.definition || '尚未填写定义。' }}</p></div><span>{{ typeNames[detail.conceptType] || detail.conceptType }}</span></header>
        <section class="term-detail-grid">
          <article><h3>同义词与安全边界</h3><div class="term-record" v-for="alias in detail.aliases" :key="String(alias.id)"><div><strong>{{ alias.aliasText }}</strong><small>{{ relationNames[String(alias.relationType)] || alias.relationType }} · {{ alias.approvalStatus }}</small></div><p><span :data-safe="bool(alias.retrievalEnabled)">检索 {{ bool(alias.retrievalEnabled) ? '可用' : '禁用' }}</span><span :data-safe="bool(alias.sqlSafe)">SQL {{ bool(alias.sqlSafe) ? '可用' : '禁用' }}</span><button v-if="adminToken && alias.approvalStatus === 'pending'" type="button" @click="approveAlias(alias.id)">批准</button></p></div><p v-if="!detail.aliases.length">暂无同义词。</p><form v-if="adminToken" class="term-inline-form" @submit.prevent="addAlias"><input v-model="aliasText" required maxlength="200" placeholder="新增本院候选词" /><select v-model="aliasRelation"><option value="exact">完全同义</option><option value="abbreviation">简称</option><option value="colloquial">口语表达</option><option value="related">相关词</option><option value="forbidden">禁止替换</option></select><label><input v-model="aliasSqlSafe" type="checkbox" />可进 SQL</label><button :disabled="adminBusy">保存候选</button></form></article>
          <article><h3>本院编码和值</h3><div class="term-record" v-for="mapping in detail.hospitalMappings" :key="String(mapping.id)"><div><strong>{{ mapping.localName }}</strong><small>{{ mapping.codeSystem }} · {{ mapping.localCode || '无本院编码' }}</small></div><p><span>{{ mapping.localValue }}</span><span>{{ mapping.approvalStatus }}</span><button v-if="adminToken && mapping.approvalStatus === 'pending'" type="button" @click="approveMapping(mapping.id)">批准</button></p></div><p v-if="!detail.hospitalMappings.length">当前医院尚未配置映射。</p><form v-if="adminToken" class="term-inline-form term-mapping-form" @submit.prevent="addMapping"><input v-model="mappingSystem" required placeholder="编码体系" /><input v-model="mappingCode" placeholder="本院编码" /><input v-model="mappingName" required placeholder="本院名称" /><input v-model="mappingValue" required placeholder="数据库值" /><button :disabled="adminBusy">保存映射</button></form></article>
          <article><h3>关联指标</h3><div class="term-record" v-for="link in detail.ruleLinks" :key="`${link.indexCode}-${link.usageSection}`"><div><strong>{{ link.indexCode }}</strong><small>使用位置 {{ link.usageSection }}</small></div><p><span>{{ link.businessFieldKey || '规则文本' }}</span></p></div><p v-if="!detail.ruleLinks.length">当前概念尚未关联指标。</p></article>
          <article><h3>术语版本</h3><button v-if="adminToken" class="term-publish" type="button" :disabled="adminBusy" @click="publish">发布已审核术语</button><div class="term-record" v-for="release in releases" :key="String(release.releaseId)"><div><strong>v{{ release.version }} · {{ release.releaseId }}</strong><small>{{ release.changeSummary || '术语发布' }}</small></div><p><span>{{ release.status === 'active' ? '当前生效' : '历史版本' }}</span><button v-if="adminToken && release.status !== 'active'" type="button" @click="restore(release.releaseId)">回退</button></p></div><p v-if="!releases.length">尚无发布版本。</p></article>
        </section>
      </article>
      <article v-else class="term-detail term-empty">请选择一个标准概念。</article>
    </section>

    <section class="term-recognition">
      <div><p class="eyebrow">Deterministic recognition</p><h2>识别链路测试</h2><p>不调用 LLM；按最长词优先、本院优先和歧义拒绝规则测试当前发布术语。</p></div>
      <form @submit.prevent="recognize"><textarea v-model="recognitionText" maxlength="1000" /><button class="primary-button" type="submit" :disabled="recognizing">{{ recognizing ? '识别中…' : '测试识别' }}</button></form>
      <article v-if="recognition"><header><strong>{{ recognition.normalizedText }}</strong><span :data-eligible="recognition.sqlEligible">{{ recognition.sqlEligible ? '可继续 SQL 条件检查' : '不可直接进入 SQL' }}</span></header><div><p v-for="match in recognition.matches" :key="`${match.matchedText}-${match.conceptCode}`"><b>{{ match.matchedText }}</b> → {{ match.canonicalName }} · {{ match.source }} · {{ relationNames[String(match.relationType)] || match.relationType }}</p><p v-if="!recognition.matches.length">未命中唯一术语。</p></div><small>版本 {{ recognition.releaseVersion }} · {{ recognition.durationMs }}ms · 歧义 {{ recognition.ambiguities.length }} 项</small></article>
    </section>
  </main>
</template>
