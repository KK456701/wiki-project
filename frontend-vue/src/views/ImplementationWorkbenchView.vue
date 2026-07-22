<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  advanceIndicatorDraft, loadIndicatorDrafts, updateIndicatorDraft, type IndicatorDraft,
} from '../api/agent'
import { useAgentStore } from '../stores/agent'

const store = useAgentStore()
const router = useRouter()
const drafts = ref<IndicatorDraft[]>([])
const selectedId = ref('')
const loading = ref(false)
const error = ref('')
const message = ref('')
const form = ref({ index_name: '', index_desc: '', numerator_rule: '', denominator_rule: '',
  filter_rule: '', exclude_rule: '', stat_cycle: 'month', metric_type: 'ratio' as 'ratio' | 'count',
  metadata_requirements: '' })
const selected = computed(() => drafts.value.find((item) => item.draft_id === selectedId.value) || null)
const phases = ['requirements_pending', 'metadata_pending', 'metadata_ready', 'sql_ready', 'trial_passed', 'pending_approval', 'published']

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

    <p class="monitor-boundary">本批 Java 接管任务读取、版本化编辑、取数要求确认和提交审批；字段建议、映射确认、SQL 生成/试运行与正式发布仍按迁移批次逐步切换。</p>
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

        <details><summary>当前映射、SQL 与试运行证据</summary><div class="implementation-evidence"><pre>{{ JSON.stringify(selected.field_mapping, null, 2) }}</pre><pre>{{ selected.current_sql || '尚未生成 SQL' }}</pre><pre>{{ JSON.stringify(selected.trial_result, null, 2) }}</pre></div></details>
      </section>
      <section v-else class="implementation-empty"><h2>请选择实施任务</h2><p>任务详情会显示版本、状态和可执行动作。</p></section>
    </section>
  </main>
</template>
