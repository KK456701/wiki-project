<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  loadTerminologyConcept,
  loadTerminologyConcepts,
  loadTerminologyReleases,
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

async function refresh(preferred = detail.value?.concept_code || '') {
  loading.value = true
  error.value = ''
  try {
    const result = await loadTerminologyConcepts(store.token, {
      query: search.value.trim(), conceptType: conceptType.value,
    })
    concepts.value = result.items
    const selected = result.items.find((item) => item.concept_code === preferred) || result.items[0]
    detail.value = selected ? await loadDetail(selected.concept_code) : null
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
    <p v-if="error" class="runs-error term-error">{{ error }}</p>

    <section class="term-layout">
      <aside class="term-index">
        <header><h2>标准概念</h2><span>{{ loading ? '读取中' : concepts.length }}</span></header>
        <button v-for="item in concepts" :key="item.concept_code" type="button" :class="{ active: detail?.concept_code === item.concept_code }" @click="loadDetail(item.concept_code)">
          <strong>{{ item.canonical_name }}</strong><small>{{ item.concept_code }} · {{ typeNames[item.concept_type] || item.concept_type }}</small><em>{{ item.alias_count || 0 }} 个同义词</em>
        </button>
        <p v-if="!loading && !concepts.length">没有符合条件的标准概念。</p>
      </aside>

      <article v-if="detail" class="term-detail">
        <header><div><p class="eyebrow">{{ detail.concept_code }}</p><h2>{{ detail.canonical_name }}</h2><p>{{ detail.definition || '尚未填写定义。' }}</p></div><span>{{ typeNames[detail.concept_type] || detail.concept_type }}</span></header>
        <section class="term-detail-grid">
          <article><h3>同义词与安全边界</h3><div class="term-record" v-for="alias in detail.aliases" :key="String(alias.id)"><div><strong>{{ alias.alias_text }}</strong><small>{{ relationNames[String(alias.relation_type)] || alias.relation_type }} · {{ alias.approval_status }}</small></div><p><span :data-safe="bool(alias.retrieval_enabled)">检索 {{ bool(alias.retrieval_enabled) ? '可用' : '禁用' }}</span><span :data-safe="bool(alias.sql_safe)">SQL {{ bool(alias.sql_safe) ? '可用' : '禁用' }}</span></p></div><p v-if="!detail.aliases.length">暂无同义词。</p></article>
          <article><h3>本院编码和值</h3><div class="term-record" v-for="mapping in detail.hospital_mappings" :key="String(mapping.id)"><div><strong>{{ mapping.local_name }}</strong><small>{{ mapping.code_system }} · {{ mapping.local_code || '无本院编码' }}</small></div><p><span>{{ mapping.local_value }}</span><span>{{ mapping.approval_status }}</span></p></div><p v-if="!detail.hospital_mappings.length">当前医院尚未配置映射。</p></article>
          <article><h3>关联指标</h3><div class="term-record" v-for="link in detail.rule_links" :key="`${link.index_code}-${link.usage_section}`"><div><strong>{{ link.index_code }}</strong><small>使用位置 {{ link.usage_section }}</small></div><p><span>{{ link.business_field_key || '规则文本' }}</span></p></div><p v-if="!detail.rule_links.length">当前概念尚未关联指标。</p></article>
          <article><h3>术语版本</h3><div class="term-record" v-for="release in releases" :key="String(release.release_id)"><div><strong>v{{ release.version }} · {{ release.release_id }}</strong><small>{{ release.change_summary || '术语发布' }}</small></div><p><span>{{ release.status === 'active' ? '当前生效' : '历史版本' }}</span></p></div><p v-if="!releases.length">尚无发布版本。</p></article>
        </section>
      </article>
      <article v-else class="term-detail term-empty">请选择一个标准概念。</article>
    </section>

    <section class="term-recognition">
      <div><p class="eyebrow">Deterministic recognition</p><h2>识别链路测试</h2><p>不调用 LLM；按最长词优先、本院优先和歧义拒绝规则测试当前发布术语。</p></div>
      <form @submit.prevent="recognize"><textarea v-model="recognitionText" maxlength="1000" /><button class="primary-button" type="submit" :disabled="recognizing">{{ recognizing ? '识别中…' : '测试识别' }}</button></form>
      <article v-if="recognition"><header><strong>{{ recognition.normalized_text }}</strong><span :data-eligible="recognition.sql_eligible">{{ recognition.sql_eligible ? '可继续 SQL 条件检查' : '不可直接进入 SQL' }}</span></header><div><p v-for="match in recognition.matches" :key="`${match.matched_text}-${match.concept_code}`"><b>{{ match.matched_text }}</b> → {{ match.canonical_name }} · {{ match.source }} · {{ relationNames[String(match.relation_type)] || match.relation_type }}</p><p v-if="!recognition.matches.length">未命中唯一术语。</p></div><small>版本 {{ recognition.release_version }} · {{ recognition.duration_ms }}ms · 歧义 {{ recognition.ambiguities.length }} 项</small></article>
    </section>
  </main>
</template>
